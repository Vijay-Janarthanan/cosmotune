package com.cosmotune.design

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Title art for every album (Canvas repo, design/cosmotune/lyrics-titles, "Title art for every album"): which treatment an
 * album's title gets in a place, which typeface its mood asks for, and the colours that keep it readable. Pure data and
 * arithmetic, so the rules are tested without a window; `sharedUi`'s `AlbumTitleArt` draws what these decide.
 *
 * A, the album's own logotype, is not here. Apple's catalogue was asked first (11 November 2026, `albums/{id}?extend=
 * editorialArtwork` for "eternal sunshine", "Hurry Up Tomorrow", "GNX", "Short n' Sweet", "HIT ME HARD AND SOFT" and "THE
 * TORTURED POETS DEPARTMENT"): `editorialArtwork` carries only `staticDetailSquare`, `staticDetailTall`, `storeFlowcase`,
 * `subscriptionHero` and `superHeroTall`, all full opaque pictures (no alpha channel), and no key names a logo. With no
 * transparent logotype to fetch, every album gets one of the treatments below.
 */
object TitleArt {

    /** The treatments: B Cover Cut, C Voiced type, D Arc title, F Split stack. E (breathing) moves B, C or F; see [breathes]. */
    enum class Style { CUT, VOICED, ARC, SPLIT }

    /** Where a title is drawn, which decides what it may be (the board's placement table). */
    enum class Place {
        /** The album page's card: the full rules, breathing while the album plays. */
        HERO,
        /** The Now Playing song card's heading (phone, desktop): Cover Cut or Voiced type, compact. */
        HEADING,
        /** Cinema's slim card: one small line, so Cover Cut or Voiced type (a stack needs two lines). */
        SLIM,
        /** Round a disc: the mini player, the TV and the watch. */
        RING,
        /** Home's hero and featured cards, Replay's albums card: Cover Cut or Voiced type. */
        FEATURE,
        /** The lyric share video's end card: the full rules. */
        END_CARD,
    }

    /** The typefaces of Voiced type: italic serif, condensed heavy, wide, mono. */
    enum class Voice { SERIF, CONDENSED, WIDE, MONO }

    /**
     * The treatment for [title] in [place]. A ring place is always the arc; the song card, Cinema's one-line card and the
     * featured cards take Voiced type when the album has a [voice] and Cover Cut otherwise; everywhere else a title of two or
     * more words is a Split stack unless its mood gives it a voice (then Voiced type), and a one-word title is Cover Cut. A title
     * in a script whose letters join ([tallScript]: Tamil, Devanagari and the like) is never stacked: Archivo has none of those
     * letters, so the stack's wide and narrow lines would be one system face, and a long first word would have to be cut short.
     */
    fun choose(title: String, place: Place, voice: Voice?): Style = when (place) {
        Place.RING -> Style.ARC
        Place.HEADING, Place.SLIM, Place.FEATURE -> if (voice != null) Style.VOICED else Style.CUT
        Place.HERO, Place.END_CARD -> when {
            words(title).size < 2 -> Style.CUT
            voice != null -> Style.VOICED
            tallScript(title) -> Style.CUT
            else -> Style.SPLIT
        }
    }

    /**
     * Whether the title breathes (E): only on the album page, only while that album is the one playing, never with reduced
     * motion, and only in the faces with a width axis (the serif and the mono have none).
     */
    fun breathes(place: Place, style: Style, voice: Voice?, playing: Boolean, reducedMotion: Boolean): Boolean =
        playing && !reducedMotion && place == Place.HERO && when (style) {
            Style.CUT, Style.SPLIT -> true
            Style.VOICED -> voice == Voice.CONDENSED || voice == Voice.WIDE
            Style.ARC -> false
        }

    /** The words of a title, split on white space; a mark on its own ("-", "&", "·") is not a word. */
    fun words(title: String): List<String> =
        title.trim().split(WHITESPACE).filter { word -> word.any { it.isLetterOrDigit() } }

    /** A Split stack's two lines: the first word, then the rest of the title as it was written. */
    fun split(title: String): Pair<String, String> {
        val trimmed = title.trim()
        val first = words(trimmed).firstOrNull() ?: return trimmed to ""
        val at = trimmed.indexOf(first)
        val rest = trimmed.substring(at + first.length).trim()
        return trimmed.substring(0, at + first.length).trim() to rest
    }

    private val WHITESPACE = Regex("\\s+")

    // ---- the voice ---------------------------------------------------------------------------------------------------------------

    /**
     * The genres that say their face outright, matched as parts of the genre's name in this order (so "pop punk" and "pop rock"
     * are rock, "electropop" is electronic and "jazz pop" is jazz): hip-hop and rock condensed and heavy, electronic in mono,
     * acoustic, jazz and classical in the italic serif, pop wide.
     */
    private val GENRE_VOICES: List<Pair<String, Voice>> = listOf(
        "hip-hop" to Voice.CONDENSED, "hip hop" to Voice.CONDENSED, "rap" to Voice.CONDENSED, "trap" to Voice.CONDENSED,
        "drill" to Voice.CONDENSED, "grime" to Voice.CONDENSED, "rock" to Voice.CONDENSED, "metal" to Voice.CONDENSED,
        "punk" to Voice.CONDENSED, "grunge" to Voice.CONDENSED, "hardcore" to Voice.CONDENSED, "emo" to Voice.CONDENSED,
        "electronic" to Voice.MONO, "electro" to Voice.MONO, "edm" to Voice.MONO, "house" to Voice.MONO, "techno" to Voice.MONO,
        "trance" to Voice.MONO, "dubstep" to Voice.MONO, "drum and bass" to Voice.MONO, "drum & bass" to Voice.MONO,
        "dnb" to Voice.MONO, "ambient" to Voice.MONO, "idm" to Voice.MONO, "synthwave" to Voice.MONO, "garage" to Voice.MONO,
        "breakbeat" to Voice.MONO, "downtempo" to Voice.MONO, "dance" to Voice.MONO,
        "acoustic" to Voice.SERIF, "folk" to Voice.SERIF, "singer" to Voice.SERIF, "jazz" to Voice.SERIF, "blues" to Voice.SERIF,
        "classical" to Voice.SERIF, "opera" to Voice.SERIF, "orchestral" to Voice.SERIF, "chamber" to Voice.SERIF,
        "baroque" to Voice.SERIF, "piano" to Voice.SERIF, "bossa" to Voice.SERIF, "carnatic" to Voice.SERIF,
        "hindustani" to Voice.SERIF, "ghazal" to Voice.SERIF,
        "pop" to Voice.WIDE,
    )

    /**
     * Indian film music and the languages it is filed under (Apple's genres, JioSaavn's languages). A soundtrack holds both mass
     * numbers and melodies, and its cover says which it leans to: a vivid or dark, punchy cover sets it condensed and heavy, a
     * soft, light one in the italic serif.
     */
    private val INDIAN_FILM = listOf(
        "tamil", "telugu", "hindi", "malayalam", "kannada", "punjabi", "marathi", "bengali", "bhojpuri", "gujarati",
        "bollywood", "kollywood", "tollywood", "mollywood", "sandalwood", "indian", "filmi",
    )

    /** At or above this saturation a cover reads as vivid (a mass number's poster rather than a melody's). */
    const val VIVID_COVER = 0.45f

    /**
     * Genres whose face the cover decides: a dark, moody cover sets them in the italic serif, a bright one wide. R&B and soul,
     * indie and alternative, film music, anime and the like.
     */
    private val COVER_DECIDES = listOf(
        "r&b", "rnb", "soul", "indie", "alternative", "country", "latin", "reggae", "world", "gospel", "funk", "disco",
        "soundtrack", "film", "anime", "new age", "christian", "worship",
    )

    /** At or under this mean brightness (0 black, 1 white) a cover reads as dark. */
    const val DARK_COVER = 0.42f

    /**
     * The face of an album: from its [genre] where the genre names one; for Indian film music, from how vivid or dark the cover
     * is ([saturation], [brightness]); for the other in-between genres from the cover's [brightness]; else none (an album with no
     * known genre has no voice). The same album always gets the same face.
     */
    fun voice(genre: String?, brightness: Float?, saturation: Float? = null): Voice? {
        val name = genre?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return null
        GENRE_VOICES.firstOrNull { (part, _) -> name.contains(part) }?.let { return it.second }
        if (INDIAN_FILM.any { name.contains(it) }) {
            val light = brightness ?: return null
            val vivid = (saturation ?: 0f) >= VIVID_COVER
            return if (vivid || light <= DARK_COVER) Voice.CONDENSED else Voice.SERIF
        }
        if (COVER_DECIDES.any { name.contains(it) }) {
            val light = brightness ?: return null
            return if (light <= DARK_COVER) Voice.SERIF else Voice.WIDE
        }
        return null
    }

    /** The weight a voice is set in: heavier on a bright cover, so the type holds its own against it. */
    fun voiceWeight(voice: Voice, brightness: Float?): Int {
        val bright = (brightness ?: 0f) > DARK_COVER
        return when (voice) {
            Voice.SERIF -> 400
            Voice.CONDENSED -> if (bright) 900 else 820
            Voice.WIDE -> if (bright) 900 else 760
            Voice.MONO -> if (bright) 700 else 600
        }
    }

    /** Whether the voice is set in capitals (the condensed and the mono are; the serif and the wide keep the title's case). */
    fun voiceCaps(voice: Voice): Boolean = voice == Voice.CONDENSED || voice == Voice.MONO

    // ---- scripts -----------------------------------------------------------------------------------------------------------------

    /**
     * Whether [text] is in a script whose marks reach far above and below the line (the Indian scripts, Thai, Lao, Tibetan,
     * Myanmar, Sinhala), so the tight line height of the Latin caps would cut them.
     */
    fun tallScript(text: String): Boolean = text.any { c ->
        val code = c.code
        code in 0x0900..0x0DFF || code in 0x0E00..0x0FFF || code in 0x1000..0x109F
    }

    /** The line height of the display faces as a multiple of the size: tight caps for Latin, room for a tall script. */
    fun lineHeight(text: String): Float = if (tallScript(text)) 1.32f else 0.92f

    // ---- fitting -----------------------------------------------------------------------------------------------------------------

    /**
     * The largest size from [min] to [max] (to a tenth) at which [fits] holds, found by halving; [min] when nothing fits, as
     * the type never goes below its floor (it is cut short instead).
     */
    fun fitSize(min: Float, max: Float, fits: (Float) -> Boolean): Float {
        if (max <= min) return min
        if (fits(max)) return max
        if (!fits(min)) return min
        var low = min
        var high = max
        while (high - low > 0.1f) {
            val mid = (low + high) / 2f
            if (fits(mid)) low = mid else high = mid
        }
        return low
    }

    // ---- colour ------------------------------------------------------------------------------------------------------------------

    /**
     * What a cover gives the title: its mean [brightness] (0..1), the colour at its darkest tenth ([dark]) and its brightest
     * tenth ([light]), which bound how far its colours stray from the middle, its leading hue ([hue], degrees, with its
     * [saturation]), and its middle tone ([mid], the median by brightness), which is what most of a letter shows.
     */
    data class CoverStats(val brightness: Float, val dark: Int, val light: Int, val hue: Float, val saturation: Float, val mid: Int? = null)

    /**
     * The stats of a cover from its pixels (ARGB, any size; transparent pixels are skipped), [dark] and [light] at the [low] and
     * [high] fractions by brightness. The hue is the strongest of 24 buckets, each colourful pixel voting by how saturated and how
     * bright it is, so a small red sign on a grey cover still leads.
     */
    fun coverStats(pixels: IntArray, low: Float = 0.1f, high: Float = 0.9f): CoverStats? {
        val opaque = pixels.filter { (it ushr 24) >= 0x80 }
        if (opaque.isEmpty()) return null
        val lumas = FloatArray(opaque.size)
        var sum = 0.0
        val votes = DoubleArray(24)
        val satSum = DoubleArray(24)
        val counts = IntArray(24)
        opaque.forEachIndexed { i, argb ->
            val l = luma(argb)
            lumas[i] = l
            sum += l
            val (h, s, v) = hsv(argb)
            if (s > 0.12f && v > 0.12f) {
                val bucket = ((h / 15f).toInt()).coerceIn(0, 23)
                votes[bucket] += (s * v).toDouble()
                satSum[bucket] += s.toDouble()
                counts[bucket]++
            }
        }
        val order = lumas.indices.sortedBy { lumas[it] }
        val dark = opaque[order[(order.size * low).toInt().coerceIn(0, order.size - 1)]]
        val light = opaque[order[(order.size * high).toInt().coerceIn(0, order.size - 1)]]
        val mid = opaque[order[order.size / 2]]
        val top = votes.indices.maxByOrNull { votes[it] } ?: 0
        val count = counts[top]
        val hue = if (votes[top] > 0.0) top * 15f + 7.5f else 0f
        val saturation = if (votes[top] > 0.0 && count > 0) (satSum[top] / count).toFloat() else 0f
        return CoverStats((sum / opaque.size).toFloat(), dark, light, hue, saturation, mid)
    }

    /** Gamma-encoded luma, 0..1 (Rec. 709 weights): how bright a colour looks, for "is this cover dark". */
    fun luma(argb: Int): Float =
        (0.2126f * ((argb shr 16) and 0xFF) + 0.7152f * ((argb shr 8) and 0xFF) + 0.0722f * (argb and 0xFF)) / 255f

    /** [a] painted over by [b] at [t] (0..1), the way a translucent wash composites: in gamma-encoded sRGB, opaque. */
    fun over(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1f - t)) + (((b shr shift) and 0xFF) * t)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * How much of [ink] to wash over the cover inside the letters of Cover Cut so they read on [ground]: the least that brings
     * the cover's worst colour ([CoverStats.dark] when the ink is the lighter, [CoverStats.light] when it is the darker) to
     * [target] contrast and its middle tone ([CoverStats.mid]) to [MID_TARGET] at least, 0 when the cover reads as it is, 1 when
     * only the ink itself does. The middle tone is what most of a letter shows: a mostly dark cover held to the floor alone
     * reads dim on a dark card even where its darkest tenth passes.
     */
    fun wash(stats: CoverStats, ink: Int, ground: Int, target: Double): Float {
        val worst = if (luma(ink) >= luma(ground)) stats.dark else stats.light
        // A little over each target: the cover is resampled into the letters, which moves a colour by a few levels.
        val forWorst = washFor(worst, ink, ground, target + MARGIN)
        val forMid = stats.mid?.let { washFor(it, ink, ground, max(target, MID_TARGET) + MARGIN) } ?: 0f
        return max(forWorst, forMid)
    }

    /** The contrast the middle tone of a Cover Cut letter is held to. */
    const val MID_TARGET = 4.5

    private const val MARGIN = 0.2

    /**
     * The share of the drawn part of a cover that Cover Cut's extremes are read at: its darkest and brightest 4 %, so a sun or a
     * spotlight behind the letters is held to the contrast too, not only the cover's broad tones.
     */
    const val REGION_LOW = 0.04f
    const val REGION_HIGH = 0.96f

    private fun washFor(colour: Int, ink: Int, ground: Int, target: Double): Float {
        if (Contrast.ratio(colour, ground) >= target) return 0f
        if (Contrast.ratio(ink, ground) < target) return 1f
        var low = 0f
        var high = 1f
        repeat(14) {
            val mid = (low + high) / 2f
            if (Contrast.ratio(over(colour, ink, mid), ground) >= target) high = mid else low = mid
        }
        return high
    }

    /** The contrast a title needs: 3:1 for display type (24 sp and up, as WCAG has it for large text), 4.5:1 under that. */
    fun target(sizeSp: Float): Double = if (sizeSp >= 24f) 3.0 else 4.5

    /**
     * The cover's own colour for type on [ground]: its leading hue made pale on a dark ground (or deep on a light one), then
     * moved towards [ink] until it reaches [target]. A grey cover gives a near-neutral tint of the ink.
     */
    fun tint(stats: CoverStats?, ink: Int, ground: Int, target: Double = 4.5): Int {
        if (stats == null || stats.saturation < 0.12f) return ink
        val darkGround = luma(ground) < luma(ink)
        val raw = if (darkGround) hsvToArgb(stats.hue, 0.42f, 1f) else hsvToArgb(stats.hue, 0.75f, 0.42f)
        if (Contrast.ratio(raw, ground) >= target) return raw
        var low = 0f
        var high = 1f
        repeat(14) {
            val mid = (low + high) / 2f
            if (Contrast.ratio(over(raw, ink, mid), ground) >= target) high = mid else low = mid
        }
        return over(raw, ink, high)
    }

    /** Hue (degrees), saturation and value of an opaque colour. */
    fun hsv(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        return Triple(h, if (mx == 0f) 0f else d / mx, mx)
    }

    /** The opaque colour of hue [h] (degrees), saturation [s] and value [v]. */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hh % 2f - 1f))
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    // ---- breathing ---------------------------------------------------------------------------------------------------------------

    /** How many width instances a breathing title steps through (the axes are never animated per frame; see `CosmoFontSpecs`). */
    const val BREATH_STEPS = 9

    /** One breath, in and out, when the song's tempo is not known. */
    const val BREATH_MS = 4_000

    /** The width a breathing line has at [step] (0 until [BREATH_STEPS]) of its breath: from its own [rest] to [toward]. */
    fun breathWidth(rest: Float, toward: Float, step: Int): Float =
        rest + (toward - rest) * step.coerceIn(0, BREATH_STEPS - 1).toFloat() / (BREATH_STEPS - 1)

    /**
     * The step of the breath [elapsedMs] into it: up the steps for half of [periodMs], back down for the other half, eased (a
     * sine), so the steps bunch at the turns the way a breath slows.
     */
    fun breathStep(elapsedMs: Long, periodMs: Int = BREATH_MS): Int {
        val t = ((elapsedMs % periodMs).toFloat() / periodMs)
        val up = if (t < 0.5f) t * 2f else (1f - t) * 2f
        val eased = ((1 - kotlin.math.cos(up * kotlin.math.PI)) / 2).toFloat()
        return (eased * (BREATH_STEPS - 1)).roundToInt().coerceIn(0, BREATH_STEPS - 1)
    }
}
