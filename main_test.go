package main

import (
	"bytes"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

type upFile struct{ name, body string }

func upload(t *testing.T, dir string, files ...upFile) *httptest.ResponseRecorder {
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
	newMux(dir).ServeHTTP(rec, req)
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
	upload(t, dir, upFile{`..\..\evil2.jpg`, "x"})
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
