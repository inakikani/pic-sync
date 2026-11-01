package com.inakikani.picsync.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumsTest {
    private data class Item(val id: Long, val album: Long, val name: String, val uploaded: Boolean)

    private fun group(vararg items: Item) =
        groupAlbums(items.toList(), { it.album }, { it.name }, { it.uploaded })

    @Test fun groupsInOrderOfMostRecentItem() {
        val albums = group(
            Item(1, 10, "Screenshots", false), // newest
            Item(2, 20, "Camera", true),
            Item(3, 10, "Screenshots", true),
            Item(4, 20, "Camera", false),
            Item(5, 30, "WhatsApp Images", false),
        )
        assertEquals(
            listOf(
                Album(10, "Screenshots", count = 2, notUploaded = 1),
                Album(20, "Camera", count = 2, notUploaded = 1),
                Album(30, "WhatsApp Images", count = 1, notUploaded = 1),
            ),
            albums,
        )
    }

    @Test fun fullyUploadedAlbumHasZeroPending() {
        assertEquals(listOf(Album(1, "Camera", 2, 0)), group(Item(1, 1, "Camera", true), Item(2, 1, "Camera", true)))
    }

    @Test fun emptyInput() {
        assertEquals(emptyList<Album>(), group())
    }
}
