package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules the lyric styles (the stage files and the Style chip) are written under, pinned by reading the source, as
 * [LyricsParityContractTest] does for the list: the clock is read only where a frame redraws without recomposing, a frame
 * allocates nothing, reduced motion is honoured in every style, the axes are stepped between fixed instances, and the panel,
 * the TV and the share video take the styles the way the board places them.
 */
class LyricStylesContractTest {
    private val stageFiles = listOf(
        "CosmoLyricStage.kt", "CosmoLyricOrbital.kt", "CosmoLyricStretch.kt", "CosmoLyricCallAnswer.kt",
        "CosmoLyricPulse.kt", "CosmoLyricPoster.kt",
    )
    private val view = LyricSourceRules.file("CosmoLyricsView.kt")
    private val video = LyricSourceRules.file("CosmoLyricVideo.kt")
    private val chip = LyricSourceRules.file("CosmoLyricStyleChip.kt")

    /** What may open a block a frame redraws without recomposing: the list's openers, and a canvas's draw block. */
    private val readers = listOf("derivedStateOf {", "drawWithContent {", "onDrawWithContent {", "drawBehind {", "drawWithCache {", "onDrawBehind {", "Canvas(")

    private fun readsInDraw(src: String, at: Int): Boolean {
        var lineStart = src.lastIndexOf('\n', at) + 1
        while (lineStart > 0) {
            val line = src.substring(lineStart, src.indexOf('\n', lineStart).let { if (it < 0) src.length else it })
            if (readers.any { line.contains(it) }) return true
            if (Regex("""^\s*(private |internal |public )?(inline )?fun """).containsMatchIn(line)) return false
            lineStart = src.lastIndexOf('\n', lineStart - 2) + 1
        }
        return false
    }

    /** The bodies of the `DrawScope` functions in [src]: the code a frame runs. */
    private fun drawBodies(src: String): List<Pair<String, String>> =
        Regex("""fun (DrawScope|ContentDrawScope)\.(\w+)\(""").findAll(src).map { match ->
            val open = src.indexOf('{', src.indexOf(')', match.range.last))
            var depth = 0
            var end = open
            for (i in open until src.length) {
                if (src[i] == '{') depth++
                if (src[i] == '}') { depth--; if (depth == 0) { end = i; break } }
            }
            match.groupValues[2] to src.substring(open, end + 1)
        }.toList()

    @Test fun theClockIsReadOnlyInDrawLambdasAndDerivedStatesInEveryStage() {
        for (name in stageFiles) {
            val src = LyricSourceRules.file(name)
            val reads = LyricSourceRules.readsOf(src, "clock.longValue")
            assertTrue(reads.all { readsInDraw(src, it) }, "$name reads the clock in composition")
        }
        assertTrue(view.contains("val clock = rememberCosmoLyricClock(trackKey, playhead, isPlaying)"), "a stage runs on the list's own frame clock")
    }

    @Test fun aFrameAllocatesNothing() {
        val forbidden = listOf(".forEach", ".map {", ".filter {", ".any {", ".all {", "listOf(", "mutableListOf", "arrayListOf", "emptyList(", ".toList(", ".firstOrNull {", ".indexOfFirst", ".indexOfLast", "Brush.", "measure(", "ArrayList", "getBoundingBox(")
        val bodies = (stageFiles + "CosmoLyricVideo.kt").flatMap { drawBodies(LyricSourceRules.file(it)) }
        assertTrue(bodies.map { it.first }.containsAll(listOf("drawWordsLit", "drawArcText", "drawOrbital", "drawStretch", "drawPulse", "drawPoster")))
        for ((fn, body) in bodies) {
            for (token in forbidden) assertFalse(body.contains(token), "$fn has $token")
            assertFalse(Regex("""(?<![A-Za-z])Path\(""").containsMatchIn(body), "$fn makes a path")
        }
        // The glow's brush is made once per size, in the draw cache.
        assertTrue(LyricSourceRules.file("CosmoLyricPulse.kt").contains("val glow = pulseGlow(scene, size, ink.accent)"))
    }

    @Test fun everyLineIsLaidOutOnceALineNotOnceAFrame() {
        for (name in listOf("CosmoLyricOrbital.kt", "CosmoLyricStretch.kt", "CosmoLyricPulse.kt", "CosmoLyricPoster.kt")) {
            val src = LyricSourceRules.file(name)
            assertTrue(src.contains("remember(lines, focus"), "$name keys its layout on the line in focus")
        }
        assertTrue(LyricSourceRules.file("CosmoLyricStage.kt").contains("val focus = derivedStateOf { LyricCursor.focus(lines, clock.longValue) }"), "the focus changes once a line")
    }

    @Test fun reducedMotionTurnsMovementOffInEveryStyle() {
        assertTrue(view.contains("if (env.reduced || shown <= 0f || line.isGap) return@drawBehind"), "no moon under the list's line")
        assertTrue(LyricSourceRules.file("CosmoLyricOrbital.kt").contains("moon = !reduced"), "no moon on the ring")
        assertTrue(LyricSourceRules.file("CosmoLyricOrbital.kt").contains("if (reduced) slide.snapTo(1f)"), "no slide from the outer ring")
        assertTrue(LyricSourceRules.file("CosmoLyricStretch.kt").contains("StretchCurve.amount(stage.startMs[w], stage.wordEndMs[w], stage.wordSung(w, positionMs), positionMs, reduced)"), "no stretch")
        val pulse = LyricSourceRules.file("CosmoLyricPulse.kt")
        assertTrue(pulse.contains("val glowScale = if (reduced) 1f else") && pulse.contains("val lineScale = if (reduced) 1f else"), "no swell, no kick")
        assertTrue(pulse.contains("val live = !reduced &&"), "reduced motion does not even read the levels")
        assertTrue(LyricSourceRules.file("CosmoLyricCallAnswer.kt").contains("if (reduced || now == LyricsMetrics.LineState.CURRENT) 1f else 0.97f"))
        assertTrue(LyricSourceRules.file("CosmoLyricPoster.kt").contains("else if (reduced) 1f else"))
    }

