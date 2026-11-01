package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// savedFile describes the outcome of one uploaded file.
type savedFile struct {
	Name      string `json:"name"`      // file name in upload_dir (existing file if Duplicate)
	Original  string `json:"original"`  // name sent by the client
	Size      int64  `json:"size"`      // bytes received
	SHA256    string `json:"sha256"`    // hex SHA-256 of the received bytes
	Duplicate bool   `json:"duplicate"` // identical content already existed; nothing new written
}

// store writes uploads into dir and detects content duplicates.
//
// Duplicate detection is by full SHA-256, computed while the upload streams to
// disk. Only existing files of exactly the same size are compared, and their
// hashes are cached (invalidated by size/mtime changes), so in the common case
// nothing extra is hashed at all.
type store struct {
	dir   string
	mu    sync.Mutex // serialises duplicate check + final rename
	cache map[string]hashEntry
}

type hashEntry struct {
	size    int64
	modTime time.Time
	sum     string
}

func newStore(dir string) *store {
	return &store{dir: dir, cache: map[string]hashEntry{}}
}

func (s *store) save(rawName string, src io.Reader) (savedFile, error) {
	name, err := sanitizeName(rawName)
	if err != nil {
		return savedFile{}, err
	}

	// Stream to a hidden temp file (hashing on the way) so a broken upload
	// never appears under a real name.
	tmp, err := os.CreateTemp(s.dir, ".upload-*.part")
	if err != nil {
		return savedFile{}, err
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath) // no-op after successful rename
	h := sha256.New()
	size, err := io.Copy(io.MultiWriter(tmp, h), src)
	if err != nil {
		tmp.Close()
		return savedFile{}, fmt.Errorf("writing file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return savedFile{}, err
	}
	res := savedFile{Original: name, Size: size, SHA256: hex.EncodeToString(h.Sum(nil))}

	s.mu.Lock()
	defer s.mu.Unlock()

	existing, err := s.findDuplicate(size, res.SHA256)
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
	final, err := claimName(s.dir, name)
	if err != nil {
		return savedFile{}, err
	}
	finalPath := filepath.Join(s.dir, final)
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

// findDuplicate returns the name of a file in dir with the given size and
// hash, or "" if there is none. Must be called with s.mu held.
func (s *store) findDuplicate(size int64, sum string) (string, error) {
	entries, err := os.ReadDir(s.dir)
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
		path := filepath.Join(s.dir, e.Name())
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
