package main

// macOS .app mode. When the binary runs from inside a .app bundle there is no
// terminal, so it uses osascript for native dialogs and a local browser page to
// show the QR code.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
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
			slog.Warn("ignoring invalid config", "path", path, "err", err)
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
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0o644)
}

// runApp runs the dialog-driven UX. cfgPath overrides the default app config
// location (~/Library/Application Support/pic-sync/config.json) when non-empty.
func runApp(cfgPath string, debugLog bool) {
	home, _ := os.UserHomeDir()
	if err := setupLogging(filepath.Join(home, "Library", "Logs", "pic-sync.log"), debugLog); err != nil {
		slog.Error("log file unavailable, logging to stderr only", "err", err)
	}

	if cfgPath == "" {
		cfgPath = filepath.Join(appSupportDir(), "config.json")
	}
	cfg := loadAppConfig(cfgPath)

	dir, err := chooseFolder(cfg.UploadDir)
	if errors.Is(err, errCanceled) {
		slog.Info("folder choice cancelled, quitting")
		return
	}
	if err != nil {
		fatalAlert("Could not choose a folder", err)
	}
	cfg.UploadDir = filepath.Clean(dir)
	if err := saveAppConfig(cfgPath, cfg); err != nil {
		slog.Error("saving config", "path", cfgPath, "err", err)
	}

	ui := "menu bar"
	if !trayAvailable {
		ui = "window (built without the menu bar)"
	}
	logStartup("mode", "app", "ui", ui, "config", cfgPath, "folder", cfg.UploadDir)
	ln, url, err := startListening(cfg)
	if err != nil {
		fatalAlert("Could not start the server", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	statusURL := fmt.Sprintf("http://localhost:%d/status", port)

	st := newStore(cfg.UploadDir)
	st.awake = newAwake(awakeIdle) // caffeinate also exits with us on the os.Exit paths
	defer st.awake.close()
	dirs, _ := cfg.folders() // checked by startListening
	st.SetFolders(dirs)
	mux := newStoreMux(st)
	addStatusPage(mux, func() []string { return st.Folders().summary() }, url)

	// The server runs until the user quits (quit) or macOS asks the app to stop,
	// e.g. at logout (SIGTERM). Either way it shuts down cleanly: uploads in
	// progress get a few seconds, and their temp files are removed.
	base, quit := context.WithCancel(context.Background())
	ctx, stop := signal.NotifyContext(base, os.Interrupt, syscall.SIGTERM)
	defer stop()
	served := make(chan struct{})
	go func() {
		defer close(served)
		err := serve(ctx, ln, mux)
		st.removeTemps()
		if err != nil {
			fatalAlert("The server stopped", err)
		}
	}()
	go func() {
		<-ctx.Done()
		if base.Err() == nil { // a signal, not the user quitting: the UI is still up
			<-served
			slog.Info("stopped by signal")
			os.Exit(0)
		}
	}()

	if trayAvailable {
		// Menu bar item (macOS builds with cgo, i.e. "make app" on a Mac).
		runTray(&trayApp{st: st, phoneURL: url, statusURL: statusURL, cfgPath: cfgPath, cfg: cfg})
	} else {
		runningUI(strings.Join(dirs.summary(), "\n"), url, statusURL)
	}
	slog.Info("stopped by user")
	quit()
	<-served
}

// trayApp is the state of the menu bar UI (see tray.go).
type trayApp struct {
	st        *store
	phoneURL  string
	statusURL string
	cfgPath   string
	cfg       Config
	busy      atomic.Bool  // a QR window or folder picker is open
	errSeenAt atomic.Int64 // unix nanos when the menu was last opened
}

// runningUI shows the "pic-sync is running" window until the user presses Stop.
// It prefers a native alert with the QR code inside; if that can't be shown
// (e.g. a scripting error on some macOS version) it falls back to the plain
// AppleScript dialog plus the QR page in the browser.
func runningUI(dirs, phoneURL, statusURL string) {
	msg := fmt.Sprintf("Scan with your phone, or open:\n%s\n\nFiles are saved to:\n%s", phoneURL, dirs)

	qrPath, err := writeTempQR(phoneURL)
	if err == nil {
		defer os.Remove(qrPath)
		for {
			button, err := qrAlert(msg, qrPath, "Open in browser", "Stop")
			if err != nil {
				slog.Warn("QR window unavailable, falling back to dialog", "err", err)
				break
			}
			if button != "Open in browser" {
				return // "Stop"
			}
			openBrowser(statusURL)
		}
	} else {
		slog.Error("rendering QR image", "err", err)
	}

	openBrowser(statusURL)
	for runningDialog(dirs, phoneURL) == "Show QR code" {
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
// QR image as its accessory view. argv[0] = message, argv[1] = PNG path,
// argv[2:] = button titles (the first is the default, Return key). It prints the
// title of the button pressed.
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
  var buttons = argv.slice(2);
  buttons.forEach(function (b) { alert.addButtonWithTitle($(b)); }); // 1000, 1001, ...

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
  var i = response - 1000;
  return (i >= 0 && i < buttons.length) ? buttons[i] : buttons[buttons.length - 1];
}`

// qrAlert shows the native QR window and returns the pressed button's title.
func qrAlert(msg, qrPath string, buttons ...string) (string, error) {
	args := append([]string{msg, qrPath}, buttons...)
	out, err := runOsascript([]string{"-l", "JavaScript", "-e", qrAlertScript}, args...)
	if err != nil {
		return "", err
	}
	for _, b := range buttons {
		if out == b {
			return out, nil
		}
	}
	return "", fmt.Errorf("unexpected result %q", out)
}

// addStatusPage registers the Mac-side page showing the QR code. It is only
// served to the Mac itself, never to devices on the LAN.
func addStatusPage(mux *http.ServeMux, dirs func() []string, phoneURL string) {
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
		if err := statusTmpl.Execute(w, map[string]any{"URL": phoneURL, "Folders": dirs()}); err != nil {
			slog.Warn("rendering status page", "err", err)
		}
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
<p class="dir">Saving to:<br>{{range .Folders}}{{.}}<br>{{end}}</p>
<p class="dir">Quit pic-sync from its menu bar icon (or its window) to stop the server.</p>
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
	start := time.Now()
	out, err := cmd.Output()
	// Includes the time the window was open; with a quick cancel it shows the start-up cost.
	slog.Debug("osascript", "took", roundDur(time.Since(start)), "err", err)
	if err != nil {
		if strings.Contains(stderr.String(), "-128") { // user canceled
			return "", errCanceled
		}
		return "", fmt.Errorf("%v: %s", err, strings.TrimSpace(stderr.String()))
	}
	return strings.TrimSpace(string(out)), nil
}

func chooseFolder(defaultDir string) (string, error) {
	return chooseFolderFor(catPhotos, defaultDir)
}

// chooseFolderFor asks where files of category c should be saved. The scripts
// deliberately don't "activate": pic-sync is a menu bar app, never the frontmost
// one, and from the background "activate" took about 2 s before the picker
// appeared (0.7 s without it). Without it the picker still shows in front.
func chooseFolderFor(c category, defaultDir string) (string, error) {
	prompt := "Where should " + strings.ToLower(c.label()) + " from your phone be saved?"
	if c == catOther {
		prompt = "Where should other files from your phone be saved?"
	}
	if info, err := os.Stat(defaultDir); defaultDir != "" && err == nil && info.IsDir() {
		return osascript(`on run argv
	return POSIX path of (choose folder with prompt (item 1 of argv) default location (POSIX file (item 2 of argv)))
end run`, prompt, defaultDir)
	}
	return osascript(`on run argv
	return POSIX path of (choose folder with prompt (item 1 of argv))
end run`, prompt)
}

// runningDialog blocks until the user presses a button and returns its name.
func runningDialog(dirs, url string) string {
	msg := fmt.Sprintf("pic-sync is running.\n\nOn your phone, open:\n%s\n\nFiles are saved to:\n%s", url, dirs)
	out, err := osascript(`on run argv
	activate
	return button returned of (display dialog (item 1 of argv) with title "pic-sync" buttons {"Show QR code", "Stop"} default button "Show QR code")
end run`, msg)
	if err != nil {
		slog.Warn("dialog failed", "err", err)
		return "Stop"
	}
	return out
}

func fatalAlert(title string, err error) {
	errorAlert(title, err)
	os.Exit(1)
}

// errorAlert shows a critical alert and logs the error, without exiting.
func errorAlert(title string, err error) {
	slog.Error(title, "err", err)
	osascript(`on run argv
	activate
	display alert (item 1 of argv) message (item 2 of argv) as critical
end run`, title, err.Error())
}

// openPath opens a file or folder in Finder.
func openPath(path string) {
	if err := exec.Command("open", path).Run(); err != nil {
		slog.Warn("opening in Finder failed", "path", path, "err", err)
	}
}

func openBrowser(url string) {
	if err := exec.Command("open", url).Run(); err != nil {
		slog.Warn("opening browser failed", "err", err)
	}
}
