package com.cosmotune.ui

import com.cosmotune.design.BlockMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What no render can say about the desktop's collection page, artist page and song row (task P4-16), read from the sources: the
 * three delegates in `DesktopApp.kt` (the first statement of each old function, every parameter passed by name), the signatures the
 * new functions take, the numbers of the boards, the draw-phase rule for the live palette, and what the old pages did that the new
 * ones keep.
 */
class DesktopPagesContractTest {
    private val app = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"
    private val dir = "desktopApp/src/main/kotlin/com/cosmotune/desktop"
    private val collectionFile = "$dir/CosmoDesktopCollectionPage.kt"
    private val artistFile = "$dir/CosmoDesktopArtistPage.kt"
    private val rowFile = "$dir/CosmoDesktopSongRow.kt"

    /** The upstream function, the function it hands over to, and the file that function is in. */
    private data class Pair3(val old: String, val new: String, val file: String)

    private val pairs = listOf(
        Pair3("DesktopCollectionPage", "CosmoDesktopCollectionPage", collectionFile),
        Pair3("DesktopArtistPage", "CosmoDesktopArtistPage", artistFile),
        Pair3("DesktopSongRow", "CosmoDesktopSongRow", rowFile),
    )

    // ---- the delegates ----

    @Test fun eachOldFunctionKeepsItsParameterList() {
        val src = SourceContract.read(app)
        assertEquals(
            listOf(
                "collection", "loadingMore", "onLoadMore", "onRename", "onDelete", "onShare", "onRemoveFromPlaylist", "likedIds", "onPlaySongs",
                "onShuffle", "onDownloadAll", "animatedCanvas", "onToggleLike", "onDownload", "onAddToPlaylist", "downloadedIds",
                "downloadInProgress", "contentPadding", "onReorder",
            ),
            SourceContract.names(SourceContract.parameters(src, "DesktopCollectionPage")),
        )
        assertEquals(
            listOf(
                "state", "fallbackName", "onRetry", "onPlaySongs", "onShuffle", "onOpenMenu", "onShelfItemClick", "onToggleSubscription",
                "contentPadding",
            ),
            SourceContract.names(SourceContract.parameters(src, "DesktopArtistPage")),
        )
        assertEquals(
            listOf(
                "song", "liked", "onClick", "onToggleLike", "onDownload", "onAddToPlaylist", "downloaded", "downloadInProgress", "number", "menu",
            ),
            SourceContract.names(SourceContract.parameters(src, "DesktopSongRow")),
        )
    }

    @Test fun theDelegateIsTheFirstStatementOfEachOldFunctionAndPassesEveryParameterByName() {
        val src = SourceContract.read(app)
        for ((old, new, _) in pairs) {
            val statement = SourceContract.firstStatement(SourceContract.functionBody(src, old))
            assertTrue(
                statement.startsWith("if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.desktop.$new("),
                "the first statement of $old is the delegate, but it is: $statement",
            )
            SourceContract.assertPassesEveryParameter(statement, SourceContract.names(SourceContract.parameters(src, old)))
        }
    }

    @Test fun theOldBodyFollowsTheDelegateUnchanged() {
        val src = SourceContract.read(app)
        val after = mapOf(
            "DesktopCollectionPage" to "val songs = collection.songs",
            "DesktopArtistPage" to "DesktopPageScaffold(contentPadding) {",
            "DesktopSongRow" to "Row(",
        )
        for ((old, next) in after) {
            val body = SourceContract.functionBody(src, old)
            val statement = SourceContract.firstStatement(body)
            val rest = body.substringAfter(statement.take(40)).substringAfter(")\n", "")
            assertTrue(next in rest, "$old still has its old body ($next) after the delegate")
        }
    }

    @Test fun theDelegatesAreTheOnlyPlacesDesktopAppReachesThePages() {
        val src = SourceContract.code(SourceContract.read(app))
        for ((_, new, _) in pairs) {
            assertEquals(1, Regex("""com\.cosmotune\.desktop\.$new\(""").findAll(src).count(), "$new is called once, from its delegate")
        }
    }

