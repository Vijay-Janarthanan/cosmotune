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
                "contentPadding", "artistId", "onStartRadio",
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

    @Test fun theCollectionPageIsTheOrbitCardAndHomesRows() {
        val page = SourceContract.code(SourceContract.read(collectionFile))
        assertTrue(Regex("""PAGE_PADDING_X = 28\.dp""").containsMatchIn(page) && Regex("""PAGE_PADDING_TOP = 16\.dp""").containsMatchIn(page), "padding 16 28")
        assertTrue("OrbitReleaseCard(" in page, "the page leads with the shared colour card, Play in its bite")
        assertTrue("motion = motion" in page && "albumCanvas?.toShared()" in page, "the album's motion artwork loops inside the card's disc")
        assertTrue("OrbitCardButton(CosmoIcons.Shuffle" in page && "OrbitCardButton(CosmoIcons.Download" in page && "OrbitCardButton(CosmoIcons.MoreVertical" in page,
            "Shuffle, Download and More are the card's round buttons")
        assertTrue("OrbitTrackRow(" in page, "the songs are Home's rows")
        assertTrue("OrbitPageButton(" in page, "find in the list is a round button by the list's controls")
        assertTrue("import com.cosmotune.ui.bridge.DropdownMenu\n" in SourceContract.read(collectionFile), "the menu is the bridge's")
    }

    @Test fun theArtistPageIsTheSharedOrbitPage() {
        val page = SourceContract.code(SourceContract.read(artistFile))
        assertTrue("OrbitArtistPage(" in page, "the artist page is the one both hosts share")
        assertTrue("onToggleFollow = subscription?.let" in page && "onToggleSubscription != null" in page, "Follow only when the account can")
        assertTrue("onRadio = onStartRadio?.let" in page, "Radio starts a station from the first top song")
        assertTrue("onItemClick = { item, shelf -> onShelfItemClick(item, shelf.title) }" in page, "a card calls onShelfItemClick(item, the shelf's title)")
        assertTrue("onSongMore = onOpenMenu" in page && "onSongLongPress = onOpenMenu" in page, "the dots on a top song, and a right click, open the song's menu")
        assertTrue("loading = state is UiState.Loading" in page, "while it loads the card stands over a skeleton")
        assertTrue("MessageState(message = state.message" in page && "onAction = onRetry" in page, "an error is a MessageState with a retry")
    }

    @Test fun theOldPagesBehaviourIsKept() {
        val page = SourceContract.code(SourceContract.read(collectionFile))
        assertTrue("DesktopCanvasClient.lookupAlbum(collection.title" in page && "if (!animatedCanvas)" in page, "the album's motion artwork, only when animatedCanvas")
        assertTrue("song.isUnresolvedSpotify" in page && "enabled = !unresolved" in page, "an unresolved Spotify row is inert")
        assertTrue("OrbitTrackRow(" in page, "a row with no match is dimmed by the shared row")
        for (item in listOf("onShare?.let", "onRename?.let", "onReorder?.let", "onDelete?.let")) assertTrue(item in page, "the more menu still has $item")
        assertTrue("onDownloadAll = { onDownloadAll(queue) }" in page, "Download on the card downloads the visible rows")
        assertTrue("onRemoveFromPlaylist?.takeIf { song.setVideoId != null }" in page, "only a row that came from the playlist can be removed")
        val filtering = SourceContract.code(SourceContract.read("shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/model/MusicSongList.kt"))
        assertTrue("MusicSongList.rows(songs, listQuery, listOrder, downloadedOnly, downloadedIds)" in page,
            "the desktop delegates its query, order and downloaded filter to the shared song-list model")
        assertTrue("\${song.title} \${song.artist} \${song.albumName.orEmpty()}" in filtering,
            "the shared filter still searches the title, artist and album")
    }

    @Test fun collectionPlaybackUsesTheVisibleQueueAndSelectionKeepsCanonicalOccurrences() {
        val body = SourceContract.code(SourceContract.functionBody(SourceContract.read(collectionFile), "CosmoDesktopCollectionPage"))
            .replace(Regex("\\s+"), " ")
        assertTrue("val queue = remember(shownSongs) { shownSongs.map { it.value } }" in body,
            "the playback queue follows the filtered and sorted rows")
        assertTrue("ArtistPlay.Tap.PLAY_LIST -> if (queue.isNotEmpty()) onPlaySongs(queue, 0)" in body,
            "the bite plays the visible queue, not the collection's unfiltered provider order")
        assertTrue("onShuffle = { if (queue.isNotEmpty()) onShuffle(queue) }" in body &&
            "onDownloadAll = { onDownloadAll(queue) }" in body,
            "header Shuffle and bulk download receive the same visible rows")
        assertTrue("else onPlaySongs(queue, position)" in body,
            "a row starts its visible queue at the visible position")
        assertTrue("itemsIndexed(shownSongs, key = { _, entry -> MusicSongList.occurrence(entry) })" in body,
            "rows retain canonical occurrence identity after sorting or filtering")
        assertTrue("MusicSongList.rows(songs, order = listOrder).filter { MusicSongList.occurrence(it) in keys }.map { it.value }" in body,
            "selected songs are resolved from exact occurrences in the current list order")
        assertTrue("onPlaySelection = { if (selectedSongs.isNotEmpty()) onPlaySongs(selectedSongs, 0) }" in body &&
            "onDownloadAll(selectedSongs)" in body && "selectedSongs.filter { it.videoId !in likedIds }.forEach(onToggleLike)" in body,
            "selection playback, download and like callbacks act on the selected occurrence set")
        for (callback in listOf("onShare", "onRename", "onDelete", "onReorder")) {
            assertTrue("$callback = $callback" in body, "the collection hero preserves $callback")
        }
        for (callback in listOf("onToggleLike", "onDownload", "onAddToPlaylist")) {
            assertTrue("$callback = $callback" in body, "the song row preserves $callback")
        }
        val row = SourceContract.code(SourceContract.functionBody(SourceContract.read(collectionFile), "CollectionTrackRow"))
            .replace(Regex("\\s+"), " ")
        assertTrue("onClick = { if (!unresolved) onClick() }" in row,
            "selecting cannot bypass the disabled unresolved-track row")
    }

    @Test fun collectionPaginationFollowsContinuationChangesAndExposesAnExplicitRetry() {
        val page = SourceContract.code(SourceContract.read(collectionFile)).replace(Regex("\\s+"), " ")
        assertTrue("if (collection.continuation != null || collection.paginationError != null)" in page,
            "the paging row remains visible for a continuation or an actionable failure")
        assertTrue("LaunchedEffect(collection.continuation, songs.size)" in page,
            "a continuation-only page can advance even when the loaded song count stays unchanged")
        assertTrue("if (collection.continuation != null && collection.paginationError == null) onLoadMore()" in page,
            "reaching the paging row loads the next page but does not loop automatically on a failure")
        assertTrue("collection.paginationError?.let { Text(it," in page,
            "a failed page reports its error inline")
        assertTrue("if (loadingMore) CosmoLoadingBlob(" in page && "else if (collection.continuation != null) CosmoButton(" in page &&
            "onClick = onLoadMore" in page,
            "loading feedback and an explicit load/retry action remain reachable")
        assertTrue("text = if (collection.paginationError != null) DesktopStrings[\"retry\", \"Try again\"] else DesktopStrings[\"load_more\", \"Load more\"]" in page,
            "the idle action identifies retry versus loading another page truthfully")
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
