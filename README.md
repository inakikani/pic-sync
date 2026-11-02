# pic-sync

A tiny LAN server for dumping photos and videos from a phone onto a Mac. You run it on the Mac, open the URL it prints in your phone's browser, pick files, and press Upload.

It's a single Go binary; its only dependency is `rsc.io/qr` for the startup QR code. The upload page is compiled in.

## Mac app

```sh
make app              # Apple Silicon -> bin/pic-sync.app
make app ARCH=amd64   # Intel Macs
```

Copy `pic-sync.app` to `/Applications` (or anywhere) and double-click it:

1. A folder picker asks where to save the photos. It defaults to the folder you picked last time.
2. pic-sync appears as an icon in the **menu bar** (top right) and runs in the background. The icon shows what it's doing:
   - icon only: running, waiting for uploads
   - **↑ 45%** or **↑ 12.3 MB**: receiving a file (**↑ 3 files** for several at once)
   - **!**: an upload failed; open the menu to see the error (this clears the **!**)
3. Click the icon for the menu:
   - status, today's count (e.g. *Today: 148 received, 3 already on Mac · last 2 min ago*) and the last error, if any
   - **Show QR code…**: a window with the QR code to scan with the phone, the phone address and the folder
   - **Open QR page in browser**: the same, larger, in your browser
   - **Open folder** in Finder, and **Change folder…** (new uploads go to the new folder right away)
   - **Open log**: what pic-sync has been doing, in Console (see [troubleshooting](docs/troubleshooting.md))
   - **Quit pic-sync**

The menu bar item needs to be built on a Mac (`make app` there; it uses the Xcode command line tools, which come with `git`). An app built elsewhere, e.g. cross-compiled from Linux, falls back to a **pic-sync is running** window with the QR code and **Open in browser** / **Stop** buttons.

The app keeps its settings in `~/Library/Application Support/pic-sync/config.json` (same format as below; `listen` sets the port) and writes a log to `~/Library/Logs/pic-sync.log`. The QR page is only served to the Mac itself, not to other devices on the LAN.

The app is not signed with an Apple developer ID. If macOS refuses to open it, right-click it and choose **Open** once, or run `xattr -cr pic-sync.app`. On the Mac you can also ad-hoc sign it with `make sign`.

## Choosing the UX

Both UXs are in the same binary. Pick one with `-mode`:

| `-mode` | UX |
|---------|----|
| `auto` (default) | app UX when launched from `pic-sync.app`, CLI otherwise |
| `cli`  | terminal: folder from the config file, QR code printed in the terminal |
| `app`  | folder picker dialog, QR page in the browser, running/Stop dialog |

```sh
./bin/pic-sync-darwin-arm64 -mode app                                    # app UX without the bundle
bin/pic-sync.app/Contents/MacOS/pic-sync -mode cli -config config.json   # CLI from the bundle
```

In app mode, `-config` replaces the default `~/Library/Application Support/pic-sync/config.json`. The folder you choose is saved back to that file.

## Command line

```sh
cp config.example.json config.json   # then edit upload_dir
make build-mac                       # bin/pic-sync-darwin-arm64 (Apple Silicon) / -amd64 (Intel)
./bin/pic-sync-darwin-arm64 -config config.json
```

Or on the Mac itself: `go run . -config config.json`.

At startup it prints a **QR code** of the server URL in the terminal. Scan it with the phone's camera (same Wi-Fi) to open the upload page. The QR encodes the address on the Mac's default route (normally Wi-Fi), and every LAN address is also printed as text in case you need a different one.

## Config

| key          | required | default | meaning                                   |
|--------------|----------|---------|-------------------------------------------|
| `upload_dir` | yes      |         | where files are written; `~` is expanded; created if missing |
| `listen`     | no       | `:8080` | address/port to listen on                 |
| `log_file`   | no       |         | CLI mode: also write the log to this file (capped at 5 MB, one `.1` copy kept). The app always logs to `~/Library/Logs/pic-sync.log` |

## Behaviour

- **Duplicates are detected by content, not name.** Each upload is SHA-256 hashed while it streams to disk and compared with existing files in `upload_dir` of the same size. If an identical file is already there, the upload is discarded and the page shows **already on Mac**. This works even for renamed copies and files you put in the folder yourself. Hashing happens during the transfer, so it doesn't noticeably slow uploads. A duplicate is still sent over the network before it's detected.
- **Files keep their original date.** The saved copy gets the date the photo or video was taken as its modification date (in Finder, *Created* moves back with it), not the upload time. The date comes from the file itself (EXIF in JPEG and HEIC, the movie header in MP4/MOV); for files without one (e.g. PNG screenshots) the date sent by the browser or the Android app is used. The file's contents, including EXIF, are never modified.
  Files uploaded before this existed can be fixed the same way: `pic-sync -fix-dates ~/Pictures/phone-dump -dry-run` shows what would change, and without `-dry-run` it applies it (only files with a date inside are changed).
