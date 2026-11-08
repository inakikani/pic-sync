package com.inakikani.picsync.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The rules that decide which photos may be moved to the phone's trash. */
class TrashTest {
    private fun item(id: Long, size: Long = 100, modified: Long = 1, album: Long = 1) =
        MediaItem(id, "IMG_$id.jpg", "image/jpeg", size, modified, 0, false, album, "Camera", 0)

    private fun record(item: MediaItem, sha: String = "sha-${item.id}") =
        UploadRecord(item.id, item.snapshot, item.size, sha, item.name, "http://mac:8080", 0)

    private val all: (MediaItem) -> Boolean = { true }
    private var hashed = mutableListOf<Long>()
    private fun hashes(vararg byId: Pair<Long, HashResult?>): (MediaItem) -> HashResult? {
        val m = byId.toMap()
        return { hashed += it.id; m[it.id] }
    }

    @Test fun unchangedVerifiedUploadsAreTrashedWithoutRehashing() {
        val a = item(1)
        val plan = planTrash(listOf(a), mapOf(1L to record(a)), emptySet(), all, hashes())
        assertEquals(listOf(a), plan.trash)
        assertEquals(emptyList<Long>(), hashed)
    }

    @Test fun itemsWithoutARecordAreNeverTrashed() {
        val plan = planTrash(listOf(item(1)), emptyMap(), emptySet(), all, hashes())
        assertEquals(TrashPlan(emptyList(), emptyList(), emptyList()), plan)
    }

    @Test fun skippedItemsAreNeverTrashed() {
        val a = item(1)
        val plan = planTrash(listOf(a), mapOf(1L to record(a)), setOf(1L), all, hashes())
        assertEquals(emptyList<MediaItem>(), plan.trash)
    }

    @Test fun onlyItemsInScope() {
        val a = item(1, album = 1)
        val b = item(2, album = 2)
        val plan = planTrash(listOf(a, b), mapOf(1L to record(a), 2L to record(b)), emptySet(), { it.albumId == 2L }, hashes())
        assertEquals(listOf(b), plan.trash)
    }

    @Test fun changedItemWithSameBytesIsTrashedAndItsRecordRefreshed() {
        val uploaded = item(1, modified = 1)
        val renamed = item(1, modified = 2) // e.g. renamed: new date_modified, same bytes
        val rec = record(uploaded)
        val plan = planTrash(listOf(renamed), mapOf(1L to rec), emptySet(), all, hashes(1L to HashResult(rec.sha256, rec.bytes)))
        assertEquals(listOf(renamed), plan.trash)
        assertEquals(listOf(rec.copy(snapshot = renamed.snapshot)), plan.refreshed)
        assertEquals(listOf(1L), hashed)
    }

    @Test fun editedItemIsKept() {
        val uploaded = item(1, size = 100)
        val edited = item(1, size = 120)
        val rec = record(uploaded)
        val plan = planTrash(listOf(edited), mapOf(1L to rec), emptySet(), all, hashes(1L to HashResult("other", 120)))
        assertEquals(emptyList<MediaItem>(), plan.trash)
        assertEquals(listOf(edited), plan.skipped)
        assertEquals(emptyList<UploadRecord>(), plan.refreshed)
    }

    @Test fun sameHashButDifferentSizeIsKept() {
        val uploaded = item(1, size = 100)
        val changed = item(1, size = 101)
        val rec = record(uploaded)
        val plan = planTrash(listOf(changed), mapOf(1L to rec), emptySet(), all, hashes(1L to HashResult(rec.sha256, 101)))
        assertEquals(listOf(changed), plan.skipped)
    }

    @Test fun unreadableChangedItemIsKept() {
        val uploaded = item(1, modified = 1)
        val changed = item(1, modified = 2)
        val plan = planTrash(listOf(changed), mapOf(1L to record(uploaded)), emptySet(), all, hashes(1L to null))
        assertEquals(listOf(changed), plan.skipped)
        assertEquals(emptyList<MediaItem>(), plan.trash)
    }
}
