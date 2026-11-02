package main

import (
	"bytes"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// captureLog sends the default logger to a buffer for the test.
func captureLog(t *testing.T, level slog.Level) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	old := slog.Default()
	slog.SetDefault(slog.New(newLogHandler(&buf, level)))
	t.Cleanup(func() { slog.SetDefault(old) })
	return &buf
}

func TestRotatingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "logs", "pic-sync.log")
	r, err := openRotating(path, 100)
	if err != nil {
		t.Fatal(err)
	}
	line := strings.Repeat("x", 39) + "\n" // 40 bytes
	for i := 0; i < 3; i++ {
		r.Write([]byte(line))
	}
	cur, _ := os.ReadFile(path)
	old, _ := os.ReadFile(path + ".1")
	if len(old) != 80 || len(cur) != 40 {
		t.Fatalf("after rotation: current %d bytes, .1 %d bytes; want 40 and 80", len(cur), len(old))
	}
	// Reopening continues the existing file and its size.
	r.f.Close()
	r2, err := openRotating(path, 100)
	if err != nil {
		t.Fatal(err)
	}
	if r2.size != 40 {
		t.Errorf("reopened size %d, want 40", r2.size)
	}
}

func TestRequestLog(t *testing.T) {
	buf := captureLog(t, slog.LevelInfo)
	srv := httptest.NewServer(logRequests(newMux(t.TempDir())))
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/api/info", nil)
	req.Header.Set("User-Agent", "pic-sync-android/1.0")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	http.Get(srv.URL + "/nope")
	http.Get(srv.URL + "/favicon.ico") // quiet: debug only

	out := buf.String()
	for _, want := range []string{
		`level=INFO msg=request client=127.0.0.1 method=GET path=/api/info status=200`,
		`agent=pic-sync-android/1.0`,
		`level=WARN msg=request client=127.0.0.1 method=GET path=/nope status=404`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("log lacks %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "favicon") {
		t.Errorf("favicon request logged at info level:\n%s", out)
	}
}

func TestUploadLogDetails(t *testing.T) {
	buf := captureLog(t, slog.LevelInfo)
	mux := newMux(t.TempDir())
	uploadTo(t, mux, upFile{"a.jpg", "hello"})
	uploadTo(t, mux, upFile{"b.jpg", "hello"}) // duplicate
	out := buf.String()
	for _, want := range []string{
		`msg=saved file=a.jpg size="5 B"`,
		`date_from="none (upload time kept)"`,
		`msg="duplicate, not stored (identical file already in the folder)" file=a.jpg sent_as=b.jpg`,
		"sha256=2cf24dba5fb0",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("log lacks %q:\n%s", want, out)
		}
	}
}

func TestInterruptedUploadIsClientError(t *testing.T) {
	buf := captureLog(t, slog.LevelInfo)
	srv := httptest.NewServer(newMux(t.TempDir()))
	defer srv.Close()

	// A multipart body that stops in the middle of the file (no closing boundary).
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("file", "cut.jpg")
	fw.Write([]byte(strings.Repeat("z", 5000)))
	resp, err := http.Post(srv.URL+"/upload", mw.FormDataContentType(), &body)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("status %d, want 400", resp.StatusCode)
	}
	if out := buf.String(); !strings.Contains(out, "level=WARN msg=\"upload failed\" file=cut.jpg") ||
		!strings.Contains(out, "upload interrupted after 5 KB") {
		t.Errorf("interruption not logged as expected:\n%s", out)
	}
}

func TestMacSideFailureIs500(t *testing.T) {
	buf := captureLog(t, slog.LevelInfo)
	dir := filepath.Join(t.TempDir(), "gone")
	os.Mkdir(dir, 0o755)
	srv := httptest.NewServer(newMux(dir))
	defer srv.Close()
	os.Remove(dir) // e.g. the upload folder was on a disk that got ejected

	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("file", "a.jpg")
	fw.Write([]byte("data"))
	mw.Close()
	resp, err := http.Post(srv.URL+"/upload", mw.FormDataContentType(), &body)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusInternalServerError {
		t.Errorf("status %d, want 500", resp.StatusCode)
	}
	if !strings.Contains(buf.String(), "level=ERROR msg=\"upload failed\" file=a.jpg") {
		t.Errorf("not logged as an error:\n%s", buf.String())
	}
}

func TestBadNameStays400(t *testing.T) {
	captureLog(t, slog.LevelInfo)
	srv := httptest.NewServer(newMux(t.TempDir()))
	defer srv.Close()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("file", ".hidden")
	fw.Write([]byte("data"))
	mw.Close()
	resp, _ := http.Post(srv.URL+"/upload", mw.FormDataContentType(), &body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("status %d, want 400", resp.StatusCode)
	}
}
