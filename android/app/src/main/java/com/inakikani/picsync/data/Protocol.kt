package com.inakikani.picsync.data

import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

// Pure (Android-free) pieces of the pic-sync protocol, unit-tested on the JVM.

/** One entry of the server's `{"saved": [...]}` upload response. */
data class SavedFile(
    val name: String,       // file name on the Mac (the existing file if duplicate)
    val original: String,   // name we sent
    val size: Long,         // bytes the server received
    val sha256: String,     // hex SHA-256 of the bytes the server received
    val duplicate: Boolean, // identical content was already on the Mac
    val folder: String = "",   // where the Mac saved it, e.g. "~/Documents/Phone" ("" = older server)
    val category: String = "", // photos, videos, documents or other ("" = older server)
)

/** "Saved as X in ~/Folder" / "Already on the Mac as X in ~/Folder", for the share screen. */
fun SavedFile.statusText(): String {
    val where = if (folder.isNotEmpty()) " in $folder" else ""
    return if (duplicate) "Already on the Mac as $name$where" else "Saved as $name$where"
}

/** Parses the first entry of an upload response. Throws [IllegalArgumentException] if malformed. */
fun parseUploadResponse(body: String): SavedFile = try {
    val f = JSONObject(body).getJSONArray("saved").getJSONObject(0)
    SavedFile(
        name = f.getString("name"),
        original = f.optString("original", f.getString("name")),
        size = f.getLong("size"),
        sha256 = f.getString("sha256"),
        duplicate = f.optBoolean("duplicate", false),
        folder = f.optString("folder", ""),
        category = f.optString("category", ""),
    )
} catch (e: JSONException) {
    throw IllegalArgumentException("Unexpected server response", e)
}

/** Human-readable message for a non-2xx response (`{"error": "..."}` if present). */
fun parseServerError(body: String?, code: Int): String {
    val msg = body?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
    return when {
        msg.isNullOrBlank() -> "Server error $code"
        code >= 500 -> "the Mac couldn't save it: $msg" // disk full, folder gone, no permission
        else -> msg
    }
}

/** True if a `/api/info` body identifies a pic-sync server. */
fun isPicSyncInfo(body: String): Boolean =
    runCatching { JSONObject(body).optString("app") == "pic-sync" }.getOrDefault(false)

/**
 * The core safety check: the server must report exactly the bytes we sent.
 * Only uploads that pass this are ever recorded, and only recorded items can be trashed.
 */
fun isVerified(localSha256: String, localBytes: Long, saved: SavedFile): Boolean =
    saved.size == localBytes && saved.sha256.equals(localSha256, ignoreCase = true)

/** MediaStore metadata captured at upload time, compared again before trashing. */
data class MediaSnapshot(val size: Long, val dateModified: Long)

/** True if the item looks edited since upload, so its bytes must be re-hashed before trashing. */
fun changedSinceUpload(recorded: MediaSnapshot, current: MediaSnapshot): Boolean = recorded != current

/**
 * Normalises a scanned or typed server address to "http://host:port" (no trailing slash),
 * or returns null if it isn't a usable http(s) URL.
 */
fun normalizeServerUrl(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "http://$s"
    val uri = runCatching { URI(s) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return "${uri.scheme}://${uri.host}$port"
}

data class HashResult(val sha256: String, val bytes: Long)

/** Copies [input] to [output] while hashing, reporting total bytes copied to [onProgress]. */
fun copyHashing(
    input: InputStream,
    output: OutputStream,
    onProgress: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): HashResult {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        checkCancelled()
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
        digest.update(buf, 0, n)
        total += n
        onProgress(total)
    }
    return HashResult(digest.digest().toHex(), total)
}

/** Hashes a stream without writing it anywhere. */
fun hashStream(input: InputStream, checkCancelled: () -> Unit = {}): HashResult =
    copyHashing(input, DiscardOutputStream, checkCancelled = checkCancelled)

private object DiscardOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Minimal multipart/form-data framing: optional plain fields, then one file part named "file". */
object Multipart {
    fun header(boundary: String, filename: String, mimeType: String): ByteArray =
        ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${safeFilename(filename)}\"\r\n" +
            "Content-Type: ${mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n")
            .toByteArray(Charsets.UTF_8)

    /** A plain form field, written before the file part. */
    fun field(boundary: String, name: String, value: String): ByteArray =
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8)

    fun footer(boundary: String): ByteArray = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    /** Characters that would break the quoted header value are replaced with '_'. */
    fun safeFilename(name: String): String =
        name.map { if (it == '"' || it == '\\' || it == '\r' || it == '\n') '_' else it }
            .joinToString("")
            .ifBlank { "upload" }
}
