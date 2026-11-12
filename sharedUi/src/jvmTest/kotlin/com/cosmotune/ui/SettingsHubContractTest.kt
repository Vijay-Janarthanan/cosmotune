package com.cosmotune.ui

import com.cosmotune.ui.settings.SettingsCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings home and its pages, read from the source: every setting the flat list had is still on a page (the same setter, the
 * same callback), every page holds something on the phone, the desktop draws every section of its own and wires every callback,
 * and the shared row lights up for a search result.
 */
class SettingsHubContractTest {
    private val sheetPath = "app/src/main/java/com/cosmotune/music/ui/screens/SettingsSheet.kt"
    private val desktopPath = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"
    private val blocksPath = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/settings/CosmoSettingsBlocks.kt"

    private val sheet = SourceContract.code(SourceContract.read(sheetPath))
    private val desktop = SourceContract.code(SourceContract.read(desktopPath))

    /** Every AppSettings setter the phone's settings screen called before the settings home: each is still called there. */
    private val phoneSetters = listOf(
        "setAnimatedCanvas", "setAudioCacheLimitBytes", "setAudioQualityCellular", "setAudioQualityWifi", "setAutomixPerformanceMode",
        "setBitPerfectUsb", "setCanvasOverCellular", "setCrossfadeSeconds", "setDolbyAtmos", "setDontRepeatSuggestions", "setDownloadQuality",
        "setExportDownloads", "setFilterNonMusicAudio", "setFullBleedArtwork", "setHideSongStatus", "setHideStatusBarInPlayer", "setHideVolumeBar",
        "setHighPerformanceMode", "setLastfmEnabled", "setLastfmSessionKey", "setLastfmUsername", "setLegacyMeshGradient",
        "setListenBrainzToken", "setLocalMusicFolderUri", "setLoudnessNormalization", "setLoudnessOffOnSpeaker", "setLyricsBlur",
        "setOpenCanvasEnabled", "setOpenCanvasResolution", "setOutputPcmMode", "setPerformanceRefreshRate", "setPreferMusicOnly",
        "setPreferUsbDac", "setReduceAnimation", "setReduceDynamicBlur", "setReplayGenres", "setShowCacheFolder", "setShowNerdStats",
        "setShowQueueHistory", "setSignatureApiEnabled", "setSkipSilence", "setSmartFadeEnabled", "setSmartVersionAlignment", "setSpatialAudio",
        "setStopOnTaskRemoved", "setSwipeToPlayNext", "setSyncedLyrics", "setThemeMode", "setWifiOnlyDownloads",
    )

    @Test fun everyPhoneSettingIsStillWritten() {
        val body = SourceContract.functionBody(SourceContract.read(sheetPath), "SettingsScreen")
        for (setter in phoneSetters) {
            assertTrue(Regex("""AppSettings(::|\.)$setter\b""").containsMatchIn(body), "SettingsScreen still calls AppSettings.$setter")
        }
        // The one setting that went with the old look: Liquid glass dressed the old bars, which are gone.
    }

    @Test fun everyPhoneCallbackIsStillReachable() {
        val src = SourceContract.read(sheetPath)
        val body = SourceContract.functionBody(src, "SettingsScreen")
        val callbacks = SourceContract.names(SourceContract.parameters(src, "SettingsScreen"))
            .filter { it.startsWith("on") && it != "onSignOut" }
        assertTrue(callbacks.size >= 13, "the callbacks: $callbacks")
        for (callback in callbacks) assertTrue(Regex("""\b$callback\b""").containsMatchIn(body), "$callback is used on a page")
        // the sheets and confirmations the rows open
        for (state in listOf("picking = QualityTarget.WIFI", "picking = QualityTarget.CELLULAR", "pickingDownloadQuality = true",
            "pickingAutomixPerformance = true", "confirmClearSongCache = true", "confirmImport = true", "showPerformanceWarning = true",
            "localMusicFolderPicker.launch(null)", "exportPicker.launch(Backup.suggestedName())")) {
            assertTrue(body.contains(state), "a row still does: $state")
        }
    }

    @Test fun everyPhoneGroupIsOnAPageAndEveryPageHoldsSomething() {
        val categories = Regex("""SearchableSettingsGroup\(search, category = SettingsCategory\.(\w+)""").findAll(sheet).map { it.groupValues[1] }.toList()
        assertTrue(categories.size >= 25, "the groups: $categories")
        assertTrue("ALL" !in categories, "no group is on the settings home itself")
        for (page in SettingsCategory.pages) assertTrue(page.name in categories, "the phone's ${page.name} page has a group")
        val calls = Regex("""SearchableSettingsGroup\(search,[^\n]*""").findAll(sheet).map { it.value }.toList()
        assertEquals(categories.size, calls.size, "every group names its page: ${calls.filter { "category = SettingsCategory." !in it }}")
    }

