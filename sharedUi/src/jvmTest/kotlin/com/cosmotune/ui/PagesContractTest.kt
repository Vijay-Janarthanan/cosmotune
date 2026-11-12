package com.cosmotune.ui

import com.cosmotune.design.BlockMetrics
import com.cosmotune.ui.pages.isArtists
import com.cosmotune.ui.pages.matchingQuery
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What no screenshot can check, read from the sources of the album, playlist and artist pages: the switch that has the old screen's
 * parameter list and hands every one of them to the new pages (the old screen is no longer called), the one-line call site in
 * `MainActivity.kt`, the draw-phase rule
 * for the live palette, the keys of the lists, and the numbers and rules of the pages that are pure (the find-in-list filter and
 * the numbers of the boards).
 */
class PagesContractTest {
    private val switchPath = "app/src/main/java/com/cosmotune/ui/pages/DetailSwitch.kt"
    private val screenPath = "app/src/main/java/com/cosmotune/music/ui/screens/DetailScreen.kt"
    private val mainPath = "app/src/main/java/com/cosmotune/music/MainActivity.kt"
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
        // P4-20 and Orbit: more at the end, defaulted, that the old call does not pass: the root's play/pause toggle, the release card's
        // Download and the artist card's Radio
        assertEquals(old, switch.take(old.size), "DetailSwitch starts with DetailScreen's parameters: same names, types, defaults and order")
        assertEquals(
            listOf("onPlayPause: (() -> Unit)? = null", "onDownloadAll: ((List<Song>) -> Unit)? = null", "onStartRadio: ((Song) -> Unit)? = null"),
            switch.drop(old.size), "and has the extras of P4-20 and Orbit",
        )
    }

    @Test fun theSwitchChoosesOnlyBetweenTheNewPagesAndNeverCallsTheOldScreen() {
        val body = SourceContract.functionBody(SourceContract.read(switchPath), "DetailSwitch")
        val statement = SourceContract.firstStatement(body)
        assertTrue(statement.startsWith("if (page.type == BrowseType.ARTIST) { CosmoArtistPage("), "the first statement picks the artist page or the release page: $statement")
        assertTrue("} else {" in statement && "CosmoReleasePage(" in statement.substringAfter("} else {"), "the release page is the else branch")
        assertEquals("{ $statement }", body.replace(Regex("\\s+"), " "), "and that choice is the whole body")
        assertTrue("LocalCosmoShell" !in body, "no question whether the look is on")
        assertTrue(!Regex("""(?<![\w.])DetailScreen\(""").containsMatchIn(body), "the old screen is not called")
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

    @Test fun everyParameterOfTheOldScreenReachesTheNewPages() {
        val body = SourceContract.functionBody(SourceContract.read(switchPath), "DetailSwitch")
        val missing = names(SourceContract.read(screenPath), "DetailScreen").filterNot { Regex("""\b$it\b""").containsMatchIn(body) }
        assertTrue(missing.isEmpty(), "the new pages hear about every parameter of DetailScreen; missing: $missing")
    }

    @Test fun theSignaturesOfThePagesAreThoseOfTheBrief() {
        assertEquals(
            listOf(
                "page", "songs", "currentSong", "isPlaying", "listState", "contentPadding", "onSongClick", "onSongLongPress",
                "onSongSwipe", "onShuffle", "onMore", "onArtistClick", "onAddSuggested", "onToggleLibrary", "modifier", "initialOrder", "downloadedIds",
                "onDownloadAll", "albumCanvas", "onPlayPause",
            ),
            names(page("CosmoReleasePage"), "CosmoReleasePage"),
        )
        assertEquals(
            listOf(
                "page", "heroUrl", "currentSong", "isPlaying", "listState", "contentPadding", "activeShelf", "onActiveShelfChange", "onSongClick",
                "onSongLongPress", "onSongSwipe", "onShuffle", "onSectionItemClick", "onSectionItemLongPress", "onToggleSubscription",
                "releaseLibrary", "onLoadReleaseLibrary", "onToggleReleaseLibrary", "modifier", "onStartRadio", "onPlayPause",
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
        assertTrue(seen >= 10, "the lists of the pages were found ($seen)")
    }

    // ---- roles, targets and text ----

    @Test fun thePagesAreBuiltOfTheOrbitKitAndKeepTheCallersPaddingAndState() {
        val release = SourceContract.code(page("CosmoReleasePage"))
        assertTrue("OrbitReleaseCard(" in release && "OrbitTrackRow(" in release, "the release is the colour card and Home's rows")
        assertTrue("motion = albumCanvas" in release, "the album's animated cover plays in the card's disc")
        assertTrue("top = contentPadding.calculateTopPadding() + PageMetrics.COVER_TOP_DP.dp" in release, "the card stands below the bar")
        val artist = SourceContract.code(page("CosmoArtistPage"))
        assertTrue("OrbitArtistPage(" in artist && "contentPadding = contentPadding" in artist, "the artist page is the shared Orbit page, with the caller's padding")
        assertTrue("OrbitLatestRelease(" in artist && "onToggleSaved = onToggleReleaseLibrary" in artist, "the latest release keeps its save toggle")
        assertTrue("state = listState" in release && "listState = listState" in artist, "both lists use the caller's state")
    }

    @Test fun everyStringTheSharedPagesSayIsAKeyThatExists() {
        val english = SourceContract.read(strings) + SourceContract.read("sharedUi/src/commonMain/composeResources/values/music_list.xml")
        val declared = Regex("""<(?:string|plurals) name="([a-z0-9_]+)"""").findAll(english).map { it.groupValues[1] }.toSet()
        for (name in pageFiles) {
            val used = Regex("""import com\.cosmotune\.music\.sharedui\.resources\.(\w+)""").findAll(page(name)).map { it.groupValues[1] }.filter { it != "Res" }.toList()
            for (key in used) assertTrue(key in declared, "$name uses the string $key, which values/strings.xml does not have")
            assertTrue(
                Regex("""Text\(\s*(text = )?"[A-Za-z]""").find(SourceContract.code(page(name))) == null,
                "$name has a literal English text: a shared page says its words through string resources",
            )
        }
        assertTrue("%1\$d min" in english && "%1\$d h %2\$d min" in english, "the two duration keys have their texts")
    }

    // ---- numbers of the boards ----

    @Test fun theNumbersAreThoseOfTheBlueprint() {
        // these are internal to the pages: read from the source, so a change shows up here
        val metrics = SourceContract.code(page("PageParts"))
        fun const(name: String): String = Regex("""const val $name\s*=\s*([0-9.]+f?)""").find(metrics)?.groupValues?.get(1) ?: fail("PageMetrics.$name is not there")
        assertEquals("8f", const("COVER_TOP_DP"))
        assertEquals("2", const("SEARCH_ITEM_INDEX"))
        assertTrue("TRACK_ROW_DP = BlockMetrics.TRACK_ROW_DP" in metrics, "the track rows are the 60 dp of the blocks")
        assertEquals(60f, BlockMetrics.TRACK_ROW_DP)
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
