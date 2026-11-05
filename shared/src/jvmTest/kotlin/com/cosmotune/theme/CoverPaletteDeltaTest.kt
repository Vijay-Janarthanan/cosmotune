package com.cosmotune.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The v3 colours: glass, the skeleton pair, the danger family and `dangerClash` (`ui/v3/02-colour-delta.md`). The designer's
 * table in section 3 of that file is typed in below. It was made from the covers' exact cluster centres and the seeds are
 * those centres rounded to 8 bits, so, as in [CoverPaletteDesignerTableTest], a colour can be a few levels off the table
 * (the tolerance is 4 per channel; the flag, the alpha and the danger family are exact).
 */
class CoverPaletteDeltaTest : ThemeTestBase() {

    /** One of the twelve covers: the seeds are those of [CoverPaletteDesignerTableTest]; "Portrait, 1971" is a second grey cover. */
    private class Cover(val id: String, val name: String, val seed: String, val secondHue: Double?, val mono: Boolean)

    private class Expected(val glass: String, val skelBase: String, val skelShine: String, val clash: Boolean)

    private val covers = listOf(
        Cover("c10", "Glasshouse Weather", "466044", null, false),
        Cover("c2", "Parallel Rooms", "D4552E", null, false),
        Cover("c4", "Kite Season", "ADD6C8", 25.9, false),
        Cover("c5", "after the rain", "2E7C58", 88.3, false),
        Cover("c9", "Ninefold", "2D5A8A", 42.9, false),
        Cover("c11", "Night Shift Radio", "84755F", 29.1, false),
        Cover("c12", "Overpass", "F1AD75", null, false),
        Cover("c1", "Low Tide Hours", "2C4E79", null, false),
        Cover("h1", "Sunblock", "FFE500", null, false),
        Cover("h2", "Paper", "FFB98F", null, true),
        Cover("h3", "Portrait, 1971", "FFB98F", null, true),
        Cover("h4", "Arcade Heat", "FF2D95", 182.7, false),
    )

    /** Section 3 of `02-colour-delta.md`: (dark, light) per cover. The glass is the colour; its alpha is checked on its own. */
    private val table: Map<String, Pair<Expected, Expected>> = mapOf(
        "c10" to (Expected("293028", "293029", "404B3F", false) to Expected("F0FAF0", "DFE7DE", "ECFAEB", false)),
        "c2" to (Expected("3C2822", "3B2923", "5F3E34", true) to Expected("FFF0E8", "F8DED7", "FFEDE3", true)),
        "c4" to (Expected("26302D", "27302D", "3C4C46", false) to Expected("EDFAF5", "DCE8E3", "E7FAF3", false)),
        "c5" to (Expected("203328", "213229", "334F40", false) to Expected("E6FEF0", "D5EBDE", "DDFFEB", false)),
        "c9" to (Expected("232F3D", "242F3C", "37495E", false) to Expected("E9F9FF", "D8E6F7", "E5F7FF", false)),
        "c11" to (Expected("312D27", "312D28", "4D473E", false) to Expected("FBF6EE", "E9E4DD", "FCF4E9", false)),
        "c12" to (Expected("3A2A1D", "392A1E", "5B412D", false) to Expected("FFF2E2", "F4E0D1", "FFEFD7", false)),
        "c1" to (Expected("252F3B", "262F3A", "3A495C", false) to Expected("EBF8FF", "DAE6F5", "E6F7FF", false)),
        "h1" to (Expected("342D1B", "342D1C", "4D4928", false) to Expected("FFF6DF", "EDE4CF", "FCF7D1", false)),
        "h2" to (Expected("2F2D2C", "2F2D2C", "4A4745", false) to Expected("F9F6F4", "E7E4E2", "F8F4F2", false)),
        "h3" to (Expected("2F2D2C", "2F2D2C", "4A4745", false) to Expected("F9F6F4", "E7E4E2", "F8F4F2", false)),
        "h4" to (Expected("3B272E", "3A282E", "5E3C48", true) to Expected("FFEEF7", "F6DDE5", "FFEBF5", true)),
    )

