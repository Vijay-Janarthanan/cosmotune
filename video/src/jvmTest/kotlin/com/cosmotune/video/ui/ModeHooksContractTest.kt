package com.cosmotune.video.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mode switch and the root hooks (plan task Y-3). Nothing here can be seen without a window, so the sources are read: the two
 * upstream roots hold only the hooks of the hook table (and nothing else of Video), Music's root is drawn through the same slot
 * whatever the look, the drawer stores the mode, the desktop chrome follows it, and Music never imports Video.
 */
class ModeHooksContractTest {
    private val mainActivity = "app/src/main/java/com/cosmotune/music/MainActivity.kt"
    private val desktopApp = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"
    private val phoneHost = "app/src/main/java/com/cosmotune/video/host/PhoneModeHost.kt"
    private val desktopHost = "desktopApp/src/main/kotlin/com/cosmotune/video/host/DesktopModeHost.kt"
    private val drawer = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/chrome/CosmoProfileDrawer.kt"
    private val desktopDir = "desktopApp/src/main/kotlin/com/cosmotune/desktop/"

    private fun read(path: String) = VideoSourceContract.read(path)

    /** The file without comments, white space collapsed to single spaces. */
    private fun flat(path: String) = VideoSourceContract.code(read(path)).replace(Regex("\\s+"), " ")

    private fun count(text: String, what: String) = Regex(Regex.escape(what)).findAll(text).count()

    private val rule = "mode == AppMode.VIDEO && AppModes.available(AppMode.VIDEO)"

    private val importOfVideo = Regex("""(?m)^\s*import\s+com\.cosmotune\.video\b""")

    // ---- MainActivity.kt (hook table row 6) -------------------------------------------------------------------------------------

    @Test fun mainActivityDrawsTheRootInsideThePhoneModeHostAndNamesVideoNowhereElse() {
        val wrap = "BoxWithConstraints(Modifier.fillMaxSize()) { com.cosmotune.video.host.PhoneModeHost(windowWidth = maxWidth, windowHeight = maxHeight) { " +
            "CosmotuneApp( darkTheme = darkTheme, windowWidth = maxWidth, windowHeight = maxHeight, appBackdrop = appBackdrop, ) } }"
        assertEquals(1, count(flat(mainActivity), wrap), "the CosmotuneApp call and its arguments stand untouched inside the host's block")
        assertEquals(1, count(read(mainActivity), "com.cosmotune.video"), "the host is the only mention of Video in the file")
        assertFalse(importOfVideo.containsMatchIn(read(mainActivity)), "a full name, not an import")
    }

    // ---- DesktopApp.kt (hook table rows 8 and 9) --------------------------------------------------------------------------------

    @Test fun desktopAppHoldsTheTwoLinesOfTheHookTableAtTheirPlaces() {
        val text = read(desktopApp)
        val page = "if (com.cosmotune.video.host.desktopVideoShown()) return@DesktopFrame com.cosmotune.video.host.DesktopVideoPage(contentPadding)"
        val keys = "if (com.cosmotune.video.host.DesktopModeHost.active) return@onPreviewKeyEvent false"
        val lines = text.lines().map { it.trim() }
        assertEquals(1, lines.count { it == page }, "Video's page stands in place of Music's")
        assertEquals(1, lines.count { it == keys }, "the music shortcuts stand aside")
        assertEquals(") { contentPadding ->", lines[lines.indexOf(page) - 1], "the first line inside the content lambda")
        assertEquals("modifier = Modifier.onPreviewKeyEvent { event ->", lines[lines.indexOf(keys) - 1], "the first line inside the key handler")
        // Y-26 adds two provider lines of its own (the plan's hook table, row 10): they are the only other mentions allowed.
        val later = listOf(
            "val videoModeShown = com.cosmotune.video.host.desktopVideoShown()",
            "com.cosmotune.video.host.LocalDesktopPlaySong provides com.cosmotune.video.host.rememberStableSongAction { playSong(it) },",
            "com.cosmotune.ui.modes.LocalModeLinks provides com.cosmotune.video.host.rememberDesktopModeLinks(),",
        )
        val others = lines.filter { "com.cosmotune.video" in it } - setOf(page, keys) - later.toSet()
        assertEquals(emptyList(), others, "no other line of the file names Video")
        assertFalse(importOfVideo.containsMatchIn(text), "full names, not imports")
    }

