# Logging and troubleshooting

## Where the logs are

| Where | Log |
|-------|-----|
| Mac app | `~/Library/Logs/pic-sync.log`. Open it from the menu bar with **Open log**, which opens Console. |
| Command line | Printed in the terminal. To also write a file, add `"log_file": "~/Library/Logs/pic-sync.log"` to `config.json`. |
| Android app | Logcat (tag `pic-sync`) in Android Studio, and a file inside the app. Use **Settings → Share diagnostics** (also offered on the connect screen after an error) to send it. |

- **Mac log size:** the file is capped at 5 MB. When it's full it's renamed to `pic-sync.log.1` and a new one starts, so at most two files exist.
- **Mac log detail:** start with `-debug` for more, which also logs requests for the Mac's own QR page. For the app: `pic-sync.app/Contents/MacOS/pic-sync -debug`.
- **Android log size:** 256 KB plus one previous file. It survives restarts and records crashes.

## What the Mac logs

One line per event, as `key=value` fields. Levels are `INFO`, `WARN` (a problem caused by the phone or the network) and `ERROR` (a problem on the Mac).

```
time=2026-11-02T14:00:01.204 level=INFO msg="==== pic-sync starting ====" version="964d020 (2026-11-02)" go=go1.27.3 os=darwin/arm64 macos=26.1 pid=4242 log=/Users/me/Library/Logs/pic-sync.log mode=app ui="menu bar" config=… folder=/Users/me/Pictures/phone-dump
time=2026-11-02T14:00:01.210 level=INFO msg="address for the phone" url=http://192.168.1.23:8080
time=2026-11-02T14:00:01.210 level=INFO msg=listening addr=[::]:8080 qr_url=http://192.168.1.23:8080
time=2026-11-02T14:02:10.551 level=INFO msg=request client=192.168.1.40 method=GET path=/api/info status=200 took=1ms agent="pic-sync-android/1.0 (1) (Android 17; Google Pixel 9)"
time=2026-11-02T14:02:14.902 level=INFO msg=saved file=IMG_1234.jpg size="3.2 MB" took=1.1s speed="2.9 MB/s" folder=~/Pictures/Phone client=192.168.1.40 sha256=2cf24dba5fb0 date="2024-07-14 18:30" date_from="file metadata"
time=2026-11-02T14:02:14.903 level=INFO msg=request client=192.168.1.40 method=POST path=/upload status=200 took=1.1s received="3.2 MB" agent=…
time=2026-11-02T14:02:20.117 level=INFO msg="duplicate, not stored (identical file already in the folder)" file=IMG_1234.jpg sent_as=IMG_1234(1).jpg …
time=2026-11-02T14:03:41.006 level=WARN msg="upload failed" file=VID_0042.mp4 client=192.168.1.40 took=20s err="upload interrupted after 48.1 MB: unexpected EOF"
time=2026-11-02T14:05:00.000 level=ERROR msg="upload failed" file=IMG_2000.jpg … err="writing file: write …: no space left on device"
```

| Message | Meaning |
|---------|---------|
| `pic-sync starting` | What is running: commit, macOS, mode, config file, upload folder. Every run starts with this line. |
| `folder` | At startup, where each type of file goes (e.g. `for="Documents: ~/Documents/Phone"`), one line each. |
| `address for the phone` / `listening` | Every LAN address, and the one put in the QR code. |
| `request` | Every HTTP request: phone IP, path, status, size received, time and user agent. The agent tells you whether it was the Android app (`pic-sync-android/…`) or a browser. |
| `saved` | A stored file: the name on disk, `sent_as` if it was renamed to avoid overwriting, size, speed, the `folder` it went to, the first 12 hex digits of its SHA-256, and where its date came from (`file metadata`, `phone`, or `none`). |
| `duplicate, not stored` | Identical content was already in the folder under `file`. Nothing new was written. |
| `upload failed` (WARN, status 400) | The request was bad, or the connection broke or stalled (no data for 30 s, `i/o timeout`) mid-file. Nothing is saved, and no partial file is left behind. |
| `request rejected: sent by another website` (status 403) | A web page from another site tried to upload through a browser. See *Uploads fail with 403* below. |
| `shutting down`, `uploads still in progress were cut off`, `stopped` | The server stopped (Ctrl-C, **Quit pic-sync**, logout). Uploads get 5 s to finish. |
| `removed leftover temp files from an interrupted run` | Startup cleanup of `.upload-*.part` files left by a crash or kill. |
| `page: …` (e.g. `page: picker returned files count=3 types="image/heic×3"`) | What the upload page's file picker did in the phone's browser: opened, files handed over (count, size, types, seconds), cancelled, upload errors. Used to diagnose browser-side problems, e.g. on iPhone. |
| `upload failed` (ERROR, status 500) | The Mac couldn't write the file: folder missing or not writable, disk full. The Android app shows *the Mac couldn't save it: …* |
| `upload folder changed`, `saving config`, `QR window unavailable…`, `stopped by user` | Mac app events. |
| `folder picker closed` | The **Folders → Choose Folder…** window closed: `took` is how long it was open, `canceled` whether a folder was chosen. With `-debug`, `osascript took=…` lines time every Mac window, including the time it takes to appear. |

