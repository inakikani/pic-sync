# pic-sync

A small LAN server for copying photos and videos from a phone to a Mac. Run it on the Mac, scan the QR code with the phone, pick files and upload.

It's a single Go binary with the upload page compiled in. Its only dependency is `rsc.io/qr`.

## Mac app

```sh
make app              # Apple Silicon -> bin/pic-sync.app
make app ARCH=amd64   # Intel
```

Copy `pic-sync.app` anywhere (e.g. `/Applications`) and open it. It asks for a destination folder (defaulting to the last one) and then runs in the **menu bar**. The icon shows its state:

- icon only: waiting for uploads
- **↑ 45%**, **↑ 12.3 MB** or **↑ 3 files**: receiving
- **!**: an upload failed (opening the menu shows the error and clears it)

The menu shows today's count (e.g. *Today: 148 received, 3 already on Mac · last 2 min ago*) and the last error. It also has **Show QR code…**, **Open QR page in browser**, **Open folder**, **Change folder…** (takes effect immediately), **Open log** and **Quit pic-sync**. The QR page is only served to the Mac itself.

The menu bar item needs cgo, so build on a Mac with the Xcode command line tools. A cross-compiled app shows a plain window with the QR code and **Open in browser** / **Stop** buttons instead.

Settings are in `~/Library/Application Support/pic-sync/config.json` (format [below](#config)).

**First launch of a downloaded copy:** the app isn't notarized, so macOS blocks it. Dismiss the warning, then go to **System Settings → Privacy & Security** and click **Open Anyway**. Or run `xattr -dr com.apple.quarantine /Applications/pic-sync.app`. Apps you build yourself aren't affected; `make sign` ad-hoc signs them.

### Releasing

On a Mac:

```sh
git tag v1.0 && git push origin v1.0
make dist      # all three below
make release   # bin/pic-sync-1.0-macos.zip: universal, ad-hoc signed
make pkg       # bin/pic-sync-1.0.pkg, installs to /Applications
make apk       # bin/pic-sync-1.0.apk: Android release build, plus -mapping.txt (keep it private)
gh release create v1.0 bin/pic-sync-1.0-macos.zip bin/pic-sync-1.0.pkg bin/pic-sync-1.0.apk \
  --title "pic-sync 1.0" --notes "…"
```

The version comes from the latest tag. Override it with `VERSION=1.1`. The Android app gets the same version, and `versionCode` is derived from it (1.2.3 → 1002003), so each release installs as an update.

`make apk` uses `android/gradlew` if present, otherwise `gradle` on the `PATH` (override with `GRADLE=…`). It needs a JDK, e.g. `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`, and `android/local.properties`, which Android Studio creates. The APK is signed with your local debug key, so updates only install over APKs built on the same machine.

## Command line

```sh
cp config.example.json config.json   # set upload_dir
make build-mac                       # bin/pic-sync-darwin-arm64 / -amd64
./bin/pic-sync-darwin-arm64 -config config.json   # or: go run . -config config.json
```

It prints a QR code of the server URL. The URL uses the Mac's default-route address (normally Wi-Fi). All LAN addresses are also printed. If macOS refuses to run a copied binary, run `xattr -d com.apple.quarantine <binary>`.

### Modes

The same binary does both. Choose with `-mode`:

| `-mode` | UX |
|---------|----|
| `auto` (default) | app when launched from `pic-sync.app`, otherwise CLI |
| `cli`  | config file, QR code in the terminal |
| `app`  | folder picker, then the menu bar (or the running/Stop window) |

In app mode, `-config` overrides the default config path, and the chosen folder is saved to it.

## Config

| key          | required | default | meaning |
|--------------|----------|---------|---------|
| `upload_dir` | yes      |         | destination; `~` expanded; created if missing |
| `listen`     | no       | `:8080` | listen address/port |
| `log_file`   | no       |         | CLI only: also log to this file (5 MB cap, one `.1` backup) |

## Behaviour

- **Duplicates are detected by content.** Each upload is SHA-256 hashed as it streams and compared with same-size files in `upload_dir`. Identical files are discarded and reported as **already on Mac**, even if renamed. The file is still transferred before this check.
- **Files keep their original date.** The modification date is set from EXIF (JPEG, HEIC) or the movie header (MP4, MOV), or from the date sent by the client if the file has none. File contents are never modified. To fix older uploads, run `pic-sync -fix-dates <dir>`; add `-dry-run` to preview.
- **Location (GPS)** is stored if it arrives. Android strips it for apps without the *media location* permission, and phone browsers may strip it too. On Android, use the app (**Settings → Photo location**) to keep it.
- **Nothing is overwritten.** Name clashes become `IMG_001 (1).jpg`, `IMG_001 (2).jpg`, and so on.
- **No partial files.** Uploads are written to a hidden `.upload-*.part` file and renamed when complete. An upload fails after 30 s without data. On quit, in-progress uploads get 5 s to finish. Leftover temp files are deleted at startup.
- **Filenames are sanitised.** Directory parts are stripped and dot names rejected.
- **No authentication.** Anyone on the LAN can upload while it runs, so only use it on trusted networks. Cross-site requests from other web pages are rejected with 403.

On first run macOS may ask to allow incoming connections. Choose **Allow**.

## Android app

`android/` contains an optional Android 13+ app (Material 3 Expressive). Unlike the browser page, it can **move uploaded photos to the phone's trash**.

**Install:** open `android/` in Android Studio, set **Build Variants → app** to **release**, connect the phone and press Run. The debug build is unoptimised and can stutter. The release build is signed with your debug key, so it installs over the debug build and keeps app data.

**Use:**
1. Start pic-sync on the Mac, then tap **Scan QR code** (or type the address).
2. Optionally pick an **album** and switch between **All / To upload / Uploaded**. The **filters** button narrows by type, date taken and minimum size. Only on-device albums appear, not cloud-only Google Photos albums.
3. Select photos (or **Select not uploaded**, or **Select N** on a month header) and tap **Upload**. The screen stays on while uploading.
4. Tap **Move N uploaded to trash**. This applies to the current album and filters. Android asks once per batch, and trashed items are recoverable for 30 days.

The grid is grouped by month, newest first. Tap a thumbnail's full-screen button to view it. Swipe to browse, pinch or double-tap to zoom, and tap to toggle the info bar. **Long-press** a photo to skip it. Skipped photos are never selected, uploaded or trashed, and this is remembered across restarts.

**Sharing from other apps:** **Share → pic-sync** sends to the last Mac (scanning first if there is none). Keep the screen open until it's done. Cloud-only Google Photos items work too. Each verified file is matched to the phone's library by size and SHA-256 so it can be trashed later. Unmatched files (cloud-only or changed by Google Photos) are never trashed.

**Safety:** a photo is only offered for trashing if the server's size and SHA-256 matched what the phone sent. Photos edited after upload are re-hashed and skipped if they changed.

**Other devices:** **Settings → Show QR code for the upload page** shows the Mac's address so another device on the Wi-Fi can upload from its browser.

**Permissions:** photos and videos (limited access works) with location, and on Android 17 **Nearby devices / local network**. Optionally grant **Media management** to skip the system confirmation when trashing. The app then confirms itself.

## Logs and troubleshooting

- **Mac:** logs go to the terminal or, for the app, `~/Library/Logs/pic-sync.log` (**Open log** in the menu). Add `-debug` for more detail.
- **Android:** tap a **!** photo to see why it failed. **Settings → Share diagnostics** sends the log.

See [docs/troubleshooting.md](docs/troubleshooting.md) for common problems and fixes.

## Development

Read [docs/performance.md](docs/performance.md) before changing the gallery, image loading or upload paths. The server needs Go 1.25+.

```sh
make test                      # server tests + menu bar compile check
cd android && gradle test      # Android unit tests
```

## License

[MIT](LICENSE). `rsc.io/qr` is BSD-3-Clause; the Material Symbols icons in `android/app/src/main/res/drawable` are Apache 2.0.
