package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What cannot be seen without a window, read from the sources (there is no Compose UI test harness here and none is to be
 * added): the three entry switches of the Library, the "Show all" grid and History, the five call sites, and the rules the new
 * pages were written under. The pure rules are `LibrarySegmentsTest` in `shared`.
 */
class LibraryContractTest {
    private val library = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/library"
    private val oldLibrary = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/LibraryScreen.kt"
    private val oldHistory = "app/src/main/java/com/music/bitchord/ui/screens/HistoryScreen.kt"
    private val historySwitch = "app/src/main/java/com/cosmotune/ui/library/HistorySwitch.kt"
    private val mainActivity = "app/src/main/java/com/music/bitchord/MainActivity.kt"
    private val desktopApp = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt"

    private fun lib(name: String): String = SourceContract.read("$library/$name.kt")

    private fun params(src: String, name: String): List<String> = SourceContract.parameters(src, name)

    // ---- the seams: same list as the old screen -----------------------------------------------------------------------

    @Test fun libraryScreenAndItsReplacementsTakeTheSameList() {
        val old = params(SourceContract.read(oldLibrary), "LibraryScreen")
        assertEquals(19, old.size, "the old screen's list was read (${old.size})")
        assertEquals(old, params(lib("LibrarySwitch"), "LibrarySwitch"))
        assertEquals(old, params(lib("CosmoLibrary"), "CosmoLibrary"))
    }

    @Test fun libraryGridPageAndItsReplacementsTakeTheSameList() {
        val old = params(SourceContract.read(oldLibrary), "LibraryGridPage")
        assertEquals(7, old.size, "the old grid page's list was read (${old.size})")
        assertEquals(old, params(lib("LibraryGridSwitch"), "LibraryGridSwitch"))
        assertEquals(old, params(lib("CosmoLibraryGrid"), "CosmoLibraryGrid"))
    }

    @Test fun historyScreenAndItsReplacementsTakeTheSameList() {
        val old = params(SourceContract.read(oldHistory), "HistoryScreen")
        assertEquals(10, old.size, "the old history screen's list was read (${old.size})")
        assertEquals(old, params(SourceContract.read(historySwitch), "HistorySwitch"))
        assertEquals(old, params(lib("CosmoHistory"), "CosmoHistory"))
    }

    // ---- the seams: both branches, by name ---------------------------------------------------------------------------

    /** The new branch (after the `if`, before `} else {`) and the old branch of a switch's first statement. */
    private fun branches(src: String, name: String): Pair<String, String> {
        val statement = SourceContract.firstStatement(SourceContract.functionBody(src, name))
        assertTrue(statement.startsWith("if (LocalCosmoShell.current != null) {"), "$name starts with the shell test: $statement")
        val body = statement.removePrefix("if (LocalCosmoShell.current != null) {")
        assertTrue("} else {" in body, "$name has an else branch")
        return body.substringBefore("} else {") to body.substringAfter("} else {")
    }

    private fun checkSwitch(src: String, name: String, newCall: String, oldCall: String, oldParams: List<String>) {
        val (new, old) = branches(src, name)
        assertTrue(new.trim().startsWith("$newCall("), "$name's new branch is $newCall: ${new.trim().take(60)}")
        assertTrue(old.trim().startsWith("$oldCall("), "$name's old branch is $oldCall: ${old.trim().take(60)}")
        val names = SourceContract.names(oldParams)
        SourceContract.assertPassesEveryParameter(new, names)
        SourceContract.assertPassesEveryParameter(old, names)
    }

    @Test fun bothBranchesOfLibrarySwitchPassEveryParameterByName() =
        checkSwitch(lib("LibrarySwitch"), "LibrarySwitch", "CosmoLibrary", "LibraryScreen", params(SourceContract.read(oldLibrary), "LibraryScreen"))