## What the Android app logs

```
2026-11-02 14:02:09.870 I ==== app start: app 1.0 (1), Android 17 (SDK 37), Google Pixel 9; permissions: photos=FULL, location=yes, local network=yes, media management=no
2026-11-02 14:02:10.540 I connect: ok, http://192.168.1.23:8080 is a pic-sync server
2026-11-02 14:02:11.020 I media: 8312 items (412 videos) in 23 albums, 5120 upload records, 3 skipped, read in 480 ms
2026-11-02 14:02:12.001 I upload: 25 files, 81.0 MB to http://192.168.1.23:8080
2026-11-02 14:02:14.905 I upload: IMG_1234.jpg (id 1000123, 3.2 MB, 1104 ms, 2.9 MB/s) saved as IMG_1234.jpg, verified, location kept
2026-11-02 14:03:41.010 W upload: VID_0042.mp4 (id 1000456) failed after 20011 ms | java.net.SocketException: Connection reset
2026-11-02 14:05:30.000 I trash: 24 to move, 1 skipped, 3 protected by skip, scope 'from Camera'
```

The app logs:
- **At start:** app and Android version, the phone model and every permission that matters.
- **Connecting:** each attempt and why it failed.
- **The photo library:** what was read.
- **Each upload:** size, time, speed, the name on the Mac, whether it was verified, and whether GPS location was kept or why not. On a verification failure it logs both hashes.
- **Moving to trash:** what was moved, skipped or protected.
- **Sharing to pic-sync:** what was received and from which app, and each file's result. That includes whether it was matched to a photo on the phone, e.g. `phone library: matched id 1000123 (IMG_1234.jpg)` or `no match: no photo of that size on the phone (cloud-only, or a different version)`.
- **Other:** permission answers, thumbnail failures (the first few), QR scanner errors and crashes.

Errors also have a stack trace in the file.

On the phone:
- A photo that failed shows a red **!** badge. Tap it to see why, or tap **N failed to upload · see why** above the buttons for the full list.
- **Share diagnostics** sends the environment line, server address, library counts, this session's failures and the recent log as plain text. It includes file names and the server address, but no photos.

## Common problems

**The phone can't open the page or connect** (it times out or *can't reach the Mac*)
1. Check the Mac log for `request` lines from the phone's IP. If there are none, the phone never reached the Mac:
   - **Wi-Fi:** the phone and the Mac must be on the same network. Guest networks and some mesh or "client isolation" setups keep devices apart. A VPN on either device can also get in the way.
   - **Address:** check you're using the address from the `address for the phone` lines. If the Mac has several (Wi-Fi + Ethernet, VPN), the QR code uses the default route's address and may not be the one the phone can reach. Type another one by hand.
   - **macOS firewall:** System Settings → Network → Firewall must allow incoming connections for pic-sync. The prompt appears on first run, and choosing *Deny* blocks the phone.
   - **Local network privacy:** if macOS asked whether pic-sync may access the local network and you declined, allow it in System Settings → Privacy & Security → Local Network.
2. **Android app, *blocked by Android: allow Nearby devices*:** on Android 17 the app needs the local network permission. Turn it on in Settings → Apps → pic-sync → Permissions → Nearby devices.
3. ***connection refused*:** pic-sync isn't running, or it's listening on a different port (see `listening addr=`).
4. ***listening on :8080 (is another pic-sync already running?)*:** quit the other instance, or set another `listen` port.

**Uploads stop halfway** (`upload interrupted` in the Mac log, *connection dropped* on the phone)
- The phone locked or switched networks, or the browser went to the background. The Android app keeps uploading in the background (a notification shows the progress) unless it's removed from Recents; in a browser, keep the page open and the screen on.
- Nothing partial is saved. Upload the same files again: files that did arrive are reported as *already on Mac*.

**Uploads fail with 403 / *cross-origin request rejected***
- The browser said the upload came from a different website than the pic-sync page. Open the page by the address pic-sync shows (scan the QR code) and upload from there. Don't use pages saved to disk, other sites, or a proxy or extension that rewrites the address.

