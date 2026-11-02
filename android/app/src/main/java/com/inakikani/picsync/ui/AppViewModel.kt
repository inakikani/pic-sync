package com.inakikani.picsync.ui

import android.Manifest
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inakikani.picsync.data.Album
import com.inakikani.picsync.data.AppLog
import com.inakikani.picsync.data.MediaFilters
import com.inakikani.picsync.data.MediaItem
import com.inakikani.picsync.data.MediaRepository
import com.inakikani.picsync.data.Opened
import com.inakikani.picsync.data.ServerClient
import com.inakikani.picsync.data.Settings
import com.inakikani.picsync.data.UploadRecord
import com.inakikani.picsync.data.UploadResult
import com.inakikani.picsync.data.UploadStore
import com.inakikani.picsync.data.changedSinceUpload
import com.inakikani.picsync.data.explain
import com.inakikani.picsync.data.groupAlbums
import com.inakikani.picsync.data.normalizeServerUrl
import com.inakikani.picsync.data.toggleMonthSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.Locale

/** Which file is uploading. Its byte progress is in [AppViewModel.uploadFraction], not here. */
data class UploadProgress(val index: Int, val total: Int, val currentId: Long)

/** Album + filters predicate; used for the gallery and again, freshly, for trashing. */
fun scopeMatcher(album: Long?, filters: MediaFilters): (MediaItem) -> Boolean {
    val match = filters.matcher(ZonedDateTime.now())
    return { item -> (album == null || item.albumId == album) && match(item) }
}

/** The All / Not uploaded / Uploaded chips above the grid. */
enum class UploadView { ALL, NOT_UPLOADED, UPLOADED }

