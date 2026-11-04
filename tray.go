//go:build (darwin && cgo) || (linux && traycheck)

// Menu bar item for the macOS app. Built only with cgo on macOS ("make app" on a
// Mac). The linux+traycheck variant exists solely so the code can be
// compile-checked on Linux: go vet -tags traycheck ./...

package main

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	"fyne.io/systray"
)

const trayAvailable = true

// runTray shows the menu bar item and blocks until "Quit pic-sync".
// It must run on the main goroutine (systray locks it to the main thread).
func runTray(t *trayApp) {
	systray.Run(t.onReady, func() {})
}

func (t *trayApp) onReady() {
	icon := trayIcon()
	systray.SetTemplateIcon(icon, icon)
	systray.SetTooltip("pic-sync")

	mStatus := systray.AddMenuItem("Starting…", "")
	mStatus.Disable()
	mToday := systray.AddMenuItem("", "")
	mToday.Disable()
	mErr := systray.AddMenuItem("", "")
	mErr.Disable()
	mErr.Hide()
	systray.AddSeparator()
	mAddr := systray.AddMenuItem("Phone: "+t.phoneURL, "Open this address on your phone")
	mAddr.Disable()
	mQR := systray.AddMenuItem("Show QR code…", "Show the code to scan with your phone")
	mBrowser := systray.AddMenuItem("Open QR page in browser", "A larger QR code in your browser")
	systray.AddSeparator()
	mFolder := systray.AddMenuItem("", "Open the photos folder in Finder")
	// "Folders" submenu: one entry per category; clicking one changes its folder.
	mFolders := systray.AddMenuItem("Folders", "Choose where each type of file is saved")
	catItems := make([]*systray.MenuItem, len(categories))
	catClicks := make(chan category, 1)
	for i, c := range categories {
		catItems[i] = mFolders.AddSubMenuItem("", "Change where "+strings.ToLower(c.label())+" are saved")
		go func(item *systray.MenuItem, c category) {
			for range item.ClickedCh {
				catClicks <- c
			}
		}(catItems[i], c)
	}
	// Extension rules are only edited in config.json; list them for reference.
	if exts := t.st.Folders().extList(); len(exts) > 0 {
		mFolders.AddSubMenuItem("Extension rules (config.json): ."+strings.Join(exts, ", ."), "").Disable()
	}
	systray.AddSeparator()
	mLog := systray.AddMenuItem("Open log", "Show what pic-sync has been doing (opens in Console)")
	if logFilePath == "" {
		mLog.Disable()
	}
	mQuit := systray.AddMenuItem("Quit pic-sync", "Stop the server and quit")

	// Only push changes to AppKit, so an idle menu bar does no work.
	var last trayStatus
	var lastFolders folders
	foldersShown := false
	errShown := false
	refresh := func() {
		s := describe(t.st.act.snapshot(), time.Now(), time.Unix(0, t.errSeenAt.Load()))
		if s.Title != last.Title {
			systray.SetTitle(s.Title)
		}
		if s.Status != last.Status {
			mStatus.SetTitle(s.Status)
			systray.SetTooltip("pic-sync · " + s.Status)
		}
		if s.Today != last.Today {
			mToday.SetTitle(s.Today)
		}
		if s.LastErr != last.LastErr {
			mErr.SetTitle(truncate(s.LastErr, 90))
		}
		if (s.LastErr != "") != errShown {
			errShown = s.LastErr != ""
			if errShown {
				mErr.Show()
			} else {
				mErr.Hide()
			}
		}
		if f := t.st.Folders(); !foldersShown || !sameFolders(f, lastFolders) {
			lastFolders, foldersShown = f, true
			mFolder.SetTitle("Open folder: " + shortPath(f.Photos))
			for i, c := range categories {
				catItems[i].SetTitle(c.label() + ": " + f.describeFolder(c))
			}
		}
		last = s
	}
	refresh()

	changed := make(chan struct{}, 1)
	go func() {
		tick := time.NewTicker(time.Second)
		defer tick.Stop()
		for {
			select {
			case <-tick.C:
				refresh()
			case <-changed:
				refresh()
			case <-systray.TrayOpenedCh:
				t.errSeenAt.Store(time.Now().UnixNano()) // the user has seen any error now
				refresh()
			case <-mQR.ClickedCh:
				go t.showQR()
			case <-mBrowser.ClickedCh:
				openBrowser(t.statusURL)
			case <-mFolder.ClickedCh:
				openPath(t.st.Dir())
			case <-mLog.ClickedCh:
				openPath(logFilePath)
			case c := <-catClicks:
				go func() {
					t.changeFolder(c)
					select {
					case changed <- struct{}{}:
					default:
					}
				}()
			case <-mQuit.ClickedCh:
				systray.Quit()
				return
			}
		}
	}()
}

