package com.cosmotune.watch

import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Season
import com.cosmotune.watch.model.WatchKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AbsoluteEpisodeTest {
    private val show = WatchKey.tmdbSeries(1)
    private fun season(n: Int, count: Int) = Season(show, n, "Season $n", count)

    @Test fun countsFromTheSeriesFirstEpisodeLeavingSpecialsOut() {
        val seasons = listOf(season(0, 4), season(1, 12), season(2, 13), season(3, 24))
        assertEquals(15, PlayTarget.absoluteOf(seasons, 2, 3))
        assertEquals(12 + 13 + 1, PlayTarget.absoluteOf(seasons, 3, 1))
        assertNull(PlayTarget.absoluteOf(seasons, 1, 5), "season 1 is numbered the same either way")
        assertNull(PlayTarget.absoluteOf(listOf(season(1, 12), season(3, 10)), 3, 1), "a missing season leaves it unknown")
    }
}

class ThreeDBadgeTest {
    private fun copy(name: String) = com.cosmotune.watch.model.Stream(com.cosmotune.watch.model.StreamSource("a", "A", "A"), "1080p",
        url = "https://x/y.mkv", protocol = com.cosmotune.watch.model.StreamProtocol.PROGRESSIVE, filename = name, sizeBytes = 1L shl 30)

    @Test fun aThreeDCopySaysSo() {
        kotlin.test.assertEquals("3D", copy("Avatar.2009.1080p.3D.HSBS.mkv").threeD)
        kotlin.test.assertEquals("3D", copy("Film (2012) H-OU 1080p.mkv").threeD)
        kotlin.test.assertNull(copy("3Days.to.Kill.2014.1080p.mkv").threeD)
        kotlin.test.assertNull(copy("Toy Story 3 2010.mkv").threeD)
        kotlin.test.assertTrue(copy("Avatar 3D SBS.mkv").watchMetadata().endsWith("3D"))
    }
}