data class UiState(
    val serverUrl: String? = null,
    val connecting: Boolean = false,
    val connectError: String? = null,
    val media: List<MediaItem> = emptyList(),
    val mediaLoaded: Boolean = false,
    val records: Map<Long, UploadRecord> = emptyMap(), // verified uploads, by MediaStore id
    val failed: Map<Long, String> = emptyMap(),        // this session's failures
    val selected: Set<Long> = emptySet(),
    val excluded: Set<Long> = emptySet(),     // long-pressed to skip: never uploaded or trashed (persisted)
    val uploadView: UploadView = UploadView.ALL,
    val selectedAlbum: Long? = null, // null = all photos
    val filters: MediaFilters = MediaFilters(),
    val upload: UploadProgress? = null,
    val busy: String? = null,
    val message: String? = null,
) {
    val albums: List<Album> by lazy {
        // Skipped files don't count as pending.
        groupAlbums(media, { it.albumId }, { it.albumName }, { it.id in records || it.id in excluded })
    }
    val albumName: String? get() = selectedAlbum?.let { id -> albums.firstOrNull { it.id == id }?.name }

    // Everything below is scoped to the selected album (or all photos) and the filters.
    val inScope: List<MediaItem> by lazy { media.filter(scopeMatcher(selectedAlbum, filters)) }
    // Verified on the Mac (including skipped ones that had been uploaded before).
    val uploaded: List<MediaItem> by lazy { inScope.filter { it.id in records } }
    // Skipped (long-pressed) files aren't pending, so they're left out of counts and "Select not uploaded".
    val notUploaded: List<MediaItem> by lazy { inScope.filter { it.id !in records && it.id !in excluded } }
    // Long-pressed files are protected: never uploaded, never trashed.
    val uploadedOnPhone: Int by lazy { inScope.count { it.id in records && it.id !in excluded } }

    /** " from Camera", " from Camera (filtered)", " (filtered)" or "" for button/dialog texts. */
    val scopeLabel: String
        get() = (albumName?.let { " from $it" } ?: "") + (if (filters.isActive) " (filtered)" else "")
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    init {
        AppLog.i("startup: view model construction started")
    }

    private val resolver = app.contentResolver
    private val repo = MediaRepository(resolver) {
        app.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    private val store = UploadStore(app)
    private val settings = Settings(app)
    private val userAgent = "pic-sync-android/${appVersion(app)} (Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL})"

    // Thumbnails are ~300-400 KB each, so the cache is bounded by bytes, not by count:
    // 1/8 of the app's heap limit, at most 48 MB. It's emptied when the app is in the background.
    private val thumbs = object : LruCache<Long, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L shl 20).toInt(),
    ) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) thumbs.evictAll()
        }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = thumbs.evictAll()
    }

    private val _state = MutableStateFlow(UiState(serverUrl = settings.serverUrl, excluded = settings.excluded))
    val state = _state.asStateFlow()

    // The current file's upload progress (0..1). Kept out of UiState so the ~100 updates per
    // file don't rebuild the whole state (and its derived lists over the full library).
    private val _uploadFraction = MutableStateFlow(0f)
    val uploadFraction = _uploadFraction.asStateFlow()

    init {
        app.registerComponentCallbacks(memoryCallbacks)
        AppLog.i("startup: view model created (server ${_state.value.serverUrl ?: "not set"}, ${_state.value.excluded.size} skipped)")
    }

    private var uploadJob: Job? = null
    @Volatile private var lastReported = -1f
    private var pendingTrash = 0
    private var pendingSkipped = 0
    private var pendingScope = ""

    // ---- server ----

    fun connect(input: String) {
        val url = normalizeServerUrl(input)
        if (url == null) {
            _state.update { it.copy(connectError = "\"$input\" isn't a valid address") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, connectError = null) }
            AppLog.i("connect: trying $url")
            try {
                withContext(Dispatchers.IO) { ServerClient(url, userAgent).checkServer() }
                settings.serverUrl = url
                AppLog.i("connect: ok, $url is a pic-sync server")
                _state.update { it.copy(serverUrl = url, connecting = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("connect: failed for $url", e)
                _state.update {
                    it.copy(connecting = false, connectError = "Couldn't connect to $url: ${e.explain()}")
                }
            }
        }
    }

    fun setConnectError(msg: String) {
        AppLog.w("connect: $msg")
        _state.update { it.copy(connectError = msg) }
    }

    fun disconnect() {
        AppLog.i("disconnect from ${_state.value.serverUrl}")
        uploadJob?.cancel()
        settings.serverUrl = null
        _state.update { it.copy(serverUrl = null, connectError = null) }
    }

    // ---- gallery ----

    fun refreshMedia() {
        AppLog.d("media: reading the photo library…")
        viewModelScope.launch {
            val started = System.nanoTime()
            val (media, records) = try {
                withContext(Dispatchers.IO) { repo.query() to store.all() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("media: reading the photo library failed", e)
                showMessage("Couldn't read photos: ${e.explain()}")
                return@launch
            }
            AppLog.i(
                "media: ${media.size} items (${media.count { it.isVideo }} videos) in " +
                    "${media.mapTo(HashSet()) { it.albumId }.size} albums, ${records.size} upload records, " +
                    "${_state.value.excluded.size} skipped, read in ${ms(started)} ms",
            )
            val ids = media.mapTo(HashSet()) { it.id }
            val albumIds = media.mapTo(HashSet()) { it.albumId }
            _state.update { s ->
                s.copy(
                    media = media,
                    records = records,
                    mediaLoaded = true,
                    selected = s.selected intersect ids,
                    // e.g. the album was emptied by moving everything to trash
                    selectedAlbum = s.selectedAlbum?.takeIf { it in albumIds },
                    // excluded is deliberately not pruned: with limited photo access a file can
                    // be hidden for a while, and it must still be protected when it comes back.
                )
            }
        }
    }

    /** A screen-sized decode of a photo for the full-screen viewer (not cached: ~10 MB each). */
    suspend fun fullImage(item: MediaItem, maxW: Int, maxH: Int): ImageBitmap? =
        withContext(Dispatchers.IO) { repo.fullImage(item, maxW, maxH)?.asImageBitmap() }

    suspend fun thumbnail(item: MediaItem): ImageBitmap? {
        thumbs.get(item.id)?.let { return it }
        return withContext(Dispatchers.IO) { repo.thumbnail(item)?.asImageBitmap() }
            ?.also { thumbs.put(item.id, it) }
    }

    fun toggle(id: Long) = _state.update {
        if (it.upload != null || id in it.excluded) it
        else if (id in it.selected) it.copy(selected = it.selected - id)
        // Selecting a photo that failed also shows why it failed.
        else it.copy(selected = it.selected + id, message = it.failed[id] ?: it.message)
    }

    /** Long-press: mark/unmark a file to skip. Excluded files drop out of the selection. */
    fun toggleExcluded(id: Long) {
        val s = _state.updateAndGet {
            if (it.upload != null) it
            else if (id in it.excluded) it.copy(excluded = it.excluded - id)
            else it.copy(excluded = it.excluded + id, selected = it.selected - id)
        }
        settings.excluded = s.excluded
        AppLog.d("skip ${if (id in s.excluded) "on" else "off"} for id $id (${s.excluded.size} skipped)")
    }

    fun selectNotUploaded() = _state.update { s -> s.copy(selected = s.notUploaded.mapTo(HashSet()) { it.id }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** Month header tap: select that month's photos still to upload, or deselect them. */
    fun toggleMonth(items: List<MediaItem>) = _state.update { s ->
        if (s.upload != null) s
        else s.copy(
            selected = toggleMonthSelection(s.selected, items.map { it.id }) { id -> id !in s.records && id !in s.excluded },
        )
    }
    fun setUploadView(view: UploadView) = _state.update { it.copy(uploadView = view) }
    fun selectAlbum(id: Long?) = _state.update { it.copy(selectedAlbum = id) }
    fun setFilters(filters: MediaFilters) = _state.update { it.copy(filters = filters) }
    fun showMessage(msg: String) = _state.update { it.copy(message = msg) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- upload ----

    fun uploadSelected() {
        val s = _state.value
        val url = s.serverUrl ?: return
        if (s.upload != null) return
        val items = s.media.filter { it.id in s.selected }
        if (items.isEmpty()) return

        AppLog.i("upload: ${items.size} files, ${mb(items.sumOf { it.size })} to $url")
        uploadJob = viewModelScope.launch {
            val client = ServerClient(url, userAgent)
            val batchStart = System.nanoTime()
            var ok = 0
            var dup = 0
            var failed = 0
            var firstError: String? = null
            var cancelled = false
            try {
                for ((i, item) in items.withIndex()) {
                    lastReported = -1f
                    _uploadFraction.value = 0f
                    _state.update { it.copy(upload = UploadProgress(i, items.size, item.id)) }
                    val fileStart = System.nanoTime()
                    try {
                        val (result, opened) = uploadOne(client, item) { sent -> reportProgress(item, sent) }
                        val took = ms(fileStart)
                        val where = "${item.name} (id ${item.id}, ${mb(result.local.bytes)}, ${took} ms" +
                            "${speed(result.local.bytes, took)})"
                        val location = if (opened.withLocation) "location kept" else "WITHOUT location: ${opened.whyNoLocation}"
                        if (result.verified) {
                            AppLog.i(
                                "upload: $where " +
                                    (if (result.saved.duplicate) "already on Mac as ${result.saved.name}" else "saved as ${result.saved.name}") +
                                    ", verified, $location",
                            )
                            val rec = UploadRecord(
                                mediaId = item.id,
                                snapshot = item.snapshot,
                                bytes = result.local.bytes,
                                sha256 = result.local.sha256,
                                serverName = result.saved.name,
                                serverUrl = url,
                                uploadedAt = System.currentTimeMillis(),
                            )
                            withContext(Dispatchers.IO) { store.put(rec) }
                            if (result.saved.duplicate) dup++ else ok++
                            _state.update {
                                it.copy(
                                    records = it.records + (item.id to rec),
                                    failed = it.failed - item.id,
                                    selected = it.selected - item.id,
                                )
                            }
                        } else {
                            AppLog.e(
                                "upload: $where NOT verified: sent ${result.local.bytes} bytes sha256 ${result.local.sha256}, " +
                                    "Mac reported ${result.saved.size} bytes sha256 ${result.saved.sha256}",
                            )
                            failed++
                            val msg = "${item.name}: the Mac didn't receive identical bytes"
                            firstError = firstError ?: msg
                            _state.update { it.copy(failed = it.failed + (item.id to msg)) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w("upload: ${item.name} (id ${item.id}) failed after ${ms(fileStart)} ms", e)
                        failed++
                        val msg = "${item.name}: ${e.explain()}"
                        firstError = firstError ?: msg
                        _state.update { it.copy(failed = it.failed + (item.id to msg)) }
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                val parts = buildList {
                    add("$ok uploaded")
                    if (dup > 0) add("$dup already on Mac")
                    if (failed > 0) add("$failed failed")
                }
                val summary = (if (cancelled) "Cancelled: " else "") + parts.joinToString(", ") +
                    (firstError?.let { ". $it" } ?: "")
                AppLog.i("upload: done in ${ms(batchStart) / 1000} s: $summary")
                _state.update { it.copy(upload = null, message = summary) }
            }
        }
    }

    fun cancelUpload() {
        AppLog.i("upload: cancelled by the user")
        uploadJob?.cancel()
    }

    private suspend fun uploadOne(
        client: ServerClient,
        item: MediaItem,
        onProgress: (Long) -> Unit,
    ): Pair<UploadResult, Opened> =
        withContext(Dispatchers.IO) {
            val ctx = coroutineContext
            val opened = repo.open(item)
            val result = opened.stream.use {
                client.upload(it, item.name, item.mimeType, item.takenAt, onProgress, checkCancelled = { ctx.ensureActive() })
            }
            result to opened
        }

    private fun reportProgress(item: MediaItem, sent: Long) {
        val f = if (item.size > 0) (sent.toFloat() / item.size).coerceIn(0f, 1f) else 0f
        if (f - lastReported < 0.01f && f < 1f) return // throttle UI updates to ~1% steps
        lastReported = f
        _uploadFraction.value = f
    }

    // ---- clean up ----

    fun canManageMedia(): Boolean = MediaStore.canManageMedia(getApplication<Application>())

    /**
     * Builds the system trash request for verified uploads still on the phone that are in
     * the current scope (album + filters), or returns null if there's nothing to trash. Items whose MediaStore metadata changed since upload
     * are re-hashed and only included if their bytes still match what the Mac confirmed.
     */
    suspend fun prepareTrash(): IntentSender? {
        val scope = _state.value
        val inScope = scopeMatcher(scope.selectedAlbum, scope.filters)
        val excluded = scope.excluded
        pendingScope = scope.scopeLabel
        _state.update { it.copy(busy = "Checking files…") }
        try {
            val (uris, skipped) = withContext(Dispatchers.IO) {
                val media = repo.query().filter(inScope)
                val records = store.all()
                val uris = ArrayList<Uri>()
                var skipped = 0
                for (item in media) {
                    if (item.id in excluded) continue // long-pressed: protected from trashing
                    val rec = records[item.id] ?: continue
                    if (!changedSinceUpload(rec.snapshot, item.snapshot)) {
                        uris += item.uri
                        continue
                    }
                    val now = try {
                        repo.hash(item)
                    } catch (e: Exception) {
                        AppLog.w("trash: re-hashing ${item.name} (id ${item.id}) failed, skipping it", e)
                        null
                    }
                    if (now != null && now.bytes == rec.bytes && now.sha256 == rec.sha256) {
                        store.put(rec.copy(snapshot = item.snapshot)) // same bytes, just new metadata
                        uris += item.uri
                    } else {
                        // Edited after upload (the Mac doesn't have this version), or read with a
                        // different location setting than when it was uploaded.
                        if (now != null) AppLog.i("trash: skipping ${item.name} (id ${item.id}): bytes differ from the upload")
                        skipped++
                    }
                }
                AppLog.i("trash: ${uris.size} to move, $skipped skipped, ${excluded.size} protected by skip, scope '${scope.scopeLabel.trim()}'")
                uris to skipped
            }
            pendingTrash = uris.size
            pendingSkipped = skipped
            if (uris.isEmpty()) {
                showMessage(
                    if (skipped > 0) "Nothing to move: $skipped item(s) changed after upload. Upload them again first."
                    else "Nothing to clean up",
                )
                return null
            }
            return MediaStore.createTrashRequest(resolver, uris, true).intentSender
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("trash: preparing the request failed", e)
            showMessage("Couldn't prepare the trash request: ${e.explain()}")
            return null
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    fun onTrashResult(ok: Boolean) {
        AppLog.i(if (ok) "trash: moved $pendingTrash item(s) to trash" else "trash: cancelled or refused by the user/system")
        showMessage(
            if (ok) {
                "Moved $pendingTrash item(s)$pendingScope" +
                    " to trash (recoverable for 30 days)" +
                    (if (pendingSkipped > 0) ". Skipped $pendingSkipped changed after upload." else "")
            } else {
                "Nothing was moved to trash"
            },
        )
        refreshMedia()
    }

    override fun onCleared() {
        getApplication<Application>().unregisterComponentCallbacks(memoryCallbacks)
        thumbs.evictAll()
        store.close()
    }
}

private fun ms(sinceNanos: Long): Long = (System.nanoTime() - sinceNanos) / 1_000_000

private fun mb(bytes: Long): String = "%.1f MB".format(Locale.ROOT, bytes / 1e6)

private fun speed(bytes: Long, millis: Long): String =
    if (millis <= 0) "" else ", %.1f MB/s".format(Locale.ROOT, bytes / 1e3 / millis)