    @Test fun theMusicSongNeverRewritesVideosAccentAndTheRootCallIsUntouched() {
        // The palette follower is still the root's second line (UpstreamHooksContractTest); Video's accent is protected inside
        // FollowCover, which is ours, and not by a third hook line in DesktopApp.kt.
        val follow = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/theme/FollowCover.kt")
        assertTrue("val videoShown = settings.mode.collectAsState().value == AppMode.VIDEO && AppModes.available(AppMode.VIDEO)" in follow)
        assertTrue("if (videoShown) return@SideEffect" in follow, "while Video is drawn the Music song does not rewrite the accent")
        assertTrue(follow.indexOf("if (videoShown) return@SideEffect") < follow.indexOf("coverUrl = url"), "before the first write")
    }

    // ---- The phone's host -------------------------------------------------------------------------------------------------------

    @Test fun thePhoneHostDrawsMusicThroughOneSlotWhateverTheLookAndChecksThatVideoIsAvailable() {
        val code = VideoSourceContract.code(read(phoneHost))
        val flat = flat(phoneHost)
        assertEquals(
            listOf("windowWidth: Dp", "windowHeight: Dp", "music: @Composable () -> Unit"),
            VideoSourceContract.parameters(read(phoneHost), "PhoneModeHost"),
        )
        assertEquals(1, count(flat, "holder.SaveableStateProvider(\"music\") { music() }"), "Music's root is drawn in one slot, with no argument of the host")
        assertEquals(1, count(flat, "holder.SaveableStateProvider(\"video\")"))
        assertTrue(flat.indexOf("SaveableStateProvider(\"music\")") < flat.indexOf("SaveableStateProvider(\"video\")"), "Music is the first branch")
        assertTrue("if (videoShell == null) holder.SaveableStateProvider(\"music\")" in flat, "Music whenever Video is not the mode that is drawn")
        assertTrue("shell?.takeIf { $rule }" in flat, "Video needs a shell (the look on), the stored mode and its availability")
        assertEquals(1, count(code, "rememberSaveableStateHolder()"))
        assertTrue("shell?.settings?.mode?.collectAsStateWithLifecycle()?.value ?: AppMode.MUSIC" in flat, "Music where there is no shell to ask")
        assertFalse("music(" in flat.substringAfter("holder.SaveableStateProvider(\"video\")"), "Video's branch draws nothing of Music")
    }

    @Test fun thePhoneLinksLeaveForMusicPlayAsASongAndPassOnPendingLinks() {
        val src = read(phoneHost)
        val flat = flat(phoneHost)
        assertTrue("switchToMusic = { settings.setMode(AppMode.MUSIC) }" in flat)
        val play = flat.substringAfter("playAsSong = {").substringBefore("pauseMusic")
        assertTrue(play.indexOf("settings.setMode(AppMode.MUSIC)") in 0 until play.indexOf("MusicLink.consume("), "the mode first, so Video does not take the request for its own")
        assertTrue("\"https://music.youtube.com/watch?v=\"" in src && "Intent.ACTION_VIEW" in src, "Music serves it as it serves a shared link")
        assertTrue("pauseMusic = {}" in flat, "audio focus pauses Music when the video starts")
        assertTrue("onPendingHandled = { if (asked.value != null) asked.value = null else MusicLink.handled() }" in flat)
        assertTrue(
            "pendingVideo = { asked.value ?: if (settings.mode.value == AppMode.VIDEO) videoOf(request.value) else null }" in flat,
            "a video Music asked for first, then a pending track link; both snapshot state",
        )
        assertTrue("val request = MusicLink.pending.collectAsStateWithLifecycle()" in flat, "the pending link is read as state, so one that arrives while Video is open is noticed")
        assertTrue("if (belongsToMusic(request.value)) shell.settings.setMode(AppMode.MUSIC)" in flat, "a page, a search and Resume switch to Music")
        assertTrue("LaunchedEffect(request.value)" in flat)
        assertTrue("What leaves composition when the mode flips" in src, "the KDoc says what a flip leaves behind")
    }

    // ---- The desktop's host -----------------------------------------------------------------------------------------------------

