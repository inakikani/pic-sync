# Keeping pic-sync fast

These are the rules behind the current performance, and the mistakes that caused stutter, crashes or memory waste before. Follow them for new code, and check changes against them.

## Android app

### How to judge smoothness
- **Measure on a release build.** In Android Studio: Build Variants → `app` → **release**, then Run. The debug build is debuggable and unoptimised, so it stutters in ways a release build doesn't; never judge smoothness on it. The release build is signed with the local debug key and installs over the debug build without losing data.
- **Profile.** Use Profiler → CPU → System Trace while doing the slow action. Look for long frames and work on the main thread.
- **Freezes in debug builds:** the main-thread watchdog (`MainThreadWatchdog`) logs `watchdog: main thread blocked …` with a stack trace when the UI thread stalls for more than 2 s.

### State and recomposition
- **Never compute over the whole library in code that runs per `UiState` copy.** `UiState` is copied on every selection tap, upload step and message. Lists derived from the library (in scope, to upload, uploaded, album counts) live in `Derived` (`AppViewModel.kt`). It is memoized on the *identity* of its inputs: `media`, `records`, `excluded`, album, filters. If you add a derived list, add it there. Don't put it in a `by lazy` on `UiState`, because those are recomputed for every copy.
- **Replace collections, never mutate them.** The memo relies on `records`, `excluded` and so on being new objects when they change (`it.records + (id to rec)`, `selected - id`). Mutating in place would leave the cached lists stale.
- **Keep high-frequency state out of `UiState`.** Upload byte progress is a separate `StateFlow` (`uploadFraction`). Leaf composables read it through lambdas (`progress = { fraction.value }`), so only the progress bar and the current cell redraw.
- **Read scroll position or other fast-changing state only in small, separate composables, through `derivedStateOf`.** `ScrollMonthLabel` is the example. Reading `gridState.firstVisibleItemIndex` in the grid's own scope re-ran the whole grid setup for every row scrolled.
- **Lazy grids and lists:** always give a stable `key` (the MediaStore id) and a `contentType`. Keep item parameters simple and stable.

### Per-item work
- **Precompute per-item values when the library is read, on the IO thread,** not during UI passes. Example: `MediaItem.month` (a month key) is computed in `MediaRepository.query`. Converting 30k timestamps with `java.time` on every grouping pass was visible jank.
- **Keep `MediaItem` small.** The `Uri` is built on demand from the id, and repeated strings (album name, MIME type) are shared through the `share()` map in `query()`. Every extra field is multiplied by tens of thousands of items.

### Images
- **Thumbnail cache:** limited by **bytes** (1/8 of the heap limit, at most 48 MB), not by count, and emptied on `onTrimMemory(UI_HIDDEN)`. A count-limited cache of 400 thumbnails used about 150 MB.
- **Thumbnail loading:**
  - Decodes run on a dispatcher limited to **4 at a time**, so a fling doesn't start dozens of decodes competing with drawing. Requests for cells that have scrolled away are cancelled before they start.
  - Bitmaps are copied to `Bitmap.Config.HARDWARE` in the background, so the GPU upload doesn't happen on the UI thread at first draw.
  - Cells start with `cachedThumbnail(id)`, so cached images appear in the first frame.
- **Full-screen photos:** decoded to screen size (at most 1.5×, for zoom) with `ImageDecoder` and **not cached**. Only the page on screen and its neighbours hold one.
- **Thumbnail size:** about 320 px, roughly a grid cell. Don't decode larger images for the grid.

### Threads and I/O
- **Nothing blocking on the main thread:** no MediaStore queries, SQLite (`UploadStore`), file reads, hashing or network. Use `Dispatchers.IO`, or the thumbnail dispatcher, inside the view model. Building diagnostics reads the log file, so it runs off the main thread (`shareDiagnosticsAsync`).
- **Stream uploads, never buffer them:** chunked streaming (`setChunkedStreamingMode`), 64 KB buffers, hashing on the way (`copyHashing`). A video must never sit in memory as a whole.
- **Logging (`AppLog`) is asynchronous** (one background writer) and capped at 256 KB plus one previous file. Don't log in per-frame or per-progress-tick paths. Log per file and per batch.

### Animation (Material 3 Expressive)
- **Expressive springs overshoot.** An animated value going back to 0 briefly dips below it. Clamp anything that must not be negative: `padding`, sizes, corner radii (`coerceAtLeast(0.dp)`). Negative padding crashed the app when unselecting a photo.
- **Animate cheap properties.** Selection animates one cell's inset and corners. Don't animate layout of the whole grid.
- **Prefer `graphicsLayer`** (scale, translation, alpha) over layout changes for anything that runs every frame, as the full-screen viewer does for zoom.

## Mac server (Go)

- **Stream everything:** `r.MultipartReader()` → temp file, hashing on the way with `io.MultiWriter`. Never `ParseMultipartForm` or `io.ReadAll` an upload.
- **Duplicate check:** compare only files of the **same size**, and cache hashes by size and modification time. The cache is dropped when the folder changes. The lock covers only the duplicate check plus the rename, not the transfer. Known cost: each upload lists the whole folder. If folders get very large, add an index by size.
- **Bounded metadata reads.** Date parsing (`mediadate.go`) seeks with `ReaderAt` and reads at most 1 MiB of metadata (`maxMetaRead`), never the whole file. Keep new parsers bounded the same way.
- **Timeouts:** `ReadHeaderTimeout` and a 30 s idle deadline on request bodies, but **no overall read timeout**, because large videos legitimately take minutes. Don't add one.
- **Logging:** one line per request and one per file. Noisy paths (the Mac's own QR page, favicon) are logged only at `-debug`. The menu bar refreshes once per second and only sends AppKit what changed.
- **Before merging:** `make test` (tests plus a compile check of the menu bar code), `go test -race ./...`, and `go run honnef.co/go/tools/cmd/staticcheck@latest -tags traycheck ./...`.

## Checklist for a change
1. Does it compute over the whole library? If so, is it in `Derived`, or done once off the main thread?
2. Does it add state that changes often? Keep it out of `UiState`, and read it in leaf composables.
3. Does it read scroll or animation state? Use a small composable with `derivedStateOf`.
4. Does it load images? Size them to where they're shown, bound the cache by bytes, and decode off the main thread.
5. Does it animate with a spring? Clamp values that must stay non-negative.
6. Does it read or write files, the database, MediaStore or the network? Do it off the main thread, streaming.
7. Server: does it read a whole upload or file into memory? It must stream.
8. Was it checked on a release build (Android) and with `go test -race` (server)?
