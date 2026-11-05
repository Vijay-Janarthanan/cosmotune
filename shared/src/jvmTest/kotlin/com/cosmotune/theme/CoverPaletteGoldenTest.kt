package com.cosmotune.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin engine against the designer's reference implementation. `theme-golden/golden.json` holds, for 61 seeds in
 * both themes, every colour that the designer's `dynamic-colour-v3.js` derives (made by `theme-golden/make-golden.mjs`,
 * which runs that file unchanged under Node; v3 only adds fields to v2's engine, so the v2 colours are the v2 file's).
 * Each colour must agree within 1 per channel and its alpha within 1 of 255 (the two are separate implementations of one
 * formula; a 1 can come from `pow` and `cbrt` differing in the last bit before rounding to 8 bits). The v3 colours are
 * `glass` (with its alpha), `skelBase`, `skelShine`, the four danger colours, and the flag `dangerClash`.
 */
class CoverPaletteGoldenTest {

    private val golden: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/theme-golden/golden.json")) { "theme-golden/golden.json is missing" }
            .bufferedReader().use { it.readText() },
    ).jsonObject

    private fun argbOf(hex: String): Int = 0xFF000000.toInt() or hex.removePrefix("#").toInt(16)

    private fun fieldsOf(c: CoverColors): List<Pair<String, Int>> = listOf(
        "seed" to c.seed, "ambient" to c.ambient, "backgroundTop" to c.backgroundTop, "backgroundMid" to c.backgroundMid,
        "backgroundBottom" to c.backgroundBottom, "surface" to c.surface, "surfaceHigh" to c.surfaceHigh,
        "surfaceVariant" to c.surfaceVariant, "textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary,
        "textTertiary" to c.textTertiary, "accent" to c.accent, "accentStrong" to c.accentStrong, "onAccent" to c.onAccent,
        "buttonGradientStart" to c.buttonGradientStart, "buttonGradientEnd" to c.buttonGradientEnd,
        "accentContainer" to c.accentContainer, "onAccentContainer" to c.onAccentContainer, "glow" to c.glow,
        "shadow" to c.shadow, "scrim" to c.scrim, "track" to c.track, "outline" to c.outline,
        "glass" to c.glass, "skelBase" to c.skelBase, "skelShine" to c.skelShine,
        "danger" to c.danger, "onDanger" to c.onDanger, "dangerContainer" to c.dangerContainer,
        "onDangerContainer" to c.onDangerContainer,
    )

    private fun intensityOf(name: String) = when (name) {
        "calm" -> 0.7
        "rich" -> 1.25
        else -> 1.0
    }

    @Test
    fun theGoldenFileHasTheCasesItShould() {
        val cases = golden["cases"]!!.jsonArray
        assertTrue(cases.size >= 40, "about 40 seeds or more, got ${cases.size}")
        assertEquals("ui/v3/dynamic-colour-v3.js", golden["reference"]!!.jsonObject["file"]!!.jsonPrimitive.content)
        val names = cases.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(names.size, names.toSet().size, "case names are unique")
        // Both themes of every case, and the failing-rail list of the reference is empty (the designer's own rails hold on all of them).
        cases.forEach {
            val o = it.jsonObject
            listOf("dark", "light").forEach { theme ->
                assertTrue(o[theme]!!.jsonObject["pass"]!!.jsonPrimitive.boolean, "the reference's own rails fail on ${o["name"]} ($theme)")
                assertTrue(o[theme]!!.jsonObject.containsKey("dangerClash"), "${o["name"]} ($theme) has no dangerClash")
            }
        }
    }

    @Test
    fun everyColourOfEverySeedAgreesWithTheReferenceInBothThemes() {
        val cases = golden["cases"]!!.jsonArray
        val problems = mutableListOf<String>()
        var compared = 0
        var maxDifference = 0
        for (element in cases) {
            val o = element.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val second: Int? = o["second"].let { if (it == null || it is JsonNull) null else argbOf(it.jsonPrimitive.content) }
            val spec = CoverPaletteSpec(intensity = intensityOf(o["intensity"]!!.jsonPrimitive.content))
            for (theme in listOf("dark", "light")) {
                val expected = o[theme]!!.jsonObject
                val actual = CoverPalette.fromSeed(
                    seed = argbOf(o["seed"]!!.jsonPrimitive.content),
                    second = second,
                    mono = o["mono"]!!.jsonPrimitive.boolean,
                    bright = o["bright"]!!.jsonPrimitive.boolean,
                    dark = theme == "dark",
                    spec = spec,
                )
                val alphas: JsonObject = expected["alpha"]?.jsonObject ?: JsonObject(emptyMap())
                for ((field, argb) in fieldsOf(actual)) {
                    val want = argbOf(expected[field]!!.jsonPrimitive.content)
                    val wantAlpha = alphas[field]?.jsonPrimitive?.double ?: 1.0
                    val alphaDifference = abs((argb ushr 24) - wantAlpha * 255.0)
                    var worst = 0
                    for (shift in listOf(16, 8, 0)) worst = maxOf(worst, abs(((argb shr shift) and 0xFF) - ((want shr shift) and 0xFF)))
                    compared++
                    maxDifference = maxOf(maxDifference, worst)
                    if (worst > 1 || alphaDifference > 1.0) {
                        problems += "$name ($theme) $field: Kotlin #${(argb and 0xFFFFFF).toString(16).padStart(6, '0')} " +
                            "alpha ${argb ushr 24}, reference ${expected[field]!!.jsonPrimitive.content} alpha $wantAlpha"
                    }
                }
                assertEquals(o["mono"]!!.jsonPrimitive.boolean, actual.isFallback, "$name: isFallback")
                assertEquals(expected["dangerClash"]!!.jsonPrimitive.boolean, actual.dangerClash, "$name ($theme): dangerClash")
            }
        }
        assertTrue(compared > 3500, "compared only $compared colours")
        assertTrue(problems.isEmpty(), "${problems.size} of $compared colours differ from the reference by more than 1:\n" + problems.take(30).joinToString("\n"))
        println("golden: $compared colours compared, largest per-channel difference $maxDifference")
    }

    @Test
    fun theFadeEasingMatchesTheReference() {
        val ease: JsonArray = golden["ease"]!!.jsonArray
        assertEquals(11, ease.size)
        for (pair in ease) {
            val x = pair.jsonArray[0].jsonPrimitive.double
            val y = pair.jsonArray[1].jsonPrimitive.double
            val actual = CoverPalette.fadeProgress(x.toFloat()).toDouble()
            assertTrue(abs(actual - y) < 1e-4, "ease($x): Kotlin $actual, reference $y")
        }
        assertEquals(0f, CoverPalette.fadeProgress(0f), 1e-6f)
        assertEquals(1f, CoverPalette.fadeProgress(1f), 1e-6f)
    }
}
