package com.inakikani.picsync.data

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.io.IOException
import java.io.InputStream

/** A photo or video on the phone, as seen by MediaStore. */
data class MediaItem(
    val id: Long,
    val name: String,
    val mimeType: String,
    override val size: Long,
    val dateModified: Long, // seconds since epoch
    override val takenAt: Long, // millis: DATE_TAKEN, or DATE_ADDED if the file has none
    override val isVideo: Boolean,
    val albumId: Long,      // MediaStore bucket (on-device folder)
    val albumName: String,
) : Filterable {
    val snapshot get() = MediaSnapshot(size, dateModified)

    /**
     * Content URI, built on demand rather than stored: a Uri costs a few hundred bytes and
     * is only needed to upload, thumbnail or trash an item. Trash requests need
     * Images/Video collection URIs, not Files URIs.
     */
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id,
        )
}

/**
 * An opened media file. [withLocation] is false when Android stripped the GPS
 * location (see [MediaRepository.open]); [whyNoLocation] says why.
 */
class Opened(val stream: InputStream, val withLocation: Boolean, val whyNoLocation: String?)

/** Reads photos/videos from MediaStore. Blocking: call from a background thread. */
class MediaRepository(
    private val resolver: ContentResolver,
    /** Whether ACCESS_MEDIA_LOCATION is granted (checked on every open; it can change). */
    private val canReadLocation: () -> Boolean,
) {

    /** All images and videos visible to the app, newest taken first. Trashed and pending items are excluded. */
    fun query(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        val items = mutableListOf<MediaItem>()
        // Album names and MIME types repeat across thousands of rows; keep one copy of each.
        val shared = HashMap<String, String>()
        fun share(s: String) = shared.getOrPut(s) { s }
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, args, sort,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                items += MediaItem(
                    id = id,
                    name = c.getString(nameCol) ?: "item-$id",
                    mimeType = share(c.getString(mimeCol) ?: ""),
                    size = c.getLong(sizeCol),
                    dateModified = c.getLong(modCol),
                    takenAt = c.getLong(takenCol).takeIf { !c.isNull(takenCol) && it > 0 }
                        ?: (c.getLong(addedCol) * 1000), // DATE_ADDED is in seconds
                    isVideo = isVideo,
                    albumId = if (c.isNull(bucketCol)) 0L else c.getLong(bucketCol),
                    albumName = share(c.getString(bucketNameCol) ?: "Other"),
                )
            }
        }
        // Newest first by the date taken (what the gallery's month headers use), not by
        // when the file arrived on the phone: received/downloaded photos sort into their month.
        items.sortByDescending { it.takenAt }
        return items
    }

    private var thumbFailures = 0

    fun thumbnail(item: MediaItem, px: Int = 320): Bitmap? =
        try {
            resolver.loadThumbnail(item.uri, Size(px, px), null)
        } catch (e: Exception) {
            // Common for corrupt or unusual files; log the first few, then every 100th.
            val n = ++thumbFailures
            if (n <= 5 || n % 100 == 0) AppLog.w("thumbnail failed for ${item.name} (id ${item.id}, failure #$n)", e)
            null
        }

    /**
     * Opens the item's bytes for uploading or hashing. Without [MediaStore.setRequireOriginal]
     * Android strips the GPS location from photos and videos it hands to apps, so the
     * Mac would get a copy without location. That needs ACCESS_MEDIA_LOCATION; if it
     * isn't granted (or the original can't be opened) the redacted bytes are used.
     * Uploading and re-hashing both go through here, so they always see the same bytes.
     */
    fun open(item: MediaItem): Opened {
        var why = "media location permission not granted"
        if (canReadLocation()) {
            try {
                resolver.openInputStream(MediaStore.setRequireOriginal(item.uri))?.let { return Opened(it, true, null) }
                why = "original not available"
            } catch (e: SecurityException) { // e.g. limited (selected photos) access
                why = "original refused: ${e.message}"
            } catch (e: UnsupportedOperationException) {
                why = "original unsupported: ${e.message}"
            }
        }
        val stream = resolver.openInputStream(item.uri) ?: throw IOException("Can't open ${item.name}")
        return Opened(stream, false, why)
    }

    /**
     * Decodes a photo for the full-screen viewer, scaled to fit [maxW]×[maxH] times
     * 1.5 (some headroom for zooming) but never larger than the original. EXIF
     * rotation is applied by ImageDecoder. Null if it can't be decoded.
     */
    fun fullImage(item: MediaItem, maxW: Int, maxH: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1.0, 1.5 * minOf(maxW.toDouble() / w, maxH.toDouble() / h))
            if (scale < 1.0) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
        }
    } catch (e: Exception) {
        AppLog.w("viewer: can't decode ${item.name} (id ${item.id})", e)
        null
    }

    /** Re-reads the item's bytes and hashes them. */
    fun hash(item: MediaItem, checkCancelled: () -> Unit = {}): HashResult =
        open(item).stream.use { hashStream(it, checkCancelled) }
}
