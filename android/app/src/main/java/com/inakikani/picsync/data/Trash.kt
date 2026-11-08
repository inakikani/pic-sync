package com.inakikani.picsync.data

/** What "Move to trash" will do. Built by [planTrash]. */
data class TrashPlan(
    /** Verified on the Mac, byte for byte: safe to move to the phone's trash. */
    val trash: List<MediaItem>,
    /** Changed since upload, or unreadable now: the Mac may not have these bytes, so they stay. */
    val skipped: List<MediaItem>,
    /** Same bytes, new MediaStore metadata (e.g. a rename): records to save with the new snapshot. */
    val refreshed: List<UploadRecord>,
)

/**
 * Decides which library items may be moved to trash. The safety rules:
 * - only items with a verified upload record (the Mac confirmed size and SHA-256);
 * - never items the user marked to skip ([excluded]), whatever their record says;
 * - items whose size or date changed since the upload are re-hashed with [rehash]
 *   and kept unless their bytes still match the record. [rehash] returns null if the
 *   file can't be read, which also keeps it.
 *
 * Only items matching [inScope] (the current album and filters) are considered.
 * Blocking when items need re-hashing: call from a background thread.
 */
fun planTrash(
    media: List<MediaItem>,
    records: Map<Long, UploadRecord>,
    excluded: Set<Long>,
    inScope: (MediaItem) -> Boolean,
    rehash: (MediaItem) -> HashResult?,
): TrashPlan {
    val trash = ArrayList<MediaItem>()
    val skipped = ArrayList<MediaItem>()
    val refreshed = ArrayList<UploadRecord>()
    for (item in media) {
        if (item.id in excluded || !inScope(item)) continue
        val rec = records[item.id] ?: continue
        if (!changedSinceUpload(rec.snapshot, item.snapshot)) {
            trash += item
            continue
        }
        val now = rehash(item)
        if (now != null && now.bytes == rec.bytes && now.sha256 == rec.sha256) {
            trash += item
            refreshed += rec.copy(snapshot = item.snapshot)
        } else {
            skipped += item
        }
    }
    return TrashPlan(trash, skipped, refreshed)
}
