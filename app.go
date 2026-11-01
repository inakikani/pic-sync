package main

// macOS .app mode. When the binary runs from inside a .app bundle there is no
// terminal, so it uses osascript for native dialogs and a local browser page to
// show the QR code.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"image/png"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"rsc.io/qr"
)

func inAppBundle() bool {
	exe, err := os.Executable()
	return err == nil && strings.Contains(exe, ".app/Contents/MacOS/")
}

func appSupportDir() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Library", "Application Support", "pic-sync")
}

// loadAppConfig reads the app's config; a missing file or upload_dir is fine,
// since the folder is chosen interactively on every launch.
func loadAppConfig(path string) Config {
	cfg := Config{Listen: ":8080"}
	if data, err := os.ReadFile(path); err == nil {
		if err := json.Unmarshal(data, &cfg); err != nil {
			log.Printf("ignoring invalid config %s: %v", path, err)
		}
	}
	if cfg.Listen == "" {
		cfg.Listen = ":8080"
	}
	return cfg
}

func saveAppConfig(path string, cfg Config) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, _ := json.MarshalIndent(cfg, "", "  ")
	return os.WriteFile(path, append(data, '\n'), 0o644)
}

// runApp runs the dialog-driven UX. cfgPath overrides the default app config
// location (~/Library/Application Support/pic-sync/config.json) when non-empty.
func runApp(cfgPath string) {
	home, _ := os.UserHomeDir()
	if f, err := os.OpenFile(filepath.Join(home, "Library", "Logs", "pic-sync.log"),
		os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); err == nil {
		log.SetOutput(io.MultiWriter(os.Stderr, f)) // stderr is visible when started from a terminal
	}

	if cfgPath == "" {
		cfgPath = filepath.Join(appSupportDir(), "config.json")
	}
	cfg := loadAppConfig(cfgPath)

	dir, err := chooseFolder(cfg.UploadDir)
	if errors.Is(err, errCanceled) {
		return
	}
	if err != nil {
		fatalAlert("Could not choose a folder", err)
	}
	cfg.UploadDir = filepath.Clean(dir)
	if err := saveAppConfig(cfgPath, cfg); err != nil {
		log.Printf("saving config: %v", err)
	}

	ln, url, err := startListening(cfg)
	if err != nil {
		fatalAlert("Could not start the server", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	statusURL := fmt.Sprintf("http://localhost:%d/status", port)

	mux := newMux(cfg.UploadDir)
	addStatusPage(mux, cfg.UploadDir, url)
	go func() {
		fatalAlert("The server stopped", http.Serve(ln, mux))
	}()

	openBrowser(statusURL)
	for {
		switch runningDialog(cfg.UploadDir, url) {
		case "Show QR code":
			openBrowser(statusURL)
		default: // "Stop", or the dialog failed
			log.Print("stopped by user")
			return
		}
	}
}

// addStatusPage registers the Mac-side page showing the QR code. It is only
// served to the Mac itself, never to devices on the LAN.
func addStatusPage(mux *http.ServeMux, dir, phoneURL string) {
	localOnly := func(h http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			host, _, _ := net.SplitHostPort(r.RemoteAddr)
			if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
				http.NotFound(w, r)
				return
			}
			h(w, r)
		}
	}
	mux.HandleFunc("GET /status", localOnly(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		statusTmpl.Execute(w, map[string]string{"URL": phoneURL, "Dir": dir})
	}))
	mux.HandleFunc("GET /qr.png", localOnly(func(w http.ResponseWriter, r *http.Request) {
		code, err := qr.Encode(phoneURL, qr.M)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		code.Scale = 10
		var buf bytes.Buffer
		png.Encode(&buf, code.Image())
		w.Header().Set("Content-Type", "image/png")
		w.Write(buf.Bytes())
	}))
}

var statusTmpl = template.Must(template.New("status").Parse(`<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>pic-sync</title>
<style>
 body { font-family: system-ui, sans-serif; text-align: center; padding: 2rem; }
 img { width: 320px; height: 320px; image-rendering: pixelated; border: 16px solid #fff; }
 code { font-size: 1.2rem; }
 .dir { color: #555; }
</style></head>
<body>
<h1>Scan with your phone</h1>
<img src="/qr.png" alt="QR code for {{.URL}}">
<p>or open <code>{{.URL}}</code></p>
<p class="dir">Saving to {{.Dir}}</p>
<p class="dir">Use the pic-sync dialog to stop the server.</p>
</body></html>`))

var errCanceled = errors.New("canceled")

// osascript runs an AppleScript, passing args via argv so they are never
// interpolated into the script source.
func osascript(script string, args ...string) (string, error) {
	cmd := exec.Command("osascript", append([]string{"-e", script}, args...)...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		if strings.Contains(stderr.String(), "-128") { // user canceled
			return "", errCanceled
		}
		return "", fmt.Errorf("%v: %s", err, strings.TrimSpace(stderr.String()))
	}
	return strings.TrimSpace(string(out)), nil
}

func chooseFolder(defaultDir string) (string, error) {
	const prompt = "Where should photos from your phone be saved?"
	if info, err := os.Stat(defaultDir); defaultDir != "" && err == nil && info.IsDir() {
		return osascript(`on run argv
	activate
	return POSIX path of (choose folder with prompt (item 1 of argv) default location (POSIX file (item 2 of argv)))
end run`, prompt, defaultDir)
	}
	return osascript(`on run argv
	activate
	return POSIX path of (choose folder with prompt (item 1 of argv))
end run`, prompt)
}

// runningDialog blocks until the user presses a button and returns its name.
func runningDialog(dir, url string) string {
	msg := fmt.Sprintf("pic-sync is running.\n\nOn your phone, open:\n%s\n\nPhotos are saved to:\n%s", url, dir)
	out, err := osascript(`on run argv
	activate
	return button returned of (display dialog (item 1 of argv) with title "pic-sync" buttons {"Show QR code", "Stop"} default button "Show QR code")
end run`, msg)
	if err != nil {
		log.Printf("dialog: %v", err)
		return "Stop"
	}
	return out
}

func fatalAlert(title string, err error) {
	log.Printf("%s: %v", title, err)
	osascript(`on run argv
	activate
	display alert (item 1 of argv) message (item 2 of argv) as critical
end run`, title, err.Error())
	os.Exit(1)
}

func openBrowser(url string) {
	if err := exec.Command("open", url).Run(); err != nil {
		log.Printf("opening browser: %v", err)
	}
}