// showQR opens the native QR window (one at a time).
func (t *trayApp) showQR() {
	if !t.busy.CompareAndSwap(false, true) {
		return
	}
	defer t.busy.Store(false)

	msg := fmt.Sprintf("Scan with your phone, or open:\n%s\n\nFiles are saved to:\n%s", t.phoneURL, strings.Join(t.st.Folders().summary(), "\n"))
	qrPath, err := writeTempQR(t.phoneURL)
	if err != nil {
		slog.Error("rendering QR image", "err", err)
		openBrowser(t.statusURL)
		return
	}
	defer os.Remove(qrPath)
	button, err := qrAlert(msg, qrPath, "Close", "Open in browser")
	if err != nil {
		slog.Warn("QR window unavailable, opening browser instead", "err", err)
		openBrowser(t.statusURL)
		return
	}
	if button == "Open in browser" {
		openBrowser(t.statusURL)
	}
}

// changeFolder lets the user pick a new folder for category c; new uploads go
// there. Categories other than photos can also go back to the photos folder.
func (t *trayApp) changeFolder(c category) {
	if !t.busy.CompareAndSwap(false, true) {
		return
	}
	defer t.busy.Store(false)

	cur := t.st.Folders()
	if c != catPhotos {
		msg := fmt.Sprintf("%s are saved to:\n%s", c.label(), cur.describeFolder(c))
		if c == catOther {
			msg = fmt.Sprintf("Other files (not photos, videos or documents) are saved to:\n%s", cur.describeFolder(c))
		}
		buttons := []string{"Cancel", "Choose Folder…"}
		if cur.Category[c] != "" {
			buttons = []string{"Cancel", "Same as Photos", "Choose Folder…"}
		}
		button, err := choiceDialog(msg, buttons...)
		if err != nil {
			if !errors.Is(err, errCanceled) {
				slog.Warn("folder dialog failed", "err", err)
			}
			return
		}
		if button == "Same as Photos" {
			t.setFolders(cur.with(c, ""), c)
			return
		}
	}
	dir, err := chooseFolderFor(c, cur.forCategory(c))
	if errors.Is(err, errCanceled) {
		return
	}
	if err == nil {
		dir = filepath.Clean(dir)
		err = os.MkdirAll(dir, 0o755)
	}
	if err != nil {
		errorAlert("Could not change the folder", err)
		return
	}
	t.setFolders(cur.with(c, dir), c)
}

// setFolders switches the store to f and saves it to the config file.
func (t *trayApp) setFolders(f folders, changed category) {
	t.st.SetFolders(f)
	t.cfg.setFolders(f)
	if err := saveAppConfig(t.cfgPath, t.cfg); err != nil {
		slog.Error("saving config", "path", t.cfgPath, "err", err)
	}
	slog.Info("upload folder changed", "category", changed, "folder", f.describeFolder(changed))
}

// sameFolders reports whether a and b route the categories identically.
func sameFolders(a, b folders) bool {
	for _, c := range categories {
		if a.describeFolder(c) != b.describeFolder(c) {
			return false
		}
	}
	return true
}
