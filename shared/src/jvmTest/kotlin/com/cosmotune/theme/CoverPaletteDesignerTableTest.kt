package com.cosmotune.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The designer's twelve-cover table (`ui/v2/01-dynamic-colour.md`, section 6), typed in by hand: the hex values the
 * designer's engine printed for each cover. The table was made from the covers' exact cluster centres; the seeds listed
 * in it are those centres rounded to 8 bits, so the palettes derived from the listed seeds can differ by a few levels
 * (the tolerance below is 4 per channel). The bottom stop's second colour is given by its hue, read from the table's own bottom stop.
 */
class CoverPaletteDesignerTableTest : ThemeTestBase() {

    private class Row(
        val name: String,
        val seed: String,
        val secondHue: Double?,
        val mono: Boolean,
        val dark: Expected,
        val light: Expected,
    )

    private class Expected(val top: String, val mid: String, val bottom: String, val start: String, val end: String, val on: String, val accent: String)

    private val rows = listOf(
        Row("Glasshouse Weather", "466044", null, false,
            Expected("202D20", "161F15", "0E150E", "BFD7A7", "79BC94", "0E130E", "9DC49B"),
            Expected("EEF6EE", "E7F0E6", "F8FBF7", "657E4A", "1F6743", "FFFFFF", "376535")),
        Row("Parallel Rooms", "D4552E", null, false,
            Expected("401E14", "2C150E", "1D0E0A", "F8BDB8", "F48C49", "1D0D08", "F59E84"),
            Expected("FFEFE9", "FFE7E0", "FFF8F5", "B84D4C", "84451A", "FFFFFF", "B54623")),
        Row("Kite Season", "ADD6C8", 25.9, false,
            Expected("1B2D27", "121F1B", "19100F", "A8DCBC", "61BDB3", "0C1311", "9BDAC5"),
            Expected("ECF6F2", "E4F0EC", "FEF9F8", "45805F", "1B625B", "FFFFFF", "297F69")),
        Row("after the rain", "2E7C58", 88.3, false,
            Expected("0B3120", "082115", "181204", "ACDDAE", "51C1A5", "08150E", "7ECBA2"),
            Expected("E8F8EE", "DEF3E6", "FDFAF3", "4F7F52", "1C6352", "FFFFFF", "2E7C58")),
        Row("Ninefold", "2D5A8A", 42.9, false,
            Expected("152A41", "0D1D2D", "1D0E08", "A2D5F8", "8BA9EF", "0A121C", "8BBCF2"),
            Expected("E9F5FF", "E0EFFF", "FFF8F5", "407BA0", "3D5591", "FFFFFF", "2D5A8A")),
        Row("Night Shift Radio", "84755F", 29.1, false,
            Expected("2F281E", "201B14", "171110", "DFCD99", "D49E6E", "14110D", "D1B285"),
            Expected("F7F3ED", "F1ECE5", "FDF9F8", "867339", "7B4C1A", "FFFFFF", "8C672B")),
        Row("Overpass", "F1AD75", null, false,
            Expected("3C2209", "291707", "1C1006", "F8BFA5", "D99E48", "1A0F06", "F1AD75"),
            Expected("FFF0E5", "FCE9DB", "FFF9F4", "A36142", "73501B", "FFFFFF", "9C5E24")),
        Row("Low Tide Hours", "2C4E79", null, false,
            Expected("192A3F", "101C2C", "0B131E", "A6D4F8", "93A8E8", "0B121B", "94BAEC"),
            Expected("EAF4FF", "E2EEFE", "F6FBFF", "467AA0", "42548F", "FFFFFF", "345989")),
        Row("Sunblock (bright and yellow)", "FFE500", null, false,
            Expected("342702", "231B04", "181204", "CBD780", "CEA53C", "141203", "D6C65A"),
            Expected("F9F3E3", "F4ECD8", "FDFAF3", "737C27", "6B551B", "FFFFFF", "7B7024")),
        Row("Paper (grey rule)", "FFB98F", null, true,
            Expected("2C2826", "1E1B1A", "141211", "F8BEAC", "DB9C59", "131110", "FFB98F"),
            Expected("F5F3F2", "EFECEB", "FBFAF9", "A2614D", "794D1A", "FFFFFF", "96572E")),
        Row("Arcade Heat", "FF2D95", 182.7, false,
            Expected("3E1C29", "2A131C", "031714", "F8B7DD", "F48395", "1C0C12", "F696B8"),
            Expected("FFEEF3", "FEE6ED", "F3FCFB", "AB4E89", "9B2241", "FFFFFF", "B33C6F")),
    )

    private fun argb(hex: String) = 0xFF000000.toInt() or hex.toInt(16)

    private fun worstDifference(actual: Int, expected: String): Int {
        val want = argb(expected)
        return listOf(16, 8, 0).maxOf { abs(((actual shr it) and 0xFF) - ((want shr it) and 0xFF)) }
    }

    @Test
    fun everyCoverOfTheDesignersTableComesOutAsPrinted() {
        val problems = mutableListOf<String>()
        var compared = 0
        var worst = 0
        for (row in rows) {
            val seed = argb(row.seed)
            val second = row.secondHue?.let { lchColour(0.55, 0.10, it) }
            val bright = !row.mono && lightness(seed) > 0.86
            for ((theme, expected) in listOf("dark" to row.dark, "light" to row.light)) {
                val p = CoverPalette.fromSeed(seed, second, row.mono, bright, dark = theme == "dark")
                assertRails("${row.name} ($theme)", p, dark = theme == "dark")
                listOf(
                    "top" to (p.backgroundTop to expected.top), "mid" to (p.backgroundMid to expected.mid),
                    "bottom" to (p.backgroundBottom to expected.bottom), "button start" to (p.buttonGradientStart to expected.start),
                    "button end" to (p.buttonGradientEnd to expected.end), "on button" to (p.onAccent to expected.on),
                    "accent" to (p.accent to expected.accent),
                ).forEach { (field, pair) ->
                    val difference = worstDifference(pair.first, pair.second)
                    compared++
                    worst = maxOf(worst, difference)
                    if (difference > 4) problems += "${row.name} ($theme) $field: ${hex(pair.first)}, the table says #${pair.second} (off by $difference)"
                }
            }
        }
        println("designer table: $compared colours, the largest difference is $worst levels")
        assertTrue(problems.isEmpty(), "${problems.size} of $compared colours are off the table by more than 4 levels:\n" + problems.joinToString("\n"))
    }
}
