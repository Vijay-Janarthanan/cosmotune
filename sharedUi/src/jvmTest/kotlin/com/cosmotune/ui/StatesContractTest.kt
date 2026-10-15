package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What no screenshot harness can check about the loading, empty and error states of the new look, read from the source: the three
 * shared functions (`ShimmerBox`, `MessageState`, `SignInBanner`) and the desktop's three pages hand over to ours as their first
 * statement, by name and with every parameter, so with the look off the old code runs untouched; our blocks keep the signatures the
 * brief gives; the shimmer reads its colours only while drawing and stands completely still under reduced motion; and the numbers of
 * `.skel`, `.empty3` and the banner are the design's.
 */
class StatesContractTest {
    private val statesDir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/states/"
    private val skeletonsPath = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Skeletons.kt"
    private val commonPath = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt"
    private val desktopPath = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt"

    private val shimmerSrc = SourceContract.read(statesDir + "CosmoShimmer.kt")
    private val emptySrc = SourceContract.read(statesDir + "CosmoEmptyState.kt")
    private val bannerSrc = SourceContract.read(statesDir + "CosmoSignInBanner.kt")
    private val skeletons = SourceContract.read(skeletonsPath)
    private val common = SourceContract.read(commonPath)
    private val desktop = SourceContract.read(desktopPath)

    private val guard = "if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.states."

    private fun first(src: String, name: String): String = SourceContract.firstStatement(SourceContract.functionBody(src, name))

    // ---- the delegates -----------------------------------------------------------------------------------------------------

    /** The six old functions, where each lives and what it hands over to. */
    private val delegates = listOf(
        Triple("ShimmerBox", "CosmoShimmer", skeletons),
        Triple("MessageState", "CosmoEmptyState", common),
        Triple("SignInBanner", "CosmoSignInBanner", common),
        Triple("DesktopLoadingPage", "CosmoLoadingState", desktop),
        Triple("DesktopErrorPage", "CosmoEmptyState", desktop),
        Triple("DesktopEmptyPage", "CosmoEmptyState", desktop),
    )

    @Test fun eachOldFunctionHandsOverAsItsFirstStatementAndKeepsItsOldBody() {
        for ((old, new, src) in delegates) {
            val statement = first(src, old)
            assertTrue(statement.startsWith("$guard$new("), "$old: the first statement is the delegate to $new, not: $statement")
            val body = SourceContract.functionBody(src, old)
            assertTrue(SourceContract.code(body).length > statement.length + 20, "$old: the old body follows the delegate, untouched")
        }
    }

    @Test fun theDelegatesNameEveryParameterOfTheFunctionTheyReplace() {
        // each passes `name = name` for every parameter, except the two desktop pages whose arguments are built from their own
        for ((old, new, src) in delegates) {
            if (old == "DesktopErrorPage") continue
            val names = SourceContract.names(SourceContract.parameters(src, old))
            SourceContract.assertPassesEveryParameter(first(src, old).substringAfter("$new("), names)
        }
    }

    @Test fun theDesktopErrorPageNamesTheTitleTheMessageAndTheRetry() {
        val statement = first(desktop, "DesktopErrorPage")
        assertEquals(listOf("message: String", "onRetry: () -> Unit"), SourceContract.parameters(desktop, "DesktopErrorPage"))
        assertTrue(statement.contains("title = DesktopStrings[\"d_error_title\", \"Something went wrong\"]"), statement)
        assertTrue(statement.contains("message = message"), statement)
        assertTrue(statement.contains("actionLabel = DesktopStrings[\"d_try_again\", \"Try again\"]"), statement)
        assertTrue(statement.contains("onAction = onRetry"), statement)
    }

    @Test fun theDesktopEmptyPagePassesItsIconToTheFrontTile() {
        assertTrue(first(desktop, "DesktopEmptyPage").contains("icon = icon"), "an icon that is not ours still goes to the front tile")
    }

