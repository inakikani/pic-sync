# Plan: Android companion app (upload, then delete from phone)

> Status: **not started**, written 2026-11-01. It will be built when the owner decides to go ahead.
> For a future Claude session: read this file, `README.md`, `main.go` and `app.go` first.
> The decisions under "Open decisions" have defaults; confirm them with the user before starting.

## Goal

Free up space on the phone safely after photos reach the Mac. The browser upload page can't
do this, because browsers have no access to delete files on the phone. A small native Android
app will upload photos to the existing pic-sync server and, once the server has **verifiably** received
them, move them to the phone's trash in a single batch.

The web upload page stays as it is. The app is an optional companion.

## Current server (as of `main` @ b2db7eb)

- **Stack:** a single Go binary with no cgo. Its only dependency is `rsc.io/qr`. Two UXs, chosen with `-mode cli|app|auto`.
- **`GET /`:** the upload page (`index.html`, compiled in).
- **`POST /upload`:** a multipart request. Every part with a filename is saved; the field name doesn't matter, and the web UI uses `file`.
  - Files are streamed to `.upload-*.part` and renamed when complete. Existing files are never overwritten: a name clash becomes `name (1).ext`.
  - Directory parts of the filename are stripped, and names starting with a dot are rejected (400).
  - Response: `{"saved": [{"name", "original", "size", "sha256", "duplicate"}]}`. On error: `{"error": "..."}` with a 4xx status.
  - Duplicates (same SHA-256 as a file already in `upload_dir`) are not stored. `name` then points at the existing file.
- **`GET /status`, `GET /qr.png`:** loopback only (404 for LAN clients). Used by the Mac app mode.
- **No auth; plain HTTP.** Default listen address is `:8080`. The QR code encodes `http://<lan-ip>:<port>`.

## Part 1: Server changes (small, do first)

> Items 1 and 3 are **done** (branch `dedupe-uploads`): every `saved` entry now has `name`, `original`, `size`, `sha256` and `duplicate`, and identical content is never stored twice (see `store.go`). Only item 2 (`/api/info`) and its test remain.

1. **Return proof of receipt.** Hash with SHA-256 while streaming (`io.MultiWriter(tmp, hasher)` in `saveFile`) and change the response to:
   ```json
   {"saved": [{"name": "IMG_001 (1).jpg", "original": "IMG_001.jpg", "size": 2481234, "sha256": "<hex>"}]}
   ```
   `name` is the name on disk; `original` is the name the client sent.
2. **`GET /api/info`** → `{"app": "pic-sync", "version": "1"}`. After scanning the QR, the app calls this to confirm it is talking to a pic-sync server.
3. **Optional (decide while implementing):** if a file with the same name and identical hash already exists, don't save a `(1)` copy; return the existing file's entry instead. This makes retries idempotent.
4. Update `main_test.go`: check that the hash and size in the response match the uploaded bytes, and test `/api/info`.

## Part 2: Android app

### Tech choices (defaults)
- **Language and UI:** Kotlin with Jetpack Compose, single activity.
- **Android versions:** `minSdk 30` (Android 11, the first version with `createTrashRequest` / `createDeleteRequest`). `targetSdk` = latest stable.
- **Package:** `com.inakikani.picsync`, to match the macOS bundle id style in `packaging/Info.plist`.
- **Location in repo:** `android/`, a standalone Gradle project with its wrapper committed.
- **Libraries:** OkHttp (multipart upload with a progress-reporting `RequestBody`); Room (upload records); Google code scanner `com.google.android.gms:play-services-code-scanner` (QR scanning with no camera permission; needs Play services; the fallback is ML Kit bundled + CameraX).
- **Distribution:** sideloaded APK. Debug signing at first; a release keystore can come later and must **not** be committed.

### Permissions and manifest
- **Media read access:** `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` (API 33+), plus `READ_EXTERNAL_STORAGE` with `maxSdkVersion=32`. Handle Android 14's partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) correctly.
- **`INTERNET`.**
- **Cleartext HTTP:** the server is plain HTTP on a LAN IP. Network security config can't whitelist arbitrary IPs, so use a `network_security_config.xml` with `<base-config cleartextTrafficPermitted="true">`. Add a comment explaining why.
- **Optional:** `MANAGE_MEDIA` (Android 12+). If the user grants it in Settings (`MediaStore.canManageMedia()`), trash requests skip the confirmation dialog.

