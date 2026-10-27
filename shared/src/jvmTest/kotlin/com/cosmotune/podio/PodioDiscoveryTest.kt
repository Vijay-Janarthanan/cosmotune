package com.cosmotune.podio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import kotlin.math.abs
import kotlin.test.*

class PodioDiscoveryTest {
    private fun station(id: String, name: String = "Station $id", code: String = "IN", state: String = "", lat: Double? = null, lon: Double? = null) =
        RadioStation(id, name, "https://audio.example/$id", country = "India", countryCode = code, state = state, latitude = lat, longitude = lon)

    @Test fun aStationWithCoordinatesIsDrawnWhereTheDirectorySaysAndNotMarkedApproximate() {
        val point = requireNotNull(station("a", lat = 13.08, lon = 80.27).mapPoint())
        assertEquals(13.08, point.latitude); assertFalse(point.approximate)
    }

    @Test fun zeroZeroAndMissingCoordinatesAreNotAStationInTheGulfOfGuinea() {
        val point = requireNotNull(station("b", state = "Tamil Nadu", lat = 0.0, lon = 0.0).mapPoint())
        assertTrue(point.approximate)
        assertTrue(abs(point.latitude - 11.1) < 2 && abs(point.longitude - 78.65) < 2, "$point is not in Tamil Nadu")
    }

    @Test fun anIndianStationIsPlacedInItsCityFromItsRegionOrItsName() {
        val byRegion = requireNotNull(station("c", state = "Chennai").mapPoint())
        val byName = requireNotNull(station("d", name = "Suryan FM Coimbatore").mapPoint())
        assertTrue(abs(byRegion.latitude - 13.08) < .2 && abs(byRegion.longitude - 80.27) < .2, "$byRegion")
        assertTrue(abs(byName.latitude - 11.02) < .2 && abs(byName.longitude - 76.96) < .2, "$byName")
    }

    @Test fun anIndianStationWithNoPlaceIsDrawnWhereItsLanguageIsSpoken() {
        val point = requireNotNull(station("j").copy(language = "tamil").mapPoint())
        assertTrue(abs(point.latitude - 11.1) < 2 && abs(point.longitude - 78.65) < 2, "$point is not in Tamil Nadu")
    }

    @Test fun aCityNameInsideAnotherWordIsNotACityMatch() {
        // "Salem" must not be found inside "Jerusalem"; the station falls back to its state or the capital.
        val point = requireNotNull(station("e", name = "Jerusalem Radio", state = "Kerala").mapPoint())
        assertTrue(abs(point.latitude - 10.5) < 2 && abs(point.longitude - 76.3) < 2, "$point")
    }

    @Test fun stationsOfOneCityShareOnePlaceAndAnUnknownCountryHasNoPlace() {
        val places = PodioPlaces.group(listOf(station("f", lat = 13.0827, lon = 80.2707), station("g", lat = 13.0830, lon = 80.2710), station("h", lat = 11.0, lon = 76.9)))
        assertEquals(listOf(2, 1), places.map { it.stations.size }.sortedDescending())
        assertNull(station("i", code = "ZZ").mapPoint())
    }

    @Test fun theStreamTitleIsReadFromTheStreamsOwnMetadata() = runBlocking {
        val metadata = "StreamTitle='Artist - Song';".padEnd(32, '\u0000')
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("icy-metaint", "8").setBody(Buffer().write(ByteArray(8)).writeByte(2).writeUtf8(metadata)))
            assertEquals("Artist - Song", PodioCatalogue().nowPlaying(server.url("/stream").toString()))
            assertEquals("1", server.takeRequest().getHeader("Icy-MetaData"))
        }
    }

    @Test fun aStreamWithoutMetadataHasNoSongAndAnEmptyFirstBlockIsSkipped() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("audio only"))
            assertNull(PodioCatalogue().nowPlaying(server.url("/plain").toString()))
            val second = "StreamTitle='Later song';".padEnd(32, '\u0000')
            server.enqueue(MockResponse().setHeader("icy-metaint", "4").setBody(Buffer().write(ByteArray(4)).writeByte(0).write(ByteArray(4)).writeByte(2).writeUtf8(second)))
            assertEquals("Later song", PodioCatalogue().nowPlaying(server.url("/gap").toString()))
        }
    }

    @Test fun theChartKeepsItsOrderAndTakesTheFeedAddressFromTheLookup() = runBlocking {
        val paths = mutableListOf<String>()
        MockWebServer().use { apple ->
            apple.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path.orEmpty()
                    return when {
                        request.path.orEmpty().startsWith("/in/rss/toppodcasts/limit=30/genre=1489/json") ->
                            MockResponse().setBody("""{"feed":{"entry":[{"id":{"attributes":{"im:id":"222"}}},{"id":{"attributes":{"im:id":"111"}}},{"id":{"attributes":{"im:id":"333"}}}]}}""")
                        request.path.orEmpty().startsWith("/lookup") -> MockResponse().setBody("""{"results":[
                            {"collectionId":111,"collectionName":"Second","artistName":"B","feedUrl":"https://feeds.example/b"},
                            {"collectionId":222,"collectionName":"First","artistName":"A","feedUrl":"https://feeds.example/a"}]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val shows = PodioCatalogue(appleBase = apple.url("/").toString().trimEnd('/')).topPodcasts("IN", "1489")
            assertEquals(listOf("First", "Second"), shows.map { it.title }, "chart order, and a show with no feed is left out: $paths")
            assertEquals("https://feeds.example/a", shows.first().feed)
        }
    }
}
