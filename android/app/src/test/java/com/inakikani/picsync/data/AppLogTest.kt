package com.inakikani.picsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AppLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val logcat = mutableListOf<String>()

    @Before fun setUp() {
        AppLog.logcat = { level, msg, _ -> logcat += "$level $msg" } // no android.util.Log on the JVM
        AppLog.init(tmp.root)
    }

    @Test fun linesGoToLogcatAndFile() {
        AppLog.i("hello")
        AppLog.w("careful", IOException("disk"))
        val text = AppLog.recent()
        assertEquals(listOf("I hello", "W careful"), logcat)
        assertTrue(text, Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} I hello\n""").containsMatchIn(text))
        assertTrue(text, text.contains("W careful | java.io.IOException: disk\n"))
        assertTrue("warnings have no stack trace", !text.contains("\tat "))
    }

    @Test fun errorsIncludeAStackTrace() {
        AppLog.e("broken", IllegalStateException("bad state"))
        val text = AppLog.recent()
        assertTrue(text, text.contains("E broken | java.lang.IllegalStateException: bad state\n"))
        assertTrue(text, text.contains("    \tat "))
    }

    @Test fun fileIsCappedAndRotated() {
        val line = "x".repeat(1000)
        repeat(AppLog.MAX_FILE / 1000 + 50) { AppLog.d(line) }
        AppLog.recent(maxChars = 10) // waits for pending writes
        val current = File(tmp.root, "app.log")
        val previous = File(tmp.root, "app.log.1")
        assertTrue(previous.exists())
        assertTrue(current.length() <= AppLog.MAX_FILE)
        assertTrue(previous.length() <= AppLog.MAX_FILE)
    }

    @Test fun recentIsCutToTheNewestLines() {
        repeat(100) { AppLog.i("line $it") }
        val text = AppLog.recent(maxChars = 200)
        assertTrue(text, text.startsWith("…(older lines cut)\n"))
        assertTrue(text, text.trimEnd().endsWith("line 99"))
    }
}