    @Test fun theDesktopHostHasTheRuleTheFlagThePageAndTheSongAction() {
        val flat = flat(desktopHost)
        assertTrue("object DesktopModeHost {" in flat && "@Volatile var active: Boolean = false" in flat)
        assertTrue("fun desktopVideoShown(): Boolean {" in flat)
        assertTrue("val shown = shell != null && $rule" in flat, "the phone's rule")
        assertTrue("SideEffect { DesktopModeHost.active = shown }" in flat)
        assertTrue("fun DesktopVideoPage(contentPadding: PaddingValues) {" in flat)
        assertTrue("val LocalDesktopPlaySong = staticCompositionLocalOf<((Song) -> Unit)?> { null }" in flat)
        assertTrue("switchToMusic = { shell.settings.setMode(AppMode.MUSIC) }" in flat)
        val play = flat.substringAfter("playAsSong = {").substringBefore("pauseMusic")
        assertTrue(play.indexOf("setMode(AppMode.MUSIC)") in 0 until play.indexOf("playSong.value?.invoke("), "switch first, then play the song if the root provides the action")
        assertTrue("pauseMusic = { if (playing.value) playPause.value?.invoke() }" in flat)
        assertTrue("LocalCosmoPlaying.current" in flat && "LocalCosmoPlayPause.current" in flat)
        assertTrue(
            "pendingVideo = { DesktopModeHost.askedVideo }" in flat && "var askedVideo: PendingVideo? by mutableStateOf(null)" in flat,
            "the pending video is snapshot state",
        )
        assertTrue("VideoDesktopPage(contentPadding, links)" in flat)
    }

    @Test fun thePhoneAndTheDesktopUseOneRuleForWhetherVideoIsShown() {
        for (path in listOf(phoneHost, desktopHost)) assertTrue(rule in flat(path), "$path: $rule")
    }

    @Test fun aStoredModeThatDoesNotWorkBecomesMusicInBothHosts() {
        // The removal procedure switches Video off with one line (spec 4.6): Music's chrome draws only for the stored mode, so a stored
        // Video must not outlive it.
        for (path in listOf(phoneHost, desktopHost)) {
            assertTrue(
                "if (shell != null && !AppModes.available(mode)) LaunchedEffect(mode) { shell.settings.setMode(AppMode.MUSIC) }" in flat(path),
                path,
            )
        }
    }

    // ---- The desktop chrome follows the mode ------------------------------------------------------------------------------------

    @Test fun theSidebarIsVideosWhileTheModeIsVideoAndKeepsTheWindowCaption() {
        val src = read(desktopDir + "CosmoDesktopSidebar.kt")
        assertEquals(
            "if (com.cosmotune.video.host.desktopVideoShown()) return VideoSidebar(focusSearch = focusSearch, onSearchFocused = onSearchFocused)",
            VideoSourceContract.firstStatement(VideoSourceContract.functionBody(src, "CosmoDesktopSidebar")),
            "the first statement, read through the composable function so the sidebar recomposes when the mode changes",
        )
        val video = VideoSourceContract.functionBody(src, "VideoSidebar").replace(Regex("\\s+"), " ")
        assertTrue(
            "com.cosmotune.video.ui.desktop.VideoDesktopSidebar( focusSearch = focusSearch, onSearchFocused = onSearchFocused, caption = if (inlineCaption) CAPTION else null, )" in video,
        )
        assertTrue("val inlineCaption = DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled" in video, "the old rule for the caption")
        assertTrue("if (inlineCaption) SidebarCaption()" in flat(desktopDir + "CosmoDesktopSidebar.kt"), "Music's sidebar draws the same caption")
    }

    @Test fun theTopBarFollowsVideosBackAndDrawerEntriesWhileTheModeIsVideo() {
        val body = VideoSourceContract.functionBody(read(desktopDir + "CosmoDesktopChrome.kt"), "CosmoDesktopTopBar").replace(Regex("\\s+"), " ")
        assertTrue("val video = com.cosmotune.video.host.desktopVideoShown()" in body)
        assertTrue("val backEnabled = if (video) com.cosmotune.video.ui.desktop.VideoDesktopBack.canGoBack else canGoBack" in body)
        assertTrue("if (video) ({ com.cosmotune.video.ui.desktop.VideoDesktopBack.goBack() }) else onBack" in body)
        assertTrue("entries = if (video) com.cosmotune.video.ui.desktop.videoDesktopDrawerEntries() else entries," in body)
    }

