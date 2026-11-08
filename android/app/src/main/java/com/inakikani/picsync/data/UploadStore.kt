package com.inakikani.picsync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A verified upload. Only items with a record can ever be offered for trashing,
 * and a record is only written after the server's size + SHA-256 matched ours.
 */
data class UploadRecord(
    val mediaId: Long,
    val snapshot: MediaSnapshot, // MediaStore size + date_modified when uploaded
    val bytes: Long,             // bytes actually sent and confirmed by the server
    val sha256: String,
    val serverName: String,      // file name on the Mac
    val serverUrl: String,
    val uploadedAt: Long,        // millis since epoch
)

/**
 * SQLite table of verified uploads. Blocking: call from a background thread. Use the one
 * shared instance, `PicSyncApp.uploadStore`.
 */
class UploadStore(context: Context) :
    SQLiteOpenHelper(context, "uploads.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE uploads (
                media_id INTEGER PRIMARY KEY,
                ms_size INTEGER NOT NULL,
                date_modified INTEGER NOT NULL,
                bytes INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                server_name TEXT NOT NULL,
                server_url TEXT NOT NULL,
                uploaded_at INTEGER NOT NULL
            )""",
        )
    }

    // Version 1 is the only schema so far. A schema change must bump the version above and
    // migrate here: these records are what allows trashing, so they must never be dropped.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun put(r: UploadRecord) {
        val values = ContentValues().apply {
            put("media_id", r.mediaId)
            put("ms_size", r.snapshot.size)
            put("date_modified", r.snapshot.dateModified)
            put("bytes", r.bytes)
            put("sha256", r.sha256)
            put("server_name", r.serverName)
            put("server_url", r.serverUrl)
            put("uploaded_at", r.uploadedAt)
        }
        writableDatabase.insertWithOnConflict("uploads", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun all(): Map<Long, UploadRecord> {
        val out = HashMap<Long, UploadRecord>()
        readableDatabase.rawQuery(
            "SELECT media_id, ms_size, date_modified, bytes, sha256, server_name, server_url, uploaded_at FROM uploads",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val r = UploadRecord(
                    mediaId = c.getLong(0),
                    snapshot = MediaSnapshot(c.getLong(1), c.getLong(2)),
                    bytes = c.getLong(3),
                    sha256 = c.getString(4),
                    serverName = c.getString(5),
                    serverUrl = c.getString(6),
                    uploadedAt = c.getLong(7),
                )
                out[r.mediaId] = r
            }
        }
        return out
    }
}
