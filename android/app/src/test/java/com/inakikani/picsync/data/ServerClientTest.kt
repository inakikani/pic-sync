package com.inakikani.picsync.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest

/** Runs the real HTTP client against a tiny fake pic-sync server. */
class ServerClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String

    /** How the fake server answers uploads. */
    private var mode = "honest"
    private var receivedFilename: String? = null
    private var receivedBody: ByteArray? = null
    private var receivedMtime: String? = null

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/info") { ex -> ex.reply(200, """{"app":"pic-sync","version":"1"}""") }
        server.createContext("/upload") { ex -> handleUpload(ex) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() = server.stop(0)

    private fun handleUpload(ex: HttpExchange) {
        val boundary = ex.requestHeaders.getFirst("Content-Type").substringAfter("boundary=")
        val raw = ex.requestBody.readBytes()
        // Latin-1 maps bytes 1:1, so string offsets are byte offsets.
        val text = String(raw, Charsets.ISO_8859_1)
        receivedMtime = Regex("name=\"mtime\"\r\n\r\n([^\r]*)\r\n").find(text)?.groupValues?.get(1)
        val headerEnd = text.indexOf("\r\n\r\n", text.indexOf("filename=")) + 4
        val footer = "\r\n--$boundary--\r\n".toByteArray()
        val header = String(raw, 0, headerEnd, Charsets.UTF_8).substringAfterLast("--$boundary")
        val content = raw.copyOfRange(headerEnd, raw.size - footer.size)
        assertTrue("footer intact", raw.copyOfRange(raw.size - footer.size, raw.size).contentEquals(footer))
        receivedFilename = Regex("filename=\"([^\"]*)\"").find(header)?.groupValues?.get(1)
        receivedBody = content

        var sha = MessageDigest.getInstance("SHA-256").digest(content).toHex()
        var size = content.size.toLong()
        when (mode) {
            "wrong-hash" -> sha = "0".repeat(64)
            "wrong-size" -> size += 1
            "error" -> return ex.reply(400, """{"error":"invalid filename"}""")
        }
        ex.reply(
            200,
            """{"saved":[{"name":"$receivedFilename","original":"$receivedFilename","size":$size,"sha256":"$sha","duplicate":${mode == "duplicate"}}]}""",
        )
    }

    private fun upload(data: ByteArray, name: String = "IMG_1.jpg", takenAt: Long = 0) =
        ServerClient(base).upload(
            ByteArrayInputStream(data), name, "image/jpeg", takenAt = takenAt, onProgress = {}, checkCancelled = {},
        )

    @Test fun sendsTakenTimeBeforeTheFile() {
        val data = "photo bytes".toByteArray()
        val r = upload(data, takenAt = 1_700_000_000_123)
        assertEquals("1700000000123", receivedMtime)
        assertTrue(receivedBody!!.contentEquals(data))
        assertEquals("IMG_1.jpg", receivedFilename)
        assertTrue(r.verified)
    }

    @Test fun noTakenTimeNoField() {
        upload("abc".toByteArray())
        assertEquals(null, receivedMtime)
    }

    @Test fun checkServerAcceptsPicSync() {
        ServerClient(base).checkServer() // no exception
    }

    @Test fun checkServerRejectsOtherServers() {
        server.removeContext("/api/info")
        server.createContext("/api/info") { ex -> ex.reply(200, "<html>router login</html>") }
        assertThrows(IOException::class.java) { ServerClient(base).checkServer() }
    }

    @Test fun honestServerIsVerified() {
        val data = ByteArray(300_000) { (it * 7).toByte() } // several chunks
        val r = upload(data)
        assertTrue(r.verified)
        assertEquals("IMG_1.jpg", receivedFilename)
        assertTrue("server got exact bytes", data.contentEquals(receivedBody))
        assertEquals(data.size.toLong(), r.local.bytes)
    }

    @Test fun duplicateIsStillVerified() {
        mode = "duplicate"
        val r = upload("same".toByteArray())
        assertTrue(r.verified)
        assertTrue(r.saved.duplicate)
    }

    @Test fun wrongHashIsNotVerified() {
        mode = "wrong-hash"
        assertFalse(upload("abc".toByteArray()).verified)
    }

    @Test fun wrongSizeIsNotVerified() {
        mode = "wrong-size"
        assertFalse(upload("abc".toByteArray()).verified)
    }

    @Test fun serverErrorThrowsWithMessage() {
        mode = "error"
        val e = assertThrows(IOException::class.java) { upload("abc".toByteArray()) }
        assertEquals("invalid filename", e.message)
    }

    @Test fun unreachableServerThrows() {
        val client = ServerClient("http://127.0.0.1:1") // nothing listens on port 1
        assertThrows(IOException::class.java) {
            client.upload(ByteArrayInputStream("abc".toByteArray()), "a.jpg", "image/jpeg", 0, {}, {})
        }
    }

    private fun HttpExchange.reply(code: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        error("not found")
    }
}
