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
	flag.Parse()

	if inAppBundle() {
		runApp()
		return
	}
	runCLI(*configPath)
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
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(indexHTML)
	})
	mux.HandleFunc("POST /upload", func(w http.ResponseWriter, r *http.Request) {
		handleUpload(w, r, dir)
	})
	return mux
}

func handleUpload(w http.ResponseWriter, r *http.Request, dir string) {
	mr, err := r.MultipartReader()
	if err != nil {
		httpError(w, http.StatusBadRequest, "expected multipart/form-data")
		return
	}
	saved := []string{}
	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			httpError(w, http.StatusBadRequest, "reading upload: "+err.Error())
			return
		}
		if part.FileName() == "" {
			part.Close()
			continue // ordinary form field
		}
		name, err := saveFile(dir, part.FileName(), part)
		part.Close()
		if err != nil {
			log.Printf("upload failed (%q): %v", part.FileName(), err)
			httpError(w, http.StatusBadRequest, err.Error())
			return
		}
		log.Printf("saved %s", name)
		saved = append(saved, name)
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

// saveFile streams src into dir under a unique version of rawName and returns the final name.
func saveFile(dir, rawName string, src io.Reader) (string, error) {
	name, err := sanitizeName(rawName)
	if err != nil {
		return "", err
	}

	// Stream to a temp file first so a broken upload never appears under a real name.
	tmp, err := os.CreateTemp(dir, ".upload-*.part")
	if err != nil {
		return "", err
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath) // no-op after successful rename
	if _, err := io.Copy(tmp, src); err != nil {
		tmp.Close()
		return "", fmt.Errorf("writing file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return "", err
	}
	if err := os.Chmod(tmpPath, 0o644); err != nil { // CreateTemp uses 0600
		return "", err
	}

	// Atomically claim a free final name, then move the data into it.
	final, err := claimName(dir, name)
	if err != nil {
		return "", err
	}
	if err := os.Rename(tmpPath, filepath.Join(dir, final)); err != nil {
		os.Remove(filepath.Join(dir, final))
		return "", err
	}
	return final, nil
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
