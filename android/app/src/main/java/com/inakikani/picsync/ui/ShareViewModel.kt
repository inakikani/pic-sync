package com.inakikani.picsync.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inakikani.picsync.data.AppLog
import com.inakikani.picsync.data.MediaRepository
import com.inakikani.picsync.data.ServerClient
import com.inakikani.picsync.data.UploadRecord
import com.inakikani.picsync.data.UploadStore
import com.inakikani.picsync.data.explain
import com.inakikani.picsync.data.matchShared
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

/** A file shared to pic-sync from another app (e.g. Google Photos). */
data class SharedItem(val uri: Uri, val name: String, val size: Long?, val mimeType: String, val takenAt: Long)

enum class ShareStatus { WAITING, SENDING, SAVED, DUPLICATE, FAILED }

data class ShareRow(
    val item: SharedItem,
    val status: ShareStatus = ShareStatus.WAITING,
    val detail: String? = null,  // "Saved as …", the error, …
    val onPhone: Boolean? = null, // matched to a photo in the phone's library (can be trashed later)
)

data class ShareState(
    val loaded: Boolean = false,
    val rows: List<ShareRow> = emptyList(),
    val current: Int = -1,
    val running: Boolean = false,
    val finished: Boolean = false,
    val summary: String? = null,
)

/**
 * Uploads files shared to pic-sync. Each verified upload is matched back to the
 * phone's own photo (same size, then same SHA-256) and recorded like a gallery
 * upload, so it shows ✓ in the gallery and can be moved to trash from there.
 */
class ShareViewModel(app: Application) : AndroidViewModel(app) {
    private val resolver = app.contentResolver
    private val repo = MediaRepository(resolver) {
        app.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    private val store = UploadStore(app)
    private val userAgent =
        "pic-sync-android/${appVersion(app)} (Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL})"

    private val _state = MutableStateFlow(ShareState())
    val state = _state.asStateFlow()
    private val _fraction = MutableStateFlow(0f) // current file, kept out of ShareState (see AppViewModel)
    val fraction = _fraction.asStateFlow()
    private var job: Job? = null

    fun load(uris: List<Uri>) {
        if (_state.value.loaded) return // already loaded (e.g. after rotating the phone)
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { uris.map(::describe) }
            items.forEach { AppLog.d("share: ${it.name}, ${it.size ?: "?"} bytes, ${it.mimeType}, uri ${it.uri.authority}") }
            _state.update { it.copy(loaded = true, rows = items.map { item -> ShareRow(item) }) }
        }
    }