    @Test fun theOldTextsAreKeptWithTheLookOff() {
        val error = SourceContract.code(SourceContract.functionBody(desktop, "DesktopErrorPage"))
        assertTrue(error.contains("DesktopEmptyPage(Icons.Rounded.Tune, \"Something went wrong\", message, onRetry, \"Try again\")"), "the old error page is still there")
        val loading = SourceContract.code(SourceContract.functionBody(desktop, "DesktopLoadingPage"))
        assertTrue(loading.contains("CircularProgressIndicator(color = DesktopAccent)"), "the old loading page is still there")
        assertTrue(SourceContract.code(SourceContract.functionBody(skeletons, "ShimmerBox")).contains("rememberInfiniteTransition"), "the old shimmer is still there")
        assertTrue(SourceContract.code(SourceContract.functionBody(common, "SignInBanner")).contains("Button(onClick = onSignIn)"), "the old banner is still there")
        assertTrue(SourceContract.code(SourceContract.functionBody(common, "MessageState")).contains("MaterialTheme.typography.bodyLarge"), "the old message state is still there")
    }

    // ---- our blocks keep the old parameter lists --------------------------------------------------------------------------

    @Test fun ourBlocksTakeTheParametersTheBriefGives() {
        assertEquals(listOf("modifier: Modifier = Modifier", "shape: Shape = RoundedCornerShape(CosmoRadii.xs)"), SourceContract.parameters(shimmerSrc, "CosmoShimmer"))
        assertEquals(
            listOf(
                "message: String", "modifier: Modifier = Modifier", "title: String? = null", "icon: ImageVector = CosmoIcons.Play",
                "actionLabel: String? = null", "onAction: (() -> Unit)? = null",
            ),
            SourceContract.parameters(emptySrc, "CosmoEmptyState"),
        )
        assertEquals(listOf("onSignIn: () -> Unit", "modifier: Modifier = Modifier"), SourceContract.parameters(bannerSrc, "CosmoSignInBanner"))
        assertEquals(listOf("label: String", "modifier: Modifier = Modifier"), SourceContract.parameters(emptySrc, "CosmoLoadingState"))
    }

