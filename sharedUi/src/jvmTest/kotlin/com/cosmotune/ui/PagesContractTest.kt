package com.cosmotune.ui

import com.cosmotune.design.BlockMetrics
import com.cosmotune.ui.pages.isArtists
import com.cosmotune.ui.pages.matchingQuery
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What no screenshot can check, read from the sources of the album, playlist and artist pages: the switch that has the old screen's
 * parameter list and passes everything by name in both branches, the one-line call site in `MainActivity.kt`, the draw-phase rule
 * for the live palette, the keys of the lists, and the numbers and rules of the pages that are pure (the find-in-list filter and
 * the numbers of the boards).
 */
class PagesContractTest {
    private val switchPath = "app/src/main/java/com/cosmotune/ui/pages/DetailSwitch.kt"
    private val screenPath = "app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt"
    private val mainPath = "app/src/main/java/com/music/bitchord/MainActivity.kt"
    private val pagesDir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/pages"
    private val pageFiles = listOf("CosmoReleasePage", "CosmoArtistPage", "CosmoShelfGridPage", "PageParts")
    private val strings = "sharedUi/src/commonMain/composeResources/values/strings.xml"

    private fun page(name: String): String {
        val file = File(SourceContract.root, "$pagesDir/$name.kt")
        if (!file.isFile) fail("$pagesDir/$name.kt is not where this test expects it")
        return SourceContract.read("$pagesDir/$name.kt")
    }

