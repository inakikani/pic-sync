// pic-sync: a tiny LAN server for uploading photos from a phone browser.
package main

import (
	_ "embed"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

//go:embed index.html
var indexHTML []byte

// apiVersion is bumped when the HTTP API changes incompatibly.
const apiVersion = "1"

type Config struct {
	UploadDir string `json:"upload_dir"`
	Listen    string `json:"listen"`
	LogFile   string `json:"log_file,omitempty"` // CLI mode: also log to this file (app mode always logs to ~/Library/Logs)
}

func loadConfig(path string) (Config, error) {
	var cfg Config
	data, err := os.ReadFile(path)
	if err != nil {
		return cfg, fmt.Errorf("reading config: %w", err)
	}
	if err := json.Unmarshal(data, &cfg); err != nil {
		return cfg, fmt.Errorf("parsing config %s: %w", path, err)
	}
	if cfg.UploadDir == "" {
		return cfg, errors.New("config: upload_dir is required")
	}
	for _, p := range []*string{&cfg.UploadDir, &cfg.LogFile} {
		if *p == "~" || strings.HasPrefix(*p, "~/") {
			home, err := os.UserHomeDir()
			if err != nil {
				return cfg, fmt.Errorf("expanding ~: %w", err)
			}
			*p = filepath.Join(home, strings.TrimPrefix(*p, "~"))
		}
	}
	if cfg.Listen == "" {
		cfg.Listen = ":8080"
	}
	return cfg, nil
}

func main() {
	configPath := flag.String("config", "config.json", "path to JSON config file")
	mode := flag.String("mode", "auto", `UX to use: "cli" (terminal + QR), "app" (macOS dialogs + browser QR page), or "auto" (app when launched from pic-sync.app, cli otherwise)`)
	fixDir := flag.String("fix-dates", "", "instead of serving, give files already in this folder the date they were taken (from EXIF/video metadata) and exit")
	dryRun := flag.Bool("dry-run", false, "with -fix-dates: only show what would change")
	debugLog := flag.Bool("debug", false, "log more detail (also requests for the Mac's own QR page)")
	flag.Parse()

	if *fixDir != "" {
		if err := fixDates(*fixDir, *dryRun, os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}

	configSet := false
	flag.Visit(func(f *flag.Flag) { configSet = configSet || f.Name == "config" })

	m, err := resolveMode(*mode, inAppBundle())
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		flag.Usage()
		os.Exit(2)
	}
	if m == "app" {
		appConfig := ""
		if configSet {
			appConfig = *configPath
		}
		runApp(appConfig, *debugLog)
		return
	}
	runCLI(*configPath, *debugLog)
}

// resolveMode turns the -mode flag into "cli" or "app".
func resolveMode(mode string, inBundle bool) (string, error) {
	switch mode {
	case "cli", "app":
		return mode, nil
	case "auto", "":
		if inBundle {
			return "app", nil
		}
		return "cli", nil
	}
	return "", fmt.Errorf("invalid -mode %q: want cli, app or auto", mode)
}

func runCLI(configPath string, debugLog bool) {
	setupLogging("", debugLog)
	cfg, err := loadConfig(configPath)
	if err != nil {
		fatal("loading config", err)
	}
	if err := setupLogging(cfg.LogFile, debugLog); err != nil {
		slog.Error("log file unavailable, logging to the terminal only", "err", err)
	}
	logStartup("mode", "cli", "config", configPath, "folder", cfg.UploadDir)
	ln, url, err := startListening(cfg)
	if err != nil {
		fatal("starting server", err)
	}
	if art, err := renderQR(url); err == nil {
		fmt.Printf("\nScan to open %s\n\n%s\n", url, art)
	}
	fatal("server stopped", serve(ln, newMux(cfg.UploadDir)))
}

func fatal(msg string, err error) {
	slog.Error(msg, "err", err)
	os.Exit(1)
}

// serve runs the HTTP server with request logging.
func serve(ln net.Listener, h http.Handler) error {
	srv := &http.Server{
		Handler:  logRequests(h),
		ErrorLog: slog.NewLogLogger(slog.Default().Handler(), slog.LevelWarn),
	}
	return srv.Serve(ln)
}

// startListening creates the upload dir, opens the listener, logs every LAN URL
// and returns the URL most likely reachable from the phone.
func startListening(cfg Config) (net.Listener, string, error) {
	if err := os.MkdirAll(cfg.UploadDir, 0o755); err != nil {
		return nil, "", fmt.Errorf("creating upload folder: %w", err)
	}
	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return nil, "", fmt.Errorf("listening on %s (is another pic-sync already running?): %w", cfg.Listen, err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ips := lanIPs()
	if len(ips) == 0 {
		slog.Warn("no LAN address found: is the Mac connected to Wi-Fi or Ethernet?")
		ips = []string{"localhost"}
	}
	for _, ip := range ips {
		slog.Info("address for the phone", "url", fmt.Sprintf("http://%s:%d", ip, port))
	}
	qrIP := ips[0]
	if p := primaryIP(); p != "" {
		qrIP = p
	}
	slog.Info("listening", "addr", ln.Addr().String(), "qr_url", fmt.Sprintf("http://%s:%d", qrIP, port))
	return ln, fmt.Sprintf("http://%s:%d", qrIP, port), nil
}

func newMux(dir string) *http.ServeMux {
	return newStoreMux(newStore(dir))
}

func newStoreMux(st *store) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(indexHTML)
	})
	mux.HandleFunc("POST /upload", func(w http.ResponseWriter, r *http.Request) {
		handleUpload(w, r, st)
	})
	// Lets clients (the Android app) confirm they are talking to pic-sync.
	mux.HandleFunc("GET /api/info", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"app": "pic-sync", "version": apiVersion})
	})
	return mux
}

