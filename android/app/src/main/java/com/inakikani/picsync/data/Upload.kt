package com.inakikani.picsync.data

import kotlinx.coroutines.CancellationException

/** What happened to one file of a batch (gallery or share). See [uploadVerified]. */
sealed interface FileOutcome {
    /** The Mac has exactly these bytes (newly saved, or [UploadResult.saved] says duplicate), and it was recorded. */
    data class Verified(val result: UploadResult) : FileOutcome

    /** The Mac answered, but with a different size or SHA-256: nothing was recorded. */
    data class NotVerified(val result: UploadResult) : FileOutcome

    /** Sending, or recording, failed. */
    data class Failed(val error: Exception) : FileOutcome

    /** Why it failed, for the user ("the Mac didn't …", "timed out: …"); null if it didn't. */
    val problem: String?
        get() = when (this) {
            is Verified -> null
            is NotVerified -> "the Mac didn't receive identical bytes"
            is Failed -> error.explain()
        }
}

/**
 * Uploads one file with [upload] and calls [record] only if the Mac reported exactly the
 * bytes sent ([UploadResult.verified]). This is what makes an item eligible for trashing
 * later (see [planTrash]), so it is the one place that decides it.
 *
 * Errors become [FileOutcome.Failed], so a batch goes on with the next file; cancellation
 * is rethrown. Blocking: call from a background thread.
 */
fun uploadVerified(upload: () -> UploadResult, record: (UploadResult) -> Unit): FileOutcome =
    try {
        val result = upload()
        if (result.verified) {
            record(result)
            FileOutcome.Verified(result)
        } else {
            FileOutcome.NotVerified(result)
        }
    } catch (e: CancellationException) {
        throw e // a CancellationException is an Exception too: don't turn it into a failure
    } catch (e: Exception) {
        FileOutcome.Failed(e)
    }

/**
 * The line shown when a batch ends, e.g. "3 uploaded, 1 already on Mac, 2 failed", or
 * "Cancelled: 1 sent". [verb] names what happened to saved files ("uploaded", "sent").
 */
fun batchSummary(verb: String, saved: Int, duplicates: Int, failed: Int, cancelled: Boolean): String {
    val parts = buildList {
        add("$saved $verb")
        if (duplicates > 0) add("$duplicates already on Mac")
        if (failed > 0) add("$failed failed")
    }
    return (if (cancelled) "Cancelled: " else "") + parts.joinToString(", ")
}
