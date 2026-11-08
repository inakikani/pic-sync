package com.inakikani.picsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTasksTest {
    @Test fun beginShowsStartingThenProgress() {
        val tasks = UploadTasks()
        val t = tasks.begin("Uploading to the Mac") {}
        assertEquals(UploadStatus("Uploading to the Mac", "Starting…", null), tasks.tasks.value[t])

        t.progress(index = 2, total = 4, name = "IMG_3.jpg", fileFraction = 0.5f)
        assertEquals(UploadStatus("Uploading to the Mac", "3 of 4: IMG_3.jpg", 0.625f), tasks.tasks.value[t])
    }

    @Test fun endRemovesAndLateProgressIsIgnored() {
        val tasks = UploadTasks()
        val t = tasks.begin("Sending to the Mac") {}
        t.end()
        assertTrue(tasks.tasks.value.isEmpty())
        t.progress(0, 1, "a.pdf", 1f) // e.g. a last report racing the end
        assertTrue(tasks.tasks.value.isEmpty())
        t.end() // twice is fine
    }

    @Test fun cancelAllCancelsEveryUpload() {
        val tasks = UploadTasks()
        var cancelled = 0
        tasks.begin("Uploading to the Mac") { cancelled++ }
        tasks.begin("Sending to the Mac") { cancelled++ }
        tasks.cancelAll()
        assertEquals(2, cancelled)
    }

    @Test fun summaryOfNoneOneAndSeveral() {
        val one = UploadStatus("Uploading to the Mac", "1 of 2: a.jpg", 0.25f)
        assertEquals(one, summary(listOf(one)))
        assertEquals("2 uploads to the Mac", summary(listOf(one, one.copy(title = "Sending to the Mac"))).title)
        assertEquals(null, summary(listOf(one, one)).progress)
        assertEquals(null, summary(emptyList()).progress)
    }

    @Test fun progressStepsThrottleToOnePercent() {
        val steps = ProgressSteps()
        assertTrue(steps.next(0f)) // first always
        assertFalse(steps.next(0.005f))
        assertTrue(steps.next(0.012f))
        assertFalse(steps.next(0.02f))
        assertTrue(steps.next(0.03f))
        assertTrue(steps.next(1f))
        assertFalse(steps.next(1f)) // but only once
        steps.reset() // next file
        assertTrue(steps.next(0f))
    }

    @Test fun progressStepsLastWithinAStep() {
        val steps = ProgressSteps()
        assertTrue(steps.next(0.995f))
        assertTrue(steps.next(1f))
    }
}
