package main

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPageLog(t *testing.T) {
	buf := captureLog(t, slog.LevelInfo)
	srv := httptest.NewServer(newHandler(newMux(t.TempDir())))
	defer srv.Close()

	post := func(body string, headers map[string]string) int {
		req, _ := http.NewRequest("POST", srv.URL+"/api/page-log", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
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

	if got := post(`{"event":"picker returned files","count":3,"mb":12.1,"types":"image/heic×3","nested":{"x":1}}`,
		map[string]string{"Sec-Fetch-Site": "same-origin"}); got != 204 {
		t.Errorf("status %d, want 204", got)
	}
	if got := post(`{"event":"picker returned nothing","seconds":4}`, nil); got != 204 {
		t.Errorf("status %d, want 204", got)
	}
	for body, want := range map[string]int{
		`not json`:    400,
		`{"count":1}`: 400, // no event name
		`{"event":"x","pad":"` + strings.Repeat("a", 5000) + `"}`: 400,
	} {
		if got := post(body, nil); got != want {
			t.Errorf("%.30q: status %d, want %d", body, got, want)
		}
	}
	if got := post(`{"event":"evil"}`, map[string]string{"Sec-Fetch-Site": "cross-site", "Origin": "https://evil.example"}); got != 403 {
		t.Errorf("cross-site event: status %d, want 403", got)
	}

	out := buf.String()
	for _, want := range []string{
		`level=INFO msg="page: picker returned files" client=127.0.0.1 count=3 mb=12.1 types=image/heic×3`,
		`level=WARN msg="page: picker returned nothing" client=127.0.0.1 seconds=4`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("log lacks %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "nested") {
		t.Errorf("non-scalar field logged:\n%s", out)
	}
	if strings.Contains(out, "msg=request client=127.0.0.1 method=POST path=/api/page-log") {
		t.Errorf("page-log requests should only be logged at debug level:\n%s", out)
	}
}
