package com.cosmotune.ui

import com.cosmotune.design.BlockMetrics
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What no screenshot can check, read from the sources of the content blocks (rows, cards, shelves, headers, tiles, moods and the
 * search field): the two delegates in the upstream files, the draw-phase rule for the live palette, the roles and the 48 dp
 * targets, the swipe numbers the song row inherits, and the signatures other screens call.
 */
class BlocksStructureTest {
    private val dir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/blocks"
    private val blocks = listOf("CosmoListRow", "CosmoSongRow", "CosmoCards", "CosmoHeaders", "CosmoTiles", "CosmoMood", "CosmoSearchField")

    private fun block(name: String): String {
        val file = File(SourceContract.root, "$dir/$name.kt")
        if (!file.isFile) fail("$dir/$name.kt is not where this test expects it")
        return SourceContract.read("$dir/$name.kt")
    }

    private val common = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt"
    private val searchField = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SearchField.kt"

    // ---- the delegates ----

    @Test fun theSongRowDelegateIsTheFirstStatementAndPassesEveryParameterButTheColourKnobs() {
        val src = SourceContract.read(common)
        val statement = SourceContract.firstStatement(SourceContract.functionBody(src, "SongRow"))
        assertTrue(
            statement.startsWith("if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.blocks.CosmoSongRow("),
            "the first statement of SongRow is the delegate, but it is: $statement",
        )
        val knobs = setOf("rowBackground", "subtitleColor", "activeTint", "searchPlayingStyle")
        val names = SourceContract.names(SourceContract.parameters(src, "SongRow"))
        assertTrue(knobs.all { it in names }, "the four colour knobs are still SongRow parameters: $names")
        SourceContract.assertPassesEveryParameter(statement, names.filterNot { it in knobs })
        for (knob in knobs) assertTrue(!Regex("""\b$knob\b""").containsMatchIn(statement), "the delegate does not pass $knob: the palette decides")
    }

    @Test fun theDelegateOfTheSongRowCallsAFunctionThatTakesWhatItPasses() {
        val src = SourceContract.read(common)
        val passed = SourceContract.names(SourceContract.parameters(src, "SongRow")).filterNot { it in setOf("rowBackground", "subtitleColor", "activeTint", "searchPlayingStyle") }
        val taken = SourceContract.names(SourceContract.parameters(block("CosmoSongRow"), "CosmoSongRow"))
        assertTrue(taken.containsAll(passed), "CosmoSongRow takes ${passed - taken.toSet()} too")
    }

    @Test fun theSearchFieldDelegateIsTheFirstStatementAndPassesEveryParameter() {
        val src = SourceContract.read(searchField)
        val statement = SourceContract.firstStatement(SourceContract.functionBody(src, "SearchField"))
        assertTrue(
            statement.startsWith("if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.blocks.CosmoSearchField("),
            "the first statement of SearchField is the delegate, but it is: $statement",
        )
        val names = SourceContract.names(SourceContract.parameters(src, "SearchField"))
        assertEquals(setOf("query", "onQueryChange", "onSubmit", "focusRequester", "placeholder", "modifier"), names.toSet())
        SourceContract.assertPassesEveryParameter(statement, names)
        val taken = SourceContract.names(SourceContract.parameters(block("CosmoSearchField"), "CosmoSearchField"))
        assertTrue(taken.containsAll(names), "CosmoSearchField takes ${names - taken.toSet()} too")
    }

    // ---- the draw-phase rule ----