    // ---- text helpers: the call that starts at an index, and its arguments ----

    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        fail("the parenthesis at $open is never closed")
    }

    /** The text of the call `name(...)` whose name starts at the first occurrence of [name] followed by `(` in [text], parentheses included. */
    private fun call(text: String, name: String): String {
        val at = Regex("""(?<![\w.])${Regex.escape(name)}\(""").find(text)?.range?.first ?: fail("$name( is not called")
        val open = text.indexOf('(', at)
        return text.substring(at, matchingParen(text, open) + 1)
    }

    /** The arguments of a call as written, split at the commas that are not inside a bracket. */
    private fun arguments(call: String): List<String> {
        val inside = call.substring(call.indexOf('(') + 1, call.lastIndexOf(')'))
        val parts = ArrayList<String>()
        var depth = 0
        var from = 0
        var i = 0
        while (i < inside.length) {
            when (inside[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '-' -> if (inside.startsWith("->", i)) i++
                ',' -> if (depth == 0) { parts += inside.substring(from, i); from = i + 1 }
            }
            i++
        }
        parts += inside.substring(from)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parameters(src: String, function: String) = SourceContract.parameters(src, function)
    private fun names(src: String, function: String) = SourceContract.names(parameters(src, function))

    // ---- the switch ----

    @Test fun theSwitchHasTheParameterListOfTheOldScreen() {
        val old = parameters(SourceContract.read(screenPath), "DetailScreen")
        val switch = parameters(SourceContract.read(switchPath), "DetailSwitch")
        assertEquals(23, old.size, "DetailScreen has 23 parameters; if this changed, DetailSwitch has to decide about the new one")
        // P4-20: one more at the end, defaulted: the root's play/pause toggle, which only the artist page uses (the old call does not pass it)
        assertEquals(old, switch.take(old.size), "DetailSwitch starts with DetailScreen's parameters: same names, types, defaults and order")
        assertEquals(listOf("onPlayPause: (() -> Unit)? = null"), switch.drop(old.size), "and has the one extra of P4-20")
    }

    @Test fun theOldBranchPassesEveryParameterByName() {
        val src = SourceContract.read(switchPath)
        val body = SourceContract.functionBody(src, "DetailSwitch")
        val old = call(body, "DetailScreen")
        SourceContract.assertPassesEveryParameter(old, names(SourceContract.read(screenPath), "DetailScreen"))
        assertTrue(arguments(old).all { Regex("""^\w+ = """).containsMatchIn(it) }, "every argument of the old call is by name")
    }

    @Test fun theSwitchTestsTheShellFirstAndTheOldScreenIsItsElseBranch() {
        val body = SourceContract.functionBody(SourceContract.read(switchPath), "DetailSwitch")
        val statement = SourceContract.firstStatement(body)
        assertTrue(statement.startsWith("if (LocalCosmoShell.current != null) {"), "the first statement tests the shell: $statement")
        assertTrue("} else { DetailScreen(" in statement, "the old screen is the else branch")
    }

    @Test fun theNewBranchCallsThePagesByNameWithEveryParameterTheyTake() {
        val body = SourceContract.functionBody(SourceContract.read(switchPath), "DetailSwitch")
        for ((function, file) in listOf("CosmoReleasePage" to "CosmoReleasePage", "CosmoArtistPage" to "CosmoArtistPage")) {
            val declared = parameters(page(file), function)
            val declaredNames = SourceContract.names(declared)
            val required = declared.filterNot { " = " in it }.let(SourceContract::names)
            val given = arguments(call(body, function))
            assertTrue(given.all { Regex("""^\w+ = """).containsMatchIn(it) }, "$function is called with every argument by name")
            val givenNames = given.map { it.substringBefore(" = ") }
            assertTrue(givenNames.containsAll(required), "$function: missing ${required - givenNames.toSet()}")
            assertTrue(declaredNames.containsAll(givenNames), "$function: unknown ${givenNames - declaredNames.toSet()}")
            assertEquals(givenNames.size, givenNames.toSet().size, "$function: an argument is passed twice")
        }
    }

    @Test fun everyParameterOfTheOldScreenReachesTheNewBranch() {
        val body = SourceContract.functionBody(SourceContract.read(switchPath), "DetailSwitch")
        val newBranch = body.substringBefore("DetailScreen(")
        val missing = names(SourceContract.read(screenPath), "DetailScreen").filterNot { Regex("""\b$it\b""").containsMatchIn(newBranch) }
        assertTrue(missing.isEmpty(), "the new pages hear about every parameter of DetailScreen; missing in the new branch: $missing")
    }

    @Test fun theSignaturesOfThePagesAreThoseOfTheBrief() {
        assertEquals(
            listOf(
                "page", "songs", "trackNumbers", "currentSong", "isPlaying", "listState", "contentPadding", "onSongClick", "onSongLongPress",
                "onSongSwipe", "onShuffle", "onMore", "onArtistClick", "onAddSuggested", "onToggleLibrary", "modifier",
            ),
            names(page("CosmoReleasePage"), "CosmoReleasePage"),
        )
        assertEquals(
            listOf(
                "page", "heroUrl", "currentSong", "isPlaying", "listState", "contentPadding", "activeShelf", "onActiveShelfChange", "onSongClick",
                "onSongLongPress", "onSongSwipe", "onShuffle", "onSectionItemClick", "onSectionItemLongPress", "onToggleSubscription",
                "releaseLibrary", "onLoadReleaseLibrary", "onToggleReleaseLibrary", "modifier", "onPlayPause",
            ),
            names(page("CosmoArtistPage"), "CosmoArtistPage"),
        )
        assertEquals(
            listOf("shelf", "onItemClick", "onItemLongPress", "contentPadding", "modifier"),
            names(page("CosmoShelfGridPage"), "CosmoShelfGridPage"),
        )
    }

    // ---- the call site ----

    @Test fun theCallSiteIsTheSwitchWithTheSameNamedArguments() {
        val src = SourceContract.code(SourceContract.read(mainPath))
        assertTrue(!Regex("""(?<![\w.])DetailScreen\(""").containsMatchIn(src), "no call of DetailScreen( is left in MainActivity.kt")
        val site = call(src, "com.cosmotune.ui.pages.DetailSwitch")
        val old = parameters(SourceContract.read(screenPath), "DetailScreen")
        val required = old.filterNot { " = " in it }.let(SourceContract::names)
        val given = arguments(site).map { it.substringBefore(" = ") }
        assertTrue(arguments(site).all { Regex("""^\w+ = """).containsMatchIn(it) }, "every argument of the call is by name")
        assertTrue(given.containsAll(required), "the call passes what DetailScreen needs; missing ${required - given.toSet()}")
        assertTrue(given.containsAll(listOf("listState", "activeShelf", "onActiveShelfChange", "songSort", "contentPadding")), "the page state and the sort still reach the switch")
        assertEquals(1, Regex("""com\.cosmotune\.ui\.pages\.DetailSwitch\(""").findAll(src).count(), "one call")
    }

    // ---- the draw-phase rule and the lists ----

    private fun enclosingHeads(code: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += code.substring(maxOf(0, i - 160), i)
            }
            i--
        }
        return heads
    }

    // The lambdas of liftShadow( are only called inside the layer block, which is a draw-phase block.
    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer)\s*(\([^)]*\))?\s*$|liftShadow\s*\([^{}]*$""")
    private val topLevelFun = Regex("""(?m)^(?:(?:internal|private|public)\s+)?fun\s+(?:<[^>]*>\s*)?([\w.<>?]+\.)?\w+\s*\(""")

    private fun inDrawScopeFunction(code: String, index: Int): Boolean {
        val header = topLevelFun.findAll(code).lastOrNull { it.range.first <= index } ?: return false
        return header.groupValues[1] == "DrawScope."
    }

    @Test fun theLivePaletteIsReadOnlyWhileDrawing() {
        var seen = 0
        for (name in pageFiles) {
            val code = SourceContract.code(page(name))
            var from = 0
            while (true) {
                val at = code.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (code[at - 1].isLetterOrDigit() || code[at - 1] == '_')) continue
                seen++
                val inDraw = enclosingHeads(code, at).any { drawHead.containsMatchIn(it) }
                assertTrue(inDraw || inDrawScopeFunction(code, at), "$name: live( at line ${code.take(at).count { it == '\n' } + 1} is neither in a draw lambda nor in a DrawScope function")
            }
        }
        assertTrue(seen >= 3, "the pages read the live palette in the banner, its scrim and the hero shadow ($seen found)")
    }

    @Test fun noColourIsPickedByHandNoBrushIsRememberedAndNoPageHasABackgroundOfItsOwn() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\(|cssGradient|Fill\()""")
        for (name in pageFiles) {
            val code = SourceContract.code(page(name))
            assertTrue(!Regex("""Color\(0x""").containsMatchIn(code), "$name picks a colour by hand")
            assertTrue(!Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|White|Black)\b""").containsMatchIn(code), "$name uses a named colour")
            assertTrue(remembering.find(code) == null, "$name remembers a brush or a live colour: ${remembering.find(code)?.value}")
            assertTrue(!Regex("""\.background\(""").containsMatchIn(code), "$name paints a background: the ambient ground shows")
            assertTrue("MaterialTheme.colorScheme" !in code, "$name takes a colour from Material: the palette decides")
        }
    }

    @Test fun everyItemOfEveryListHasAKey() {
        var seen = 0
        for (name in pageFiles) {
            val code = SourceContract.code(page(name))
            assertTrue(!Regex("""(?<![\w.])item\s*\{""").containsMatchIn(code), "$name has an item with no key")
            for (match in Regex("""(?<![\w.])(item|items|itemsIndexed)\s*\(""").findAll(code)) {
                seen++
                val open = code.indexOf('(', match.range.first)
                val args = code.substring(open, matchingParen(code, open) + 1)
                assertTrue("key =" in args, "$name: ${match.value} at line ${code.take(match.range.first).count { it == '\n' } + 1} has no key")
            }
        }
        assertTrue(seen >= 14, "the lists of the pages were found ($seen)")
    }

    // ---- roles, targets and text ----

    @Test fun theArtistLineAndTheLatestReleaseAreButtonsOf48DpAndTheFormerNeverMergesTheLatterWithItsToggle() {
        val parts = page("PageParts")
        assertTrue("Role.Button" in parts && "heightIn(min = 48.dp)" in parts, "the artist line is a 48 dp button")
        val artist = page("CosmoArtistPage")
        assertTrue("role = Role.Button" in artist && "mergeDescendants = true" in artist && "longPressMenuClickable(" in artist && "contextClick(" in artist, "the latest release opens and holds like a card")
        // the toggle is a sibling of the region that merges, not inside it
        val region = artist.substringAfter("mergeDescendants = true").substringBefore("if (saved != null && onToggleSaved != null)")
        assertTrue("CosmoIconButton(" !in region, "the save toggle is outside the merged region of the tile")
    }

    @Test fun theBannerRunsUnderTheBarAndTheReleaseListKeepsTheCallersPadding() {
        val artist = SourceContract.code(page("CosmoArtistPage"))
        assertTrue("PaddingValues(bottom = contentPadding.calculateBottomPadding())" in artist, "only the foot of the padding is the artist list's")
        val release = SourceContract.code(page("CosmoReleasePage"))
        assertTrue("contentPadding = contentPadding" in release, "the release list takes the caller's padding whole")
        assertTrue("state = listState" in release && "state = listState" in artist, "both lists use the caller's state")
    }

    @Test fun everyStringTheSharedPagesSayIsAKeyThatExists() {
        val english = SourceContract.read(strings)
        val declared = Regex("""<string name="([a-z0-9_]+)"""").findAll(english).map { it.groupValues[1] }.toSet()
        for (name in pageFiles) {
            val used = Regex("""import com\.music\.bitchord\.sharedui\.resources\.(\w+)""").findAll(page(name)).map { it.groupValues[1] }.filter { it != "Res" }.toList()
            for (key in used) assertTrue(key in declared, "$name uses the string $key, which values/strings.xml does not have")
            assertTrue(
                Regex("""Text\(\s*(text = )?"[A-Za-z]""").find(SourceContract.code(page(name))) == null,
                "$name has a literal English text: a shared page says its words through string resources",
            )
        }
        assertTrue("%1\$d min" in english && "%1\$d h %2\$d min" in english, "the two duration keys have their texts")
    }

    // ---- numbers of the boards ----

    @Test fun theNumbersAreThoseOfTheBoards() {
        // these are internal to the pages: read from the source, so a change shows up here
        val metrics = SourceContract.code(page("PageParts"))
        fun const(name: String): String = Regex("""const val $name\s*=\s*([0-9.]+f?)""").find(metrics)?.groupValues?.get(1) ?: fail("PageMetrics.$name is not there")
        assertEquals("236f", const("COVER_DP"))
        assertEquals("6f", const("COVER_TOP_DP"))
        assertEquals("24f", const("HERO_SHADOW_DP"))
        assertEquals("28f", const("ARTIST_AVATAR_DP"))
        assertEquals("10f", const("ARTIST_GAP_DP"))
        assertEquals("15f", const("ARTIST_NAME_SP"))
        assertEquals("13f", const("META_SP"))
        assertEquals("8f", const("ACTIONS_GAP_DP"))
        assertEquals("300f", const("BANNER_DP"))
        assertEquals("5", const("POPULAR_COUNT"))
        assertEquals("96f", const("LATEST_COVER_DP"))
        assertEquals("12f", const("LATEST_PADDING_DP"))
        assertEquals("150f", const("SHELF_CARD_DP"))
        assertEquals("5", const("SHELF_PREVIEW_ITEMS"))
        assertEquals("4", const("ABOUT_LINES"))
        assertEquals("1.55f", const("ABOUT_LINE_HEIGHT"))
        assertEquals("3", const("SEARCH_ITEM_INDEX"))
        assertTrue("TRACK_ROW_DP = BlockMetrics.TRACK_ROW_DP" in metrics, "the track rows are the 60 dp of the blocks")
        assertEquals(60f, BlockMetrics.TRACK_ROW_DP)
    }

    @Test fun theBannerScrimIsTheBoardsInThePalettesGround() {
        val metrics = SourceContract.code(page("PageParts"))
        assertTrue("BANNER_SCRIM_TOP_ALPHA = 0.30f" in metrics && "BANNER_SCRIM_CLEAR_AT = 0.30f" in metrics, "backgroundTop at 30 %, clear at 30 %")
        assertTrue("BANNER_SCRIM_DEEP_AT = 0.85f" in metrics && "BANNER_SCRIM_DEEP_ALPHA = 0.85f" in metrics, "85 % at 85 %")
        val artist = SourceContract.code(page("CosmoArtistPage"))
        assertTrue("CoverField.BACKGROUND_TOP" in artist, "the scrim is the palette's ground")
        assertTrue("1f to ground" in artist, "and ends solid")
    }

    // ---- the filter and the shelves (pure) ----

    private fun song(id: String, title: String, artist: String) = Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    @Test fun theFindInListFieldMatchesTheTitleOrTheArtistWhateverTheCaseAndKeepsEachRowsPlace() {
        val songs = listOf(
            song("a", "Low Tide Hours", "The Paper Coast"),
            song("b", "Kite Season", "Ritu"),
            song("c", "Tidal Clock", "The Paper Coast"),
        )
        assertEquals(listOf(0, 2), songs.matchingQuery("tid").map { it.index })
        assertEquals(listOf(0, 2), songs.matchingQuery("PAPER").map { it.index })
        assertEquals(listOf(1), songs.matchingQuery("  ritu ").map { it.index })
        assertEquals(listOf("c"), songs.matchingQuery("clock").map { it.value.videoId })
        assertEquals(emptyList(), songs.matchingQuery("zzz").map { it.index })
    }

    @Test fun aBlankQueryLeavesEverythingAndTheAlbumNameIsNotSearched() {
        val songs = listOf(song("a", "One", "A").copy(albumName = "Hidden Album"), song("b", "Two", "B"))
        assertEquals(listOf(0, 1), songs.matchingQuery("").map { it.index })
        assertEquals(listOf(0, 1), songs.matchingQuery("   ").map { it.index })
        assertEquals(emptyList(), songs.matchingQuery("hidden").map { it.index })
    }

    private fun item(browseId: String?) = ShelfItem("t", "s", null, videoId = null, browseId = browseId)

    @Test fun aShelfOfArtistsIsDrawnAsCirclesAndAnyOtherShelfIsNot() {
        assertTrue(HomeShelf("Fans also like", listOf(item("UC1"), item("UC2"))).isArtists())
        assertTrue(!HomeShelf("Albums", listOf(item("MPREb_1"), item("MPREb_2"))).isArtists())
        assertTrue(!HomeShelf("Mixed", listOf(item("UC1"), item("MPREb_2"))).isArtists())
        assertTrue(!HomeShelf("Videos", listOf(item(null))).isArtists())
        assertTrue(!HomeShelf("Empty", emptyList()).isArtists())
    }
}
