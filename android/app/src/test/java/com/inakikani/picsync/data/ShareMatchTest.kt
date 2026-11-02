package com.inakikani.picsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareMatchTest {
    private val target = HashResult("aa", 100)

    @Test fun matchesTheIdenticalCandidate() {
        val hashes = mapOf("x" to HashResult("bb", 100), "y" to HashResult("aa", 100))
        val (match, why) = matchShared(listOf("x", "y"), target) { hashes[it] }
        assertEquals("y", match)
        assertEquals("matched", why)
    }

    @Test fun noCandidatesMeansCloudOnlyOrDifferentVersion() {
        val (match, why) = matchShared(emptyList<String>(), target) { error("not called") }
        assertNull(match)
        assertTrue(why, why.contains("no photo of that size"))
    }

    @Test fun sameSizeDifferentContentIsNotAMatch() {
        val (match, why) = matchShared(listOf("x"), target) { HashResult("bb", 100) }
        assertNull(match)
        assertTrue(why, why.contains("different content"))
    }

    @Test fun unreadableCandidatesAreSkipped() {
        val (match, _) = matchShared(listOf("broken", "ok"), target) { if (it == "broken") null else target }
        assertEquals("ok", match)
    }

    @Test fun hashesAtMostTheCap() {
        var hashed = 0
        matchShared(List(50) { it }, target) { hashed++; HashResult("zz", 100) }
        assertEquals(MAX_MATCH_CANDIDATES, hashed)
    }
}
