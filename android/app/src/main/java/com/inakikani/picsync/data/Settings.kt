package com.inakikani.picsync.data

import android.content.Context

/** Small persisted settings: the server URL and the long-pressed "skip" files. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(value) = prefs.edit().putString("server_url", value).apply()

    /**
     * MediaStore ids of files the user marked to skip: never uploaded, never trashed.
     * Persisted so the trash protection survives the app being killed or restarted.
     * Ids of deleted files are kept: they're harmless, and if MediaStore ever reused one the
     * new file would just start out skipped, which is the safe side.
     */
    var excluded: Set<Long>
        get() = prefs.getStringSet("excluded_ids", emptySet()).orEmpty()
            .mapNotNullTo(HashSet()) { it.toLongOrNull() }
        // commit(), not apply(): this protects files from trashing, so write it before going on.
        set(value) { prefs.edit().putStringSet("excluded_ids", value.mapTo(HashSet()) { it.toString() }).commit() }
}