### Screens and flow
1. **Connect:** scan the QR code shown by the Mac (CLI terminal or app-mode page), check `/api/info`, and remember the server URL (DataStore). Manual URL entry as a fallback.
2. **Gallery:** a grid of MediaStore images and videos, newest first. Each item shows a status badge: not uploaded, uploaded and verified, or failed. A filter shows only "not uploaded". Multi-select, plus "select all not uploaded".
3. **Upload:** send files one per request, in sequence (same approach as `index.html`), with progress per file and overall. A failure doesn't stop the batch.
4. **Clean up:** a "Move N verified photos to trash" button calls `MediaStore.createTrashRequest(resolver, uris, true)`, which shows one system confirmation for the whole batch. Afterwards, refresh the gallery.

### Safety rules (non-negotiable)
- Only offer an item for deletion if the **local SHA-256 and size match the server's response** for that upload. The local hash is computed by streaming the content URI; it can be computed during the upload itself.
- Record each verified upload in Room: MediaStore id, content URI, size, sha256, server name, server URL, timestamp. Deletion candidates come **only** from these records.
- Before trashing, confirm the MediaStore item still exists and its size hasn't changed (it may have been edited since upload). If it changed, skip it.
- Default to **trash** (recoverable for 30 days), not permanent delete.

### Out of scope for v1 (possible later)
- Background or automatic uploads (WorkManager), uploads continuing when the app is closed.
- mDNS/NSD discovery (`_pic-sync._tcp`) as an alternative to the QR code. That would also need the server to advertise itself.
- Auth / HTTPS.

## Build environment notes (for the sandbox)
- **Toolchain:** needs JDK 17+ and the Android SDK command-line tools (platform, build-tools). Add only the init lines to `/etc/sandbox-persistent.sh`, **never shell completion scripts** (see the repo-level CLAUDE.md).
- **Network:** downloads come from `dl.google.com`, `maven.google.com` / `dl.google.com/android/maven2`, and `repo.maven.apache.org`. If they are blocked (HTTP 403), the user must run `sbx policy allow network <domain>` on the host.
- **Risk: the sandbox is linux/arm64.** Google's `aapt2` (pulled by the Android Gradle Plugin) has historically shipped only x86_64 Linux binaries. Options, in order:
  1. Try it.
  2. Build inside an x86_64 Docker container (`docker run --platform linux/amd64 ...`, with qemu emulation; slow but works).
  3. Have the user build on the Mac with Android Studio.
- **No emulator or device in the sandbox:** on-device testing is done by the user (`adb install app-debug.apk`).

## Open decisions (defaults in brackets; confirm with the user)
- **Delete style:** trash or permanent delete? [trash]
- **`MANAGE_MEDIA`:** support it so there's no confirmation dialog? [yes, optional, off unless granted]
- **Skip identical duplicates on the server (Part 1.3)?** [yes]
- **Minimum Android version:** [11 / API 30]
- **App name shown on the phone:** [pic-sync]

## Verification
- **Server:** `go test ./...`. Manually: `curl -F file=@x.jpg localhost:8080/upload` returns a `sha256` equal to `sha256sum x.jpg`, and `curl localhost:8080/api/info` works.
- **App build:** `./gradlew assembleDebug` succeeds; add unit tests for the hash/verification logic and for parsing the response (`./gradlew test`).
- **On device (user):**
  1. Scan the QR and connect.
  2. Upload 5 photos and check they are on the Mac, byte-identical (`shasum -a 256`).
  3. Trash them and check they appear in the phone's trash, recoverable.
  4. Kill the server mid-upload: the photo must **not** become a deletion candidate.
  5. Edit a photo after uploading it: it must be skipped at cleanup.

## Suggested order of work
1. Branch `android-companion`.
2. Part 1 server changes, with tests, committed separately.
3. Scaffold `android/` and get `assembleDebug` building (resolve the aapt2/arm64 issue here).
4. Connect screen with QR scan and `/api/info`.
5. Gallery with status badges.
6. Upload with verification and Room records.
7. Trash flow.
8. README section "Android companion app" (install APK, permissions, how deletion works).
