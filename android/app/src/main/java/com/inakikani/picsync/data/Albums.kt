package com.inakikani.picsync.data

/** An on-device album (MediaStore bucket, i.e. a folder such as Camera or Screenshots). */
data class Album(
    val id: Long,
    val name: String,
    val count: Int,       // photos + videos in the album
    val notUploaded: Int, // of those, not yet verified on the Mac
)

/**
 * Groups items into albums. [items] are expected newest-first, so albums come out
 * ordered by their most recent photo, as in the system Gallery. Generic so it can be
 * unit-tested without Android types.
 */
fun <T> groupAlbums(
    items: List<T>,
    albumId: (T) -> Long,
    albumName: (T) -> String,
    isUploaded: (T) -> Boolean,
): List<Album> {
    val order = LinkedHashMap<Long, Album>()
    for (item in items) {
        val id = albumId(item)
        val a = order[id] ?: Album(id, albumName(item), 0, 0)
        order[id] = a.copy(count = a.count + 1, notUploaded = a.notUploaded + if (isUploaded(item)) 0 else 1)
    }
    return order.values.toList()
}