- **Location (GPS):** the server stores exactly the bytes it receives. Android removes the location from photos it hands to apps that lack the *media location* permission. The Android app asks for it together with photo access; if photo access was already given (by an older version), allow it in the app under **Settings → Photo location**. Phone browsers can strip location too, before the file is sent, and the server can't restore it; on Android, use the app if you need location.
- Existing files are never overwritten. A duplicate name is saved as `IMG_001 (1).jpg`, `IMG_001 (2).jpg`, and so on.
- Uploads go to a hidden `.upload-*.part` temp file first and are renamed only once complete, so an interrupted upload leaves no partial file.
  - **Stalled connections:** an upload fails if no data arrives for 30 s, e.g. when the phone leaves Wi-Fi.
  - **Quitting:** quitting (Ctrl-C, **Quit pic-sync**, logout) gives uploads in progress 5 s to finish and removes their temp files.
  - **Crashes:** temp files left by a crash are deleted at the next start.
- Directory parts of client filenames are stripped, and hidden/dot names are rejected.
- There is **no authentication**. Anyone on your LAN can upload while the server runs, so only run it on trusted networks and stop it (Ctrl-C) when you're done. Web pages from other sites can't upload through your browser: such cross-site requests are rejected with 403.

## macOS notes (both modes)

- On first run macOS may ask whether to allow incoming network connections. Choose **Allow**, or the phone won't be able to connect.
- If you copied a binary that macOS refuses to open, clear the quarantine flag with `xattr -d com.apple.quarantine bin/pic-sync-darwin-arm64`.

## Android companion app

`android/` contains an optional Android app (Android 13+) that uploads photos to this server and can then **move the uploaded ones to the phone's trash**. The browser page can't delete anything on the phone; the app can.

**Build and install:** open the `android/` folder in Android Studio, connect your phone (USB or wireless debugging) and press Run. Studio downloads Gradle 9.6.1 and the dependencies on first sync.

**Using it:**
1. Start pic-sync on the Mac. In the app, tap **Scan QR code** (or type the address).
2. Optionally pick an **album** (Camera, Screenshots, WhatsApp Images, …) from the row at the top. Each one shows how many items still need uploading. Cloud-only Google Photos albums aren't visible to other apps, so only on-device folders appear.
3. Optionally tap **Filters** to narrow the view by **type** (photos/videos), **date taken** (last 7/30 days, this year, older than a year, or a custom range) and **minimum size** in MB.
4. Pick photos (or **Select not uploaded**) and tap **Upload**. The screen stays on while uploading.
   The grid is grouped by month (e.g. **November 2026 · 132 · 20 to upload**), newest first by date taken. While you scroll, a label at the top shows which month you're in.
   **Long-press** a photo to skip it (it dims and shows ⊘): it's never selected, uploaded or moved to trash, and doesn't count as *not uploaded*. Long-press again to undo. The app remembers skipped photos across restarts.
5. Tap **Move N uploaded to trash**. This applies to what you're viewing (album + filters), e.g. *Videos · Older than 1 year · ≥ 100 MB*, or to everything under *All photos* with no filters. Android asks once for the whole batch, and the photos stay recoverable in the phone's trash for 30 days.

**Safety:** a photo is only ever offered for trashing if the server's reported size and SHA-256 matched the bytes the phone sent, including uploads the server recognised as duplicates. If a photo was edited after uploading, it's re-hashed before trashing and skipped if it no longer matches.

**Permissions:** photos and videos (limited access works too) including their location, so uploads keep GPS data, and on Android 17 **Nearby devices / local network**, which is needed to reach the Mac. Optionally, allow **Media management** for pic-sync in Android Settings so trashing doesn't need the system confirmation. The app then asks for confirmation itself.

## Logs and troubleshooting

- **Mac:** the log shows every request, saved file and error. It is in the terminal, or for the app in `~/Library/Logs/pic-sync.log` (menu bar → **Open log**). Add `-debug` for more detail.
- **Android app:** tap a **!** photo to see why it failed. **Settings → Share diagnostics** sends the app's log.

[docs/troubleshooting.md](docs/troubleshooting.md) explains what is logged, how to read it, and fixes for common problems (phone can't connect, interrupted uploads, wrong dates, missing location, …).

## Development

The server needs Go 1.25 or newer (`go` downloads it automatically if yours is older).

```sh
make test                      # server tests + compile-check of the menu bar code
cd android && gradle test      # Android unit tests (or run them from Android Studio)
```