    @Test fun bothBranchesOfLibraryGridSwitchPassEveryParameterByName() =
        checkSwitch(lib("LibraryGridSwitch"), "LibraryGridSwitch", "CosmoLibraryGrid", "LibraryGridPage", params(SourceContract.read(oldLibrary), "LibraryGridPage"))

    @Test fun bothBranchesOfHistorySwitchPassEveryParameterByName() =
        checkSwitch(SourceContract.read(historySwitch), "HistorySwitch", "CosmoHistory", "HistoryScreen", params(SourceContract.read(oldHistory), "HistoryScreen"))

    @Test fun theOldScreensAreStillWhatTheyWereCalledAndAreNotEditedHere() {
        // the old functions keep their names and the file they live in: the switch is the only place that changed
        assertTrue(Regex("""\bfun LibraryScreen\(""").containsMatchIn(SourceContract.read(oldLibrary)))
        assertTrue(Regex("""\bfun LibraryGridPage\(""").containsMatchIn(SourceContract.read(oldLibrary)))
        assertTrue(Regex("""\bfun HistoryScreen\(""").containsMatchIn(SourceContract.read(oldHistory)))
        assertTrue("LocalCosmoShell" !in SourceContract.read(oldLibrary) && "LocalCosmoShell" !in SourceContract.read(oldHistory), "the old screens know nothing of the look")
    }

    // ---- the five call sites ------------------------------------------------------------------------------------------

    private fun calls(src: String, name: String) = Regex("""\b${Regex.escape(name)}\(""").findAll(src).count()

