package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class LinkedPlaylistsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun s(id: String, title: String = id) = Song(id, title, "Artist", null)
    private fun remote(vararg ids: String, unavailable: Set<String> = emptySet()) =
        RemotePlaylist("Road trip", ids.map { RemoteTrack(s(it), it !in unavailable) })
    private fun LinkedPlaylist.ids() = entries.map { it.track.id + if (it.ghost) "~" else "" }

    @Test fun sourceAdditionsRemovalsAndReturnsMergeWithoutDeletingAnything() {
        val linked = newLinkedPlaylist("youtube", "PL123", remote("a", "b", "c", "d"), now = 1)
        assertEquals(listOf("a", "b", "c", "d"), linked.ids())
        val first = previewLinkedSync(linked, remote("a", "x", "c", "d", "y"))
        assertEquals(listOf("a", "x", "b~", "c", "d", "y"), first.after.ids())
        assertEquals(listOf("x", "y"), first.added.map { it.id })
        assertEquals(listOf("b"), first.ghosted.map { it.id })
        assertEquals(GhostReason.REMOVED_FROM_SOURCE, first.after.entries[2].ghostReason)
        assertEquals(listOf("a", "x", "c", "d", "y"), first.after.playable().map { it.videoId })
        // The removed track comes back: it is restored in place, not appended again.
        val second = previewLinkedSync(first.after, remote("a", "x", "b", "c", "d", "y"))
        assertEquals(listOf("a", "x", "b", "c", "d", "y"), second.after.ids())
        assertEquals(listOf("b"), second.restored.map { it.id })
        assertTrue(second.added.isEmpty())
        val unchanged = previewLinkedSync(second.after, remote("a", "x", "b", "c", "d", "y"))
        assertFalse(unchanged.changed)
    }

    @Test fun localRemovalsAdditionsAndOrderSurviveEverySync() {
        val linked = newLinkedPlaylist("youtube", "PL123", remote("a", "b", "c"), now = 1)
            .removeEntry(1).addLocal(s("mine")).move(0, 1) // c, a, mine
        assertEquals(listOf("c", "a", "mine"), linked.ids())
        val preview = previewLinkedSync(linked, remote("a", "b", "c", "n"))
        assertEquals(listOf("c", "n", "a", "mine"), preview.after.ids()) // n follows c, its source predecessor
        assertEquals(listOf("b"), preview.keptLocalRemovals.map { it.id })
        assertTrue(preview.after.entries.single { it.track.id == "mine" }.local)
        // The source removing everything still never removes the local addition, and ghosts keep identity.
        val emptied = previewLinkedSync(preview.after, remote())
        assertEquals(listOf("c~", "n~", "a~", "mine"), emptied.after.ids())
    }

    @Test fun unavailableRowsBecomeGhostsAndDuplicatesAreMatchedByOccurrence() {
        val linked = newLinkedPlaylist("youtube", "PL1", remote("a", "a", "b", unavailable = setOf("b")), now = 1)
        assertEquals(listOf("a", "a", "b~"), linked.ids())
        assertEquals(GhostReason.UNAVAILABLE, linked.entries[2].ghostReason)
        val preview = previewLinkedSync(linked, remote("a", "b"))
        assertEquals(listOf("a", "a~", "b"), preview.after.ids())
        assertEquals(listOf("b"), preview.restored.map { it.id })
    }

    @Test fun metadataRefreshKeepsLocalFileDetailsAndPartialResponsesAreFlagged() {
        val many = (1..40).map { "t$it" }.toTypedArray()
        val linked = newLinkedPlaylist("youtube", "PL1", remote(*many), now = 1).let { p ->
            p.copy(entries = p.entries.mapIndexed { i, e -> if (i == 0) e.copy(track = e.track.copy(path = "/music/t1.flac", entryId = "q1")) else e })
        }
        val renamed = RemotePlaylist("Road trip", listOf(RemoteTrack(s("t1", "New title"))) + many.drop(1).take(5).map { RemoteTrack(s(it)) })
        val preview = previewLinkedSync(linked, renamed)
        assertTrue(preview.suspiciousShrink)
        val first = preview.after.entries.first().track
        assertEquals("New title", first.title); assertEquals("/music/t1.flac", first.path); assertEquals("q1", first.entryId)
        assertEquals(34, preview.ghosted.size)
    }

    @Test fun storePersistsAppliesOnlyFreshPreviewsAndImportsWithoutDuplicates() {
        val file = File(temporary.root, "listening.json")
        val store = ListeningStore(file)
        val linked = newLinkedPlaylist("youtube", "PL9", remote("a", "b"), now = 1)
        store.saveLinked(linked)
        assertFailsWith<IllegalArgumentException> { store.saveLinked(linked.copy(id = "other")) }
        val preview = previewLinkedSync(store.state.value.linkedPlaylists.single(), remote("a"))
        store.saveLinked(store.state.value.linkedPlaylists.single().addLocal(s("edit")))
        assertFailsWith<IllegalArgumentException> { store.update { applyLinkedSync(it, preview) } }
        val fresh = previewLinkedSync(store.state.value.linkedPlaylists.single(), remote("a"))
        store.update { applyLinkedSync(it, fresh, now = 99) }
        val reopened = ListeningStore(file).state.value.linkedPlaylists.single()
        assertEquals(listOf("a", "b~", "edit"), reopened.ids())
        assertEquals(99L, reopened.syncedAt)
        val exported = store.export()
        val other = ListeningStore(File(temporary.root, "other.json"))
        other.import(exported); other.import(exported)
        assertEquals(1, other.state.value.linkedPlaylists.size)
        store.unlink(reopened.id)
        assertTrue(store.state.value.linkedPlaylists.isEmpty())
    }

    @Test fun playlistIdsAreExtractedWithoutKeepingLinksOrParameters() {
        assertEquals("PLabc_-1", youtubePlaylistId("https://music.youtube.com/playlist?list=PLabc_-1&si=tracking"))
        assertEquals("PLabc", youtubePlaylistId("VLPLabc"))
        assertEquals("OLAK5uy_x", youtubePlaylistId("  OLAK5uy_x "))
        assertNull(youtubePlaylistId("https://example.com/../x y"))
        assertFailsWith<IllegalArgumentException> { newLinkedPlaylist("youtube", "bad id!", remote("a")) }
    }
}
