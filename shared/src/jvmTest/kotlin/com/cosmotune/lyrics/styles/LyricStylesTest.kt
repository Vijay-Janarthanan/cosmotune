package com.cosmotune.lyrics.styles

import com.cosmotune.music.data.lyrics.LyricAlignment
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricWord
import com.cosmotune.settings.LyricStyleMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricStylesTest {
    private val solo = listOf(LyricLine(1_000, "We drew a map"), LyricLine(4_000, "across the night"))
    private val duet = listOf(LyricLine(1_000, "We drew a map"), LyricLine(4_000, "every light", alignment = LyricAlignment.End))
    private val backed = listOf(LyricLine(1_000, "Hold the line", background = LyricLine(2_000, "(hold it)")))
    private val plain = listOf(LyricLine(0, "No timing"), LyricLine(0, "at all"))

    @Test fun orbitReadIsTheDefaultForASingleVoice() {
        assertEquals(LyricStyle.ORBIT_READ, LyricStyles.resolve(null, null, solo, LyricSurface.PHONE))
    }

    @Test fun aDuetOrABackingVoiceTurnsOnCallAndAnswerByItself() {
        assertEquals(LyricStyle.CALL_AND_ANSWER, LyricStyles.resolve(null, null, duet, LyricSurface.PHONE))
        assertEquals(LyricStyle.CALL_AND_ANSWER, LyricStyles.resolve(null, null, backed, LyricSurface.DESKTOP))
        assertTrue(LyricStyles.hasVoices(duet) && LyricStyles.hasVoices(backed) && !LyricStyles.hasVoices(solo))
    }

    @Test fun aStyleTheListenerChoseWinsOverTheDuetDefault() {
        assertEquals(LyricStyle.STRETCH, LyricStyles.resolve(LyricStyle.STRETCH, null, duet, LyricSurface.PHONE))
        // Choosing Orbit Read on purpose is a choice too.
        assertEquals(LyricStyle.ORBIT_READ, LyricStyles.resolve(LyricStyle.ORBIT_READ, null, duet, LyricSurface.PHONE))
    }

    @Test fun theSongsOwnStyleWinsOverTheGlobalOne() {
        assertEquals(LyricStyle.POSTER, LyricStyles.resolve(LyricStyle.STRETCH, LyricStyle.POSTER, solo, LyricSurface.PHONE))
    }

    @Test fun lyricsWithoutTimingAreAlwaysTheList() {
        assertEquals(LyricStyle.ORBIT_READ, LyricStyles.resolve(LyricStyle.ORBITAL, LyricStyle.PULSE, plain, LyricSurface.PHONE))
    }

    @Test fun aSurfaceOnlyOffersItsOwnStyles() {
        assertEquals(listOf(LyricStyle.POSTER, LyricStyle.STRETCH, LyricStyle.ORBITAL), LyricStyles.on(LyricSurface.SHARE_VIDEO))
        assertEquals(LyricStyle.ORBITAL, LyricStyles.resolve(LyricStyle.PULSE, null, solo, LyricSurface.WATCH))
        assertEquals(LyricStyle.POSTER, LyricStyles.resolve(LyricStyle.PULSE, null, solo, LyricSurface.SHARE_VIDEO))
        assertTrue(LyricStyles.fullScreenOnTv(LyricStyle.ORBITAL) && LyricStyles.fullScreenOnTv(LyricStyle.STRETCH))
        assertFalse(LyricStyles.fullScreenOnTv(LyricStyle.ORBIT_READ))
    }

    @Test fun keysRoundTripAndUnknownKeysAreNoChoice() {
        for (style in LyricStyle.entries) assertEquals(style, LyricStyle.ofKey(style.key))
        assertNull(LyricStyle.ofKey("aether"))
        assertNull(LyricStyle.ofKey(""))
        assertNull(LyricStyle.ofKey(null))
    }

    @Test fun theSongMemoryRoundTripsKeepsTheNewestAndForgets() {
        var map = LyricStyleMemory.with(emptyMap(), "a", "stretch")
        map = LyricStyleMemory.with(map, "b", "poster")
        map = LyricStyleMemory.with(map, "a", "orbital")
        assertEquals(listOf("b", "a"), map.keys.toList(), "a choice made again is the newest")
        assertEquals(map, LyricStyleMemory.decode(LyricStyleMemory.encode(map)))
        assertEquals(mapOf("b" to "poster"), LyricStyleMemory.with(map, "a", null))
        assertEquals(map, LyricStyleMemory.with(map, "bad\tid", "pulse"), "an id that would break the format is not kept")
        var many: Map<String, String> = emptyMap()
        repeat(LyricStyleMemory.LIMIT + 20) { many = LyricStyleMemory.with(many, "song$it", "pulse") }
        assertEquals(LyricStyleMemory.LIMIT, many.size)
        assertFalse("song0" in many, "the oldest goes first")
        assertTrue(LyricStyleMemory.encode(many).length <= LyricStyleMemory.MAX_CHARS)
        assertEquals(emptyMap(), LyricStyleMemory.decode("garbage\n\tx\ny\t"))
    }

    @Test fun theCursorSkipsInstrumentalsAndWaitsOnTheNextLine() {
        val lines = listOf(LyricLine(1_000, "one"), LyricLine(3_000, ""), LyricLine(5_000, "two"))
        assertEquals(-1, LyricCursor.current(lines, 500))
        assertEquals(0, LyricCursor.focus(lines, 500), "before the first line it waits on the first")
        assertEquals(0, LyricCursor.sung(lines, 1_500))
        assertEquals(-1, LyricCursor.sung(lines, 3_500), "nothing is sung in a break")
        assertEquals(2, LyricCursor.focus(lines, 3_500), "a break waits on the line after it")
        assertEquals(2, LyricCursor.nextSung(lines, 0))
        assertEquals(0, LyricCursor.previousSung(lines, 2))
        assertEquals(-1, LyricCursor.nextSung(lines, 2))
    }

    @Test fun aLineStampedOnlyAtItsStartSpreadsItsWordsOverItsSlotByLetters() {
        val lines = listOf(LyricLine(1_000, "a looooong b"), LyricLine(5_000, "next"))
        val stage = StageLine.of(lines, 0)
        assertEquals(3, stage.count)
        assertEquals(5_000, stage.endMs)
        assertEquals(1_000, stage.startMs[0])
        assertTrue(stage.wordEndMs[1] - stage.startMs[1] > 5 * (stage.wordEndMs[0] - stage.startMs[0]), "the long word takes longer")
        assertEquals(5_000, stage.wordEndMs[2])
        assertEquals(0, stage.startedWords(900))
        assertEquals(1, stage.startedWords(1_000))
        assertEquals(3, stage.startedWords(5_000))
        assertEquals(0f, stage.revealedChars(900))
        assertEquals(stage.text.length.toFloat(), stage.revealedChars(6_000))
    }

    @Test fun aWordTimedLineKeepsItsOwnWordsAndTheMoonFollowsTimeThroughAHeldNote() {
        val words = listOf(LyricWord(1_000, 1_200, "hold"), LyricWord(1_300, 3_300, "long"))
        val line = LyricLine(1_000, "hold long", words)
        val stage = StageLine.of(listOf(line), 0)
        assertEquals(2, stage.count)
        assertEquals(5, stage.charStart[1])
        assertEquals(9, stage.charEnd[1])
        assertEquals(0.5f, stage.progress(2_150), 0.001f)
        assertEquals(0.5f, stage.wordSung(1, 2_300), 0.001f)
        assertEquals(1, stage.startedWords(1_250))
    }
}
