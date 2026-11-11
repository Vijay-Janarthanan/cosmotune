package com.cosmotune.music.data.lyrics

import kotlin.test.*

class OfflineRomanizationTest {
    @Test fun romanisationPreservesVocalBoundsAndBackingVoiceTiming() = kotlinx.coroutines.runBlocking {
        val lead = LyricLine(timeMs = 1000, text = "தமிழ் பாடல்", words = listOf(LyricWord(1000, 1800, "தமிழ்"), LyricWord(2000, 3000, "பாடல்")),
            background = LyricLine(timeMs = 1500, text = "வா", words = listOf(LyricWord(1500, 2500, "வா"))))
        val result = LyricsTranslation.romanize("offline-timing-fixture", listOf(lead), "en") as LyricsTranslation.RomanizationResult.Romanized
        assertEquals(1000L, result.lines.single().timeMs)
        assertEquals(1500L, result.lines.single().background?.timeMs)
        assertEquals(lead, result.lines.single().timingSource)
        assertEquals(lead.background, result.lines.single().background?.timingSource)
    }
    @Test fun indicCombiningMarksAndMixedEnglishSurviveWithoutNetwork() {
        val answer = assertNotNull(OfflineRomanization.romanize(listOf("தமிழ் பாடல் love", "తెలుగు పాట")))
        assertTrue(answer.texts[0].endsWith("love"))
        assertTrue(answer.texts.all { it.isNotBlank() && it.none { c -> c in '\u0B80'..'\u0C7F' } })
    }
    @Test fun koreanSoundChangesAreAppliedAtWordBoundaries() {
        assertEquals("hamnida", HangulRomanization.romanise("합니다"))
        assertEquals("gungmul", HangulRomanization.romanise("국물"))
        assertEquals("gachi", HangulRomanization.romanise("같이"))
        assertTrue(assertNotNull(OfflineRomanization.romanize(listOf("합니다 love"))).texts.single().contains("hamnida love"))
    }
    @Test fun japaneseKanjiNeverBecomesMandarinAndKanaStillWorks() {
        assertNull(OfflineRomanization.romanize(listOf("君の名前")))
        val kana = assertNotNull(OfflineRomanization.romanize(listOf("ありがとう")))
        assertEquals("ja", kana.language)
        assertTrue(kana.texts.single().startsWith("arigat"))
        assertEquals("zh", assertNotNull(OfflineRomanization.romanize(listOf("我爱你"))).language)
    }
}
