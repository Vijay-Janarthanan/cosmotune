package com.cosmotune.ui

import com.cosmotune.ui.settings.SettingsCategory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The redesign of Podio, Read and Manga (the approved board of 20 Nov 2026), read from the source: what each screen leads with,
 * what moved where, and the wiring a window cannot show (the queue reaching the player, the refresh being scheduled).
 */
class PodioReadMangaContractTest {
    private val podio = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/podio/ui"
    private val manga = "manga/src/jvmSharedMain/kotlin/com/cosmotune/manga/ui"
    private val reading = "manga/src/jvmSharedMain/kotlin/com/cosmotune/reading/ui"
    private fun code(path: String) = SourceContract.code(SourceContract.read(path))
    private fun body(path: String, name: String) = SourceContract.code(SourceContract.functionBody(SourceContract.read(path), name))

    @Test fun theShowPageLeadsWithTheCompactCardAndRowsWithOneButton() {
        val show = body("$podio/PodioPages.kt", "ShowPage")
        assertTrue(show.contains("OrbitPageHeader(") && !show.contains("OrbitCoverTitleCard(") && !show.contains("OrbitReleaseCard("), "the shared compact header, the cover as its disc")
        assertTrue(show.contains("playing = showsPause") && show.contains("val showsPause = loaded != null && env.playback.playing"), "Play shows pause while one of its episodes plays")
        assertTrue(show.contains("OrbitCollapsingHeaderBar(list, OrbitCollapsedHeader("), "the heading collapses into a bar once scrolled away")
        assertTrue(show.contains("\"Unplayed\" + ") && show.contains("Oldest first") && show.contains("Newest first"), "the filters with a count, and the order")
        assertTrue(show.contains("fresh = episode.id in freshIds"), "new episodes carry the peach dot")
        val row = body("$podio/PodioKit.kt", "EpisodeRow")
        assertFalse(row.contains("MoreVertical"), "no second round button on an episode row")
        assertTrue(row.contains("OrbitPlayButton(playing, onToggle") && row.contains("EpisodeDownloadButton("), "play for the one on, else the download")
        assertTrue(row.contains("onLongClick = onMore"), "the sheet on a hold")
    }

    @Test fun thePlayerShowsTheChapterTheTabsAndTheWayToTheQueue() {
        val player = body("$podio/PodioPlayer.kt", "EpisodePlayerSheet")
        assertTrue(player.contains("PodioFormat.previousChapterStart(") && player.contains("\"Next chapter\""), "chapter skips")
        assertTrue(player.contains("\"Chapter \${index + 1} of \${list.size}\""), "the chapter playing")
        assertTrue(player.contains("EpisodeExtras(") && player.contains("\"Up next · \$queued\""), "Chapters, Transcript and Notes, and Up next")
        val extras = code("$podio/PodcastExtrasControls.kt")
        assertTrue(extras.contains("CHAPTERS(\"Chapters\"), TRANSCRIPT(\"Transcript\"), NOTES(\"Notes\")"), "the three panels")
        assertTrue(extras.contains("list.animateScrollToItem("), "the transcript follows the playhead")
        assertFalse(extras.contains("KitSheet("), "no sheet over the sheet")
        val speed = body("$podio/PodioPlayer.kt", "SpeedChip")
        assertTrue(speed.contains("onLongClick = { menu = true }") && speed.contains("Use for this show"), "a hold opens every speed and the show's own")
        assertTrue(code("$podio/PodioApp.kt").contains("m.speedFor(playingEntry?.show?.id)"), "a show's speed is applied when its episode is on")
    }