    @Test fun ourBlocksHaveEveryParameterOfTheOldFunctionsInTheSameOrder() {
        for ((old, new, oldSrc, newSrc) in listOf(
            Quad("ShimmerBox", "CosmoShimmer", skeletons, shimmerSrc),
            Quad("SignInBanner", "CosmoSignInBanner", common, bannerSrc),
        )) {
            assertEquals(SourceContract.names(SourceContract.parameters(oldSrc, old)), SourceContract.names(SourceContract.parameters(newSrc, new)), "$new has the parameters of $old")
        }
        // MessageState has no title and no icon: ours puts them after `modifier`, so its other parameters keep their order
        val theirs = SourceContract.names(SourceContract.parameters(common, "MessageState"))
        val ours = SourceContract.names(SourceContract.parameters(emptySrc, "CosmoEmptyState"))
        assertEquals(theirs, ours.filter { it in theirs }, "CosmoEmptyState keeps the order of MessageState's parameters")
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    // ---- the shimmer ---------------------------------------------------------------------------------------------------------

    private val shimmerCode = SourceContract.code(shimmerSrc)

    /** The text of the first `{ ... }` that follows [marker] in [text], braces included. */
    private fun blockAfter(text: String, marker: String): String {
        val from = text.indexOf(marker)
        assertTrue(from >= 0, "`$marker` is there")
        val open = text.indexOf('{', from)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        error("`$marker` is not closed")
    }

    @Test fun theShimmerReadsItsColoursOnlyInItsDrawCache() {
        val sweeping = SourceContract.functionBody(shimmerSrc, "SweepingSkeleton")
        val cache = blockAfter(sweeping, ".drawWithCache")
        val still = SourceContract.functionBody(shimmerSrc, "StillSkeleton")
        val stillDraw = blockAfter(still, ".drawBehind")
        assertTrue(cache.contains("colors.live(CoverField.SKEL_BASE)") && cache.contains("colors.live(CoverField.SKEL_SHINE)"), "the cache reads both skeleton colours")
        assertTrue(stillDraw.contains("colors.live(CoverField.SKEL_BASE)"), "the still fill reads its colour while drawing")
        // nowhere else: not in the composable that calls them, not in the rest of either body, not in the file's other functions
        assertFalse(sweeping.replace(cache, "").contains("live("), "no live colour outside the cache")
        assertFalse(still.replace(stillDraw, "").contains("live("), "no live colour outside the draw lambda")
        assertFalse(SourceContract.functionBody(shimmerSrc, "CosmoShimmer").contains("live("), "the composable body reads no live colour")
        assertEquals(3, Regex("""\blive\(""").findAll(shimmerCode).count(), "three reads in the whole file: base and shine in the cache, base in the still fill")
        // the palette is not read for a colour either: the fade must repaint, not recompose
        assertFalse(shimmerCode.contains(".palette"), "no composition-time palette colour")
        assertFalse(Regex("""Color\(0x|Color\.(White|Black|Gray|Red|Green|Blue)""").containsMatchIn(shimmerCode), "no colour picked by hand")
    }

    @Test fun theSweepIsReadOnlyInTheDrawLambdaAndNeverInComposition() {
        val sweeping = SourceContract.functionBody(shimmerSrc, "SweepingSkeleton")
        val cache = blockAfter(sweeping, ".drawWithCache")
        val draw = blockAfter(cache, "onDrawBehind")
        assertTrue(draw.contains("sweep.value"), "the sweep is read in the draw lambda")
        assertEquals(1, Regex("""sweep\.value""").findAll(shimmerCode).count(), "and nowhere else")
        assertFalse(sweeping.replace(draw, "").contains("sweep.value"), "no read in the composition or the cache")
        assertFalse(shimmerCode.contains("by rememberInfiniteTransition") || Regex("""val sweep by """).containsMatchIn(shimmerCode), "the state is not delegated into the composable's scope")
        // nothing is allocated per frame: the brush and the line are made in the cache, the draw lambda only moves the picture
        assertFalse(Regex("""Brush\.|Offset\(line|FloatArray|listOf\(|arrayOf\(|Pair""").containsMatchIn(draw.replace("Offset(-x, 0f)", "")), "the draw lambda allocates nothing but a stack Offset")
    }

    @Test fun underReducedMotionTheShimmerIsAStillFillWithNoAnimation() {
        val body = SourceContract.functionBody(shimmerSrc, "CosmoShimmer")
        val statement = SourceContract.firstStatement(body)
        assertTrue(statement.startsWith("if (LocalReducedMotion.current) StillSkeleton(modifier, shape) else SweepingSkeleton(modifier, shape)"), statement)
        val still = SourceContract.functionBody(shimmerSrc, "StillSkeleton")
        for (word in listOf("rememberInfiniteTransition", "animate", "Animatable", "LaunchedEffect", "withFrame", "infiniteRepeatable", "tween", "sweep")) {
            assertFalse(still.contains(word), "the still skeleton has no `$word`")
        }
        assertTrue(still.contains("drawRect(colors.live(CoverField.SKEL_BASE))"), "it is one skelBase fill")
        assertTrue(SourceContract.functionBody(shimmerSrc, "SweepingSkeleton").contains("rememberInfiniteTransition"), "the other branch is the moving one")
    }

    @Test fun theShimmerIsTheDesignsSkelClippedToTheCallersShape() {
        // `.skel`: linear-gradient(100deg, skel 30%, skel-shine 50%, skel 70%); background-size: 300% 100%; 1400 ms linear infinite;
        // @keyframes shimmer: background-position 100% -> -50%
        assertTrue(shimmerCode.contains("private const val SHIMMER_MS = 1400"))
        assertTrue(shimmerCode.contains("private const val GRADIENT_ANGLE_DEG = 100f"))
        assertTrue(shimmerCode.contains("private const val STOP_BEFORE = 0.30f") && shimmerCode.contains("private const val STOP_SHINE = 0.50f") && shimmerCode.contains("private const val STOP_AFTER = 0.70f"))
        assertTrue(shimmerCode.contains("private const val IMAGE_WIDTHS = 3f"))
        assertTrue(shimmerCode.contains("private const val POSITION_FROM = 1.0f") && shimmerCode.contains("private const val POSITION_TO = -0.5f"))
        val sweeping = SourceContract.functionBody(shimmerSrc, "SweepingSkeleton")
        assertTrue(sweeping.contains("tween(SHIMMER_MS, easing = LinearEasing)") && sweeping.contains("infiniteRepeatable("), "linear, repeating")
        assertTrue(sweeping.contains("STOP_BEFORE to base, STOP_SHINE to shine, STOP_AFTER to base"), "skelBase, skelShine, skelBase")
        assertTrue(sweeping.contains(".clip(shape)"), "clipped to the caller's shape")
        assertTrue(SourceContract.functionBody(shimmerSrc, "StillSkeleton").contains(".clip(shape)"), "also still")
    }

    // ---- the empty state -----------------------------------------------------------------------------------------------------

    private val emptyCode = SourceContract.code(emptySrc)

    @Test fun theEmptyStateIsThreeTilesAndTheDesignsNumbers() {
        // `.empty3`: art 120 x 96; three 64 dp tiles of radius 18; (8, 22, -12 deg, 60 %), (48, 18, +10 deg, 80 %), front (28, 8)
        for (line in listOf(
            "ART_WIDTH = 120.dp", "ART_HEIGHT = 96.dp", "TILE_SIZE = 64.dp", "TILE_RADIUS = 18.dp", "ICON_SIZE = 28.dp",
            "GAP = 10.dp", "PADDING_V = 48.dp", "PADDING_H = 22.dp", "MESSAGE_MAX_WIDTH = 300.dp", "FRONT_LEFT = 28.dp", "FRONT_TOP = 8.dp",
        )) assertTrue(emptyCode.contains("private val $line"), line)
        assertTrue(emptyCode.contains("BackTile(left = 8.dp, top = 22.dp, degrees = -12f, alpha = 0.6f)"))
        assertTrue(emptyCode.contains("BackTile(left = 48.dp, top = 18.dp, degrees = 10f, alpha = 0.8f)"))
        val art = SourceContract.functionBody(emptySrc, "EmptyArt")
        assertTrue(art.contains("drawRect(tonalFill(colors), alpha = tile.alpha)"), "the back tiles are the tonal fill at 60 and 80 %")
        assertTrue(art.contains("drawRect(heroFill(colors))"), "the front tile is the play gradient")
        assertTrue(art.contains("tint = onAccent") && art.contains("Modifier.size(ICON_SIZE)"), "the icon is 28 dp in onAccent")
        // P4-19: one shape for the three tiles, made once
        assertTrue(emptyCode.contains("private val TILE_SHAPE = SuperellipseShape(TILE_RADIUS)"), "the tiles are superellipses of 18")
        assertEquals(2, Regex("""\.clip\(TILE_SHAPE\)""").findAll(art).count(), "the two turned tiles (one loop) and the front tile take that shape")
        assertTrue(art.contains("for (tile in BACK_TILES)") && emptyCode.contains("private val BACK_TILES = listOf("), "two back tiles")
    }

    @Test fun theEmptyStateReadsItsFillsWhileDrawingAndPicksNoColourByHand() {
        val art = SourceContract.functionBody(emptySrc, "EmptyArt")
        // P4-19: the back tiles draw their fill and their edge in a draw cache
        val withoutDraws = art.replace(blockAfter(art, ".drawWithCache {"), "").replace(blockAfter(art, ".drawBehind { drawRect(heroFill"), "")
        assertFalse(withoutDraws.contains("live("), "no live colour in the composition")
        assertFalse(Regex("""Color\(0x|Color\.(White|Black|Gray|Red|Green|Blue)""").containsMatchIn(emptyCode), "no colour picked by hand")
        assertFalse(Regex("""remember\s*\{[^}]*(Brush|SolidColor)""").containsMatchIn(emptyCode), "no brush is remembered")
    }

    @Test fun theEmptyStateTextAndAction() {
        val body = SourceContract.functionBody(emptySrc, "CosmoEmptyState")
        assertTrue(body.contains("if (title != null)"), "the title only when given")
        assertTrue(body.contains("semantics { heading() }"), "the title is a heading")
        assertTrue(body.contains("Color(palette.textSecondary)") && body.contains("Modifier.widthIn(max = MESSAGE_MAX_WIDTH)"), "the message: textSecondary, at most 300 dp")
        assertTrue(body.contains("TextAlign.Center"), "centred")
        assertTrue(body.contains("if (actionLabel != null && onAction != null)") && body.contains("CosmoButton(text = actionLabel, onClick = onAction, kind = CosmoButtonKind.PRIMARY)"), "the action is a primary button")
        assertTrue(body.contains("Arrangement.spacedBy(GAP)") && body.contains("padding(horizontal = PADDING_H, vertical = PADDING_V)"))
        assertTrue(emptyCode.contains("title(TypeScale.TITLE_M).copy(fontSize = 22.sp"), "the title is Archivo, 22 sp")
        assertTrue(emptyCode.contains("fontSize = 14.sp"), "the message is 14 sp")
    }

    @Test fun theLoadingPageIsABlobOverItsLabel() {
        val body = SourceContract.functionBody(emptySrc, "CosmoLoadingState")
        assertTrue(body.contains("CosmoLoadingBlob(Modifier.size(LOADING_BLOB))") && emptyCode.contains("private val LOADING_BLOB = 40.dp"), "a 40 dp blob")
        assertTrue(emptyCode.contains("private val LOADING_GAP = 14.dp") && body.contains("Arrangement.spacedBy(LOADING_GAP)"), "14 dp apart")
        assertTrue(body.contains("Color(palette.textSecondary)") && body.contains("Alignment.Center") && body.contains("fillMaxSize()"), "centred, the label in textSecondary")
    }

    // ---- the sign-in banner --------------------------------------------------------------------------------------------------

    @Test fun theBannerIsABTileWithTheTwoTextsAndASmallPrimaryButton() {
        val code = SourceContract.code(bannerSrc)
        val body = SourceContract.functionBody(bannerSrc, "CosmoSignInBanner")
        assertTrue(code.contains("BANNER_RADIUS = 24.dp") && body.contains(".cosmoTile(SuperellipseShape(BANNER_RADIUS))"), "a tile of 24, the tile fill")
        assertTrue(body.contains(".padding(horizontal = CosmoSpacing.margin, vertical = BANNER_VERTICAL)") && code.contains("BANNER_VERTICAL = 8.dp"), "16 dp from the sides, 8 above and below")
        assertTrue(code.contains("BANNER_PADDING = 16.dp") && body.contains(".padding(BANNER_PADDING)"), "padding 16")
        assertTrue(body.contains("Res.string.sign_in_youtube_music") && body.contains("Res.string.personalized_recommendations") && body.contains("Res.string.sign_in)"), "the three existing strings")
        assertTrue(body.contains("type.rowTitleStyle()") && body.contains("type.rowSubtitleStyle()") && body.contains("maxLines = 1"), "15.5 sp 650 over a one-line 13 sp")
        assertTrue(body.contains("Color(palette.textSecondary)"), "the reason in textSecondary")
        assertTrue(body.contains("CosmoButton(text = stringResource(Res.string.sign_in), onClick = onSignIn, kind = CosmoButtonKind.PRIMARY, size = CosmoButtonSize.SMALL)"), "the end of the row")
        assertFalse(Regex("""Color\(0x|Color\.(White|Black|Gray|Red|Green|Blue)""").containsMatchIn(code), "no colour picked by hand")
        assertFalse(code.contains("live("), "no live colour: the tile reads its own")
    }

    @Test fun noShimmerEmptyOrBannerFileUsesAnInventedString() {
        // every text is an existing resource key or a caller's argument: no literal user-facing string in the three files
        for (src in listOf(shimmerSrc, emptySrc, bannerSrc)) {
            val literals = Regex(""""[^"\n]{3,}"""").findAll(SourceContract.code(src)).map { it.value }.filter { it != "\"skeleton\"" && it != "\"sweep\"" }.toList()
            assertTrue(literals.isEmpty(), "no string literal in the states files: $literals")
        }
    }
}
