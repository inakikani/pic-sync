package main

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"
)

// Logging uses log/slog text lines, e.g.
//
//	time=2026-11-02T14:03:05.123 level=INFO msg=saved file=IMG_1.jpg size=3.2MB took=1.2s
//
// Output goes to stderr and, if a log file is set (always in app mode), to that
// file. The file is capped at logMaxSize; the previous one is kept as <file>.1.
// See docs/troubleshooting.md for what is logged.

const logMaxSize = 5 << 20

// logFilePath is the active log file ("" if logging only to stderr).
var logFilePath string

func setupLogging(file string, debugLevel bool) error {
	level := slog.LevelInfo
	if debugLevel {
		level = slog.LevelDebug
	}
	var w io.Writer = os.Stderr
	var fileErr error
	if file != "" {
		rf, err := openRotating(file, logMaxSize)
		if err != nil {
			fileErr = fmt.Errorf("opening log file %s: %w", file, err)
		} else {
			// The file first: a broken stderr (no terminal) must not stop file writes.
			w = io.MultiWriter(rf, stderrIgnoringErrors{})
			logFilePath = file
		}
	}
	slog.SetDefault(slog.New(newLogHandler(w, level)))
	return fileErr
}

func newLogHandler(w io.Writer, level slog.Leveler) slog.Handler {
	return slog.NewTextHandler(w, &slog.HandlerOptions{
		Level: level,
		ReplaceAttr: func(groups []string, a slog.Attr) slog.Attr {
			if a.Key == slog.TimeKey && len(groups) == 0 {
				return slog.String(slog.TimeKey, a.Value.Time().Format("2006-01-02T15:04:05.000"))
			}
			return a
		},
	})
}

type stderrIgnoringErrors struct{}

func (stderrIgnoringErrors) Write(p []byte) (int, error) {
	os.Stderr.Write(p)
	return len(p), nil
}

// rotatingFile is an append-only file that is renamed to path+".1" (replacing
// the previous one) when it would grow past max.
type rotatingFile struct {
	mu   sync.Mutex
	path string
	max  int64
	f    *os.File
	size int64
}

func openRotating(path string, max int64) (*rotatingFile, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	r := &rotatingFile{path: path, max: max}
	return r, r.open()
}

func (r *rotatingFile) open() error {
	f, err := os.OpenFile(r.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return err
	}
	r.f, r.size = f, info.Size()
	return nil
}

func (r *rotatingFile) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.size > 0 && r.size+int64(len(p)) > r.max {
		r.f.Close()
		os.Rename(r.path, r.path+".1")
		if err := r.open(); err != nil {
			return 0, err
		}
	}
	n, err := r.f.Write(p)
	r.size += int64(n)
	return n, err
}

// logStartup writes one line describing what is running, so a pasted log is
// self-explanatory. extra are key/value pairs (mode, folder, addresses, ...).
func logStartup(extra ...any) {
	args := []any{
		"version", buildVersion(),
		"go", runtime.Version(),
		"os", runtime.GOOS + "/" + runtime.GOARCH,
	}
	if v := macOSVersion(); v != "" {
		args = append(args, "macos", v)
	}
	args = append(args, "pid", os.Getpid())
	if logFilePath != "" {
		args = append(args, "log", logFilePath)
	}
	slog.Info("==== pic-sync starting ====", append(args, extra...)...)
}

// buildVersion is the git commit the binary was built from (embedded by go build).
func buildVersion() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "unknown"
	}
	var rev, when, dirty string
	for _, s := range info.Settings {
		switch s.Key {
		case "vcs.revision":
			rev = s.Value
		case "vcs.time":
			when = s.Value
		case "vcs.modified":
			if s.Value == "true" {
				dirty = "+changes"
			}
		}
	}
	if rev == "" {
		return "dev" // go run, or built outside the git checkout
	}
	if len(rev) > 7 {
		rev = rev[:7]
	}
	if len(when) >= 10 {
		when = " (" + when[:10] + ")"
	}
	return rev + dirty + when
}

func macOSVersion() string {
	if runtime.GOOS != "darwin" {
		return ""
	}
	out, err := exec.Command("sw_vers", "-productVersion").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// ---- HTTP request log ----

// quietPaths are logged at debug level unless they fail on the server side:
// the Mac's own QR page and browsers' automatic favicon requests.
var quietPaths = map[string]bool{"/status": true, "/qr.png": true, "/favicon.ico": true}

// logRequests logs one line per HTTP request: who, what, result, size, time.
// Every phone connection shows up here, so "no request lines" means the phone
// never reached the Mac (wrong address, Wi-Fi, firewall).
func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		body := &countingBody{ReadCloser: r.Body}
		r.Body = body
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)

		level := slog.LevelInfo
		switch {
		case rec.status >= 500:
			level = slog.LevelError
		case quietPaths[r.URL.Path]:
			level = slog.LevelDebug
		case rec.status >= 400:
			level = slog.LevelWarn
		}
		args := []any{
			"client", clientIP(r), "method", r.Method, "path", r.URL.Path, "status", rec.status,
			"took", roundDur(time.Since(start)),
		}
		if body.n > 0 {
			args = append(args, "received", formatBytes(body.n))
		}
		args = append(args, "agent", truncate(r.UserAgent(), 80))
		slog.Log(context.Background(), level, "request", args...)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

type countingBody struct {
	io.ReadCloser
	n int64
}

func (c *countingBody) Read(p []byte) (int, error) {
	n, err := c.ReadCloser.Read(p)
	c.n += int64(n)
	return n, err
}

func clientIP(r *http.Request) string {
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

func roundDur(d time.Duration) time.Duration {
	switch {
	case d >= 10*time.Second:
		return d.Round(time.Second)
	case d >= time.Second:
		return d.Round(100 * time.Millisecond)
	default:
		return d.Round(time.Millisecond)
	}
}

// speed formats a transfer rate, e.g. "4.2MB/s".
func speed(n int64, d time.Duration) string {
	if d <= 0 || n <= 0 {
		return ""
	}
	return formatBytes(int64(float64(n)/d.Seconds())) + "/s"
}

func truncate(s string, max int) string {
	if r := []rune(s); len(r) > max {
		return string(r[:max-1]) + "…"
	}
	return s
}