    @Test fun stretchStepsBetweenFixedInstancesAndNeverMovesTheAxesPerFrame() {
        val stage = LyricSourceRules.file("CosmoLyricStage.kt")
        assertTrue(stage.contains("val stretch = List(StretchCurve.INSTANCES) { i -> archivo(StageWeights.stretch(i), StretchCurve.width(StretchCurve.instanceAmount(i))) }"))
        assertTrue(stage.contains("fontSynthesis = FontSynthesis.None"), "an instance is asked for by its own weight, never faked")
        assertEquals(1, Regex("FontVariation.Settings").findAll(stage).count(), "one place cuts the instances")
        for (name in stageFiles - "CosmoLyricStage.kt") assertFalse(LyricSourceRules.file(name).contains("FontVariation"), "$name cuts no fonts of its own")
        assertTrue(LyricSourceRules.file("CosmoLyricStretch.kt").contains("scene.layout(w, StretchCurve.instance(amount))"), "a frame picks a laid-out instance")
    }

    @Test fun thePanelOffersTheStyleChipAndPicksLinesInTheList() {
        assertTrue(view.contains("val styleChoice = rememberLyricStyleChoice(trackKey, lines, surface)"))
        assertTrue(view.contains("val shownStyle = if (!offerStyles || share?.picking == true || lines.isEmpty()) LyricStyle.ORBIT_READ else styleChoice.style"), "a pick is made in the list")
        assertTrue(view.contains("styleChip = if (offerStyles && SungFraction.isSynced(lines))"), "the chip only where a style can follow the song")
        assertTrue(chip.contains("OrbitChip(") && chip.contains("OrbitMenu("), "the Orbit kit's chip and menu")
        assertTrue(chip.contains("LyricStyles.resolve(LyricStyle.ofKey(chosen), LyricStyle.ofKey(own), lines, surface)"), "global choice, the song's own, the duet default")
        assertTrue(chip.contains("Extras.settings.setLyricStyleFor(songId, pick.key)") && chip.contains("Extras.settings.setLyricStyle(pick.key)"))
    }

    @Test fun orbitalKeepsTheLineReadableOnTheUpperArcOutsideTheTrack() {
        val orbital = LyricSourceRules.file("CosmoLyricOrbital.kt")
        assertTrue(orbital.contains("LyricArc.fitUpper(widths, widths.size, space, inner, outer)"), "smaller to the floor, then two arcs")
        assertTrue(orbital.contains("LyricArc.upperStart("), "kept to the upper arc")
        assertTrue(orbital.contains("ArcText.build(measurer, it.text, lineStyle, rings.inner, rings.outer)"), "the line on its rings outside the track")
        assertTrue(orbital.contains("LyricArc.x(cx, rings.track, deg)"), "the moon on the track")
        assertTrue(orbital.contains("internal const val ORBITAL_UNLIT_ALPHA = 0.5f") && orbital.contains("maxOf(ink.unlit.alpha, ORBITAL_UNLIT_ALPHA)"), "words to come stay readable")
        assertTrue(orbital.contains("nextRing(rings, arc)"), "the next line's ring clears a line on two arcs too")
    }

    @Test fun orbitReadSetsOnlyTheLineBeingSungInWideArchivo() {
        assertTrue(view.contains("fontFamily = stageFonts.read, fontWeight = FontWeight(StageWeights.READ)"))
        assertTrue(LyricSourceRules.file("CosmoLyricStage.kt").contains("val read = archivo(StageWeights.READ, 112f)"), "wdth 112, wght 800")
        assertTrue(view.contains("Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - fill.value }"), "the rest face gives way as the line becomes current")
        assertTrue(view.contains("Modifier.fillMaxWidth().clearAndSetSemantics { }.graphicsLayer { alpha = fill.value }"), "the sung face is read once")
        assertTrue(view.contains("onTextLayout = { layoutRef.value = it }"), "the fill, the lift, the bloom and the moon follow the sung face")
    }

    @Test fun callAndAnswerNamesOnlyTheVoicesTheSourceNames() {
        val duet = LyricSourceRules.file("CosmoLyricCallAnswer.kt")
        assertTrue(duet.contains("line.singer?.let { name ->"))
    }

    @Test fun pulseListensToTheTapOnlyWhileItShows() {
        val pulse = LyricSourceRules.file("CosmoLyricPulse.kt")
        assertTrue(pulse.contains("val handle = levels.source?.listen()") && pulse.contains("onDispose { handle?.close() }"))
        assertTrue(pulse.contains("PulseMotion.breath(positionMs)"), "a tempo-less breath without levels")
    }

    @Test fun theShareVideoDrawsWithTheStagesOwnScenesAndDrawFunctions() {
        for (call in listOf("PosterScene.build(", "StretchScene.build(", "ArcText.build(", "drawPoster(", "drawStretch(", "drawOrbital(")) {
            assertTrue(video.contains(call), call)
        }
        assertTrue(video.contains("bitmap.readPixels(into, 0, 0, WIDTH, HEIGHT)"))
    }
}
