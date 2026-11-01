package com.inakikani.picsync.data

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

/** A photo or video on the phone, as seen by MediaStore. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long, // seconds since epoch
    val isVideo: Boolean,
) {
    val snapshot get() = MediaSnapshot(size, dateModified)
}

/** Reads photos/videos from MediaStore. Blocking: call from a background thread. */
class MediaRepository(private val resolver: ContentResolver) {

    /** All images and videos visible to the app, newest first. Trashed and pending items are excluded. */
    fun query(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        val items = mutableListOf<MediaItem>()
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, args, sort,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                // Trash requests need Images/Video collection URIs, not Files URIs.
                val collection = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                items += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "item-$id",
                    mimeType = c.getString(mimeCol) ?: "",
                    size = c.getLong(sizeCol),
                    dateModified = c.getLong(modCol),
                    isVideo = isVideo,
                )
            }
        }
        return items
    }

    fun thumbnail(item: MediaItem, px: Int = 320): Bitmap? =
        runCatching { resolver.loadThumbnail(item.uri, Size(px, px), null) }.getOrNull()

    /** Re-reads the item's bytes and hashes them. */
    fun hash(item: MediaItem, checkCancelled: () -> Unit = {}): HashResult =
        resolver.openInputStream(item.uri)?.use { hashStream(it, checkCancelled) }
            ?: throw java.io.IOException("Can't open ${item.name}")
}