    @Test fun thePhoneHomeSearchesAndJumpsToTheRow() {
        val body = SourceContract.functionBody(SourceContract.read(sheetPath), "SettingsScreen")
        assertTrue(body.contains("SettingsHub(") && body.contains("accountCard = {"), "the home lists the categories under the account card")
        assertTrue(body.contains("SettingsIndex.search(hubEntries.asSearchItems() + collector.items, searchQuery)"), "the search reads every page's rows")
        assertTrue(body.contains("highlight.flash(item.id)") && body.contains("selectedCategory = item.category"), "a result opens its page and lights the row")
        assertTrue(body.contains("BackHandler(enabled = handleBack"), "back leaves the page before Settings")
        val group = SourceContract.functionBody(SourceContract.read(sheetPath), "SearchableSettingsGroup")
        assertTrue(group.contains(".settingsHighlight(entry.id, search.highlight)"), "each row can be lit")
        val main = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/music/MainActivity.kt"))
        assertTrue(main.contains("handleBack = !showSpotify && !showDiscord && !showAccountScrobbling"), "the pages Settings opens keep their own back")
    }

    @Test fun theDesktopDrawsEverySectionOnAPageAndWiresEveryCallback() {
        val src = SourceContract.read(desktopPath)
        val enum = desktop.substringAfter("private enum class DesktopSettingsSection(").substringBefore("\n}\n")
        val sections = Regex("""^\s+([A-Z_]+)\(com\.cosmotune\.ui\.settings\.SettingsCategory\.(\w+)\),""", RegexOption.MULTILINE).findAll(enum)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(sections.size >= 20, "the sections: $sections")
        val body = SourceContract.functionBody(src, "DesktopSettingsScreen")
        for ((section, category) in sections) {
            assertTrue(category != "ALL" && SettingsCategory.valueOf(category) in SettingsCategory.pages, "$section is on a page")
            assertTrue(body.contains("DesktopSettingsSection.$section -> {"), "$section is drawn")
        }
        val callbacks = SourceContract.names(SourceContract.parameters(src, "DesktopSettingsScreen")).filter { it.startsWith("on") }
        assertTrue(callbacks.size >= 30, "the callbacks: $callbacks")
        for (callback in callbacks) assertTrue(Regex("""\b$callback\b""").containsMatchIn(body), "$callback is used")
        assertTrue(body.contains("SettingsCategoryRail(") && body.contains("BoxWithConstraints("), "two panes when the window is wide")
        assertTrue(body.contains("LocalSettingsCollector provides collector"), "the search runs every section")
        assertTrue(body.contains("pendingJump = section to item.title") && body.contains("highlight.flash(jump.second)"), "a result scrolls to its section and lights the row")
    }

    @Test fun theDesktopRowsRegisterWithTheSearch() {
        val visible = SourceContract.functionBody(SourceContract.read(desktopPath), "settingsRowVisible")
        assertTrue(visible.contains("collector.add(") && visible.contains("return false"), "in the search pass a row registers and draws nothing")
        val group = SourceContract.functionBody(SourceContract.read(desktopPath), "SettingsGroup")
        assertTrue(group.contains("LocalDesktopSettingsGroupTitle provides title"), "a row knows the group it lives in")
    }

    @Test fun theSharedRowLightsUpAndCardsArePartedByHairlines() {
        val blocks = SourceContract.code(SourceContract.read(blocksPath))
        val row = SourceContract.functionBody(SourceContract.read(blocksPath), "CosmoSettingsRow")
        assertTrue(row.contains(".settingsHighlight(title)"), "a search result finds the desktop's rows by their title")
        val divider = SourceContract.functionBody(SourceContract.read(blocksPath), "CosmoRowDivider")
        assertTrue(divider.contains("drawLine(") && !divider.contains("dividerPlaced"), "a divider is a hairline: the rows stay one card")
        val gap = SourceContract.functionBody(SourceContract.read(blocksPath), "CosmoTileGap")
        assertTrue(gap.contains("dividerPlaced"), "the gap is what parts two tiles")
        assertTrue(blocks.contains("header.uppercase()"), "a group's header is an overline in capitals")
    }
}