    /** The danger family is the same for every cover (section 3, above the table). */
    private val dangerDark = listOf("F2716A", "1A0E0D", "642724", "FFDEDB")
    private val dangerLight = listOf("BA2B28", "FFFFFF", "FFD8D4", "6C1614")

    private fun argb(hex: String) = 0xFF000000.toInt() or hex.toInt(16)

    private fun paletteOf(c: Cover, dark: Boolean): CoverColors {
        val seed = argb(c.seed)
        return CoverPalette.fromSeed(seed, c.secondHue?.let { lchColour(0.55, 0.10, it) }, c.mono, !c.mono && lightness(seed) > 0.86, dark)
    }

    private fun worstDifference(actual: Int, expected: String): Int {
        val want = argb(expected)
        return listOf(16, 8, 0).maxOf { abs(((actual shr it) and 0xFF) - ((want shr it) and 0xFF)) }
    }

    private fun dangerOf(p: CoverColors) = listOf(p.danger, p.onDanger, p.dangerContainer, p.onDangerContainer).map { hex(it).removePrefix("#").uppercase() }

    // ---- the golden seeds (the same 61 as CoverPaletteGoldenTest) ----

    private class GoldenSeed(val name: String, val seed: Int, val second: Int?, val mono: Boolean, val bright: Boolean, val intensity: Double)

