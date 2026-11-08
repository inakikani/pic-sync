package main

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// savedFile describes the outcome of one uploaded file.
type savedFile struct {
	Name      string `json:"name"`      // file name in Folder (existing file if Duplicate)
	Original  string `json:"original"`  // name sent by the client
	Size      int64  `json:"size"`      // bytes received
	SHA256    string `json:"sha256"`    // hex SHA-256 of the received bytes
	Duplicate bool   `json:"duplicate"` // identical content already existed; nothing new written
	Category  string `json:"category"`  // photos, videos, documents or other
	Folder    string `json:"folder"`    // where it was saved, "~/…" for folders under the home folder

	Date       time.Time `json:"-"` // date given to the saved file (zero: upload time kept)
	DateSource string    `json:"-"` // "file" (EXIF/video header), "client", or "" (none)
}

// clientError is an upload problem caused by the request (bad name, connection
// dropped mid-file), answered with 400. Any other error is the Mac's (disk full,
// no permission) and gets 500, so the two can be told apart on the phone and in the log.
type clientError struct{ error }

func (e clientError) Unwrap() error { return e.error }

func isClientError(err error) bool {
	var c clientError
	return errors.As(err, &c)
}

// srcReader remembers whether a copy failed while reading the upload (the
// client) rather than while writing the file (the Mac).
type srcReader struct {
	r   io.Reader
	err error
}

func (s *srcReader) Read(p []byte) (int, error) {
	n, err := s.r.Read(p)
	if err != nil && !errors.Is(err, io.EOF) {
		s.err = err
	}
	return n, err
}

// store writes uploads into their folder (see folders.route) and detects
// content duplicates within that folder.
//
// Duplicate detection is by full SHA-256, computed while the upload streams to
// disk. Only existing files of exactly the same size are compared, and their
// hashes are cached (invalidated by size/mtime changes), so in the common case
// nothing extra is hashed at all.
type store struct {
	dirMu sync.RWMutex
	dirs  folders    // where uploads go; can change at runtime (menu bar "Folders")
	mu    sync.Mutex // serialises duplicate check + final rename; guards cache
	cache map[string]hashEntry
	act   *activity
	awake *awake // keeps the Mac awake while uploading; nil in tests

	tmpMu sync.Mutex
	temps map[string]struct{} // temp files of uploads in progress, removed on shutdown
}

// tempPattern names in-progress uploads. They're hidden (dot), so Finder and the
// duplicate check ignore them.
const tempPattern = ".upload-*.part"

type hashEntry struct {
	size    int64
	modTime time.Time
	sum     string
}

// newStore saves everything to dir; use SetFolders for per-category folders.
func newStore(dir string) *store {
	return &store{dirs: folders{Photos: dir}, cache: map[string]hashEntry{}, act: newActivity(), temps: map[string]struct{}{}}
}

// Folders returns where uploads currently go.
func (s *store) Folders() folders {
	s.dirMu.RLock()
	defer s.dirMu.RUnlock()
	return s.dirs
}