    @Test fun theQueueReusesMusicsRowsAndReachesThePlayer() {
        val page = code("$podio/PodioQueuePage.kt")
        assertTrue(page.contains("rememberQueueDrag(") && page.contains("QueueTracks("), "Music's queue drag and groups")
        assertTrue(page.contains("\"Play next\"") && page.contains("\"Shuffle\"") && page.contains("\"Keep going\""), "the groups of the board")
        assertTrue(page.contains("endLabel = \"Remove\", onEnd = remove"), "a swipe to the left removes")
        val model = code("$podio/PodioModel.kt")
        assertTrue(model.contains("offerUndo(PodioUndo(\"Removed from the queue\")"), "a removal can be undone")
        assertTrue(model.contains("queuePlay(item, positionMs, upcoming())"), "an episode starts with what follows it")
        val app = code("$podio/PodioApp.kt")
        assertTrue(app.contains("replace(m.upcoming(queued))"), "a change of the queue reaches the player")
        val phone = code("app/src/main/java/com/cosmotune/podio/PhonePodioPage.kt")
        assertTrue(phone.contains("onPlayQueue = {") && phone.contains("onUpcoming = {") && phone.contains("replaceUpcomingEpisodes("), "the phone's player holds the queue")
        val desktop = code("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt")
        assertTrue(desktop.contains("onPlayQueue = { item, position, upcoming ->") && desktop.contains("onUpcoming = { upcoming ->"), "and the desktop's")
        val tracker = code("shared/src/jvmSharedMain/kotlin/com/cosmotune/podio/PodioProgressTracker.kt")
        assertTrue(tracker.contains("queue?.started(next.id)"), "the playback side keeps the queue right when the player moves on")
        assertTrue(code("$podio/PodioPlayer.kt").contains("QueueButton(queued, onQueue"), "the mini player and the bar lead to the queue")
    }

    @Test fun followingIsTheInboxAndTheShowsWithTheirCounts() {
        val following = body("$podio/PodioPages.kt", "FollowingPage")
        assertTrue(following.contains("\"New\" + ") && following.contains("\"Shows \${shows.size}\""), "two segments with counts")
        assertTrue(following.contains("\"Mark all seen\"") && following.contains("swipe = EpisodeSwipe.SEEN"), "the inbox can be cleared")
        assertTrue(following.contains("\"Checked \" + PodioFormat.ago("), "it says when the refresh last ran")
        for (moved in listOf("Export OPML", "Podcast playback", "Your stations", "Latest episodes per show")) {
            assertFalse(following.contains(moved), "$moved is no longer at the foot of Following")
        }
        val settings = code("$podio/PodioSettings.kt")
        for (moved in listOf("Export OPML", "Import OPML", "Latest episodes per show in Following", "Download new episodes", "Check followed shows in the background")) {
            assertTrue(settings.contains(moved), "$moved is in Settings › Podio")
        }
    }

    @Test fun theBackgroundRefreshIsScheduledAndAnnouncesNewEpisodes() {
        val worker = code("app/src/main/java/com/cosmotune/podio/PodioRefreshWorker.kt")
        assertTrue(worker.contains("PodioRefresh.run(library") && worker.contains("NotificationCompat.InboxStyle()"), "it refreshes and notifies")
        assertTrue(worker.contains("if (library.state.value.autoDownloadNew)"), "downloads only when chosen")
        assertTrue(worker.contains("NetworkType.UNMETERED") && worker.contains("ExistingPeriodicWorkPolicy.KEEP"), "on an unmetered connection, scheduled once")
        assertTrue(code("app/src/main/java/com/cosmotune/podio/PhonePodioPage.kt").contains("PodioRefreshWorker.schedule(context, refreshWanted)"), "Podio keeps it scheduled")
        assertTrue(code("shared/src/jvmSharedMain/kotlin/com/cosmotune/podio/PodioCatalogue.kt").contains("val autoDownloadNew: Boolean = false"), "auto-download is off by default")
    }

    @Test fun radioShowsTheCardOnlyOnAirAndListsStationsEitherWay() {
        val radio = body("$podio/PodioRadio.kt", "RadioPage")
        assertTrue(radio.contains("val featured = onAir.station") && !radio.contains("favourites.firstOrNull() ?: m.radio.firstOrNull()"), "no card for a station nobody chose")
        assertTrue(radio.contains("\"Show as a list\"") && radio.contains("StationRow("), "a grid or list switch")
        assertTrue(radio.contains("DiscSkeletonGrid("), "the grid loads as discs")
        assertTrue(code("$podio/PodioRadio.kt").contains("\"Next: \$nextName\""), "the skip target is named on the card")
        assertTrue(code("$podio/PodioRadioDetail.kt").contains("nextName = nextName"), "and on the station page")
    }

