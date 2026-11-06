package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Behaviour of Music mode the Orbit rebuild had dropped, pinned by reading the source (as the other contract tests do): each one
 * names what the person saw go wrong.
 */
class MusicAuditContractTest {
    private fun code(path: String): String = SourceContract.code(SourceContract.read(path))

    private val ui = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui"

    @Test fun nowPlayingPreviousRestartsTheFirstSongAndAPartyLockStillAnswers() {
        val np = code("$ui/nowplaying/CosmoNowPlaying.kt")
        assertTrue("derivedStateOf { position.positionMs > BACK_RESTARTS_AFTER_MS }" in np, "previous knows when it restarts the song")
        assertTrue("enabled = controlsLocked || hasPrevious || pastRestartPoint" in np, "previous is live on the first song once it is under way")
        assertTrue("enabled = controlsLocked || hasNext" in np, "a locked next still says the host holds the controls")
    }

    @Test fun holdingTheHomeHeroOpensItsMenu() {
        val home = code("$ui/home/OrbitHome.kt")
        assertTrue("onHold = heroHeld?.let { held -> onItemLongPress?.let { open -> { open(held) } } }" in home, "the hero is handed the feed's long press")
        assertTrue(".longPressMenuClickable(onClick = onCard, onLongClick = onHold)" in home, "a hold on the card opens the menu")
        assertTrue(".longPressMenuClickable(onClick = onBite, onLongClick = onHold)" in home, "a hold on the play button opens the menu")
    }

    @Test fun theDrawersRoomCardClosesTheDrawer() {
        val drawer = code("$ui/chrome/CosmoProfileDrawer.kt")
        assertTrue("OrbitNavRoomCard(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp), onOpen = onDismiss)" in drawer)
        val card = SourceContract.functionBody(SourceContract.read("$ui/orbit/OrbitNav.kt"), "OrbitNavRoomCard")
        assertTrue("{ onOpen(); room.openRoom() }" in card, "the drawer goes before the room opens")
    }

    @Test fun cardColoursFollowTheSettingAsItChanges() {
        val tint = SourceContract.functionBody(SourceContract.read("$ui/orbit/OrbitKit.kt"), "rememberCoverTint")
        assertTrue("coverColours?.collectAsState()" in tint, "the setting is collected, not read once")
    }

    @Test fun turningCoverColoursBackOnShowsTheCoverAgain() {
        val theme = code("$ui/theme/CosmoLookTheme.kt")
        assertTrue("(shownIntensity != intensity || showedMissing)) fresh" in theme, "after the stand-in the cover is shown as it is")
        assertTrue("showedMissing = missing || (showedMissing && fresh == null)" in theme)
    }

    @Test fun theRoomLandingDoesNotScrollInsideTheScrollingOrbitSheet() {
        val rooms = SourceContract.functionBody(SourceContract.read("app/src/main/java/com/cosmotune/music/ui/screens/RoomsScreen.kt"), "RoomEntrySheets")
        assertTrue("if (com.cosmotune.ui.theme.LocalCosmoShell.current == null) Modifier.verticalScroll(rememberScrollState()) else Modifier" in rooms,
            "Home's Start a room crashed: the landing's own scroll inside CosmoSheetSurface's")
        assertTrue("Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()" !in rooms)
    }

    @Test fun thePlaylistReorderListHasAHeightOfItsOwn() {
        val reorder = code("app/src/main/java/com/cosmotune/music/ui/components/ReorderPlaylistSheet.kt")
        assertTrue("LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = windowHeight * REORDER_LIST_SHARE))" in reorder)
    }

    @Test fun askToPlaySearchesFromTheKeyboard() {
        val host = code("app/src/main/java/com/cosmotune/music/ui/room/RoomHost.kt")
        assertTrue("onSubmit = search," in host && "imeAction = androidx.compose.ui.text.input.ImeAction.Search" in host)
    }
}
