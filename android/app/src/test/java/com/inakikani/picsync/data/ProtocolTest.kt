package com.inakikani.picsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtocolTest {
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun parsesUploadResponse() {
        val f = parseUploadResponse(
            """{"saved":[{"name":"IMG_1 (1).jpg","original":"IMG_1.jpg","size":3,"sha256":"$abcSha","duplicate":true}]}""",
        )
        assertEquals(SavedFile("IMG_1 (1).jpg", "IMG_1.jpg", 3, abcSha, true), f)
    }

    @Test fun duplicateDefaultsToFalse() {
        val f = parseUploadResponse("""{"saved":[{"name":"a.jpg","size":1,"sha256":"00"}]}""")
        assertFalse(f.duplicate)
        assertEquals("a.jpg", f.original)
    }

    @Test fun malformedResponseThrows() {
        assertThrows(IllegalArgumentException::class.java) { parseUploadResponse("""{"saved":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { parseUploadResponse("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            parseUploadResponse("""{"saved":[{"name":"a.jpg"}]}""") // no size/sha256 = old server
        }
    }

    @Test fun serverErrorMessage() {
        assertEquals("invalid filename \".x\"", parseServerError("""{"error":"invalid filename \".x\""}""", 400))
        assertEquals("Server error 500", parseServerError("oops", 500))
        assertEquals("Server error 502", parseServerError(null, 502))
    }

    @Test fun recognisesPicSyncInfo() {
        assertTrue(isPicSyncInfo("""{"app":"pic-sync","version":"1"}"""))
        assertFalse(isPicSyncInfo("""{"app":"other"}"""))
        assertFalse(isPicSyncInfo("<html>"))
    }

    @Test fun verificationNeedsSizeAndHash() {
        val saved = SavedFile("a", "a", 3, abcSha.uppercase(), false)
        assertTrue(isVerified(abcSha, 3, saved)) // hex case doesn't matter
        assertFalse(isVerified(abcSha, 4, saved))
        assertFalse(isVerified(abcSha.replaceFirst('b', 'c'), 3, saved))
    }

    @Test fun changeDetection() {
        assertFalse(changedSinceUpload(MediaSnapshot(10, 100), MediaSnapshot(10, 100)))
        assertTrue(changedSinceUpload(MediaSnapshot(10, 100), MediaSnapshot(11, 100)))
        assertTrue(changedSinceUpload(MediaSnapshot(10, 100), MediaSnapshot(10, 101)))
    }

    @Test fun normalizesUrls() {
        assertEquals("http://192.168.1.23:8080", normalizeServerUrl("http://192.168.1.23:8080"))
        assertEquals("http://192.168.1.23:8080", normalizeServerUrl("  http://192.168.1.23:8080/ "))
        assertEquals("http://192.168.1.23:8080", normalizeServerUrl("192.168.1.23:8080"))
        assertEquals("http://mac.local:8080", normalizeServerUrl("mac.local:8080/status"))
        assertEquals("https://example.com", normalizeServerUrl("https://example.com"))
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("ftp://host"))
        assertNull(normalizeServerUrl("http://"))
    }

    @Test fun copyHashingCopiesAndHashes() {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val r = copyHashing(ByteArrayInputStream("abc".toByteArray()), out, onProgress = { progress += it })
        assertEquals(HashResult(abcSha, 3), r)
        assertEquals("abc", out.toString())
        assertEquals(listOf(3L), progress)
        assertEquals(abcSha, hashStream(ByteArrayInputStream("abc".toByteArray())).sha256)
    }

    @Test fun copyHashingLargeInput() {
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val r = copyHashing(ByteArrayInputStream(data), out)
        assertEquals(data.size.toLong(), r.bytes)
        assertTrue(data.contentEquals(out.toByteArray()))
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data).toHex()
        assertEquals(expected, r.sha256)
    }

    @Test fun cancellationStopsCopy() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            copyHashing(
                ByteArrayInputStream(ByteArray(1_000_000)), ByteArrayOutputStream(),
                checkCancelled = { if (++calls > 2) throw IllegalStateException("cancelled") },
            )
        }
    }

    @Test fun multipartFraming() {
        val h = String(Multipart.header("B", "IMG 1.jpg", "image/jpeg"), Charsets.UTF_8)
        assertEquals(
            "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"IMG 1.jpg\"\r\n" +
                "Content-Type: image/jpeg\r\n\r\n",
            h,
        )
        assertEquals("\r\n--B--\r\n", String(Multipart.footer("B"), Charsets.UTF_8))
        assertTrue(String(Multipart.header("B", "x", ""), Charsets.UTF_8).contains("application/octet-stream"))
    }

    @Test fun unsafeFilenameCharactersReplaced() {
        assertEquals("a_b_c_d_.jpg", Multipart.safeFilename("a\"b\\c\rd\n.jpg"))
        assertEquals("upload", Multipart.safeFilename(""))
        assertEquals("café.jpg", Multipart.safeFilename("café.jpg"))
    }
}
