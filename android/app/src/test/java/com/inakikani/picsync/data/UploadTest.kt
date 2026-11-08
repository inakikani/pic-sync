package com.inakikani.picsync.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** The rule that makes a file eligible for trashing: recorded only when the Mac has identical bytes. */
class UploadTest {
    private fun result(verified: Boolean, duplicate: Boolean = false) = UploadResult(
        saved = SavedFile("IMG_1.jpg", "IMG_1.jpg", 5, "abc", duplicate),
        local = HashResult("abc", 5),
        verified = verified,
    )

    private val recorded = mutableListOf<UploadResult>()
    private val record: (UploadResult) -> Unit = { recorded += it }

    @Test fun verifiedUploadIsRecorded() {
        val r = result(verified = true)
        val outcome = uploadVerified({ r }, record)
        assertEquals(FileOutcome.Verified(r), outcome)
        assertEquals(listOf(r), recorded)
        assertNull(outcome.problem)
    }

    @Test fun duplicateOnTheMacIsVerifiedToo() {
        val r = result(verified = true, duplicate = true)
        assertEquals(FileOutcome.Verified(r), uploadVerified({ r }, record))
        assertEquals(listOf(r), recorded)
    }

    @Test fun mismatchIsNotRecorded() {
        val r = result(verified = false)
        val outcome = uploadVerified({ r }, record)
        assertEquals(FileOutcome.NotVerified(r), outcome)
        assertTrue(recorded.isEmpty())
        assertEquals("the Mac didn't receive identical bytes", outcome.problem)
    }

    @Test fun networkErrorIsAFailureWithAnExplanation() {
        val outcome = uploadVerified({ throw SocketTimeoutException("read timed out") }, record)
        assertTrue(outcome is FileOutcome.Failed)
        assertTrue(recorded.isEmpty())
        assertTrue(outcome.problem!!.startsWith("timed out"))
    }

    @Test fun failingToRecordIsAFailure() {
        val outcome = uploadVerified({ result(verified = true) }, { throw IllegalStateException("disk full") })
        assertEquals("disk full", outcome.problem)
    }

    @Test fun cancellationIsNotSwallowed() {
        assertThrows(CancellationException::class.java) {
            uploadVerified({ throw CancellationException("cancelled") }, record)
        }
        assertTrue(recorded.isEmpty())
    }

    @Test fun summaries() {
        assertEquals("3 uploaded", batchSummary("uploaded", 3, 0, 0, cancelled = false))
        assertEquals("3 sent, 1 already on Mac, 2 failed", batchSummary("sent", 3, 1, 2, cancelled = false))
        assertEquals("Cancelled: 0 uploaded, 1 failed", batchSummary("uploaded", 0, 0, 1, cancelled = true))
    }
}