    @Test fun theMusicPlayerBarAndSidePanelStandAsideWhileTheModeIsVideo() {
        val chrome = read(desktopDir + "CosmoDesktopChrome.kt")
        assertEquals(
            "if (com.cosmotune.video.host.desktopVideoShown()) return",
            VideoSourceContract.firstStatement(VideoSourceContract.functionBody(chrome, "CosmoDesktopPlayerBarHost")),
        )
        val panel = read(desktopDir + "CosmoDesktopSidePanel.kt")
        assertEquals(
            "if (!open || com.cosmotune.video.host.desktopVideoShown()) return",
            VideoSourceContract.firstStatement(VideoSourceContract.functionBody(panel, "CosmoDesktopSidePanel")),
        )
        val card = VideoSourceContract.functionBody(panel, "cosmoDesktopSidePanelCard").replace(Regex("\\s+"), " ")
        assertTrue("if (LocalCosmoShell.current == null) return Modifier" in card, "with the look off the modifier is still the identity")
        assertTrue(
            "if (com.cosmotune.video.host.desktopVideoShown()) return Modifier.layout {" in card && "layout(placeable.width, placeable.height) {}" in card,
            "measured, not placed",
        )
        assertTrue(card.indexOf("desktopVideoShown()") > card.indexOf("LocalCosmoShell.current == null"), "the look-off answer comes first")
        assertTrue(card.endsWith(".cosmoGlass(LocalDesktopHaze.current, shape) }"), "Music's card is what it was")
    }

    // ---- The drawer's switch ----------------------------------------------------------------------------------------------------

    @Test fun theDrawerSwitchStoresTheChosenModeAndNoLongerNamesMusicAsTheCurrentOne() {
        val src = read(drawer)
        val body = VideoSourceContract.functionBody(src, "ModeSwitch").replace(Regex("\\s+"), " ")
        assertEquals(listOf("onChosen: () -> Unit", "onLater: (AppMode) -> Unit"), VideoSourceContract.parameters(src, "ModeSwitch"))
        assertFalse(Regex("""val current = AppMode\.MUSIC\b""").containsMatchIn(body), "the current mode is not hard-wired")
        assertTrue("val settings = LocalCosmoShell.current?.settings" in body)
        assertTrue("val current = settings?.mode?.collectAsState()?.value ?: AppMode.MUSIC" in body, "the stored mode; Music where there is no shell")
        assertTrue("onClick = { if (available) { settings?.setMode(mode); onChosen() } else onLater(mode) }" in body)
        assertTrue("selected = available && mode == current" in body, "a mode that is not available is never the selected one")
        assertTrue("ModeSwitch(onChosen = onDismiss, onLater = { later = it })" in flat(drawer))
        assertFalse("onChooseMusic" in src)
    }

    // ---- The seam ---------------------------------------------------------------------------------------------------------------

    @Test fun nothingOfMusicImportsVideo() {
        val roots = listOf(
            File(VideoSourceContract.root, "sharedUi/src"),
            File(VideoSourceContract.root, "shared/src"),
            File(VideoSourceContract.root, "app/src/main/java/com/music"),
            File(VideoSourceContract.root, "desktopApp/src/main/kotlin/com/music"),
        )
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "/build/" !in it.invariantSeparatorsPath }
                // :shared holds Video's own pure logic under com/cosmotune/video: that is Video, not Music
                .filter { "/com/cosmotune/video/" !in it.invariantSeparatorsPath }
                .filter { importOfVideo.containsMatchIn(it.readText()) }
                .map { it.relativeTo(VideoSourceContract.root).invariantSeparatorsPath }
                .toList()
        }
        assertEquals(emptyList(), offenders, "Music never depends on :video")
    }

    // ---- FORK.md ----------------------------------------------------------------------------------------------------------------

    @Test fun forkMdHasARowForEachUpstreamFileThisTaskEditsWithFourColumns() {
        val rows = read("FORK.md").lines().filter { it.startsWith("|") }
        fun columns(row: String) = row.trim().removePrefix("|").removeSuffix("|").split("|").size
        val phone = rows.filter { it.startsWith("| `app/src/main/java/com/cosmotune/music/MainActivity.kt`") && "com.cosmotune.video.host.PhoneModeHost" in it }
        assertEquals(1, phone.size, "one row for the wrap in MainActivity.kt")
        val desktop = rows.filter { it.startsWith("| `desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt`") && "desktopVideoShown" in it && "DesktopModeHost.active" in it }
        assertEquals(1, desktop.size, "one row for both lines in DesktopApp.kt")
        for (row in phone + desktop) assertEquals(4, columns(row), "four columns: $row")
        val broken = rows.filter { columns(it) != 4 && !it.startsWith("| :--") }
        assertEquals(emptyList(), broken.map { it.take(80) }, "every row of the table has four columns")
    }
}
