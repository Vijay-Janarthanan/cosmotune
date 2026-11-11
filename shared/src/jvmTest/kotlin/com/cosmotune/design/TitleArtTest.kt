package com.cosmotune.design

import com.cosmotune.design.TitleArt.Place
import com.cosmotune.design.TitleArt.Style
import com.cosmotune.design.TitleArt.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The title art's rules (lyrics-titles board, "Title art for every album" and its placement table). */
class TitleArtTest {

    // ---- which treatment --------------------------------------------------------------------------------------------------------

    @Test fun aOneWordTitleIsCoverCut() {
        assertEquals(Style.CUT, TitleArt.choose("Lover", Place.HERO, voice = null))
        assertEquals(Style.CUT, TitleArt.choose("GNX", Place.END_CARD, voice = null))
        assertEquals(Style.CUT, TitleArt.choose("Lover", Place.HERO, voice = Voice.WIDE), "B otherwise, even with a voice")
    }

    @Test fun twoOrMoreWordsAreASplitStack() {
        assertEquals(Style.SPLIT, TitleArt.choose("Midnight Transit", Place.HERO, voice = null))
        assertEquals(Style.SPLIT, TitleArt.choose("the tortured poets department", Place.END_CARD, voice = null))
    }

    @Test fun aVoiceFitsAMultiWordTitleBetterThanTheStack() =
        assertEquals(Style.VOICED, TitleArt.choose("Paper Planets", Place.HERO, voice = Voice.SERIF))

    @Test fun aRingPlaceIsAlwaysTheArc() {
        assertEquals(Style.ARC, TitleArt.choose("Lantern Season", Place.RING, voice = null))
        assertEquals(Style.ARC, TitleArt.choose("GNX", Place.RING, voice = Voice.CONDENSED))
    }

    @Test fun onlyARingPlaceGetsTheArc() {
        for (place in Place.entries.filter { it != Place.RING }) for (voice in Voice.entries + null) {
            for (title in listOf("Lover", "Midnight Transit", "கண்ணாளனே", "君の名は")) {
                assertTrue(TitleArt.choose(title, place, voice) != Style.ARC, "$place $voice $title")
            }
        }
    }

    @Test fun theSongCardCinemaAndTheFeaturedCardsAreCoverCutOrVoiced() {
        for (place in listOf(Place.HEADING, Place.SLIM, Place.FEATURE)) {
            assertEquals(Style.CUT, TitleArt.choose("Midnight Transit", place, voice = null), "no stack in $place")
            assertEquals(Style.VOICED, TitleArt.choose("Lover", place, voice = Voice.WIDE))
        }
    }

    @Test fun aScriptWithoutSpacesIsOneWord() {
        assertEquals(Style.CUT, TitleArt.choose("君の名は", Place.HERO, voice = null))
        assertEquals(Style.SPLIT, TitleArt.choose("Ella Baila Sola", Place.HERO, voice = null))
    }

    @Test fun aJoiningScriptIsNeverStacked() {
        assertEquals(Style.CUT, TitleArt.choose("விண்ணைத்தாண்டி வருவாயா", Place.HERO, voice = null))
        assertEquals(Style.CUT, TitleArt.choose("रंग दे बसंती", Place.END_CARD, voice = null))
        assertEquals(Style.VOICED, TitleArt.choose("விண்ணைத்தாண்டி வருவாயா", Place.HERO, voice = Voice.SERIF))
    }

    @Test fun marksAreNotWords() {
        assertEquals(listOf("Simon", "Garfunkel"), TitleArt.words("  Simon & Garfunkel "))
        assertEquals(Style.CUT, TitleArt.choose("Folklore -", Place.HERO, voice = null))
    }

    @Test fun theStackSplitsAfterTheFirstWord() {
        assertEquals("Midnight" to "Transit", TitleArt.split("Midnight Transit"))
        assertEquals("eternal" to "sunshine (slightly deluxe)", TitleArt.split("  eternal  sunshine (slightly deluxe)"))
        assertEquals("Lover" to "", TitleArt.split("Lover"))
    }

    // ---- the voice --------------------------------------------------------------------------------------------------------------

    @Test fun genresNameTheirFaces() {
        assertEquals(Voice.SERIF, TitleArt.voice("Acoustic", null))
        assertEquals(Voice.SERIF, TitleArt.voice("Jazz", null))
        assertEquals(Voice.SERIF, TitleArt.voice("Classical", 0.9f))
        assertEquals(Voice.SERIF, TitleArt.voice("Carnatic", null))
        assertEquals(Voice.CONDENSED, TitleArt.voice("Hip-Hop/Rap", null))
        assertEquals(Voice.CONDENSED, TitleArt.voice("Alternative Rock", null))
        assertEquals(Voice.WIDE, TitleArt.voice("Pop", null))
        assertEquals(Voice.WIDE, TitleArt.voice("K-Pop", 0.1f))
        assertEquals(Voice.MONO, TitleArt.voice("Electronic", null))
        assertEquals(Voice.MONO, TitleArt.voice("Dance", null))
    }

