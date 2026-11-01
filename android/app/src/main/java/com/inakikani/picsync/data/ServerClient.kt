package com.inakikani.picsync.data

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class UploadResult(
    val saved: SavedFile,
    val local: HashResult,
    val verified: Boolean,
)

/** Blocking HTTP client for a pic-sync server. Call from a background thread. */
class ServerClient(private val baseUrl: String) {

    /** Throws [IOException] unless [baseUrl] answers as a pic-sync server. */
    fun checkServer() {
        val conn = open("/api/info").apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        try {
            val code = conn.responseCode
            val body = conn.readBody(code)
            if (code != 200 || !isPicSyncInfo(body.orEmpty())) {
                throw IOException("That address doesn't look like a pic-sync server")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams [input] as a multipart upload, hashing it on the way, and checks the
     * server's reported size and SHA-256 against what was actually sent.
     */
    fun upload(
        input: InputStream,
        filename: String,
        mimeType: String,
        onProgress: (Long) -> Unit,
        checkCancelled: () -> Unit,
    ): UploadResult {
        val boundary = "picsync-" + UUID.randomUUID()
        val conn = open("/upload").apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 120_000
            setChunkedStreamingMode(64 * 1024) // stream; never buffer whole videos in memory
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            val local = conn.outputStream.buffered(64 * 1024).use { out ->
                out.write(Multipart.header(boundary, filename, mimeType))
                val result = copyHashing(input, out, onProgress, checkCancelled)
                out.write(Multipart.footer(boundary))
                result
            }
            val code = conn.responseCode
            val body = conn.readBody(code)
            if (code !in 200..299) throw IOException(parseServerError(body, code))
            val saved = parseUploadResponse(body.orEmpty())
            return UploadResult(saved, local, isVerified(local.sha256, local.bytes, saved))
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String) = URL(baseUrl + path).openConnection() as HttpURLConnection

    private fun HttpURLConnection.readBody(code: Int): String? =
        (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }
}