    @Test fun theNewFunctionsTakeTheOldListFirstAndOnlyDefaultedExtrasAfter() {
        val old = SourceContract.read(app)
        for ((oldName, newName, file) in pairs) {
            val before = SourceContract.parameters(old, oldName)
            val after = SourceContract.parameters(SourceContract.read(file), newName)
            assertEquals(before, after.take(before.size), "$newName starts with $oldName's parameters, as written")
            val extras = after.drop(before.size)
            assertTrue(extras.all { " = " in it }, "$newName's extra parameters all have a default: $extras")
        }
    }

    @Test fun whatTheOldFileKnowsAloneIsPassedAsAnExtra() {
        val src = SourceContract.read(app)
        val extra = mapOf(
            "DesktopCollectionPage" to "nowPlaying = LocalNowPlaying.current",
            "DesktopArtistPage" to "nowPlaying = LocalNowPlaying.current",
            "DesktopSongRow" to "isCurrent = song.isNowPlaying()",
        )
        for ((old, argument) in extra) {
            val statement = SourceContract.firstStatement(SourceContract.functionBody(src, old))
            assertTrue(argument in statement, "the delegate of $old passes $argument")
        }
        assertTrue("private val LocalNowPlaying = compositionLocalOf<Song?>" in src, "the local the delegates read is still the file's own")
        assertEquals(
            listOf("nowPlaying", "isPlaying"),
            SourceContract.names(SourceContract.parameters(SourceContract.read(collectionFile), "CosmoDesktopCollectionPage")).takeLast(2),
        )
        assertEquals(
            listOf("isCurrent", "isPlaying"),
            SourceContract.names(SourceContract.parameters(SourceContract.read(rowFile), "CosmoDesktopSongRow")).takeLast(2),
        )
    }

    // ---- the numbers of the boards ----

    @Test fun theSongRowsAreTheBoardsFiftyFourDpWithRadiusEighteen() {
        assertEquals(54f, BlockMetrics.DESKTOP_ROW_DP)
        val row = SourceContract.code(SourceContract.read(rowFile))
        assertTrue("BlockMetrics.DESKTOP_ROW_DP.dp" in row, "the row's height is the metric")
        assertTrue(Regex("""DESKTOP_ROW_RADIUS: Dp = 18\.dp""").containsMatchIn(row), "radius 18")
        assertTrue(Regex("""DESKTOP_ROW_PADDING: Dp = 16\.dp""").containsMatchIn(row), "padding 0 16")
        assertTrue(Regex("""DESKTOP_ROW_ACTION: Dp = 32\.dp""").containsMatchIn(row), "the actions are 32 dp")
        assertTrue(Regex("""DESKTOP_NUMBER_COLUMN: Dp = 36\.dp""").containsMatchIn(row) && Regex("""DESKTOP_DURATION_COLUMN: Dp = 60\.dp""").containsMatchIn(row), "columns of 36 and 60 dp")
        assertTrue("CoverFrame(size = 40.dp, radius = 10.dp" in row, "a 40 dp cover with radius 10")
        assertTrue("HOVER_LAYER = 0.08f" in row, "the hover layer is 8 %")
        assertTrue("PLAYING_WASH = 0.6f" in row, "the playing row is the container at 60 %")
        assertTrue("LocalMinimumInteractiveComponentSize provides DESKTOP_ROW_ACTION" in row, "a 32 dp button is 32 dp in the row")
        assertTrue(Regex("""style = CosmoIconButtonStyle\.PLAIN""").findAll(row).count() >= 4, "like, download, add and remove are PLAIN")
    }