    private val goldenSeeds: List<GoldenSeed> = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/theme-golden/golden.json")) { "theme-golden/golden.json is missing" }
            .bufferedReader().use { it.readText() },
    ).jsonObject["cases"]!!.jsonArray.map {
        val o = it.jsonObject
        GoldenSeed(
            name = o["name"]!!.jsonPrimitive.content,
            seed = argb(o["seed"]!!.jsonPrimitive.content.removePrefix("#")),
            second = o["second"].let { s -> if (s == null || s is JsonNull) null else argb(s.jsonPrimitive.content.removePrefix("#")) },
            mono = o["mono"]!!.jsonPrimitive.boolean,
            bright = o["bright"]!!.jsonPrimitive.boolean,
            intensity = when (o["intensity"]!!.jsonPrimitive.content) { "calm" -> 0.7; "rich" -> 1.25; else -> 1.0 },
        )
    }

    private fun goldenPalette(g: GoldenSeed, dark: Boolean) =
        CoverPalette.fromSeed(g.seed, g.second, g.mono, g.bright, dark, CoverPaletteSpec(intensity = g.intensity))

    // ---- the danger family ----

    @Test
    fun theDangerFamilyIsTheSameForEveryCoverInBothThemes() {
        val problems = mutableListOf<String>()
        for (cover in covers) for (dark in listOf(true, false)) {
            val got = dangerOf(paletteOf(cover, dark))
            val want = if (dark) dangerDark else dangerLight
            if (got != want) problems += "${cover.name} (${if (dark) "dark" else "light"}): $got, the table says $want"
        }
        for (g in goldenSeeds) for (dark in listOf(true, false)) {
            val got = dangerOf(goldenPalette(g, dark))
            val want = if (dark) dangerDark else dangerLight
            if (got != want) problems += "golden ${g.name} (${if (dark) "dark" else "light"}): $got, expected $want"
        }
        assertTrue(problems.isEmpty(), "the danger family depends on the cover:\n" + problems.joinToString("\n"))
    }

    // ---- glass, the skeleton pair and the clash flag, against the designer's table ----

    @Test
    fun glassSkeletonAndTheClashFlagComeOutAsTheDesignersTablePrints() {
        val problems = mutableListOf<String>()
        var compared = 0
        var worst = 0
        for (cover in covers) {
            val (dark, light) = table.getValue(cover.id)
            for ((theme, expected) in listOf("dark" to dark, "light" to light)) {
                val p = paletteOf(cover, theme == "dark")
                listOf(
                    "glass" to (p.glass to expected.glass), "skelBase" to (p.skelBase to expected.skelBase),
                    "skelShine" to (p.skelShine to expected.skelShine),
                ).forEach { (field, pair) ->
                    val difference = worstDifference(pair.first, pair.second)
                    compared++
                    worst = maxOf(worst, difference)
                    if (difference > 4) problems += "${cover.name} ($theme) $field: ${hex(pair.first)}, the table says #${pair.second} (off by $difference)"
                }
                if (p.dangerClash != expected.clash) problems += "${cover.name} ($theme) dangerClash: ${p.dangerClash}, the table says ${expected.clash}"
            }
        }
        println("colour delta table: $compared colours, the largest difference is $worst levels")
        assertTrue(problems.isEmpty(), "${problems.size} differences from the table:\n" + problems.joinToString("\n"))
    }

    @Test
    fun glassCarriesItsAlphaAndTheOtherNewColoursAreOpaque() {
        for (cover in covers) {
            val dark = paletteOf(cover, dark = true)
            val light = paletteOf(cover, dark = false)
            assertEquals(Math.round(0.52 * 255).toInt(), dark.glass ushr 24, "${cover.name}: the dark glass is 52 percent")
            assertEquals(Math.round(0.62 * 255).toInt(), light.glass ushr 24, "${cover.name}: the light glass is 62 percent")
            for (p in listOf(dark, light)) {
                listOf(
                    "skelBase" to p.skelBase, "skelShine" to p.skelShine, "danger" to p.danger, "onDanger" to p.onDanger,
                    "dangerContainer" to p.dangerContainer, "onDangerContainer" to p.onDangerContainer,
                ).forEach { (field, colour) -> assertEquals(0xFF, colour ushr 24, "${cover.name}: $field is not opaque") }
            }
        }
    }

    // ---- the clash flag ----

    @Test
    fun aGreyCoverNeverClashesEvenThoughTheApricotHueIsWithinThirtyDegreesOfTheDangerHue() {
        // The fallback apricot sits at hue 52, 27 degrees from the danger hue 25: without the mono guard it would clash.
        val apricotHue = hueOf(apricot)
        assertTrue(hueDistance(apricotHue, 25.0) < 30.0, "the guard is only needed if the apricot is near the danger hue: $apricotHue")
        listOf(true, false).forEach { dark ->
            assertFalse(CoverPalette.fromPixels(IntArray(0), 0, 0, dark = dark).dangerClash, "the fallback palette (dark = $dark)")
            assertFalse(CoverPalette.fromSeed(apricot, null, mono = true, bright = false, dark = dark).dangerClash, "a grey cover (dark = $dark)")
        }
    }

    @Test
    fun theClashIsAHueWithinThirtyDegreesOfTwentyFiveAcrossTheWrap() {
        // Hue distances 27 and 28 clash; 33 and 34 do not, on either side of 25 (the second pair crosses 0 and 360).
        for ((hue, clash) in listOf(52.0 to true, 58.0 to false, 357.0 to true, 351.0 to false, 25.0 to true, 205.0 to false)) {
            val seed = lchColour(0.60, 0.15, hue)
            listOf(true, false).forEach { dark ->
                val p = CoverPalette.fromSeed(seed, null, mono = false, bright = false, dark = dark)
                assertEquals(clash, p.dangerClash, "seed hue $hue (found ${hueOf(seed)}), dark = $dark")
            }
        }
    }

    // ---- the contrast checks of the danger family, for every golden seed ----

    @Test
    fun theDangerColoursHoldTheirContrastForEveryGoldenSeedInBothThemes() {
        var checked = 0
        for (g in goldenSeeds) for (dark in listOf(true, false)) {
            val p = goldenPalette(g, dark)
            val where = "${g.name} (${if (dark) "dark" else "light"})"
            assertAtLeast(4.5, contrast(p.onDanger, p.danger), "$where onDanger on danger")
            assertAtLeast(4.5, contrast(p.onDangerContainer, p.dangerContainer), "$where onDangerContainer on dangerContainer")
            listOf("backgroundBottom" to p.backgroundBottom, "surface" to p.surface, "surfaceVariant" to p.surfaceVariant).forEach { (name, ground) ->
                assertAtLeast(3.0, contrast(p.danger, ground), "$where danger on $name")
            }
            checked++
        }
        assertEquals(goldenSeeds.size * 2, checked)
        assertTrue(checked >= 120, "checked only $checked palettes")
    }
}