    /** The text before the `{` of every block that encloses [index], innermost first (braces in string templates balance). */
    private fun enclosingHeads(code: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += code.substring(maxOf(0, i - 120), i)
            }
            i--
        }
        return heads
    }

    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer)\s*(\([^)]*\))?\s*$""")
    private val topLevelFun = Regex("""(?m)^(?:(?:internal|private|public)\s+)?fun\s+(?:<[^>]*>\s*)?([\w.<>?]+\.)?\w+\s*\(""")

    /** Whether [index] is inside a top-level function whose receiver is `DrawScope`: it can only be called while drawing. */
    private fun inDrawScopeFunction(code: String, index: Int): Boolean {
        val header = topLevelFun.findAll(code).lastOrNull { it.range.first <= index } ?: return false
        return header.groupValues[1] == "DrawScope."
    }

    @Test fun theLivePaletteIsReadOnlyWhileDrawing() {
        for (name in blocks) {
            val code = SourceContract.code(block(name))
            var from = 0
            while (true) {
                val at = code.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                // `colors.live(` or `c.live(`; not a longer name that ends in "live"
                if (at > 0 && (code[at - 1].isLetterOrDigit() || code[at - 1] == '_')) continue
                val inDrawLambda = enclosingHeads(code, at).any { drawHead.containsMatchIn(it) }
                assertTrue(inDrawLambda || inDrawScopeFunction(code, at), "$name: live( at ${code.take(at).count { it == '\n' } + 1} is neither in a draw lambda nor in a DrawScope function")
            }
        }
    }

    @Test fun noBrushOrLiveColourIsRememberedAndNoColourIsPickedByHand() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\(|cssGradient|Fill\()""")
        for (name in blocks) {
            val code = SourceContract.code(block(name))
            assertTrue(remembering.find(code) == null, "$name remembers a brush or a live colour: ${remembering.find(code)?.value}")
            assertTrue(!Regex("""Color\(0x""").containsMatchIn(code), "$name picks a colour by hand")
            assertTrue(!Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b""").containsMatchIn(code), "$name uses a named colour")
        }
    }

    // ---- roles and targets ----

    @Test fun everyClickableBlockHasARole() {
        // the row and the card: a semantics modifier with the role, over the hold-aware click
        for (name in listOf("CosmoListRow", "CosmoCards")) {
            val src = block(name)
            assertTrue("longPressMenuClickable(" in src && "contextClick(" in src, "$name keeps the held-row menu working")
            assertTrue("role = Role.Button" in src, "$name carries Role.Button")
            assertTrue("mergeDescendants = true" in src, "$name reads as one control")
        }
        assertTrue("role = Role.Button" in block("CosmoTiles") && "mergeDescendants = true" in block("CosmoTiles"), "the mix tile is a button")
        assertTrue("MorphBox(" in block("CosmoTiles"), "the liked tile is a MorphBox, a button by default")
        assertTrue("Role.Checkbox" in block("CosmoMood") && "toggled = selected" in block("CosmoMood"), "a mood chip is a toggle")
        assertTrue("MorphBox(" in block("CosmoMood"), "the mood tile is a MorphBox, a button by default")
        assertTrue("Role.Button" in block("CosmoSearchField"), "the magnifier is a button")
    }

    @Test fun theMoreButtonAndEveryOtherSmallControlReservesA48DpTarget() {
        val song = block("CosmoSongRow")
        assertTrue(Regex("""CosmoIconButton\(.*?size = 40\.dp""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(song), "the more button is a 40 dp CosmoIconButton")
        assertTrue("CosmoIcons.MoreVertical" in song, "the more button is the vertical three dots")
        assertTrue("minimumInteractiveComponentSize()" in SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/components/CosmoIconButton.kt"), "a CosmoIconButton reserves 48 dp")
        assertTrue("minimumInteractiveComponentSize()" in block("CosmoMood"), "the mood chip reserves 48 dp")
        assertTrue("size(48.dp)" in block("CosmoSearchField"), "the magnifier is 48 dp")
        assertTrue("CosmoButtonSize.SMALL" in block("CosmoHeaders"), "Show all is a small text button, which reserves 48 dp")
    }

    @Test fun everyBlockThatTakesFocusShowsIt() {
        assertTrue("collectIsFocusedAsState" in block("CosmoSearchField"), "the search field draws its focus")
        // MorphBox draws the ring of the controls built on it; rows and cards take the platform's focus highlight of their click
        assertTrue("cosmoFocusRing(" in SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/components/MorphBox.kt"))
    }

    // ---- the swipe ----

    @Test fun theSwipeThresholdsAreThoseOfTheOldSongRow() {
        val old = SourceContract.code(SourceContract.read(common))
        fun first(pattern: String): Float = Regex(pattern).find(old)?.groupValues?.get(1)?.toFloat() ?: fail("the old SongRow no longer has $pattern")
        val commit = first("""abs\(offset\) >= boxWidth \* ([0-9.]+)f""")
        val positional = first("""positionalThreshold = \{ distance -> distance \* ([0-9.]+)f \}""")
        val armAt = first("""val armAt = boxWidth \* ([0-9.]+)f""")
        val rearm = first("""travelled < armAt \* ([0-9.]+)f""")
        assertEquals(0.45f, commit)
        assertEquals(0.5f, positional)
        assertEquals(0.45f, armAt)
        assertEquals(0.8f, rearm)
        assertEquals(commit, BlockMetrics.SWIPE_COMMIT)
        assertEquals(positional, BlockMetrics.SWIPE_POSITIONAL_THRESHOLD)
        assertEquals(armAt, BlockMetrics.SWIPE_COMMIT)
        assertEquals(rearm, BlockMetrics.SWIPE_REARM)
    }

    @Test fun theNewSongRowSwipesWithThoseNumbersAndTheSameHaptics() {
        val song = SourceContract.code(block("CosmoSongRow"))
        for (use in listOf("BlockMetrics.SWIPE_COMMIT", "BlockMetrics.SWIPE_POSITIONAL_THRESHOLD", "BlockMetrics.SWIPE_REARM")) assertTrue(use in song, "CosmoSongRow uses $use")
        assertTrue("Haptic.Select" in song && "Haptic.Tick" in song, "a tick when armed, a select when it queues")
        assertTrue(Regex("""\n\s*false\s*\n\s*\}""").containsMatchIn(song), "the row never dismisses: confirmValueChange answers false")
        assertTrue("swipeToPlayNext" in song && "Res.string.play_next" in song && "Res.string.queue" in song, "the block says Play next or Queue by the setting")
        assertTrue("SwipeToDismissBox(" in song, "the old mechanics are kept")
    }

    // ---- what other screens call ----

    private fun names(file: String, function: String) = SourceContract.names(SourceContract.parameters(block(file), function))

    @Test fun theSignaturesAreThoseOfTheBrief() {
        assertEquals(
            listOf("title", "onClick", "modifier", "subtitle", "why", "leading", "isCurrent", "isPlaying", "onLongPress", "height", "titleContent", "trailing"),
            names("CosmoListRow", "CosmoListRow"),
        )
        assertEquals(
            listOf("song", "onClick", "modifier", "onLongPress", "onMore", "onSwipeToQueue", "trackNumber", "downloadedTint", "isCurrent", "isPlaying", "selected", "height", "trailingText"),
            names("CosmoSongRow", "CosmoSongRow"),
        )
        assertEquals(
            listOf("title", "imageUrl", "onClick", "modifier", "subtitle", "width", "circle", "onLongPress", "playing"),
            names("CosmoCards", "CosmoCard"),
        )
        assertEquals(
            listOf("title", "items", "onItemClick", "modifier", "subtitle", "onItemLongPress", "onShowAll", "leadFirst", "cardWidth", "circles", "currentSong", "isPlaying"),
            names("CosmoCards", "CosmoShelf"),
        )
        assertEquals(listOf("title", "modifier", "subtitle", "onShowAll"), names("CosmoHeaders", "CosmoSectionHeader"))
        assertEquals(listOf("text", "modifier", "trailing"), names("CosmoHeaders", "CosmoOverline"))
        assertEquals(listOf("title", "modifier", "overline", "subtitle", "large", "actions"), names("CosmoHeaders", "CosmoPageHeader"))
        assertEquals(listOf("shape"), names("CosmoTiles", "cosmoTile"))
        assertEquals(listOf("title", "subtitle", "onClick", "modifier", "onLongPress"), names("CosmoTiles", "CosmoLikedTile"))
        assertEquals(listOf("title", "subtitle", "covers", "onClick", "modifier", "onLongPress"), names("CosmoTiles", "CosmoMixTile"))
        assertEquals(listOf("label", "selected", "onClick", "modifier"), names("CosmoMood", "CosmoMoodChip"))
        assertEquals(listOf("label", "coverUrl", "onClick", "modifier", "big"), names("CosmoMood", "CosmoMoodTile"))
        assertEquals(
            listOf("query", "onQueryChange", "onSubmit", "placeholder", "modifier", "focusRequester", "height", "keyHint", "onFocusChanged"),
            names("CosmoSearchField", "CosmoSearchField"),
        )
    }

    @Test fun theDefaultsAreThoseOfTheBrief() {
        val row = SourceContract.parameters(block("CosmoListRow"), "CosmoListRow")
        assertTrue("height: Dp = 64.dp" in row && "leading: CosmoRowLeading = CosmoRowLeading.None" in row && "subtitle: String? = null" in row)
        val song = SourceContract.parameters(block("CosmoSongRow"), "CosmoSongRow")
        assertTrue("downloadedTint: Color? = Color.Unspecified" in song && "height: Dp = 64.dp" in song && "trailingText: String? = null" in song)
        val card = SourceContract.parameters(block("CosmoCards"), "CosmoCard")
        assertTrue("width: Dp = 150.dp" in card && "circle: Boolean = false" in card && "playing: Boolean = false" in card)
        val shelf = SourceContract.parameters(block("CosmoCards"), "CosmoShelf")
        assertTrue("leadFirst: Boolean = true" in shelf && "cardWidth: Dp? = null" in shelf && "circles: Boolean = false" in shelf)
        val field = SourceContract.parameters(block("CosmoSearchField"), "CosmoSearchField")
        assertTrue("height: Dp = 52.dp" in field && "keyHint: String? = null" in field && "onFocusChanged: ((Boolean) -> Unit)? = null" in field)
        val header = SourceContract.parameters(block("CosmoHeaders"), "CosmoPageHeader")
        assertTrue("large: Boolean = false" in header && "actions: @Composable RowScope.() -> Unit = {}" in header)
        val leading = block("CosmoListRow")
        for (member in listOf("data class Cover(val url: String?, val circle: Boolean = false)", "data class Number(val number: Int)", "data class Icon(val icon: ImageVector)", "data class IconPainter(val painter: Painter)", "data object None")) {
            assertTrue(member in leading, "CosmoRowLeading has $member")
        }
    }

    @Test fun theShelfKeepsItsKeysStableAndItsCardsOnTheBottomLine() {
        val cards = block("CosmoCards")
        assertTrue("LazyRow(" in cards && "itemsIndexed(" in cards && "key = " in cards, "a lazy row with keys")
        assertTrue("Alignment.Bottom" in cards, "the cards stand on one line")
        assertTrue("?: item.videoId ?: item.title" in cards, "the key is the browse id, the video id or the title, and the place")
    }
}