    @Test fun readsLibraryLeadsWithTheBookAndTilesAndItsReaderTakesATapInTheMiddle() {
        val library = body("$reading/BooksApp.kt", "BookLibrary")
        assertTrue(library.contains("BookTiles(") && !library.contains("Trending on Open Library"), "quick tiles instead of the shelves")
        assertTrue(library.contains("\"Show as a list\"") && library.contains("BookOrder.entries"), "list view and order")
        val book = body("$reading/BooksApp.kt", "BookLocalDetails")
        assertTrue(book.contains("\"Remove from this device\"") && !book.contains("OrbitCardChip(\"Remove\""), "Remove moves into More")
        assertTrue(book.contains("bookFacts(book)") && !book.contains(".background(look.row).padding(horizontal = 18.dp)"), "flat details")
        val reader = body("$reading/BookReader.kt", "BookReader")
        assertTrue(reader.contains("onCenterTap = { tools = !tools }") && reader.contains("ReaderKeepScreenOn(preferences.keepScreenOn)"), "the middle tap and the screen kept on")
        val appearance = body("$reading/BookReader.kt", "ReadingAppearanceChoices")
        assertTrue(appearance.contains("\"Brightness") && appearance.contains("\"Keep screen on while reading\""), "brightness and keep screen on in the sheet")
    }

    @Test fun mangaOpensOnTheGridWithCountsAndChoosesChaptersByHold() {
        val library = body("$manga/MangaApp.kt", "MangaLibrary")
        assertTrue(library.contains("MangaContinueRow(") && !library.contains("MangaHero(") && !library.contains("\"New chapters\""), "one Continue row, no hero")
        assertTrue(code("$manga/MangaApp.kt").contains("counts?.let(::mangaCountsLine)") && !code("$manga/MangaApp.kt").contains("MangaCountBadge"), "the counts in the line under the cover, nothing on it")
        val details = code("$manga/MangaDetails.kt")
        assertTrue(details.contains("SelectionBar(") && details.contains("selection = selection.toggle(chapter.id)"), "a hold chooses several")
        assertTrue(details.contains("number = chapterNumber(item, index + 1)"), "the chapter number's tile")
        val updates = body("$manga/MangaUpdates.kt", "MangaUpdatesPage")
        assertTrue(updates.contains("\"Mark all read\"") && updates.contains("ReadSkeletonRows(3"), "a count per day, and placeholders while checking")
        val reader = code("$manga/MangaReader.kt")
        assertTrue(reader.contains("ReaderActions({ settings = true }, { readerSettings = true }, { chapters = true }, rotation)"), "Chapters opens a sheet over the reader")
        assertFalse(reader.contains("mangaText(\"Chapters\"), ink, fill, onBack)"), "Chapters is no longer Back")
        assertTrue(reader.contains("\"\${page + 1} / \${pages.size}\""), "the page counter while the card is hidden")
    }

    @Test fun theModesHaveRealSettingsPages() {
        assertTrue(SettingsCategory.MANGA in SettingsCategory.pages, "Manga has a page of its own")
        val sheet = code("app/src/main/java/com/cosmotune/music/ui/screens/SettingsSheet.kt")
        assertTrue(sheet.contains("com.cosmotune.podio.PhonePodioSettings()") && sheet.contains("com.cosmotune.reading.ui.ReadSettingsSection()") &&
            sheet.contains("com.cosmotune.manga.ui.MangaSettingsSection()"), "the phone's pages hold the settings")
        assertTrue(Regex("""ModeLinkRow\(AppMode\.MANGA\b""").containsMatchIn(sheet), "and still lead into the mode")
        val desktop = code("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt")
        assertTrue(desktop.contains("com.cosmotune.podio.ui.PodioSettingsSection(") && desktop.contains("com.cosmotune.manga.ui.MangaSettingsSection()"), "the desktop's too")
        val hub = body("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/settings/SettingsHub.kt", "SettingsHub")
        assertTrue(hub.contains("SettingsText.GROUP_MODES") && hub.contains("SettingsText.GROUP_GENERAL") && hub.contains("current = shownCurrent"), "Modes above General, this mode tagged")
        val sources = body("$manga/MangaSources.kt", "MangaSources")
        assertFalse(sources.contains("Connect Komga library") || sources.contains("Check parser updates") || sources.contains("MangaLibraryTools("), "the Sources tab is the catalogue only")
        assertTrue(code("$manga/MangaSettings.kt").contains("Connect Komga library"), "Komga lives in Settings › Manga")
    }
}
