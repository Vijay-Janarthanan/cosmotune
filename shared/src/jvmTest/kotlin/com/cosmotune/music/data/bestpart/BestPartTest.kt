package com.cosmotune.music.data.bestpart

import com.cosmotune.music.data.lyrics.LyricLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the Best part button skips to: the bump in YouTube's "Most replayed" curve, else the chorus (adapted from the upstream test, GPL-3.0). */
class BestPartTest {
    /** A four-minute song in YouTube's hundred equal segments. */
    private val segment = 2_400L

    /** A real curve's shape: hot at the very start, sagging as people drift off, the chorus standing out of the sag. */
    private fun decaying(bumpFrom: Int? = null, bumpTo: Int? = null) = Heatmap(segment, List(100) { i ->
        val base = if (i == 0) 1f else 0.6f - 0.003f * i
        base + if (bumpFrom != null && bumpTo != null && i in bumpFrom..bumpTo) 0.25f else 0f
    })

    private fun markersEntity(type: String, markers: List<Float>) = buildJsonObject {
        putJsonObject("payload") {
            putJsonObject("macroMarkersListEntity") {
                putJsonObject("markersList") {
                    put("markerType", type)
                    putJsonArray("markers") {
                        markers.forEachIndexed { i, heat ->
                            addJsonObject {
                                put("startMillis", (i * segment).toString())
                                put("durationMillis", segment.toString())
                                put("intensityScoreNormalized", heat)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun nextAnswer(markers: List<Float>, type: String = "MARKER_TYPE_HEATMAP"): JsonObject = buildJsonObject {
        putJsonObject("frameworkUpdates") {
            putJsonObject("entityBatchUpdate") {
                putJsonArray("mutations") {
                    // A chapters entity first: the same shape, and it must be skipped.
                    add(markersEntity("MARKER_TYPE_CHAPTERS", emptyList()))
                    add(markersEntity(type, markers))
                }
            }
        }
    }

    @Test fun theHeatMarkersAreReadOutOfTheNextAnswer() {
        val heat = assertNotNull(BestPart.parseHeatmap(nextAnswer(decaying(40, 50).intensity)))
        assertEquals(segment, heat.segmentMs)
        assertEquals(100, heat.intensity.size)
    }

    @Test fun noHeatMarkersOrTooFewMeanNoCurve() {
        assertNull(BestPart.parseHeatmap(nextAnswer(decaying().intensity, type = "MARKER_TYPE_CHAPTERS")))
        assertNull(BestPart.parseHeatmap(buildJsonObject { put("contents", "nothing") }))
        assertNull(BestPart.parseHeatmap(nextAnswer(List(6) { 0.5f })))
    }

    @Test fun theChorusBumpWinsOverTheHotOpening() {
        val start = assertNotNull(BestPart.peakMs(decaying(40, 50), durationMs = 240_000))
        assertTrue(start in 38 * segment..41 * segment, "started at $start")
    }

    @Test fun aFlatCurveAShortSongOrALastHalfMinuteBumpHasNoBestPart() {
        assertNull(BestPart.peakMs(Heatmap(segment, List(100) { 0.4f }), durationMs = 240_000))
        assertNull(BestPart.peakMs(decaying(40, 50), durationMs = 50_000))
        assertNull(BestPart.peakMs(decaying(97, 99), durationMs = 240_000))
    }

    private val lyrics = listOf(
        LyricLine(10_000, "Woke up early in the morning"), LyricLine(14_000, "Coffee on the window sill"),
        LyricLine(18_000, "Every road is getting longer"), LyricLine(22_000, ""),
        LyricLine(30_000, "Hold on to me"), LyricLine(34_000, "Never let go"),
        LyricLine(50_000, "Streetlights humming out of tune"), LyricLine(54_000, "Somebody singing on the bus"),
        LyricLine(70_000, "Hold on to me"), LyricLine(74_000, "Never let go"),
        LyricLine(110_000, "Hold on, to me!"), LyricLine(114_000, "Never let go"),
    )

    @Test fun theChorusIsWhereTheMostRepeatedLinesFirstCome() {
        assertEquals(30_000L, BestPart.chorusMs(lyrics, durationMs = 180_000))
        assertNull(BestPart.chorusMs(lyrics.filterNot { "Hold" in it.text || "Never" in it.text }, durationMs = 180_000))
    }

    @Test fun theStartIsPulledBackToALineBeginning() {
        assertEquals(94_950L, BestPart.snap(96_000, listOf(95_200, 96_500)))
        assertEquals(95_000L, BestPart.snap(96_000, listOf(10_000, 200_000)))
    }

    @Test fun theCurveComesBeforeTheWordsAndNothingMeansNoButton() {
        val fromCurve = assertNotNull(BestPart.pick(decaying(40, 50), lyrics, 240_000))
        assertTrue(fromCurve in 37 * segment..41 * segment, "started at $fromCurve")
        assertEquals(29_750L, BestPart.pick(null, lyrics, 240_000))
        assertEquals(29_750L, BestPart.pick(Heatmap(segment, List(100) { 0.4f }), lyrics, 240_000))
        assertNull(BestPart.pick(null, emptyList(), 200_000))
    }

    @Test fun onlyYouTubeVideosHaveACurve() {
        assertTrue(BestPart.isYouTubeId("dQw4w9WgXcQ"))
        assertTrue(!BestPart.isYouTubeId("local:123") && !BestPart.isYouTubeId("content://x"))
    }
}