    @Test fun theCollectionPageHasTheHeroTheActionsAndTheFourColumns() {
        val page = SourceContract.code(SourceContract.read(collectionFile))
        assertTrue(Regex("""PAGE_PADDING_X = 28\.dp""").containsMatchIn(page) && Regex("""PAGE_PADDING_TOP = 16\.dp""").containsMatchIn(page), "padding 16 28")
        assertTrue(Regex("""HERO_COVER = 232\.dp""").containsMatchIn(page) && Regex("""HERO_COVER_RADIUS = 28\.dp""").containsMatchIn(page), "the cover is 232 dp, radius 28")
        assertTrue(Regex("""HERO_GAP = 28\.dp""").containsMatchIn(page), "gap 28")
        assertTrue(Regex("""ROW_GAP = 2\.dp""").containsMatchIn(page), "rows 2 dp apart")
        assertTrue("CoverFrame(size = HERO_COVER, radius = HERO_COVER_RADIUS, shadow = true)" in page, "the cover has its shadow, and the canvas is in the same frame")
        assertTrue(Regex("""CoverFrame\(.*?DesktopCanvasView\(""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(page), "the canvas is drawn inside the cover's frame")
        assertTrue("type.display(TypeScale.DISPLAY_L)" in page, "the title is display(DISPLAY_L)")
        assertTrue("CREDIT_CIRCLE = 28.dp" in page, "the credit's circle is 28 dp")
        assertTrue("kind = CosmoButtonKind.PRIMARY" in page && "size = CosmoButtonSize.LARGE" in page, "Play is PRIMARY LARGE")
        assertEquals(2, Regex("""style = CosmoIconButtonStyle\.TONAL""").findAll(page).count(), "Shuffle and find are TONAL")
        assertTrue(Regex("""icon = CosmoIcons\.More,.*?style = CosmoIconButtonStyle\.PLAIN""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(page), "More is PLAIN")
        assertTrue("import com.cosmotune.ui.bridge.DropdownMenu\n" in SourceContract.read(collectionFile), "the menu is the bridge's")
        assertTrue("weight(0.6f)" in page, "a playlist's rows have an album column")
    }

    @Test fun theArtistPageHasTheBannerTheColumnsAndTheShelves() {
        val page = SourceContract.code(SourceContract.read(artistFile))
        assertTrue(Regex("""BANNER_HEIGHT = 300\.dp""").containsMatchIn(page), "the banner is 300 dp")
        assertTrue(Regex("""BANNER_TEXT_START = 32\.dp""").containsMatchIn(page) && Regex("""BANNER_TEXT_BOTTOM = 22\.dp""").containsMatchIn(page), "the words are 32 in and 22 up")
        assertTrue("blendMode = BlendMode.DstIn" in page && Regex("""BANNER_FADE_FROM = 0\.3f""").containsMatchIn(page), "the picture is clear to 30 % and then fades into the page, by an alpha mask")
        assertTrue("type.display(TypeScale.DISPLAY_XL)" in page, "the name is display(DISPLAY_XL)")
        assertTrue(Regex("""PlayMorphButton\(.*?size = 56\.dp""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(page), "Play is the 56 dp morph button")
        assertTrue(Regex("""kind = CosmoButtonKind\.TONAL,\s*size = CosmoButtonSize\.SMALL""").containsMatchIn(page), "Follow is TONAL SMALL")
        assertTrue("onToggleSubscription != null" in page, "Follow only when the account can")
        assertTrue(Regex("""POPULAR_WEIGHT = 1\.4f""").containsMatchIn(page) && Regex("""ABOUT_WEIGHT = 1f""").containsMatchIn(page), "columns 1.4 to 1")
        assertTrue(Regex("""COLUMNS_GAP = 28\.dp""").containsMatchIn(page) && Regex("""COLUMNS_PADDING = ShellMetrics\.DESKTOP_PAGE_MARGIN_DP\.dp""").containsMatchIn(page), "gap 28, padding 0 28: the desktop page margin, not the older board's 32 (P4-22)")
        assertTrue("fontSize = 14.sp, lineHeight = 14.sp * 1.6f" in page, "About is 14 sp on lines 1.6 tall")
        assertTrue("CosmoShelf(" in page && "cardWidth = SHELF_CARD" in page && Regex("""SHELF_CARD = 150\.dp""").containsMatchIn(page), "the shelves are CosmoShelf with 150 dp cards")
        assertTrue("onShelfItemClick(it, shelf.title)" in page, "a card calls onShelfItemClick(item, the shelf's title)")
        assertTrue("onOpenMenu(song)" in page && "CosmoIcons.MoreVertical" in page, "the dots on a popular row open the song's menu")
        assertTrue("UiState.Loading ->" in page && "CosmoLoadingBlob(" in page, "loading is the blob")
        assertTrue("is UiState.Error -> MessageState(" in page && "onAction = onRetry" in page, "an error is a MessageState with a retry")
    }

    @Test fun theOldPagesBehaviourIsKept() {
        val page = SourceContract.code(SourceContract.read(collectionFile))
        assertTrue("LaunchedEffect(songs.size) { onLoadMore() }" in page, "the next page is asked for when the last row shows")
        assertTrue("collection.continuation != null" in page, "the next-page row exists only while there is one")
        assertTrue("DesktopCanvasClient.lookupAlbum(collection.title" in page && "if (!animatedCanvas)" in page, "the album's motion artwork, only when animatedCanvas")
        assertTrue("song.isUnresolvedSpotify" in page && "enabled = !unresolved" in page, "an unresolved Spotify row is inert")
        assertTrue("song.isMatchMissing" in page && "MISSING_ALPHA = 0.45f" in page, "a row with no match is dimmed")
        assertTrue("position % 2 == 1" in page, "the stripe follows the place on screen, not the track number")
        for (item in listOf("onShare?.let", "onRename?.let", "onDelete?.let", "onDownloadAll()")) assertTrue(item in page, "the more menu still has $item")
        assertTrue("onRemoveFromPlaylist?.takeIf { song.setVideoId != null }" in page, "only a row that came from the playlist can be removed")
        assertTrue("song.albumName.orEmpty().contains(needle, ignoreCase = true)" in page, "the filter looks at the title, the artist and the album")
    }

    // ---- the look ----

    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer)\s*(\([^)]*\))?\s*$""")

    /** The text before the `{` of every block that encloses [index], innermost first. */
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

    @Test fun theLivePaletteIsReadOnlyWhileDrawing() {
        for (file in listOf(collectionFile, artistFile, rowFile)) {
            val code = SourceContract.code(SourceContract.read(file))
            var from = 0
            while (true) {
                val at = code.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (code[at - 1].isLetterOrDigit() || code[at - 1] == '_')) continue
                assertTrue(
                    enclosingHeads(code, at).any { drawHead.containsMatchIn(it) },
                    "$file: live( at line ${code.take(at).count { it == '\n' } + 1} is not in a draw-phase lambda",
                )
            }
        }
    }

    @Test fun noColourIsPickedByHandAndNoBrushIsRemembered() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\()""")
        for (file in listOf(collectionFile, artistFile, rowFile)) {
            // the banner's fade is an alpha mask, not a colour: its one declaration is the exception
            val code = SourceContract.code(SourceContract.read(file)).lines().filterNot { "BANNER_FADE_MASK = " in it }.joinToString("\n")
            assertTrue(!Regex("""Color\(0x""").containsMatchIn(code), "$file picks a colour by hand")
            assertTrue(!Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|White|Black)\b""").containsMatchIn(code), "$file uses a named colour")
            assertTrue(remembering.find(code) == null, "$file remembers a brush or a live colour")
        }
    }

    @Test fun everyIconButtonHasADescriptionAndTheRowsAreButtons() {
        for (file in listOf(collectionFile, artistFile, rowFile)) {
            val code = SourceContract.code(SourceContract.read(file))
            // the call's own arguments: from its opening parenthesis to the matching one
            for (call in Regex("""CosmoIconButton\(""").findAll(code)) {
                var depth = 0
                var end = call.range.last
                while (end < code.length) {
                    if (code[end] == '(') depth++
                    if (code[end] == ')' && --depth == 0) break
                    end++
                }
                val line = code.take(call.range.first).count { it == '\n' } + 1
                assertTrue("contentDescription =" in code.substring(call.range.last, end), "$file: the CosmoIconButton at line $line has a contentDescription")
            }
        }
        val row = SourceContract.code(SourceContract.read(rowFile))
        assertTrue("role = Role.Button" in row && "cosmoFocusRing(" in row, "a row is a button with a focus ring")
        assertTrue("collectIsHoveredAsState()" in row && ".hoverable(" in row, "a row answers the pointer")
    }

    @Test fun theStringsAreExistingKeysOrDesktopFallbacks() {
        // no new cosmo_ key: the three files use the ones part two declared, and DesktopStrings for the rest
        val declared = Regex("""name="(cosmo_[a-z_0-9]+)"""").findAll(SourceContract.read("sharedUi/src/commonMain/composeResources/values/strings.xml")).map { it.groupValues[1] }.toSet()
        for (file in listOf(collectionFile, artistFile)) {
            for (key in Regex("""SharedRes\.string\.(cosmo_[a-z_0-9]+)""").findAll(SourceContract.read(file)).map { it.groupValues[1] }) {
                assertTrue(key in declared, "$file uses $key, which part two did not declare")
            }
        }
    }
}
