package com.cosmotune.desktop

import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.desktop.DesktopPlaylist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the sidebar's playlist list is made of, with no window: the account's playlists first, then the ones kept on this computer;
 * the selection and the click as the old sidebar did them; a key for every row that stays with its playlist. (Local playlists here
 * have no songs, so no string is looked up and the settings of the person running the tests are not touched.)
 */
class CosmoSidebarPlaylistsTest {
    private fun shelf(title: String, browseId: String?, subtitle: String = "Playlist", thumbnail: String? = null) =
        ShelfItem(title = title, subtitle = subtitle, thumbnailUrl = thumbnail, videoId = null, browseId = browseId)

    @Test fun theAccountsPlaylistsComeFirstThenTheLocalOnes() {
        val items = cosmoSidebarPlaylists(
            accountPlaylists = listOf(shelf("Liked Music", "VLLM"), shelf("Night mix", "VLNM")),
            localPlaylists = listOf(DesktopPlaylist(id = "a1", title = "Kept here")),
            openedCollectionId = null, onOpenAccountPlaylist = {}, onOpenLocalPlaylist = {},
        )
        assertEquals(listOf("Liked Music", "Night mix", "Kept here"), items.map { it.title })
    }

    @Test fun aPlaylistIsSelectedWhileItsCollectionIsOpenByTheOldIdsOfEachKind() {
        val account = listOf(shelf("Liked Music", "VLLM"), shelf("Night mix", "VLNM"))
        val local = listOf(DesktopPlaylist(id = "a1", title = "Kept here"))
        fun selected(open: String?) = cosmoSidebarPlaylists(account, local, open, {}, {}).map { it.selected }
        assertEquals(listOf(false, false, false), selected(null))
        assertEquals(listOf(false, true, false), selected("VLNM"))
        assertEquals(listOf(false, false, true), selected("a1"))
    }

    @Test fun aClickOpensThePlaylistItCameFromAndASongLessLocalOneHasNoLine() {
        val opened = ArrayList<String>()
        val account = shelf("Night mix", "VLNM", subtitle = "Made for you", thumbnail = "https://example.invalid/c.jpg")
        val local = DesktopPlaylist(id = "a1", title = "Kept here")
        val items = cosmoSidebarPlaylists(listOf(account), listOf(local), null, { opened += "account:${it.browseId}" }, { opened += "local:${it.id}" })
        items.forEach { it.onClick() }
        assertEquals(listOf("account:VLNM", "local:a1"), opened)
        assertEquals("Made for you", items[0].subtitle)
        assertEquals("https://example.invalid/c.jpg", items[0].imageUrl)
        assertNull(items[1].subtitle, "no songs, no line under the name")
        assertNull(items[1].imageUrl, "and no picture: the sidebar draws its tile")
    }

    @Test fun aBlankSubtitleIsNoLine() {
        val items = cosmoSidebarPlaylists(listOf(shelf("Mix", "VL1", subtitle = "")), emptyList(), null, {}, {})
        assertNull(items.single().subtitle)
    }

    @Test fun everyRowHasAKeyAndTwoPlaylistsOfOneNameDoNotShareOne() {
        assertEquals(listOf("Mix", "Mix#2", "Other", "Mix#3"), stableKeys(listOf("Mix", "Mix", "Other", "Mix")))
        val keys = stableKeys(List(50) { "Playlist ${it % 7}" })
        assertEquals(keys.size, keys.toSet().size, "no two rows of a lazy list may share a key")
        assertTrue(stableKeys(emptyList()).isEmpty())
    }
}