**ERROR `upload failed` / *the Mac couldn't save it***
- **Folder removed or disk ejected:** choose the folder again (menu bar → **Folders**, or fix `upload_dir` / `folders` / `extensions` in the config). pic-sync doesn't recreate a folder that disappears while it runs, so files never end up on the wrong disk.
- **A file went to an unexpected folder:** the `saved` line shows its `folder`. Types are decided by extension; an extension rule in `extensions` wins over the type ([README](../README.md#file-types-and-folders)).
- **Disk full:** free space. The error says `no space left on device`.
- **Permission denied:** the folder isn't writable. For folders in Desktop, Documents or Downloads, macOS may also need to allow pic-sync access (System Settings → Privacy & Security → Files and Folders).

**The Mac didn't receive identical bytes** (Android, `NOT verified` in the app log)
- What arrived differs from what the phone sent. This is rare and points to a network or proxy problem. The photo isn't recorded as uploaded and won't be offered for trash. Retry it, and if it keeps happening, compare the two hashes in the app log with the Mac's `saved` line.

**A photo isn't recognised as a duplicate**
- Duplicates are matched by identical content only. An edited copy, a different export or HEIC→JPEG conversion counts as a new file.
- Photos uploaded from the Android app before location support were stored without GPS. Re-uploading them now sends different bytes, so they're saved again as `name (1).jpg`.

**Dates in Finder are wrong**
- Check `date_from` in the `saved` line:
  - **`file metadata`:** the date inside the photo or video.
  - **`phone (no date in file)`:** the date the phone or browser sent (e.g. for screenshots).
  - **`none`:** the upload time was kept.
- Photo times without a time zone in their metadata are read as the Mac's local time.
- Fix files that are already on the Mac: `pic-sync -fix-dates <folder> -dry-run`, then run it again without `-dry-run`.

**GPS location is missing**
- **Android app:** look for `WITHOUT location: …` in the app log. The usual cause is that *media location* isn't allowed (`location=no` in the `app start` line). This happens when photo access was given by an older version of the app. Allow it in the app under **Settings → Photo location → Allow**. Limited ("selected photos") access can also prevent it.
- **Browsers:** they may remove location before sending, and the Mac can't add it back. Use the Android app.

**iPhone: tapping *Add* in the photo picker does nothing, or photos arrive as JPEG**
- The upload page reports what the phone's picker did as `page: …` lines in the Mac log. Open the page, pick photos, tap **Add**, then read the lines from the phone's IP:
  - **`page: picker opened`** but nothing after it: the picker never handed anything back to the browser. Note which browser and how many items, and whether they're photos or videos, and whether they're in iCloud.
  - **`page: picker closed, waiting for files`** followed later by **`page: picker returned files … seconds=N`**: iOS was preparing the files (converting, or downloading the originals from iCloud) for N seconds. The page now says it's waiting instead of looking frozen.
  - **`page: picker returned nothing`** or **`… some empty: error`**: iOS returned no files, or empty ones. Try fewer items, or Safari.
  - **`page: picker returned files`** but no `/upload` request: something on the page failed. **`page: upload error`** gives the status and message.
- The page accepts HEIC/HEIF, so iOS can send originals instead of converting each photo to JPEG.

**The Mac app doesn't start or shows no menu bar icon**
- **macOS refuses to open it:** right-click → **Open** once, or run `xattr -cr pic-sync.app`.
- **No icon, but a "pic-sync is running" window:** the app was built without the menu bar (cross-compiled, not with `make app` on a Mac). The startup line shows `ui="window (built without the menu bar)"`.
- **Nothing visible at all:** check `~/Library/Logs/pic-sync.log` for the startup line and errors, or run `pic-sync.app/Contents/MacOS/pic-sync` in Terminal to see the log live.

**Android app shows only a blank screen**
- In Logcat, filter on `tag:pic-sync`. A normal start logs `startup: activity created`, `building the first screen`, `view model created` and `first frame drawn`. The last line shows how far it got.
- If the app is stuck, a `watchdog: main thread blocked …` entry follows within a few seconds. It shows the code the app is stuck in. The watchdog only runs in debuggable builds, i.e. those installed from Android Studio.
- Also check for a `FATAL EXCEPTION` (filter `package:com.inakikani.picsync`).

**A photo shared from Google Photos isn't offered for trash**
- Look for the `share:` line in the app log:
  - **`no photo of that size`:** the photo is only in the cloud, or Google Photos sent a different version than the one on the phone (edited, or compressed by "Storage saver").
  - **`same size, but different content`:** usually location. Google Photos sent the file with location and pic-sync read the phone's copy without it, or the other way round. Allow **Settings → Photo location** in pic-sync, then share it again (the Mac reports it as a duplicate, and the match is retried).
  - **`no photo access`:** allow pic-sync's photo access.
- Uploading the photo from the pic-sync gallery always works for trashing.

**Android app crashes**
- The crash and its stack trace are in the app log. After reopening the app, use **Share diagnostics**, or check Logcat in Android Studio.
- **Release builds** have obfuscated stack traces (`az2.e(r8-map-id-…)`). Decode them with the mapping file of the same build: `make apk` saves it as `bin/pic-sync-<VERSION>-mapping.txt`.
  ```sh
  adb logcat -d -b crash > crash.txt
  ~/Library/Android/sdk/cmdline-tools/latest/bin/retrace bin/pic-sync-1.0-mapping.txt crash.txt
  ```
  `retrace` comes with **Android SDK Command-line Tools** (Android Studio → Settings → Android SDK → SDK Tools).
- **Crashes only in the release build** usually mean R8 removed something a library uses by reflection. Add a keep rule to `android/app/proguard-rules.pro`.
