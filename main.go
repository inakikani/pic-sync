// pic-sync: a tiny LAN server for uploading photos from a phone browser.
package main

import (
	_ "embed"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

//go:embed index.html
var indexHTML []byte

// apiVersion is bumped when the HTTP API changes incompatibly.
const apiVersion = "1"

type Config struct {
	UploadDir string `json:"upload_dir"`
	Listen    string `json:"listen"`
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
	if cfg.UploadDir == "~" || strings.HasPrefix(cfg.UploadDir, "~/") {
		home, err := os.UserHomeDir()
		if err != nil {
			return cfg, fmt.Errorf("expanding ~: %w", err)
		}
		cfg.UploadDir = filepath.Join(home, strings.TrimPrefix(cfg.UploadDir, "~"))
	}
	if cfg.Listen == "" {
		cfg.Listen = ":8080"
	}
	return cfg, nil
}

func main() {
	configPath := flag.String("config", "config.json", "path to JSON config file")
	mode := flag.String("mode", "auto", `UX to use: "cli" (terminal + QR), "app" (macOS dialogs + browser QR page), or "auto" (app when launched from pic-sync.app, cli otherwise)`)
	flag.Parse()

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
		runApp(appConfig)
		return
	}
	runCLI(*configPath)
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

func runCLI(configPath string) {
	cfg, err := loadConfig(configPath)
	if err != nil {
		log.Fatal(err)
	}
	ln, url, err := startListening(cfg)
	if err != nil {
		log.Fatal(err)
	}
	if art, err := renderQR(url); err == nil {
		fmt.Printf("\nScan to open %s\n\n%s\n", url, art)
	}
	log.Fatal(http.Serve(ln, newMux(cfg.UploadDir)))
}

// startListening creates the upload dir, opens the listener, logs every LAN URL
// and returns the URL most likely reachable from the phone.
func startListening(cfg Config) (net.Listener, string, error) {
	if err := os.MkdirAll(cfg.UploadDir, 0o755); err != nil {
		return nil, "", fmt.Errorf("creating upload folder: %w", err)
	}
	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return nil, "", err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	log.Printf("saving uploads to %s", cfg.UploadDir)
	ips := lanIPs()
	for _, ip := range ips {
		log.Printf("open on your phone: http://%s:%d", ip, port)
	}
	qrIP := ips[0]
	if p := primaryIP(); p != "" {
		qrIP = p
	}
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
	mr, err := r.MultipartReader()
	if err != nil {
		httpError(w, http.StatusBadRequest, "expected multipart/form-data")
		return
	}
	saved := []savedFile{}
	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			st.act.recordError("", fmt.Errorf("reading upload: %w", err))
			httpError(w, http.StatusBadRequest, "reading upload: "+err.Error())
			return
		}
		if part.FileName() == "" {
			part.Close()
			continue // ordinary form field
		}
		t.setName(part.FileName())
		res, err := st.save(part.FileName(), t.reader(part))
		part.Close()
		if err != nil {
			st.act.recordError(part.FileName(), err)
			log.Printf("upload failed (%q): %v", part.FileName(), err)
			httpError(w, http.StatusBadRequest, err.Error())
			return
		}
		if res.Duplicate {
			log.Printf("skipped %s: duplicate of %s", res.Original, res.Name)
		} else {
			log.Printf("saved %s", res.Name)
		}
		st.act.recordSaved(res)
		saved = append(saved, res)
	}
	if len(saved) == 0 {
		httpError(w, http.StatusBadRequest, "no files in request")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"saved": saved})
}

// sanitizeName strips any directory components a client may send.
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
