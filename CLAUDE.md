# pic-sync

A LAN tool to move photos and videos from a phone to a Mac. It has two parts:
- **Server (Go, repo root):** a single binary with a CLI mode, plus a macOS app mode with a menu bar item. It streams uploads to `upload_dir`, detects duplicates by SHA-256, and sets each file's date from its EXIF or video header.
- **Android app (`android/`):** Kotlin/Compose, Material 3 Expressive, minSdk 33, targetSdk 37. It has a gallery of the phone's photos and uploads them with hash verification. Verified uploads can be moved to trash. Sharing from other apps works too, as does a full-screen viewer.

User docs: `README.md`. Logs and fixes: `docs/troubleshooting.md`. **Performance rules: `docs/performance.md`, which must be followed.**

## Build and test
- **Server:**
  - `make test` runs the Go tests and compile-checks the menu bar code with `-tags traycheck`.
  - Also run `go test -race ./...` and `go run honnef.co/go/tools/cmd/staticcheck@latest -tags traycheck ./...`.
  - Needs Go 1.25 or newer, for `http.CrossOriginProtection`.
- **Mac app:** `make app` builds `bin/pic-sync.app`. The menu bar item needs cgo, so a real one is only built on a Mac.
- **Android:**
  - The owner builds and tests in their own Android Studio. **Don't install the Android SDK or Gradle in the sandbox.** Write the code, check it carefully, and say it hasn't been compiled.
  - Unit tests: `android/app/src/test` (JVM; `AppLog.logcat` is swapped out because `android.util.Log` isn't available there).
  - Judge smoothness on the **release** build variant only.

## Workflow
- Work on a new branch per change. Merge into `main` with `--ff-only` and delete merged branches, but only when asked.
- The sandbox is a clone: the owner gets commits with `git fetch sandbox-picsync`. Their local commits can be fetched from `/run/sandbox/source`.

## Performance rules (summary of docs/performance.md)
- **Library-wide lists** belong in `Derived` (`AppViewModel.kt`), which is memoized on input identity. Never compute them per `UiState` copy, and replace collections instead of mutating them.
- **Fast-changing state** (upload progress, scroll position) stays out of `UiState`. Read it only in small composables, through lambdas or `derivedStateOf`.
- **Per-item values** (e.g. `MediaItem.month`) are computed when the library is read, on the IO thread.
- **Thumbnails:**
  - The cache is limited by bytes and emptied in the background.
  - At most 4 decodes run at once; they produce hardware bitmaps; cached ones are shown in the first frame.
  - Full-screen images are sized to the screen and not cached.
- **Main thread:** no I/O. Uploads stream (Android chunked; Go `MultipartReader` with hashing on the way); never buffer a file.
- **Expressive springs overshoot:** clamp padding, sizes and corner radii at 0.
- **Server limits:** parsing reads at most 1 MiB of metadata; there is no overall HTTP read timeout (long videos); the duplicate check compares only same-size files and caches hashes.

## Gotchas found before
- **Don't name a composable like a class in the same package.** `PicSyncApp()` resolved to the `Application` constructor and nothing was drawn: a white screen with no crash.
- **Debug output on Android:** use `AppLog`, not `println`. Logcat filter: `tag:pic-sync`. Startup logs `startup: …` markers.
- **Material 3 Expressive APIs** come from `material3` 1.5.0-beta02, pinned in `libs.versions.toml` because the BOM pins 1.4.0. `LoadingIndicator` and `MaterialShapes` still need `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- **Icons** are Material Symbols Rounded vector drawables in `res/drawable/ic_*.xml`. There is no icons library.