    @Test fun theOrderSettlesMixedGenres() {
        assertEquals(Voice.CONDENSED, TitleArt.voice("Pop Punk", null), "rock before pop")
        assertEquals(Voice.MONO, TitleArt.voice("Electropop", null), "electronic before pop")
        assertEquals(Voice.SERIF, TitleArt.voice("Jazz Pop", null), "jazz before pop")
    }

    @Test fun theCoverDecidesTheInBetweenGenres() {
        assertEquals(Voice.SERIF, TitleArt.voice("R&B/Soul", 0.2f), "a dark cover: the serif")
        assertEquals(Voice.WIDE, TitleArt.voice("R&B/Soul", 0.7f), "a bright cover: wide")
        assertNull(TitleArt.voice("Soundtrack", null), "not until the cover is known")
    }

    @Test fun indianFilmMusicIsMassOrMelodyByItsCover() {
        // A vivid poster, or a dark and punchy one: a mass number, condensed and heavy.
        assertEquals(Voice.CONDENSED, TitleArt.voice("Soundtrack / Tamil", 0.6f, saturation = 0.7f))
        assertEquals(Voice.CONDENSED, TitleArt.voice("Telugu", 0.3f, saturation = 0.2f))
        // A soft, light cover: a melody album, in the serif.
        assertEquals(Voice.SERIF, TitleArt.voice("Tamil", 0.7f, saturation = 0.2f))
        assertEquals(Voice.SERIF, TitleArt.voice("Bollywood", 0.65f, saturation = 0.3f))
        // JioSaavn files by language.
        assertEquals(Voice.SERIF, TitleArt.voice("Hindi", 0.8f, saturation = 0.1f))
        // A genre that names its face still does.
        assertEquals(Voice.CONDENSED, TitleArt.voice("Tamil / Hip-Hop", 0.8f, saturation = 0.1f))
        assertEquals(Voice.SERIF, TitleArt.voice("Indian Classical", 0.2f, saturation = 0.9f))
        assertNull(TitleArt.voice("Tamil", null), "not until the cover is known")
    }

    @Test fun noGenreNoVoice() {
        assertNull(TitleArt.voice(null, 0.5f))
        assertNull(TitleArt.voice("  ", 0.5f))
        assertNull(TitleArt.voice("Spoken Word", 0.5f))
    }

    @Test fun theVoiceIsTheSameEveryTime() {
        val genres = listOf("Pop", "Jazz", "R&B", "Electronic", "Rock", null, "Tamil")
        val first = genres.map { TitleArt.voice(it, 0.3f) }
        repeat(5) { assertEquals(first, genres.map { TitleArt.voice(it, 0.3f) }) }
    }

    @Test fun aBrightCoverSetsTheVoiceHeavier() {
        for (voice in listOf(Voice.CONDENSED, Voice.WIDE, Voice.MONO)) {
            assertTrue(TitleArt.voiceWeight(voice, 0.8f) > TitleArt.voiceWeight(voice, 0.2f), "$voice")
        }
        assertTrue(TitleArt.voiceCaps(Voice.CONDENSED) && TitleArt.voiceCaps(Voice.MONO))
        assertFalse(TitleArt.voiceCaps(Voice.SERIF) || TitleArt.voiceCaps(Voice.WIDE))
    }

    // ---- breathing --------------------------------------------------------------------------------------------------------------

    @Test fun onlyThePlayingAlbumsPageBreathes() {
        assertTrue(TitleArt.breathes(Place.HERO, Style.CUT, null, playing = true, reducedMotion = false))
        assertFalse(TitleArt.breathes(Place.HERO, Style.CUT, null, playing = false, reducedMotion = false), "paused")
        assertFalse(TitleArt.breathes(Place.HERO, Style.CUT, null, playing = true, reducedMotion = true), "reduced motion")
        assertFalse(TitleArt.breathes(Place.HEADING, Style.CUT, null, playing = true, reducedMotion = false), "the song card")
        assertFalse(TitleArt.breathes(Place.HERO, Style.VOICED, Voice.SERIF, playing = true, reducedMotion = false), "no width axis")
        assertTrue(TitleArt.breathes(Place.HERO, Style.VOICED, Voice.WIDE, playing = true, reducedMotion = false))
    }

    @Test fun aBreathGoesOutAndComesBack() {
        val steps = (0 until 4_000 step 100).map { TitleArt.breathStep(it.toLong()) }
        assertEquals(0, steps.first())
        assertEquals(TitleArt.BREATH_STEPS - 1, TitleArt.breathStep(2_000))
        assertEquals(steps, (4_000 until 8_000 step 100).map { TitleArt.breathStep(it.toLong()) }, "it repeats")
        assertTrue(steps.all { it in 0 until TitleArt.BREATH_STEPS })
        assertEquals(62f, TitleArt.breathWidth(62f, 100f, 0))
        assertEquals(100f, TitleArt.breathWidth(62f, 100f, TitleArt.BREATH_STEPS - 1))
    }

    // ---- fitting and colour -----------------------------------------------------------------------------------------------------

