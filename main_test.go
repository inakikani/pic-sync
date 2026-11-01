package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"
)

type upFile struct{ name, body string }

func upload(t *testing.T, dir string, files ...upFile) *httptest.ResponseRecorder {
	t.Helper()
	return uploadTo(t, newMux(dir), files...)
}

func uploadTo(t *testing.T, mux http.Handler, files ...upFile) *httptest.ResponseRecorder {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	for _, f := range files {
		w, err := mw.CreateFormFile("file", f.name)
		if err != nil {
			t.Fatal(err)
		}
		w.Write([]byte(f.body))
	}
	mw.Close()
	req := httptest.NewRequest(http.MethodPost, "/upload", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func listDir(t *testing.T, dir string) []string {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	sort.Strings(names)
	return names
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestUploadSavesFile(t *testing.T) {
	dir := t.TempDir()
	rec := upload(t, dir, upFile{"IMG_001.jpg", "hello"})
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
	if got := readFile(t, filepath.Join(dir, "IMG_001.jpg")); got != "hello" {
		t.Fatalf("content = %q", got)
	}
	if names := listDir(t, dir); len(names) != 1 {
		t.Fatalf("leftover files: %v", names)
	}
}

func TestNameCollisionDoesNotOverwrite(t *testing.T) {
	dir := t.TempDir()
	upload(t, dir, upFile{"IMG_001.jpg", "first"})
	upload(t, dir, upFile{"IMG_001.jpg", "second"})
	upload(t, dir, upFile{"IMG_001.jpg", "third"})
	want := []string{"IMG_001 (1).jpg", "IMG_001 (2).jpg", "IMG_001.jpg"}
	if got := listDir(t, dir); !equal(got, want) {
		t.Fatalf("files = %v, want %v", got, want)
	}
	if got := readFile(t, filepath.Join(dir, "IMG_001.jpg")); got != "first" {
		t.Fatalf("original overwritten: %q", got)
	}
	if got := readFile(t, filepath.Join(dir, "IMG_001 (1).jpg")); got != "second" {
		t.Fatalf("(1) content = %q", got)
	}
}

func TestPathTraversalIsContained(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "uploads")
	os.Mkdir(dir, 0o755)
	upload(t, dir, upFile{"../../evil.jpg", "x"})
	upload(t, dir, upFile{`..\..\evil2.jpg`, "y"})
	if got := listDir(t, root); !equal(got, []string{"uploads"}) {
		t.Fatalf("file escaped upload dir: %v", got)
	}
	if got := listDir(t, dir); !equal(got, []string{"evil.jpg", "evil2.jpg"}) {
		t.Fatalf("files = %v", got)
	}
}

func TestRejectsBadNames(t *testing.T) {
	dir := t.TempDir()
	for _, name := range []string{"..", ".", ".hidden"} {
		if rec := upload(t, dir, upFile{name, "x"}); rec.Code != http.StatusBadRequest {
			t.Errorf("%q: status %d, want 400", name, rec.Code)
		}
	}
	if names := listDir(t, dir); len(names) != 0 {
		t.Fatalf("unexpected files: %v", names)
	}
}

func TestMultipleFilesInOneRequest(t *testing.T) {
	dir := t.TempDir()
	rec := upload(t, dir, upFile{"a.jpg", "a"}, upFile{"b.mp4", "b"})
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
	if got := listDir(t, dir); !equal(got, []string{"a.jpg", "b.mp4"}) {
		t.Fatalf("files = %v", got)
	}
}

func TestIndexServed(t *testing.T) {
	rec := httptest.NewRecorder()
	newMux(t.TempDir()).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != http.StatusOK || !bytes.Contains(rec.Body.Bytes(), []byte(`type="file"`)) {
		t.Fatalf("index: status %d", rec.Code)
	}
}

func TestLoadConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "c.json")
	os.WriteFile(path, []byte(`{"upload_dir":"~/x"}`), 0o644)
	cfg, err := loadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	home, _ := os.UserHomeDir()
	if cfg.UploadDir != filepath.Join(home, "x") || cfg.Listen != ":8080" {
		t.Fatalf("cfg = %+v", cfg)
	}
	os.WriteFile(path, []byte(`{}`), 0o644)
	if _, err := loadConfig(path); err == nil {
		t.Fatal("expected error for missing upload_dir")
	}
}

func equal(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestRenderQR(t *testing.T) {
	art, err := renderQR("http://192.168.1.23:8080")
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSuffix(art, "\n"), "\n")
	// version-2 code is 25 modules + 2*2 quiet zone = 29 modules -> 15 text rows
	if len(lines) < 10 || !strings.Contains(art, "▀") {
		t.Fatalf("unexpected QR output (%d lines)", len(lines))
	}
}

func TestStatusPageLocalOnly(t *testing.T) {
	mux := newMux(t.TempDir())
	addStatusPage(mux, "/photos", "http://192.168.1.5:8080")
	for _, tc := range []struct {
		path, remote string
		want         int
	}{
		{"/status", "127.0.0.1:5000", http.StatusOK},
		{"/status", "[::1]:5000", http.StatusOK},
		{"/qr.png", "127.0.0.1:5000", http.StatusOK},
		{"/status", "192.168.1.20:5000", http.StatusNotFound},
		{"/qr.png", "192.168.1.20:5000", http.StatusNotFound},
	} {
		req := httptest.NewRequest(http.MethodGet, tc.path, nil)
		req.RemoteAddr = tc.remote
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != tc.want {
			t.Errorf("%s from %s: status %d, want %d", tc.path, tc.remote, rec.Code, tc.want)
		}
		if tc.path == "/status" && rec.Code == 200 && !strings.Contains(rec.Body.String(), "http://192.168.1.5:8080") {
			t.Error("status page missing phone URL")
		}
	}
}

func TestAppConfigRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "config.json")
	if cfg := loadAppConfig(path); cfg.Listen != ":8080" || cfg.UploadDir != "" {
		t.Fatalf("defaults = %+v", cfg)
	}
	if err := saveAppConfig(path, Config{UploadDir: "/x", Listen: ":9000"}); err != nil {
		t.Fatal(err)
	}
	if cfg := loadAppConfig(path); cfg.UploadDir != "/x" || cfg.Listen != ":9000" {
		t.Fatalf("loaded = %+v", cfg)
	}
}

func TestResolveMode(t *testing.T) {
	for _, tc := range []struct {
		mode     string
		inBundle bool
		want     string
	}{
		{"auto", false, "cli"},
		{"auto", true, "app"},
		{"cli", true, "cli"},
		{"app", false, "app"},
	} {
		if got, err := resolveMode(tc.mode, tc.inBundle); err != nil || got != tc.want {
			t.Errorf("resolveMode(%q, %v) = %q, %v; want %q", tc.mode, tc.inBundle, got, err, tc.want)
		}
	}
	if _, err := resolveMode("gui", false); err == nil {
		t.Error("expected error for invalid mode")
	}
}

func decodeSaved(t *testing.T, rec *httptest.ResponseRecorder) []savedFile {
	t.Helper()
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
	var resp struct{ Saved []savedFile }
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	return resp.Saved
}

func TestResponseHasHashAndSize(t *testing.T) {
	body := "some photo bytes"
	got := decodeSaved(t, upload(t, t.TempDir(), upFile{"a.jpg", body}))
	sum := sha256.Sum256([]byte(body))
	want := savedFile{Name: "a.jpg", Original: "a.jpg", Size: int64(len(body)), SHA256: hex.EncodeToString(sum[:])}
	if len(got) != 1 || got[0] != want {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

func TestDuplicateContentDifferentName(t *testing.T) {
	dir := t.TempDir()
	mux := newMux(dir) // same server instance, like a real run
	post := func(f upFile) []savedFile { return decodeSaved(t, uploadTo(t, mux, f)) }

	post(upFile{"IMG_001.jpg", "photo"})
	got := post(upFile{"renamed.jpg", "photo"})
	if !got[0].Duplicate || got[0].Name != "IMG_001.jpg" || got[0].Original != "renamed.jpg" {
		t.Fatalf("got %+v", got[0])
	}
	if names := listDir(t, dir); !equal(names, []string{"IMG_001.jpg"}) {
		t.Fatalf("files = %v", names)
	}
}

func TestSameSizeDifferentContentIsSaved(t *testing.T) {
	dir := t.TempDir()
	upload(t, dir, upFile{"a.jpg", "aaaa"})
	got := decodeSaved(t, upload(t, dir, upFile{"b.jpg", "bbbb"}))
	if got[0].Duplicate {
		t.Fatal("different content flagged as duplicate")
	}
	if names := listDir(t, dir); !equal(names, []string{"a.jpg", "b.jpg"}) {
		t.Fatalf("files = %v", names)
	}
}

func TestDuplicateOfPreexistingFile(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "old.jpg"), []byte("already here"), 0o644)
	got := decodeSaved(t, upload(t, dir, upFile{"new.jpg", "already here"}))
	if !got[0].Duplicate || got[0].Name != "old.jpg" {
		t.Fatalf("got %+v", got[0])
	}
}

func TestDuplicateWithinOneRequest(t *testing.T) {
	dir := t.TempDir()
	got := decodeSaved(t, upload(t, dir, upFile{"a.jpg", "same"}, upFile{"b.jpg", "same"}))
	if got[0].Duplicate || !got[1].Duplicate || got[1].Name != "a.jpg" {
		t.Fatalf("got %+v", got)
	}
	if names := listDir(t, dir); !equal(names, []string{"a.jpg"}) {
		t.Fatalf("files = %v", names)
	}
}

func TestModifiedFileIsRehashed(t *testing.T) {
	dir := t.TempDir()
	mux := newMux(dir)
	decodeSaved(t, uploadTo(t, mux, upFile{"a.jpg", "AAAA"}))
	// Replace the file's content (same size) behind the server's back.
	path := filepath.Join(dir, "a.jpg")
	os.WriteFile(path, []byte("BBBB"), 0o644)
	os.Chtimes(path, time.Now().Add(time.Hour), time.Now().Add(time.Hour))

	if got := decodeSaved(t, uploadTo(t, mux, upFile{"x.jpg", "AAAA"})); got[0].Duplicate {
		t.Fatal("stale cached hash used for modified file")
	}
	if got := decodeSaved(t, uploadTo(t, mux, upFile{"y.jpg", "BBBB"})); !got[0].Duplicate {
		t.Fatal("modified file's new content not detected")
	}
}

func TestConcurrentIdenticalUploads(t *testing.T) {
	dir := t.TempDir()
	mux := newMux(dir)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			uploadTo(t, mux, upFile{fmt.Sprintf("p%d.jpg", i), "identical"})
		}(i)
	}
	wg.Wait()
	if names := listDir(t, dir); len(names) != 1 {
		t.Fatalf("expected exactly one file, got %v", names)
	}
}