    /** Name, size, type and (if the sharing app provides it) date taken of a shared URI. */
    private fun describe(uri: Uri): SharedItem {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) null else c.getLong(1)
                }
            }
        }.onFailure { AppLog.w("share: can't read name/size of $uri", it) }
        // Optional: most apps don't expose it; the Mac then reads the date from the file itself.
        val taken = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        return SharedItem(
            uri = uri,
            name = name?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "shared-file"),
            size = size,
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            takenAt = taken,
        )
    }

    fun start(url: String) {
        if (job != null || _state.value.finished) return
        val rows = _state.value.rows
        if (rows.isEmpty()) return
        AppLog.i("share: sending ${rows.size} item(s) to $url")
        _state.update { it.copy(running = true) }
        job = viewModelScope.launch {
            val client = ServerClient(url, userAgent)
            val canMatch = mediaAccess(getApplication<Application>()) != MediaAccess.NONE
            var cancelled = false
            try {
                for (i in rows.indices) {
                    _fraction.value = 0f
                    setRow(i) { it.copy(status = ShareStatus.SENDING) }
                    _state.update { it.copy(current = i) }
                    sendOne(client, url, i, rows[i].item, canMatch)
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                finish(cancelled)
            }
        }
    }

    private suspend fun sendOne(client: ServerClient, url: String, i: Int, item: SharedItem, canMatch: Boolean) {
        val started = System.nanoTime()
        try {
            val result = withContext(Dispatchers.IO) {
                val ctx = coroutineContext
                val input = resolver.openInputStream(item.uri) ?: throw IOException("can't open the shared file")
                input.use {
                    client.upload(it, item.name, item.mimeType, item.takenAt, onProgress = { sent ->
                        item.size?.takeIf { s -> s > 0 }?.let { s -> _fraction.value = (sent.toFloat() / s).coerceIn(0f, 1f) }
                    }, checkCancelled = { ctx.ensureActive() })
                }
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            if (!result.verified) {
                AppLog.e(
                    "share: ${item.name} NOT verified: sent ${result.local.bytes} bytes sha256 ${result.local.sha256}, " +
                        "Mac reported ${result.saved.size} bytes sha256 ${result.saved.sha256}",
                )
                setRow(i) { it.copy(status = ShareStatus.FAILED, detail = "the Mac didn't receive identical bytes") }
                return
            }
            // Link it to the phone's own photo, so the gallery can trash it later.
            val match = if (!canMatch) {
                null to "no photo access, so it can't be found in the phone's library"
            } else {
                withContext(Dispatchers.IO) {
                    matchShared(repo.withSize(result.local.bytes), result.local) { candidate ->
                        runCatching { repo.hash(candidate) }.getOrNull()
                    }
                }
            }
            val phoneItem = match.first
            if (phoneItem != null) {
                withContext(Dispatchers.IO) {
                    store.put(
                        UploadRecord(
                            mediaId = phoneItem.id,
                            snapshot = phoneItem.snapshot,
                            bytes = result.local.bytes,
                            sha256 = result.local.sha256,
                            serverName = result.saved.name,
                            serverUrl = url,
                            uploadedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            AppLog.i(
                "share: ${item.name} (${result.local.bytes} bytes, $ms ms) " +
                    (if (result.saved.duplicate) "already on Mac as ${result.saved.name}" else "saved as ${result.saved.name}") +
                    ", verified; phone library: " +
                    (phoneItem?.let { "matched id ${it.id} (${it.name})" } ?: "no match: ${match.second}"),
            )
            setRow(i) {
                it.copy(
                    status = if (result.saved.duplicate) ShareStatus.DUPLICATE else ShareStatus.SAVED,
                    detail = if (result.saved.duplicate) "Already on the Mac as ${result.saved.name}" else "Saved as ${result.saved.name}",
                    onPhone = phoneItem != null,
                )
            }
        } catch (e: CancellationException) {
            setRow(i) { it.copy(status = ShareStatus.WAITING) }
            throw e
        } catch (e: Exception) {
            AppLog.w("share: ${item.name} failed", e)
            setRow(i) { it.copy(status = ShareStatus.FAILED, detail = e.explain()) }
        }
    }

    private fun finish(cancelled: Boolean) {
        val rows = _state.value.rows
        val saved = rows.count { it.status == ShareStatus.SAVED }
        val dup = rows.count { it.status == ShareStatus.DUPLICATE }
        val failed = rows.count { it.status == ShareStatus.FAILED }
        val onPhone = rows.count { it.onPhone == true }
        val summary = buildList {
            add("$saved sent")
            if (dup > 0) add("$dup already on Mac")
            if (failed > 0) add("$failed failed")
        }.joinToString(", ").let { if (cancelled) "Cancelled: $it" else it } +
            if (onPhone > 0) ". $onPhone can be moved to trash from pic-sync." else "."
        AppLog.i("share: done: $summary")
        _state.update { it.copy(running = false, finished = true, current = -1, summary = summary) }
    }

    fun cancel() {
        AppLog.i("share: cancelled by the user")
        job?.cancel()
    }

    fun failToStart(reason: String) {
        AppLog.w("share: can't send: $reason")
        _state.update { it.copy(finished = true, summary = reason) }
    }

    private fun setRow(i: Int, change: (ShareRow) -> ShareRow) = _state.update { s ->
        s.copy(rows = s.rows.toMutableList().also { it[i] = change(it[i]) })
    }

    override fun onCleared() {
        store.close()
    }
}