    @Test fun theAndroidRootCallsTheThreeSwitchesAndNotTheOldScreens() {
        val src = SourceContract.code(SourceContract.read(mainActivity))
        assertEquals(1, calls(src, "com.cosmotune.ui.library.LibrarySwitch"), "the Library")
        assertEquals(1, calls(src, "com.cosmotune.ui.library.LibraryGridSwitch"), "the show-all grid")
        assertEquals(1, calls(src, "com.cosmotune.ui.library.HistorySwitch"), "History")
        assertTrue("else -> com.cosmotune.ui.library.LibrarySwitch(" in src, "the Library is the else branch of the page choice")
        for (old in listOf("LibraryScreen", "LibraryGridPage", "HistoryScreen")) {
            assertFalse(Regex("""(?<![\w.])$old\(""").containsMatchIn(src), "MainActivity no longer calls $old directly")
        }
        // the grid is inside the show-all branch and History inside the history branch, as the old calls were
        assertTrue(src.indexOf("""key == "history"""") < src.indexOf("com.cosmotune.ui.library.HistorySwitch("))
        assertTrue(src.indexOf("""key == "library_show_all"""") < src.indexOf("com.cosmotune.ui.library.LibraryGridSwitch("))
    }

    @Test fun theDesktopRootCallsTheTwoSwitchesAndNotTheOldScreens() {
        val src = SourceContract.code(SourceContract.read(desktopApp))
        assertEquals(1, calls(src, "com.cosmotune.ui.library.LibrarySwitch"), "the Library")
        assertEquals(1, calls(src, "com.cosmotune.ui.library.LibraryGridSwitch"), "the show-all grid")
        assertTrue("destination == DesktopDestination.LIBRARY -> com.cosmotune.ui.library.LibrarySwitch(" in src)
        for (old in listOf("LibraryScreen", "LibraryGridPage")) {
            assertFalse(Regex("""(?<![\w.])$old\(""").containsMatchIn(src), "DesktopApp no longer calls $old directly")
        }
    }

    @Test fun theTwoRootsPassTheArgumentsTheyAlwaysDid() {
        // the switch's list is the old one, so a call that compiled before still compiles; spot-check the ones the new page reads
        val phone = SourceContract.code(SourceContract.read(mainActivity)).substringAfter("else -> com.cosmotune.ui.library.LibrarySwitch(").substringBefore("when (settingsSubScreen")
        for (name in listOf("signedIn", "state", "listState", "onNewPlaylist", "onImportSpotifyPlaylist", "onShowAll", "replay", "links", "deviceItems")) {
            assertTrue(Regex("""\b$name = """).containsMatchIn(phone), "the phone passes $name")
        }
        val desk = SourceContract.code(SourceContract.read(desktopApp)).substringAfter("destination == DesktopDestination.LIBRARY -> com.cosmotune.ui.library.LibrarySwitch(").substringBefore("destination == DesktopDestination.HISTORY")
        for (name in listOf("signedIn", "state", "onNewPlaylist", "onShowAll", "replay", "links", "deviceItems", "showTitle")) {
            assertTrue(Regex("""\b$name = """).containsMatchIn(desk), "the desktop passes $name")
        }
    }

    // ---- the rules ----------------------------------------------------------------------------------------------------

    private val pages = listOf("CosmoLibrary", "CosmoLibraryGrid", "CosmoHistory", "LibrarySwitch", "LibraryGridSwitch")

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

    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer|Canvas|layout)\s*(\([^)]*\))?\s*$""")

    @Test fun theLivePaletteIsReadOnlyWhileDrawing() {
        for (name in pages) {
            val code = SourceContract.code(lib(name))
            var from = 0
            while (true) {
                val at = code.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (code[at - 1].isLetterOrDigit() || code[at - 1] == '_')) continue
                assertTrue(enclosingHeads(code, at).any { drawHead.containsMatchIn(it) }, "$name: live( at line ${code.take(at).count { it == '\n' } + 1} is not in a draw-phase lambda")
            }
        }
    }

    @Test fun noColourIsPickedByHandAndNoBrushIsRemembered() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\(|cssGradient|Fill\()""")
        for (name in pages) {
            val code = SourceContract.code(lib(name))
            assertFalse(Regex("""Color\(0x""").containsMatchIn(code), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|White|Black)\b""").containsMatchIn(code), "$name uses a named colour")
            assertTrue(remembering.find(code) == null, "$name remembers a brush or a live colour")
        }
    }

    @Test fun theLibraryHasFourSegmentsInOrderAndKeepsTheChosenOneAcrossRecreation() {
        val code = SourceContract.code(lib("CosmoLibrary"))
        val labels = Regex("""stringResource\(Res\.string\.(\w+)\),\s*stringResource\(Res\.string\.(\w+)\),\s*stringResource\(Res\.string\.(\w+)\),\s*stringResource\(Res\.string\.(\w+)\)""").find(code)
            ?: fail("the four segment labels are listed together")
        assertEquals(listOf("playlists", "albums", "artists", "downloaded"), labels.groupValues.drop(1))
        assertTrue(Regex("""var segmentIndex by rememberSaveable""").containsMatchIn(code), "the segment is saved")
        assertTrue("CosmoSegmented(" in code)
        // P4-19: the four share the row instead of scrolling sideways on a 360 dp phone
        assertTrue("fill = true" in code && "horizontalScroll(" !in code, "the four segments share the row: no sideways scroll")
    }

    @Test fun theHeadingIsTheDisplayStepAndTheNewPlaylistButtonIsATonal40DpIconButton() {
        val code = SourceContract.code(lib("CosmoLibrary"))
        assertTrue("TypeScale.DISPLAY_L else TypeScale.DISPLAY_M" in code, "DISPLAY_M, DISPLAY_L from 840 dp")
        assertTrue("WIDE_PAGE_DP = 840f" in code)
        assertTrue(Regex("""CosmoIconButton\(CosmoIcons\.Plus, stringResource\(Res\.string\.new_playlist\), onNewPlaylist, style = CosmoIconButtonStyle\.TONAL, size = 40\.dp\)""").containsMatchIn(code))
        assertTrue("heading()" in code, "the title is a heading")
        assertFalse(Regex("""\bif \(showTitle\)""").containsMatchIn(code), "the title shows on both platforms: showTitle is not read")
    }

    @Test fun everyPlaylistRowHasTheMoreButtonAndAHoldThatOpenTheSameMenu() {
        val code = SourceContract.code(lib("CosmoLibrary"))
        assertTrue(Regex("""icon = CosmoIcons\.MoreVertical,.*?onClick = onClick,.*?style = CosmoIconButtonStyle\.PLAIN,\s*size = 40\.dp""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(code), "the more button: three dots, PLAIN, 40 dp")
        assertTrue("MoreButton { onLongPress(item) }" in code, "the more button opens what a hold opens")
        assertTrue("onLongPress = { onLongPress(item) }" in code, "a hold on a row opens the menu")
        assertTrue("onLongPress = { onLongPress(mix) }" in code, "a hold on the mix tile opens the menu")
        assertTrue("contentDescription = stringResource(Res.string.more)" in code, "the more button is named")
        assertTrue("contentDescription = stringResource(Res.string.pinned)" in code, "the pin mark is named")
    }

    @Test fun thePlaylistsPageDrawsEachPlaylistOnceAndReadsThePinsFromTheHost() {
        val code = SourceContract.code(lib("CosmoLibrary"))
        assertTrue("LibrarySegments.playlistLayout(" in code, "the tiles and the rows come from one split")
        assertTrue("AppUi.host.pinnedPlaylists.collectAsStateWithLifecycle()" in code)
        assertTrue("LibrarySegments.otherShelves(shelves)" in code, "podcasts and the other shelves stay as rails")
        assertTrue("LibrarySegments.RAIL_MAX_ITEMS" in code, "the old cap of five")
        assertTrue("CosmoLikedTile(" in code && "CosmoMixTile(" in code)
        assertTrue(Regex("""leading = CosmoRowLeading\.IconPainter\(painterResource\(Res\.drawable\.spotify_logo\)\)""").containsMatchIn(code), "the Import row keeps the Spotify mark")
        assertTrue(Regex("""if \(onImportSpotifyPlaylist != null\)""").containsMatchIn(code), "Import only where this build has it")
    }

    @Test fun theSignedOutAndTheLoadingAndTheErrorStatesAreKept() {
        val code = SourceContract.code(lib("CosmoLibrary"))
        assertTrue("librarySkeleton()" in code, "loading: the library skeleton")
        assertTrue(Regex("""MessageState\(state\.message, actionLabel = stringResource\(Res\.string\.retry\), onAction = onRetry\)""").containsMatchIn(code), "error: message and retry")
        assertTrue(Regex("""message = stringResource\(Res\.string\.library_sign_in_description\)""").containsMatchIn(code), "signed out: the sign-in message")
        assertTrue("onAction = onSignIn" in code)
        // Downloaded is the device's own and does not wait for the account's library
        val downloadedBranch = code.substringAfter("LibrarySegment.DOWNLOADED ->").substringBefore("else ->")
        assertTrue("downloadedSegment(" in downloadedBranch && "state" !in downloadedBranch.replace("onShelfItemClick", ""), "Downloaded does not read the library's state")
    }

    @Test fun theDownloadedSegmentListsTheFoldersWithAChevronAndTheReleasesAsCards() {
        val code = SourceContract.code(lib("CosmoLibrary"))
        assertTrue("CosmoOverline(stringResource(Res.string.cosmo_library_folders))" in code)
        assertTrue("CosmoOverline(stringResource(Res.string.on_device))" in code)
        assertTrue("CosmoRowLeading.IconPainter(painterResource(link.logo))" in code && "CosmoRowLeading.Icon(link.icon)" in code, "a folder with a logo shows it")
        assertTrue(Regex("""Icon\(CosmoIcons\.ChevronRight, contentDescription = null, tint = Color\(palette\.textTertiary\), modifier = Modifier\.size\(20\.dp\)\)""").containsMatchIn(code), "the chevron: 20 dp, textTertiary")
        assertTrue(Regex("""CARD_GAP_ACROSS = 14\.dp""").containsMatchIn(code) && Regex("""CARD_GAP_DOWN = 18\.dp""").containsMatchIn(code), "libAlbums: 14 across, 18 down")
    }

    @Test fun theArtistsAreRoundCoversAndTheAlbumsAreCards() {
        val code = SourceContract.code(lib("CosmoLibrary"))
        assertTrue("CosmoRowLeading.Cover(item.thumbnailUrl, circle = true)" in code)
        assertTrue(Regex("""cardRows\("albums"""").containsMatchIn(code))
        assertTrue("libraryGrid(maxWidth - CosmoSpacing.margin * 2).columns" in code, "wider windows take libraryGrid's columns")
    }

    @Test fun theGridIsFixedColumnsFromLibraryGridWithTheOldSpacingAndALeadingCell() {
        val code = SourceContract.code(lib("CosmoLibraryGrid"))
        assertTrue("GridCells.Fixed(grid.columns)" in code && "libraryGrid(maxWidth - CosmoSpacing.margin * 2)" in code)
        assertTrue("Arrangement.spacedBy(LIBRARY_GRID_SPACING)" in code && "Arrangement.spacedBy(20.dp)" in code)
        assertTrue("AppUi.host.pinnedPlaylists" in code && "AppUi.host.librarySort" in code, "pin and sort are read live")
        assertTrue("LibrarySegments.sorted(LibrarySegments.pinnedFirst(" in code, "pinned first, then the explicit sort")
        assertTrue("item(key = \"leading\")" in code && "if (onNewPlaylist != null)" in code, "the new-playlist cell leads, only when asked")
        assertTrue(Regex("""\.size\(52\.dp\)""").containsMatchIn(code) && "CosmoIcons.Plus" in code, "a 52 dp tonal circle with the plus")
        assertTrue("cosmoTile(shape)" in code && "aspectRatio(1f)" in code, "a cosmoTile square")
        assertTrue("Res.string.new_playlist" in code && "Res.string.saved_to_youtube_music" in code)
        assertTrue("key = { index, item -> shelfKey(item, index) }" in code, "every card has a key that cannot repeat")
    }

    @Test fun historyIsSongRowsWithoutDividersAndKeyedOnTheIdAndTheIndex() {
        val code = SourceContract.code(lib("CosmoHistory"))
        assertTrue("CosmoSongRow(" in code)
        assertFalse("HorizontalDivider" in code || "Divider" in code, "no dividers")
        assertTrue("key = { \"\${songs[it].videoId}:\$it\" }" in code, "the keys of the old page")
        assertTrue("songListSkeleton(count = 10, keyPrefix = \"skeleton:history\")" in code)
        assertTrue(Regex("""MessageState\(message = state\.message, actionLabel = stringResource\(Res\.string\.retry\), onAction = onRetry\)""").containsMatchIn(code))
        assertTrue("onClick = { onSongClick(songs, index) }" in code && "onLongPress = { onSongLongPress(song) }" in code && "onSwipeToQueue = { onSongSwipe(song) }" in code)
        assertTrue("song.isSameTrackAs(currentSong)" in code && "isPlaying = current && isPlaying" in code)
    }

    @Test fun everyStringTheNewPagesNameExists() {
        val strings = SourceContract.read("sharedUi/src/commonMain/composeResources/values/strings.xml")
        val keys = Regex("""name="(\w+)"""").findAll(strings).map { it.groupValues[1] }.toSet()
        for (name in pages) {
            for (hit in Regex("""Res\.string\.(\w+)""").findAll(SourceContract.code(lib(name)))) {
                assertTrue(hit.groupValues[1] in keys, "$name uses Res.string.${hit.groupValues[1]}, which strings.xml does not have")
            }
        }
        val files = File(SourceContract.root, library).listFiles { f -> f.extension == "kt" }.orEmpty().map { it.nameWithoutExtension }.toSet()
        assertEquals(setOf("CosmoLibrary", "CosmoLibraryGrid", "CosmoHistory", "LibrarySwitch", "LibraryGridSwitch"), files, "the files of the library package")
    }
}
