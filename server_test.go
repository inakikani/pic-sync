package main

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"mime/multipart"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func multipartBody(t *testing.T, name, content string) (*bytes.Buffer, string) {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, err := mw.CreateFormFile("file", name)
	if err != nil {
		t.Fatal(err)
	}
	fw.Write([]byte(content))
	mw.Close()
	return &body, mw.FormDataContentType()
}

func TestCrossSiteUploadsRejected(t *testing.T) {
	captureLog(t, slog.LevelInfo)
	dir := t.TempDir()
	srv := httptest.NewServer(newHandler(newMux(dir)))
	defer srv.Close()

	post := func(name string, headers map[string]string) int {
		body, ct := multipartBody(t, name, "content of "+name)
		req, _ := http.NewRequest("POST", srv.URL+"/upload", body)
		req.Header.Set("Content-Type", ct)
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}

	for _, c := range []struct {
		name    string
		headers map[string]string
		want    int
	}{
		{"app.jpg", nil, 200}, // Android app, curl: no browser headers
		{"page.jpg", map[string]string{"Sec-Fetch-Site": "same-origin", "Origin": srv.URL}, 200},
		{"evil1.jpg", map[string]string{"Sec-Fetch-Site": "cross-site", "Origin": "https://evil.example"}, 403},
		{"evil2.jpg", map[string]string{"Origin": "https://evil.example"}, 403}, // older browser: Origin only
	} {
		if got := post(c.name, c.headers); got != c.want {
			t.Errorf("%s: status %d, want %d", c.name, got, c.want)
		}
		_, err := os.Stat(filepath.Join(dir, c.name))
		if saved := err == nil; saved != (c.want == 200) {
			t.Errorf("%s: saved=%v, want %v", c.name, saved, c.want == 200)
		}
	}
	// Reading pages is never blocked.
	req, _ := http.NewRequest("GET", srv.URL+"/", nil)
	req.Header.Set("Sec-Fetch-Site", "cross-site")
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != 200 {
		t.Errorf("GET / cross-site: %v %v", resp.StatusCode, err)
	}
}

func TestStalledUploadTimesOut(t *testing.T) {
	buf := captureLog(t, slog.LevelInfo)
	old := uploadIdleTimeout
	uploadIdleTimeout = 200 * time.Millisecond
	t.Cleanup(func() { uploadIdleTimeout = old })

	dir := t.TempDir()
	srv := httptest.NewServer(newHandler(newMux(dir)))
	defer srv.Close()

	// Send the headers and the start of a file, then go silent like a phone
	// that lost Wi-Fi.
	conn, err := net.Dial("tcp", srv.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	const boundary = "XyZ"
	start := "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"stall.mp4\"\r\n\r\n" +
		strings.Repeat("v", 4000)
	fmt.Fprintf(conn, "POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=%s\r\nContent-Length: 1000000\r\n\r\n%s",
		boundary, start)

	began := time.Now()
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatalf("no response to a stalled upload: %v", err)
	}
	resp.Body.Close()
	if took := time.Since(began); took > 3*time.Second {
		t.Errorf("stalled upload took %v to fail", took)
	}
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("status %d, want 400", resp.StatusCode)
	}
	if !strings.Contains(buf.String(), "upload interrupted after 4 KB") || !strings.Contains(buf.String(), "timeout") {
		t.Errorf("log:\n%s", buf.String())
	}
	if left, _ := filepath.Glob(filepath.Join(dir, ".upload-*")); len(left) != 0 {
		t.Errorf("temp files left: %v", left)
	}
}

func TestServeShutsDownOnCancel(t *testing.T) {
	captureLog(t, slog.LevelInfo)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- serve(ctx, ln, newMux(t.TempDir())) }()

	resp, err := http.Get("http://" + ln.Addr().String() + "/api/info")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Errorf("serve returned %v after shutdown, want nil", err)
		}
	case <-time.After(shutdownGrace + 2*time.Second):
		t.Fatal("serve didn't return after cancel")
	}
	if _, err := http.Get("http://" + ln.Addr().String() + "/api/info"); err == nil {
		t.Error("server still answering after shutdown")
	}
}

func TestRemoveTemps(t *testing.T) {
	captureLog(t, slog.LevelInfo)
	dir := t.TempDir()
	st := newStore(dir)
	p := filepath.Join(dir, ".upload-123.part")
	os.WriteFile(p, []byte("half a video"), 0o600)
	st.trackTemp(p, true)
	st.removeTemps()
	if _, err := os.Stat(p); !os.IsNotExist(err) {
		t.Error("tracked temp file not removed")
	}
	st.removeTemps() // nothing left: no error
}

func TestRemoveStaleTemps(t *testing.T) {
	captureLog(t, slog.LevelInfo)
	dir := t.TempDir()
	now := time.Now()
	write := func(name string, age time.Duration) string {
		p := filepath.Join(dir, name)
		os.WriteFile(p, []byte("x"), 0o600)
		os.Chtimes(p, now.Add(-age), now.Add(-age))
		return p
	}
	stale := write(".upload-1.part", time.Hour)
	active := write(".upload-2.part", 10*time.Second) // another instance's upload in progress
	photo := write("IMG_1.jpg", time.Hour)
	removeStaleTemps(dir, now)
	if _, err := os.Stat(stale); !os.IsNotExist(err) {
		t.Error("stale temp file kept")
	}
	for _, p := range []string{active, photo} {
		if _, err := os.Stat(p); err != nil {
			t.Errorf("%s removed", filepath.Base(p))
		}
	}
}

func TestLanIPsHasNoPlaceholder(t *testing.T) {
	for _, ip := range lanIPs() {
		if net.ParseIP(ip) == nil {
			t.Errorf("lanIPs returned %q, not an IP", ip)
		}
	}
}