    @Test fun theSizeIsTheLargestThatFits() {
        assertEquals(40f, TitleArt.fitSize(10f, 40f) { true })
        assertEquals(10f, TitleArt.fitSize(10f, 40f) { false })
        assertEquals(23f, TitleArt.fitSize(10f, 40f) { it <= 23f }, 0.11f)
    }

    @Test fun tallScriptsGetRoom() {
        assertTrue(TitleArt.tallScript("கண்ணாளனே"))
        assertTrue(TitleArt.tallScript("रंग दे"))
        assertFalse(TitleArt.tallScript("君の名は"))
        assertFalse(TitleArt.tallScript("Café Noir"))
        assertTrue(TitleArt.lineHeight("கண்ணாளனே") > TitleArt.lineHeight("LOW TIDE"))
    }

    private val ink = 0xFFFFEDE2.toInt()
    private val deepRust = 0xFF5E2A1C.toInt()

    @Test fun aDarkCoverIsWashedUntilItReads() {
        // A cover dark at its darkest tenth on its own deep card: the letters need a wash to read.
        val stats = TitleArt.CoverStats(0.3f, dark = 0xFF3A1A10.toInt(), light = 0xFFE0A070.toInt(), hue = 20f, saturation = 0.6f)
        val wash = TitleArt.wash(stats, ink, deepRust, target = 3.0)
        assertTrue(wash in 0.01f..0.99f, "a partial wash: $wash")
        assertTrue(Contrast.ratio(TitleArt.over(stats.dark, ink, wash), deepRust) >= 3.0)
    }

    @Test fun aMostlyDarkCoversMiddleIsLiftedToo() {
        // The dusk cover on its indigo card: the darkest tenth alone would stop at 3:1 and the letters would read dim.
        val indigo = 0xFF2B2160.toInt()
        val floorOnly = TitleArt.CoverStats(0.2f, dark = 0xFF161028.toInt(), light = 0xFF8C9CF0.toInt(), hue = 250f, saturation = 0.6f)
        val withMid = floorOnly.copy(mid = 0xFF2E2560.toInt())
        val a = TitleArt.wash(floorOnly, ink, indigo, 3.0)
        val b = TitleArt.wash(withMid, ink, indigo, 3.0)
        assertTrue(b >= a, "never less than the floor asks: $a vs $b")
        assertTrue(Contrast.ratio(TitleArt.over(withMid.mid!!, ink, b), indigo) >= TitleArt.MID_TARGET)
        assertTrue(Contrast.ratio(TitleArt.over(withMid.dark, ink, b), indigo) >= 3.0)
    }

    @Test fun aCoverThatReadsIsLeftAlone() {
        val stats = TitleArt.CoverStats(0.9f, dark = 0xFFE8E0D0.toInt(), light = 0xFFFFFFFF.toInt(), hue = 40f, saturation = 0.1f)
        assertEquals(0f, TitleArt.wash(stats, ink, deepRust, target = 4.5))
    }

    @Test fun onALightGroundTheBrightestColourIsTheOneToWatch() {
        val paper = 0xFFF6F1EA.toInt()
        val darkInk = 0xFF1D1416.toInt()
        val stats = TitleArt.CoverStats(0.6f, dark = 0xFF101010.toInt(), light = 0xFFF0E8E0.toInt(), hue = 30f, saturation = 0.3f)
        val wash = TitleArt.wash(stats, darkInk, paper, target = 3.0)
        assertTrue(wash > 0f)
        assertTrue(Contrast.ratio(TitleArt.over(stats.light, darkInk, wash), paper) >= 3.0)
    }

    @Test fun theCoversTintReads() {
        val stats = TitleArt.CoverStats(0.4f, dark = 0xFF102030.toInt(), light = 0xFF80A0FF.toInt(), hue = 230f, saturation = 0.7f)
        for ((ink, ground) in listOf(this.ink to deepRust, 0xFF1D1416.toInt() to 0xFFF6F1EA.toInt())) {
            val tint = TitleArt.tint(stats, ink, ground)
            assertTrue(Contrast.ratio(tint, ground) >= 4.5, "tint ${Integer.toHexString(tint)} on ${Integer.toHexString(ground)}")
        }
        assertEquals(ink, TitleArt.tint(null, ink, deepRust))
    }

    @Test fun theStatsComeFromThePixels() {
        // Half black, half a strong red: dark at the low tenth, red leads the hue.
        val pixels = IntArray(100) { if (it < 50) 0xFF000000.toInt() else 0xFFE03020.toInt() }
        val stats = requireNotNull(TitleArt.coverStats(pixels))
        assertEquals(0xFF000000.toInt(), stats.dark)
        assertEquals(0xFFE03020.toInt(), stats.light)
        assertEquals(0xFFE03020.toInt(), stats.mid, "the median by brightness")
        assertTrue(stats.hue < 15f, "red: ${stats.hue}")
        assertTrue(stats.brightness in 0.1f..0.3f)
        assertNull(TitleArt.coverStats(IntArray(4) { 0 }), "a transparent picture has none")
    }

    @Test fun targetsFollowTheSize() {
        assertEquals(3.0, TitleArt.target(30f))
        assertEquals(4.5, TitleArt.target(18f))
    }
}
