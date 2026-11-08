package com.inakikani.picsync.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the upload notification shows. [progress] is 0..1 for the whole batch; null while unknown. */
data class UploadStatus(val title: String, val text: String, val progress: Float?)

/**
 * The uploads in progress (gallery batches, shares), for the background upload notification.
 * Plain Kotlin, so it's unit-tested; `UploadService` shows [summary] and cancels through it.
 *
 * Each upload registers with [begin] and reports through its [Task]. Callers throttle their
 * reports (see [ProgressSteps]); `UploadService` redraws at most once a second regardless.
 */
class UploadTasks {
    private val _tasks = MutableStateFlow<Map<Task, UploadStatus>>(emptyMap())

    /** Empty when nothing is uploading. */
    val tasks: StateFlow<Map<Task, UploadStatus>> = _tasks.asStateFlow()

    fun begin(title: String, cancel: () -> Unit): Task =
        Task(title, cancel).also { t -> _tasks.update { it + (t to UploadStatus(title, "Starting…", null)) } }

    /** Cancels every upload (the notification's Cancel button). */
    fun cancelAll() = _tasks.value.keys.forEach { it.cancel() }

    inner class Task internal constructor(private val title: String, internal val cancel: () -> Unit) {
        /** File [index] of [total] ([name]) is [fileFraction] (0..1) sent. */
        fun progress(index: Int, total: Int, name: String, fileFraction: Float) {
            val status = UploadStatus(title, "${index + 1} of $total: $name", ((index + fileFraction) / total).coerceIn(0f, 1f))
            _tasks.update { m -> if (this in m) m + (this to status) else m }
        }

        /** The upload finished, failed or was cancelled. Safe to call more than once. */
        fun end() = _tasks.update { it - this }
    }
}

/** One line for the notification: a single upload as it is, several as a count. */
fun summary(tasks: Collection<UploadStatus>): UploadStatus = when (tasks.size) {
    0 -> UploadStatus("Finishing uploads", "", null)
    1 -> tasks.first()
    else -> UploadStatus("${tasks.size} uploads to the Mac", "Tap Cancel to stop them", null)
}

/**
 * Throttles one file's progress reports to [step] increments (0..1), always letting the
 * first and the last (1.0) through. The upload reports every 64 KB written; the progress
 * bars and the notification only need ~100 updates per file. [reset] for the next file.
 */
class ProgressSteps(private val step: Float = 0.01f) {
    private var last = -1f

    fun next(fraction: Float): Boolean {
        if (last >= 0f && fraction - last < step && !(fraction >= 1f && last < 1f)) return false
        last = fraction
        return true
    }

    fun reset() {
        last = -1f
    }
}
