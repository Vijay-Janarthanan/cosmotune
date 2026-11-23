package com.cosmotune.watch

import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.torrent.NaturalOrder
import com.cosmotune.watch.torrent.TorrentEngine
import com.cosmotune.watch.torrent.TorrentFile
import com.cosmotune.watch.torrent.TorrentItems
import com.cosmotune.watch.torrent.TorrentLinks
import com.cosmotune.watch.torrent.TorrentListing
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentLinksTest {
    private val hash = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"

    @Test fun magnetLinksAndBareHashesAreRead() {
        val link = TorrentLinks.parse("magnet:?xt=urn:btih:${hash.uppercase()}&dn=Big%20Buck%20Bunny&tr=udp%3A%2F%2Ftracker.example%3A1337&tr=udp%3A%2F%2Ftracker.example%3A1337")!!
        assertEquals(hash, link.hash)
        assertEquals("Big Buck Bunny", link.name)
        assertEquals(listOf("udp://tracker.example:1337"), link.trackers)
        assertEquals(hash, TorrentLinks.parse("  $hash ")!!.hash)
    }

    @Test fun base32HashesBecomeHex() {
        assertEquals(hash, TorrentLinks.parse("magnet:?xt=urn:btih:YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK")!!.hash)
    }

    @Test fun wordsAndOtherLinksAreNotTorrents() {
        assertNull(TorrentLinks.parse("the matrix"))
        assertNull(TorrentLinks.parse("https://example.com/file.torrent"))
        assertNull(TorrentLinks.parse("magnet:?xt=urn:sha1:abc"))
        assertNull(TorrentLinks.parse("magnet:?dn=nothing"))
    }

    @Test fun torrentFilesAreRecognisedBeforeTheEngineReadsThem() {
        assertTrue(TorrentLinks.looksLikeTorrentFile("d8:announce35:udp://tracker.example:1337/announce4:infod4:name4:testee".toByteArray()))
        assertFalse(TorrentLinks.looksLikeTorrentFile("<html>not a torrent at all, just a page</html>".toByteArray()))
    }

    @Test fun episodesSortTheWayPeopleCount() {
        val names = listOf("Show/E10.mkv", "Show/E2.mkv", "Show/E1.mkv", "Extras/Making of.mkv")
        assertEquals(listOf("Extras/Making of.mkv", "Show/E1.mkv", "Show/E2.mkv", "Show/E10.mkv"), names.sortedWith(NaturalOrder))
    }

    @Test fun aListingLineBecomesFilesAndVideosInOrder() {
        val event = TorrentEngine.parse(Json.parseToJsonElement("""{"name":"Season 1","hash":"${hash.uppercase()}","files":[
            {"index":0,"path":"Season 1/E10.mkv","length":10,"video":true},{"index":1,"path":"Season 1/E9.mkv","length":10,"video":true},
            {"index":2,"path":"Season 1/notes.txt","length":3,"video":false}]}"""))
        val listing = event.listing!!
        assertEquals(hash, listing.hash)
        assertEquals(listOf(1, 0), listing.videos.map { it.index })
        val progress = TorrentEngine.parse(Json.parseToJsonElement("""{"progress":{"done":5,"total":10,"rate":2,"peers":3,"seeding":false}}"""))
        assertEquals(5, progress.done)
        assertEquals(3, progress.peers)
        assertNull(progress.listing)
        assertEquals("No peers", TorrentEngine.parse(Json.parseToJsonElement("""{"error":"No peers"}""")).error)
    }

    @Test fun aFilePlaysAsATorrentItemWithItsIndex() {
        val listing = TorrentListing(hash, "Season 1", listOf(TorrentFile(4, "Season 1/Harbour.Town.E02.mkv", 100, true)))
        val target = TorrentItems.target(listing, listing.files[0])
        val stream = TorrentItems.stream(listing, listing.files[0])
        assertEquals("a:torrent:$hash:4", target.key.value)
        assertEquals("Harbour Town E02", target.name)
        assertEquals(hash to 4, TorrentItems.parts(target))
        assertTrue(stream.isTorrent)
        assertEquals(4, stream.torrentFileIndex)
        assertNull(TorrentItems.parts(PlayTarget.Item(target.key, "other", "x", "y")))
    }
}
