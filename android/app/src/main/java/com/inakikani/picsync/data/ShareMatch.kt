package com.inakikani.picsync.data

/**
 * Finds the library item that holds exactly the bytes of a shared file, so a
 * photo shared from Google Photos (a temporary link, not the phone's own photo)
 * can be recorded as uploaded and later moved to trash from the gallery.
 *
 * [candidates] are library items of the same size (usually zero or one); each is
 * hashed with [hash] until one matches [target]. Returns the match and a short
 * reason for the log.
 */
fun <T> matchShared(candidates: List<T>, target: HashResult, hash: (T) -> HashResult?): Pair<T?, String> {
    if (candidates.isEmpty()) return null to "no photo of that size on the phone (cloud-only, or a different version)"
    for (c in candidates.take(MAX_MATCH_CANDIDATES)) {
        val h = hash(c) ?: continue
        if (h.bytes == target.bytes && h.sha256 == target.sha256) return c to "matched"
    }
    val checked = minOf(candidates.size, MAX_MATCH_CANDIDATES)
    return null to "$checked photo(s) of the same size, but different content (e.g. location removed when shared)"
}

/** Upper bound on files hashed per shared item (each hash reads the whole file). */
const val MAX_MATCH_CANDIDATES = 10
