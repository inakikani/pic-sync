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
2. Your browser opens a page on the Mac with a large QR code. Scan it with the phone.
3. A small **pic-sync is running** dialog stays open. **Show QR code** reopens the page, and **Stop** quits.
   The app has no Dock icon, so this dialog is how you stop it.

The app keeps its settings in `~/Library/Application Support/pic-sync/config.json` (same format as below; `listen` sets the port) and writes a log to `~/Library/Logs/pic-sync.log`. The QR page is only served to the Mac itself, not to other devices on the LAN.

The app is not signed with an Apple developer ID. If macOS refuses to open it, right-click it and choose **Open** once, or run `xattr -cr pic-sync.app`. On the Mac you can also ad-hoc sign it with `make sign`.

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

## Behaviour

- Existing files are never overwritten. A duplicate name is saved as `IMG_001 (1).jpg`, `IMG_001 (2).jpg`, and so on.
- Uploads go to a hidden `.upload-*.part` temp file first and are renamed only once complete, so an interrupted upload leaves no partial file.
- Directory parts of client filenames are stripped, and hidden/dot names are rejected.
- There is **no authentication**. Anyone on your LAN can upload while the server runs, so only run it on trusted networks and stop it (Ctrl-C) when you're done.

## macOS notes (both modes)

- On first run macOS may ask whether to allow incoming network connections. Choose **Allow**, or the phone won't be able to connect.
- If you copied a binary that macOS refuses to open, clear the quarantine flag with `xattr -d com.apple.quarantine bin/pic-sync-darwin-arm64`.

## Development

```sh
make test
```