// SetFolders switches where uploads go; uploads already in progress finish in
// their old folder. Cached hashes of folders no longer used are dropped.
func (s *store) SetFolders(f folders) {
	s.dirMu.Lock()
	s.dirs = f
	s.dirMu.Unlock()

	keep := map[string]bool{}
	for _, d := range f.all() {
		keep[d] = true
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for p := range s.cache {
		if !keep[filepath.Dir(p)] {
			delete(s.cache, p)
		}
	}
}

// save stores one upload in the folder for its type. contentType is the type the
// client sent with the file (used when the extension doesn't say). clientTime is
// the file date the client sent (zero if none); it's used as the file's
// modification time when the file itself has no capture date (see takenTime).
func (s *store) save(rawName, contentType string, src io.Reader, clientTime time.Time) (savedFile, error) {
	name, err := sanitizeName(rawName)
	if err != nil {
		return savedFile{}, clientError{err}
	}
	// Fixed for this upload even if the folders are changed meanwhile. Folders are
	// created at startup and when changed, never here: a folder that disappeared
	// (e.g. on an ejected disk) is an error, not something to recreate elsewhere.
	dir, cat, _ := s.Folders().route(name, contentType)

	// Stream to a hidden temp file (hashing on the way) so a broken upload
	// never appears under a real name.
	tmp, err := os.CreateTemp(dir, tempPattern)
	if err != nil {
		return savedFile{}, err
	}
	tmpPath := tmp.Name()
	s.trackTemp(tmpPath, true)
	defer func() {
		os.Remove(tmpPath) // no-op after a successful rename
		s.trackTemp(tmpPath, false)
	}()
	h := sha256.New()
	in := &srcReader{r: src}
	size, err := io.Copy(io.MultiWriter(tmp, h), in)
	if err != nil {
		tmp.Close()
		if in.err != nil {
			return savedFile{}, clientError{fmt.Errorf("upload interrupted after %s: %w", formatBytes(size), err)}
		}
		return savedFile{}, fmt.Errorf("writing file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return savedFile{}, err
	}
	res := savedFile{Original: name, Size: size, SHA256: hex.EncodeToString(h.Sum(nil)), Category: string(cat), Folder: shortPath(dir)}

	s.mu.Lock()
	defer s.mu.Unlock()

	existing, err := s.findDuplicate(dir, size, res.SHA256)
	if err != nil {
		return savedFile{}, fmt.Errorf("checking for duplicates: %w", err)
	}
	if existing != "" {
		res.Name, res.Duplicate = existing, true
		return res, nil // deferred Remove discards the temp file
	}

	if err := os.Chmod(tmpPath, 0o644); err != nil { // CreateTemp uses 0600
		return savedFile{}, err
	}
	// Give the copy the original's date instead of the upload time. On macOS an
	// older modification time also moves Finder's "Created" date back.
	if when, src := fileDate(tmpPath, clientTime); !when.IsZero() {
		if err := os.Chtimes(tmpPath, when, when); err != nil {
			slog.Warn("setting file date failed", "file", name, "err", err) // not fatal: the bytes are intact
		} else {
			res.Date, res.DateSource = when, src
		}
	}
	final, err := claimName(dir, name)
	if err != nil {
		return savedFile{}, err
	}
	finalPath := filepath.Join(dir, final)
	if err := os.Rename(tmpPath, finalPath); err != nil {
		os.Remove(finalPath)
		return savedFile{}, err
	}
	if info, err := os.Stat(finalPath); err == nil {
		s.cache[finalPath] = hashEntry{info.Size(), info.ModTime(), res.SHA256}
	}
	res.Name = final
	return res, nil
}

// fileDate picks the date for a saved file: the capture date recorded in the
// file, else the client's date if plausible, else zero (keep the upload time).
// The file's own date wins because browsers often send the time the file was
// picked (iOS) rather than when the photo was taken.
func fileDate(path string, clientTime time.Time) (time.Time, string) {
	if t, ok := takenTimeOfFile(path); ok {
		return t, "file"
	}
	if !clientTime.IsZero() && plausible(clientTime) {
		return clientTime, "client"
	}
	return time.Time{}, ""
}

func (s *store) trackTemp(path string, add bool) {
	s.tmpMu.Lock()
	defer s.tmpMu.Unlock()
	if add {
		s.temps[path] = struct{}{}
	} else {
		delete(s.temps, path)
	}
}

// removeTemps deletes the temp files of uploads still in progress. Called on
// shutdown: the process may exit before those uploads' own cleanup runs.
func (s *store) removeTemps() {
	s.tmpMu.Lock()
	defer s.tmpMu.Unlock()
	for p := range s.temps {
		if err := os.Remove(p); err != nil && !errors.Is(err, fs.ErrNotExist) {
			slog.Warn("removing temp file", "path", p, "err", err)
		}
		delete(s.temps, p)
	}
}

// staleTempAge is how old a leftover temp file must be before it's deleted at
// startup. Uploads in progress write to theirs continuously (and time out after
// uploadIdleTimeout), so anything this old belongs to a crashed or killed run,
// even if another pic-sync shares the folder.
const staleTempAge = 10 * time.Minute

// removeStaleTemps deletes temp files left in dir by a run that was killed or crashed.
func removeStaleTemps(dir string, now time.Time) {
	matches, err := filepath.Glob(filepath.Join(dir, tempPattern))
	if err != nil {
		return
	}
	removed := 0
	for _, p := range matches {
		info, err := os.Lstat(p)
		if err != nil || !info.Mode().IsRegular() || now.Sub(info.ModTime()) < staleTempAge {
			continue
		}
		if err := os.Remove(p); err != nil {
			slog.Warn("removing leftover temp file", "path", p, "err", err)
			continue
		}
		removed++
	}
	if removed > 0 {
		slog.Info("removed leftover temp files from an interrupted run", "count", removed, "folder", dir)
	}
}

// findDuplicate returns the name of a file in dir with the given size and
// hash, or "" if there is none. Must be called with s.mu held.
func (s *store) findDuplicate(dir string, size int64, sum string) (string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "", err
	}
	for _, e := range entries {
		if !e.Type().IsRegular() || strings.HasPrefix(e.Name(), ".") {
			continue
		}
		info, err := e.Info()
		if err != nil || info.Size() != size {
			continue
		}
		path := filepath.Join(dir, e.Name())
		existing, err := s.hashOf(path, info)
		if err != nil {
			continue // unreadable file: can't be a confirmed duplicate
		}
		if existing == sum {
			return e.Name(), nil
		}
	}
	return "", nil
}

func (s *store) hashOf(path string, info os.FileInfo) (string, error) {
	if c, ok := s.cache[path]; ok && c.size == info.Size() && c.modTime.Equal(info.ModTime()) {
		return c.sum, nil
	}
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	sum := hex.EncodeToString(h.Sum(nil))
	s.cache[path] = hashEntry{info.Size(), info.ModTime(), sum}
	return sum, nil
}
