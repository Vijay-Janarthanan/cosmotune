package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reading the lyric files of the new Now Playing as text, for the rules a test cannot see without a window. */
internal object LyricSourceRules {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    fun raw(name: String): String =
        File(root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/$name").readText().replace("\r\n", "\n")

    /** The file without its comments (blocks and line comments), so a rule about code is not tripped by a sentence about it. */
    fun code(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    fun file(name: String): String = code(raw(name))

    /** Every lyric file of the new view: what draws the lines, the voices, the effects and the sharing. */
    val lyricFiles = listOf("CosmoLyricsView.kt", "CosmoLyricVoices.kt", "CosmoLyricEffects.kt", "CosmoLyricShare.kt")

    /** What may open a block that runs in the draw phase, or in a derived state that is not read in composition per frame. */
    private val readers = listOf("derivedStateOf {", "drawWithContent {", "onDrawWithContent {", "drawBehind {", "drawWithCache {")

    /** The places in [src] where [token] is read, as offsets. */
    fun readsOf(src: String, token: String): List<Int> = Regex(Regex.escape(token)).findAll(src).map { it.range.first }.toList()

    /**
     * Whether the read at [at] is inside a block a frame does not recompose: walking up from it, a line that opens a derived state
     * or a draw lambda comes before the line that opens the function it is in.
     */
    fun readsInDrawOrDerived(src: String, at: Int): Boolean {
        var lineStart = src.lastIndexOf('\n', at) + 1
        while (lineStart > 0) {
            val line = src.substring(lineStart, src.indexOf('\n', lineStart).let { if (it < 0) src.length else it })
            if (readers.any { line.contains(it) }) return true
            if (Regex("""^\s*(private |internal |public )?(inline )?fun """).containsMatchIn(line)) return false
            lineStart = src.lastIndexOf('\n', lineStart - 2) + 1
        }
        return false
    }
}

/**
 * What cannot be seen without a window, pinned by reading the source (there is no Compose UI test harness here and none is to be
 * added): the rules the features the new lyrics view took over from the old one (Task P3-13) were written under. What can be
 * seen is in [LyricsWordEffectsTest]; the numbers and decisions are in `shared`.
 */
class LyricsParityContractTest {
    private val view = LyricSourceRules.file("CosmoLyricsView.kt")
    private val voices = LyricSourceRules.file("CosmoLyricVoices.kt")
    private val effects = LyricSourceRules.file("CosmoLyricEffects.kt")
    private val share = LyricSourceRules.file("CosmoLyricShare.kt")
    private val screen = LyricSourceRules.file("CosmoNowPlaying.kt")

    // ---- the rules of the first view, which the new features must keep ------------------------------------------------------

    @Test fun theClockIsReadOnlyInDerivedStatesAndDrawLambdasInEveryLyricFile() {
        for (name in LyricSourceRules.lyricFiles) {
            val src = LyricSourceRules.file(name)
            val reads = LyricSourceRules.readsOf(src, "clock.longValue") + LyricSourceRules.readsOf(src, "positionMs")
            assertTrue(LyricSourceRules.readsOf(src, "clock.longValue").all { LyricSourceRules.readsInDrawOrDerived(src, it) }, "$name reads the clock in composition")
            assertTrue(reads.isNotEmpty() || name == "CosmoLyricShare.kt", "$name is expected to say something about the clock")
        }
    }

    @Test fun theLyricsReadThePaletteOncePerCoverNeverPerFrame() {
        for (name in LyricSourceRules.lyricFiles) assertFalse(LyricSourceRules.file(name).contains(".live("), "$name")
    }

    @Test fun nothingIsAllocatedPerFrameInTheDrawCode() {
        // the effects file is all draw code: no list is built, walked with an iterator or mapped in it
        for (forbidden in listOf(".forEach", ".map {", ".filter {", ".any {", ".all {", "listOf(", "mutableListOf", "arrayListOf", "emptyList(", ".toList(", ".firstOrNull {", ".indexOfFirst", ".indexOfLast")) {
            assertFalse(effects.contains(forbidden), "CosmoLyricEffects.kt has $forbidden")
        }
        // the two blocks the platform's shifted clip wants are made once with the lifter, and slices pass them on
        assertTrue(effects.contains("private val flat: DrawScope.() -> Unit") && effects.contains("private val swelled: DrawScope.() -> Unit"))
        assertEquals(0, Regex("""clipShiftedDown\([^)]*\)\s*\{""").findAll(effects).count(), "no lambda is made per slice")
        // one letter in hand, reused
        assertEquals(1, Regex("""CharGrowth\(\)""").findAll(effects).count())
        // the fill's brushes are built once per size, in a draw cache, and the frame only draws them
        assertTrue(view.contains("val feather = if (wordSynced) featherEdge() else null"))
        assertTrue(voices.contains("val feather = featherEdge()"))
        assertEquals(2, Regex("""Brush\.""").findAll(effects).count(), "the two feather brushes, in featherEdge")
    }

    // ---- duets -----------------------------------------------------------------------------------------------------------------

    @Test fun aDuetIsLaidOutOnTwoSidesAgainstTheEdgeEachVoiceIsWrittenFrom() {
        assertTrue(view.contains("val duet = remember(lines) { LyricDuet.isDuet(lines) }"), "only a song that names a second voice")
        assertTrue(view.contains("val onEnd = LyricDuet.onEnd(env.duet, line)"))
        assertTrue(view.contains("val align = if (onEnd) TextAlign.End else TextAlign.Start"), "the words are aligned with the side")
        assertTrue(view.contains("LyricsMetrics.layoutWidthPx(constraints.maxWidth, lanePx, env.style.currentScale)"), "the lane is kept clear")
        assertTrue(view.contains("LyricsMetrics.placeOffsetPx(constraints.maxWidth, placeable.width, onEnd)"), "placed against the end edge")
        assertTrue(view.contains("TransformOrigin(LyricsMetrics.originFraction(onEnd, env.rtl), 0.5f)"), "scaled from the edge it is written from")
        assertTrue(view.contains("val laneDp = LyricDuet.laneDp(env.duet)"))
    }

    @Test fun aVoiceThatIsStillBeingSungWhileTheNextBeginsIsCurrentToo() {
        assertTrue(view.contains("val activity = remember(lines) { LyricActivity(lines) }"))
        assertTrue(view.contains("LyricsMetrics.lineState(index, current, overlaps && env.activity.isActive(index, current, env.clock.longValue))"))
        assertTrue(view.contains("val overlaps = remember(env.activity, index) { env.activity.mayOverlap(index) }"), "a line that cannot overlap never reads the clock for it")
        assertTrue(view.contains("activity.firstActive(current.value, clock.longValue)"), "the uppermost unfinished vocal is where the list follows")
        assertTrue(view.contains("snapshotFlow { anchor.value }"))
    }

    // ---- the second voice ------------------------------------------------------------------------------------------------------

    @Test fun theSecondVoiceOpensUnderItsLeadOnItsOwnClockAndIsDrawnSmallerAndDimmer() {
        assertTrue(view.contains("BackingVoice(line.background!!, backing, env,"), "a row with a second voice draws it under the lead")
        assertTrue(view.contains("val backing = remember(line) { line.background?.let { LyricBacking.display(it) } }"), "the brackets are left off, once per line")
        assertTrue(voices.contains("LyricBacking.wanted(raw, env.synced, picking, leadActive.value,"))
        assertTrue(voices.contains("if (raw.isWordSynced) env.clock.longValue else 0L"), "a voice that borrows the lead's reads no clock")
        assertTrue(voices.contains("env.type.title(LyricsMetrics.backingStep(env.style.baseStep))"), "two steps down")
        assertTrue(voices.contains("CosmoMotion.slow(env.reduced)"), "it opens on the slow spring, reduced motion in a short fade")
        assertTrue(voices.contains("LyricsMetrics.backingFade(open) * LyricsMetrics.BACKING_ALPHA"), "dimmer than a line still to come")
        assertTrue(voices.contains("val fillOn by remember { derivedStateOf { timed && open.value > 0f } }"), "a shut voice has no accent copy and asks the clock for nothing")
        assertTrue(voices.contains("sweepWords(layoutRef.value, shown, env.clock.longValue, feather)"), "filled on its own words")
        assertFalse(voices.contains("WordLifter"), "the lift and the bloom are the lead's")
        assertTrue(voices.contains("LyricsMetrics.originFraction(onEnd, env.rtl)"), "a duet's second voice grows out of its own margin")
    }

    // ---- per-word effects ------------------------------------------------------------------------------------------------------

    @Test fun aLineWithWordTimingsFillsAWordAtATimeWithAFeatheredEdgeAndOthersFillOverTheirSlot() {
        assertTrue(view.contains("val wordSynced = synced && line.isWordSynced"))
        assertTrue(view.contains("if (wordSynced) CompositingStrategy.Offscreen else CompositingStrategy.Auto"), "the feather erases into a layer of its own")
        assertTrue(view.contains("sweepWords(layoutRef.value, line, env.clock.longValue, feather)"))
        assertTrue(view.contains("sweepFill(layoutRef.value, SungFraction.of(env.lines, index, env.clock.longValue))"))
        assertTrue(effects.contains("WordFill.revealedChars(line, positionMs)"), "the word-fill maths is shared's")
        assertTrue(effects.contains("WordFill.pieceAt(spans, chars)") && effects.contains("WordFill.sitsOnRow("))
        assertTrue(effects.contains("blendMode = BlendMode.DstIn"))
    }

    @Test fun theWordBeingSungLiftsAndAHeldNoteSwellsAndBothStandDownWithReducedMotion() {
        assertTrue(view.contains("val lifter = remember(line) { if (wordSynced) WordLifter(line) else null }"))
        assertTrue(view.contains("if (lifter != null && !reduced)"), "no lift with reduced motion")
        assertTrue(view.contains("if (layout != null && moving.value && WordFill.isLifted(line, env.clock.longValue))"), "a line not in motion reads no clock")
        assertTrue(view.contains("lifter.lift(this, layout, env.clock.longValue)"))
        assertTrue(view.contains("val bloom = !reduced && !reduceDynamicBlur && lyricsBlur && renderEffectBlurSupported"), "the old settings, as the old panel read them")
        assertTrue(view.contains("env.bloom && lifter?.hasHeldWords == true && lineState == LineState.CURRENT"), "a bloom only for a line with a held word")
        assertTrue(effects.contains("import com.music.bitchord.ui.player.clipShiftedDown"), "the platform's sub-pixel shift is reused")
        assertTrue(effects.contains("LyricsMetrics.WORD_RISE_DP.dp.toPx()"))
        assertTrue(effects.contains("word.sampleInto(char - wordFirst, positionMs, growth)"))
    }

    @Test fun theBloomIsABlurredCopyBehindTheWordsWithRoomOfItsOwnThatTakesNoSpaceInTheRow() {
        assertTrue(view.contains(".blur(LyricsMetrics.GLOW_RADIUS_DP.dp, BlurredEdgeTreatment.Unbounded)"))
        assertTrue(view.contains("layout((placeable.width - 2 * room).coerceAtLeast(0), (placeable.height - 2 * room).coerceAtLeast(0)) { placeable.place(-room, -room) }"))
        assertTrue(view.indexOf("Bloom(line, textStyle, layoutRef, env, lifter, glow)") in 0 until view.indexOf("color = baseInk,"), "behind the words")
    }

    // ---- the romanisation and translation --------------------------------------------------------------------------------------

    @Test fun theSubLineOpensOutOfTheLineAboveItAndTheMotesRiseWhenItChanges() {
        assertTrue(view.contains(".revealBelow(env.subReveal.progress)"))
        assertTrue(view.contains("val subReveal = rememberSubReveal(subLines, trackKey, reduced)"))
        assertTrue(voices.contains("SubReveal.plan(reduced, target != null, shown.value != null, shown.value === target, progress.value)"))
        assertTrue(view.contains("translationParticles({ subLayoutRef.value }, env.particles.takeIf { particlesOn }, ink.accent)"))
        assertTrue(view.contains("if (synced) abs(index - env.anchor.value) <= 1 else index < 4"), "only the rows in front of the reader get the pass")
        assertTrue(view.contains("LyricsTranslationMotion("), "the motion's clock is outside the lyrics")
        assertTrue(view.contains("particles = particleProgress"))
        assertTrue(effects.contains("if (progress == null) return this"), "not even a modifier while nothing moves")
    }

    @Test fun theTogglesShowALookupInFlightAndALyricAlreadyInTheLanguage() {
        assertTrue(view.contains("val loading = state is LyricsTranslationUiState.Loading"))
        assertTrue(view.contains("val dimmed = state is LyricsTranslationUiState.SameLanguage"))
        assertTrue(view.contains("on = showing || loading"))
        assertTrue(view.contains("CircularProgressIndicator(progress = { SPINNER_STILL }"), "a still arc with reduced motion")
        assertTrue(view.contains("translation.romanizationState, translation.showingRomanization") && view.contains("translation.translationState, translation.showingTranslation"))
    }

    @Test fun aSectionMarkerIsAChipAndABreakInAPlainSongIsRoom() {
        assertTrue(view.contains("if (LyricRows.isHeader(line, synced))"))
        assertTrue(view.contains("LyricSectionHeader(line.text, first = index == 0, env = env)"))
        assertTrue(view.contains("Spacer(Modifier.height(LyricsMetrics.LINE_GAP_DP.dp))"))
    }

    // ---- sharing ---------------------------------------------------------------------------------------------------------------

    @Test fun theChoiceIsTheOldPanelsPickerAndTheCardIsThePlatformsSheet() {
        assertTrue(share.contains("rememberLyricsPicker("), "the old state machine, its budget and its card order, reused")
        assertTrue(share.contains("lyricsShareAvailable"), "only where a card can be made")
        assertTrue(share.contains("PlayerPlatform.host.LyricsShareSheet(hazeState = hazeState, request = request, onDismiss = share::dismissCard)"))
        for (arg in listOf("song = song", "lines = lyrics", "subLines = subLines", "artworkUrl = artworkUrl", "haptics = haptics")) assertTrue(share.contains(arg), arg)
    }

    @Test fun backReachesTheCardThenThePickBeforeAnythingElse() {
        val handlers = Regex("""PlayerBackHandler\(enabled = ([\w.!= ]+)\)""").findAll(share).map { it.groupValues[1] }.toList()
        assertEquals(listOf("share.request != null", "share.picking"), handlers, "a later registration is reached first: the pick, then the card")
        // the host is composed after the sheets, so it is registered after them
        assertTrue(screen.indexOf("CosmoLyricShareHost(lyricShare, playerHaze)") > screen.indexOf("NowPlayingSheets(\n"), "after the sheets")
        assertTrue(screen.contains("val lyricShare = rememberCosmoLyricShare(song, lyrics, lyricsTranslation.subLines, remoteArt, haptics)"))
        assertTrue(screen.contains("share = lyricShare"))
    }

    @Test fun aTapAddsALineToAPickAndALongPressStartsOneWhereACardCanBeMade() {
        assertTrue(view.contains("LyricRows.onLongPress(canPick, synced, picking)"))
        assertTrue(view.contains("onLongClick = if (longPress == LyricRows.OnLongPress.PICK) ({ env.selection.onPick(index) }) else null"))
        assertTrue(view.contains("LyricRows.OnTap.TOGGLE_PICK -> env.selection.onToggle(index)"))
        assertTrue(view.contains("enabled = LyricRows.responds(synced, picking)"))
        assertTrue(view.contains("val following = !browsing && !selection.picking"), "the list stays put while lines are being picked")
        assertTrue(view.contains("LyricsPickFooter(shareEnabled = share.shareEnabled, onCancel = share::cancel, onShare = share::share)"), "Cancel and Share where the status line was")
        assertTrue(view.contains("latestShare?.cancel()"), "a pick is dropped when the lyrics close")
        assertTrue(view.contains("LyricRows.breakSeeks(synced, env.selection.picking)"))
    }

    @Test fun theChosenLinesAreLitWithTheCoversColourAndTheOthersStepBack() {
        assertTrue(view.contains("LyricRows.Mark.PICKED -> ink.accent.copy(alpha = PICKED_WASH)"))
        assertTrue(view.contains("LyricRows.Mark.OTHER -> ink.primary.copy(alpha = OTHER_WASH)"))
        assertTrue(view.contains("semantics { selected = mark == LyricRows.Mark.PICKED }"))
        assertTrue(share.contains("CosmoButton(stringResource(Res.string.cancel)") && share.contains("CosmoButtonKind.PRIMARY"), "B buttons from the new components")
        assertTrue(share.contains("enabled = shareEnabled"))
    }
}
