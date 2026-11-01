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
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
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

	runningUI(cfg.UploadDir, url, statusURL)
	log.Print("stopped by user")
}

// runningUI shows the "pic-sync is running" window until the user presses Stop.
// It prefers a native alert with the QR code inside; if that can't be shown
// (e.g. a scripting error on some macOS version) it falls back to the plain
// AppleScript dialog plus the QR page in the browser.
func runningUI(dir, phoneURL, statusURL string) {
	msg := fmt.Sprintf("Scan with your phone, or open:\n%s\n\nPhotos are saved to:\n%s", phoneURL, dir)

	qrPath, err := writeTempQR(phoneURL)
	if err == nil {
		defer os.Remove(qrPath)
		for {
			button, err := qrAlert(msg, qrPath)
			if err != nil {
				log.Printf("QR window unavailable, falling back to dialog: %v", err)
				break
			}
			if button != "Open in browser" {
				return // "Stop"
			}
			openBrowser(statusURL)
		}
	} else {
		log.Printf("rendering QR image: %v", err)
	}

	openBrowser(statusURL)
	for runningDialog(dir, phoneURL) == "Show QR code" {
		openBrowser(statusURL)
	}
}

// writeTempQR renders the QR code to a temporary PNG for the native alert.
func writeTempQR(text string) (string, error) {
	data, err := qrPNG(text, 16) // 33 modules * 16px = 528px, shown at 264pt (2x on Retina)
	if err != nil {
		return "", err
	}
	f, err := os.CreateTemp("", "pic-sync-qr-*.png")
	if err != nil {
		return "", err
	}
	defer f.Close()
	if _, err := f.Write(data); err != nil {
		os.Remove(f.Name())
		return "", err
	}
	return f.Name(), nil
}

// qrAlertScript is JavaScript for Automation: it builds a native NSAlert with the
// QR image as its accessory view. argv[0] = message, argv[1] = PNG path.
// Buttons: "Open in browser" (default, Return key) and "Stop".
const qrAlertScript = `ObjC.import('AppKit');
function run(argv) {
  var size = 264;
  var img = $.NSImage.alloc.initWithContentsOfFile($(argv[1]));
  if (!img || img.isNil()) { throw new Error('cannot load QR image'); }
  img.setSize($.NSMakeSize(size, size));
  var view = $.NSImageView.alloc.initWithFrame($.NSMakeRect(0, 0, size, size));
  view.setImage(img);
  view.setImageScaling(3); // NSImageScaleProportionallyUpOrDown

  var alert = $.NSAlert.alloc.init;
  alert.setMessageText($('pic-sync is running'));
  alert.setInformativeText($(argv[0]));
  alert.setAccessoryView(view);
  alert.addButtonWithTitle($('Open in browser')); // 1000, default
  alert.addButtonWithTitle($('Stop'));            // 1001

  // osascript is a command-line tool: it must declare itself an (accessory, no Dock
  // icon) app before its windows can come to the front.
  var nsapp = $.NSApplication.sharedApplication;
  nsapp.setActivationPolicy(1); // NSApplicationActivationPolicyAccessory
  // Bring osascript forward the same way AppleScript's "activate" does (this is what
  // makes the plain dialogs appear); the AppKit call alone is ignored on macOS 14+.
  var self = Application.currentApplication();
  self.includeStandardAdditions = true;
  self.activate();
  nsapp.activateIgnoringOtherApps(true);
  // Even if activation is refused, float above normal windows so it can't hide.
  alert.layout;
  alert.window.setLevel(3); // NSFloatingWindowLevel
  alert.window.center;
  var response = alert.runModal;
  return response == 1000 ? 'Open in browser' : 'Stop';
}`

// qrAlert shows the native QR window and returns the pressed button's title.
func qrAlert(msg, qrPath string) (string, error) {
	out, err := runOsascript([]string{"-l", "JavaScript", "-e", qrAlertScript}, msg, qrPath)
	if err != nil {
		return "", err
	}
	if out != "Open in browser" && out != "Stop" {
		return "", fmt.Errorf("unexpected result %q", out)
	}
	return out, nil
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
		img, err := qrPNG(phoneURL, 16) // large source image; CSS scales it to the window
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Write(img)
	}))
}

var statusTmpl = template.Must(template.New("status").Parse(`<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>pic-sync</title>
<style>
 body { font-family: system-ui, sans-serif; text-align: center; padding: 2rem; }
 img { width: min(70vmin, 520px); height: auto; image-rendering: pixelated; border-radius: 8px; }
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
	return runOsascript([]string{"-e", script}, args...)
}

func runOsascript(scriptArgs []string, args ...string) (string, error) {
	cmd := exec.Command("osascript", append(scriptArgs, args...)...)
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