func handleUpload(w http.ResponseWriter, r *http.Request, st *store) {
	t := st.act.begin(r.ContentLength)
	defer st.act.end(t)
	client := clientIP(r)
	mr, err := r.MultipartReader()
	if err != nil {
		slog.Warn("upload rejected: not a multipart form", "client", client, "content_type", r.Header.Get("Content-Type"))
		httpError(w, http.StatusBadRequest, "expected multipart/form-data")
		return
	}
	saved := []savedFile{}
	var clientTime time.Time // from an "mtime" field (ms since epoch) sent before the file
	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			// Typically the connection dropped between files (phone locked, Wi-Fi lost).
			err = fmt.Errorf("upload interrupted: %w", err)
			st.act.recordError("", err)
			slog.Warn("upload failed", "client", client, "err", err, "files_saved_before", len(saved))
			httpError(w, http.StatusBadRequest, err.Error())
			return
		}
		if part.FileName() == "" {
			if part.FormName() == "mtime" {
				clientTime = parseMillis(part)
			}
			part.Close()
			continue // ordinary form field
		}
		name := part.FileName()
		t.setName(name)
		start := time.Now()
		res, err := st.save(name, t.reader(part), clientTime)
		took := time.Since(start)
		clientTime = time.Time{} // applies to the next file only
		part.Close()
		if err != nil {
			st.act.recordError(name, err)
			code, level := http.StatusBadRequest, slog.LevelWarn
			if !isClientError(err) {
				code, level = http.StatusInternalServerError, slog.LevelError // the Mac's problem
			}
			slog.Log(r.Context(), level, "upload failed", "file", name, "client", client, "took", roundDur(took), "err", err)
			httpError(w, code, err.Error())
			return
		}
		logSaved(res, client, took)
		st.act.recordSaved(res)
		saved = append(saved, res)
	}
	if len(saved) == 0 {
		slog.Warn("upload rejected: no file in the request", "client", client)
		httpError(w, http.StatusBadRequest, "no files in request")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"saved": saved})
}

func logSaved(res savedFile, client string, took time.Duration) {
	args := []any{"file", res.Name}
	if res.Original != res.Name {
		args = append(args, "sent_as", res.Original)
	}
	args = append(args, "size", formatBytes(res.Size), "took", roundDur(took))
	if sp := speed(res.Size, took); sp != "" {
		args = append(args, "speed", sp)
	}
	args = append(args, "client", client, "sha256", res.SHA256[:12])
	if res.Duplicate {
		slog.Info("duplicate, not stored (identical file already in the folder)", args...)
		return
	}
	switch res.DateSource {
	case "file":
		args = append(args, "date", res.Date.Format("2006-01-02 15:04"), "date_from", "file metadata")
	case "client":
		args = append(args, "date", res.Date.Format("2006-01-02 15:04"), "date_from", "phone (no date in file)")
	default:
		args = append(args, "date_from", "none (upload time kept)")
	}
	slog.Info("saved", args...)
}

// sanitizeName strips any directory components a client may send.
// parseMillis reads a small form field holding milliseconds since the Unix
// epoch, as sent by File.lastModified. Anything else gives the zero time.
func parseMillis(r io.Reader) time.Time {
	b, _ := io.ReadAll(io.LimitReader(r, 32))
	ms, err := strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64)
	if err != nil || ms <= 0 {
		return time.Time{}
	}
	return time.UnixMilli(ms)
}

func sanitizeName(raw string) (string, error) {
	name := filepath.Base(strings.ReplaceAll(raw, "\\", "/"))
	if name == "" || name == "." || name == ".." || name == "/" || strings.HasPrefix(name, ".") {
		return "", fmt.Errorf("invalid filename %q", raw)
	}
	return name, nil
}

// claimName creates an empty placeholder for name, or "stem (N).ext" if taken.
func claimName(dir, name string) (string, error) {
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	for i := 0; i < 10000; i++ {
		candidate := name
		if i > 0 {
			candidate = fmt.Sprintf("%s (%d)%s", stem, i, ext)
		}
		f, err := os.OpenFile(filepath.Join(dir, candidate), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
		if err == nil {
			f.Close()
			return candidate, nil
		}
		if !errors.Is(err, os.ErrExist) {
			return "", err
		}
	}
	return "", fmt.Errorf("too many files named %q", name)
}

func httpError(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func lanIPs() []string {
	var ips []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ips
	}
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok && !ipn.IP.IsLoopback() && !ipn.IP.IsLinkLocalUnicast() && ipn.IP.To4() != nil {
			ips = append(ips, ipn.IP.String())
		}
	}
	if len(ips) == 0 {
		ips = append(ips, "localhost")
	}
	return ips
}
