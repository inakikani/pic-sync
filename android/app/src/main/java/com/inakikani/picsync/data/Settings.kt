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
        // apply(), not commit(): this runs on the main thread at every long-press, and commit()
        // would write the whole set to disk there. apply() updates the in-memory copy at once
        // and writes in the background; Android finishes pending writes before the app's
        // activities stop, so the protection is on disk before the app can be killed. Trashing
        // decides from the view model's state, not from here, so it never sees an older set.
        set(value) { prefs.edit().putStringSet("excluded_ids", value.mapTo(HashSet()) { it.toString() }).apply() }
}
