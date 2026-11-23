package com.cosmotune.listening

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ReleaseChecksTest {

    private fun release(id: String, artist: String = "A1", name: String = "Artist One") =
        ReleaseNotice("MPRE$id", "UC$artist", name, "Title $id")

    private fun store(): ListeningStore {
        val file = File(createTempDirectory("releases").toFile(), "listening.json")
        return ListeningStore(file).also { s ->
            s.update { it.copy(artists = listOf(WatchedArtist("UCA1", "Artist One"), WatchedArtist("UCB2", "Artist Two"))) }
        }
    }

    @Test fun theFirstCheckIsABaselineAndLaterOnesFindWhatIsNew() = runBlocking {
        val store = store()
        val catalogue = mutableMapOf("UCA1" to listOf(release("1")), "UCB2" to listOf(release("9", "B2", "Artist Two")))
        val first = ReleaseChecks.run(store, { catalogue.getValue(it.id) }, force = true)
        assertEquals(0, first.failures)
        assertEquals(emptyList(), first.found)
        catalogue["UCA1"] = listOf(release("2"), release("1"))
        val second = ReleaseChecks.run(store, { catalogue.getValue(it.id) }, force = true)
        assertEquals(listOf("MPRE2"), second.found.map { it.id })
        val third = ReleaseChecks.run(store, { catalogue.getValue(it.id) }, force = true)
        assertEquals(emptyList(), third.found)
    }

    @Test fun aFailedArtistIsCountedAndTheOthersStillRun() = runBlocking {
        val store = store()
        val outcome = ReleaseChecks.run(store, { if (it.id == "UCA1") error("offline") else emptyList() }, force = true)
        assertEquals(1, outcome.failures)
    }

    @Test fun theNotificationNamesOneReleaseOrCountsMany() {
        assertEquals("New from Artist One" to listOf("Title 1"), ReleaseChecks.notificationText(listOf(release("1"))))
        val many = ReleaseChecks.notificationText(listOf(release("1"), release("2"), release("3", "B2", "Artist Two")))
        assertEquals("3 new releases", many.first)
        assertEquals(listOf("Artist One · Title 1", "Artist One · Title 2", "Artist Two · Title 3"), many.second)
    }
}
