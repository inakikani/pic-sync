package com.inakikani.picsync.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inakikani.picsync.data.Album
import com.inakikani.picsync.data.MediaFilters
import com.inakikani.picsync.data.MediaItem
import com.inakikani.picsync.data.MediaRepository
import com.inakikani.picsync.data.ServerClient
import com.inakikani.picsync.data.Settings
import com.inakikani.picsync.data.UploadRecord
import com.inakikani.picsync.data.UploadResult
import com.inakikani.picsync.data.UploadStore
import com.inakikani.picsync.data.changedSinceUpload
import com.inakikani.picsync.data.groupAlbums
import com.inakikani.picsync.data.normalizeServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZonedDateTime

data class UploadProgress(val index: Int, val total: Int, val currentId: Long, val fraction: Float)

/** Album + filters predicate; used for the gallery and again, freshly, for trashing. */
fun scopeMatcher(album: Long?, filters: MediaFilters): (MediaItem) -> Boolean {
    val match = filters.matcher(ZonedDateTime.now())
    return { item -> (album == null || item.albumId == album) && match(item) }
}

data class UiState(
    val serverUrl: String? = null,
    val connecting: Boolean = false,
    val connectError: String? = null,
    val media: List<MediaItem> = emptyList(),
    val mediaLoaded: Boolean = false,
    val records: Map<Long, UploadRecord> = emptyMap(), // verified uploads, by MediaStore id
    val failed: Map<Long, String> = emptyMap(),        // this session's failures
    val selected: Set<Long> = emptySet(),
    val onlyNotUploaded: Boolean = false,
    val selectedAlbum: Long? = null, // null = all photos
    val filters: MediaFilters = MediaFilters(),
    val upload: UploadProgress? = null,
    val busy: String? = null,
    val message: String? = null,
) {
    val albums: List<Album> by lazy {
        groupAlbums(media, { it.albumId }, { it.albumName }, { it.id in records })
    }
    val albumName: String? get() = selectedAlbum?.let { id -> albums.firstOrNull { it.id == id }?.name }

    // Everything below is scoped to the selected album (or all photos) and the filters.
    val inScope: List<MediaItem> by lazy { media.filter(scopeMatcher(selectedAlbum, filters)) }
    val notUploaded: List<MediaItem> by lazy { inScope.filter { it.id !in records } }
    val uploadedOnPhone: Int by lazy { inScope.count { it.id in records } }

    /** " from Camera", " from Camera (filtered)", " (filtered)" or "" for button/dialog texts. */
    val scopeLabel: String
        get() = (albumName?.let { " from $it" } ?: "") + (if (filters.isActive) " (filtered)" else "")
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val resolver = app.contentResolver
    private val repo = MediaRepository(resolver)
    private val store = UploadStore(app)
    private val settings = Settings(app)
    private val thumbs = LruCache<Long, ImageBitmap>(400)

    private val _state = MutableStateFlow(UiState(serverUrl = settings.serverUrl))
    val state = _state.asStateFlow()

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
            try {
                withContext(Dispatchers.IO) { ServerClient(url).checkServer() }
                settings.serverUrl = url
                _state.update { it.copy(serverUrl = url, connecting = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(connecting = false, connectError = "Couldn't connect to $url: ${e.describe()}")
                }
            }
        }
    }

    fun setConnectError(msg: String) = _state.update { it.copy(connectError = msg) }

    fun disconnect() {
        uploadJob?.cancel()
        settings.serverUrl = null
        _state.update { it.copy(serverUrl = null, connectError = null) }
    }

    // ---- gallery ----

    fun refreshMedia() {
        viewModelScope.launch {
            val (media, records) = withContext(Dispatchers.IO) { repo.query() to store.all() }
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
                )
            }
        }
    }

    suspend fun thumbnail(item: MediaItem): ImageBitmap? {
        thumbs.get(item.id)?.let { return it }
        return withContext(Dispatchers.IO) { repo.thumbnail(item)?.asImageBitmap() }
            ?.also { thumbs.put(item.id, it) }
    }

    fun toggle(id: Long) = _state.update {
        if (it.upload != null) it
        else it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectNotUploaded() = _state.update { s -> s.copy(selected = s.notUploaded.mapTo(HashSet()) { it.id }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }
    fun setOnlyNotUploaded(only: Boolean) = _state.update { it.copy(onlyNotUploaded = only) }
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

        uploadJob = viewModelScope.launch {
            val client = ServerClient(url)
            var ok = 0
            var dup = 0
            var failed = 0
            var firstError: String? = null
            var cancelled = false
            try {
                for ((i, item) in items.withIndex()) {
                    lastReported = -1f
                    _state.update { it.copy(upload = UploadProgress(i, items.size, item.id, 0f)) }
                    try {
                        val result = uploadOne(client, item) { sent -> reportProgress(i, items.size, item, sent) }
                        if (result.verified) {
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
                            failed++
                            val msg = "${item.name}: the Mac didn't receive identical bytes"
                            firstError = firstError ?: msg
                            _state.update { it.copy(failed = it.failed + (item.id to msg)) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        val msg = "${item.name}: ${e.describe()}"
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
                _state.update { it.copy(upload = null, message = summary) }
            }
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
    }

    private suspend fun uploadOne(client: ServerClient, item: MediaItem, onProgress: (Long) -> Unit): UploadResult =
        withContext(Dispatchers.IO) {
            val ctx = coroutineContext
            val input = resolver.openInputStream(item.uri) ?: throw IOException("can't open file")
            input.use {
                client.upload(it, item.name, item.mimeType, onProgress, checkCancelled = { ctx.ensureActive() })
            }
        }

    private fun reportProgress(index: Int, total: Int, item: MediaItem, sent: Long) {
        val f = if (item.size > 0) (sent.toFloat() / item.size).coerceIn(0f, 1f) else 0f
        if (f - lastReported < 0.01f && f < 1f) return // throttle UI updates to ~1% steps
        lastReported = f
        _state.update { it.copy(upload = UploadProgress(index, total, item.id, f)) }
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
        pendingScope = scope.scopeLabel
        _state.update { it.copy(busy = "Checking files…") }
        try {
            val (uris, skipped) = withContext(Dispatchers.IO) {
                val media = repo.query().filter(inScope)
                val records = store.all()
                val uris = ArrayList<Uri>()
                var skipped = 0
                for (item in media) {
                    val rec = records[item.id] ?: continue
                    if (!changedSinceUpload(rec.snapshot, item.snapshot)) {
                        uris += item.uri
                        continue
                    }
                    val now = runCatching { repo.hash(item) }.getOrNull()
                    if (now != null && now.bytes == rec.bytes && now.sha256 == rec.sha256) {
                        store.put(rec.copy(snapshot = item.snapshot)) // same bytes, just new metadata
                        uris += item.uri
                    } else {
                        skipped++ // edited after upload: the Mac doesn't have this version
                    }
                }
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
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    fun onTrashResult(ok: Boolean) {
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
        store.close()
    }
}

private fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
