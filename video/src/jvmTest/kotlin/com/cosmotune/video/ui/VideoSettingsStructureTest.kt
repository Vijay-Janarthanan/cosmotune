package com.cosmotune.video.ui

import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.segments.SegmentAction
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.settings.allowedBehaviours
import com.cosmotune.video.ui.settings.downloadQualityText
import com.cosmotune.video.ui.settings.downloadSubtitleLanguage
import com.cosmotune.video.ui.settings.languageChoices
import com.cosmotune.video.ui.settings.languageName
import com.cosmotune.video.ui.settings.nearestScale
import com.cosmotune.video.ui.settings.qualityLabel
import com.cosmotune.video.ui.settings.regionChoices
import com.cosmotune.video.ui.settings.regionName
import java.lang.reflect.Modifier
import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Video settings (plan task Y-25, step 2): what no render can show, read from the source (every `VideoSettings` field has a row, the
 * three attributions and their links, the Downloads group, the library rows, the platform gates, the look), and the small pieces
 * of logic the page is made of, run (the behaviours a category allows, the country and language lists, the remembered download
 * choice in words).
 */
class VideoSettingsStructureTest {
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/settings"
    private fun read(path: String) = VideoSourceContract.read(path)
    private fun file(name: String) = read("$dir/$name.kt")
    private val names = listOf("VideoSettingsPage", "SegmentSettings", "ChoiceSheet", "RegionList", "SettingsPlatform")

    /** Each file of the page without its comments. */
    private val sources: Map<String, String> get() = names.associateWith { VideoSourceContract.code(file(it)) }
    private val allCode: String get() = sources.values.joinToString("\n")
    private val page: String get() = sources.getValue("VideoSettingsPage")
    private val segments: String get() = sources.getValue("SegmentSettings")
    private val strings: String get() = read("video/src/commonMain/composeResources/values/strings.xml")

    // ---- The contract ----------------------------------------------------------------------------------------------------

    @Test fun thePageHasTheSignatureOfTheBrief() {
        assertEquals(listOf("contentPadding: PaddingValues", "wide: Boolean"), VideoSourceContract.parameters(file("VideoSettingsPage"), "VideoSettingsPage"))
        assertTrue(Regex("""(?m)^@Composable\s+fun VideoSettingsPage\(""").containsMatchIn(page), "a public composable")
    }

    @Test fun theNavHostBranchIsTheSettingsPageAndNothingElseMoved() {
        val host = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt")
        val lines = host.lines()
        val line = lines.single { it.trim().startsWith("is VideoRoute.Settings ->") }
        assertEquals("is VideoRoute.Settings -> com.cosmotune.video.ui.settings.VideoSettingsPage(contentPadding, wide)", line.trim())
        val at = lines.indexOf(line)
        assertTrue(lines[at - 1].isBlank() && lines[at + 1].isBlank(), "the blank lines around the branch stay")
        // Y-34: the next branch is the Import route's, whatever page Y-22 put on it
        assertTrue(lines[at + 2].trim().startsWith("is VideoRoute.Import ->"), "the next branch is still the Import route: ${lines[at + 2].trim()}")
        assertFalse("import com.cosmotune.video.ui.settings" in host, "no import line was added for the page: the name is fully qualified")
    }

    @Test fun thePageReadsAndWritesTheGraphsSettingsAndTheLibrary() {
        assertTrue("graph.settings.settings.collectAsState()" in page, "the settings are the graph's, collected")
        assertTrue("graph.settings.update(" in page, "every change goes through update")
        assertTrue("graph.library.historyPaused.collectAsState()" in page)
        assertTrue("LocalVideoGraph.current" in page && "LocalVideoNavigator.current" in page)
    }

    // ---- Every field has a row ---------------------------------------------------------------------------------------------

    private val fields: List<String> = VideoSettings::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }

    @Test fun theFieldsAreTheOnesTheTestKnows() {
        assertEquals(29, fields.size, "a new field of VideoSettings needs a row (or a word in this test): $fields")
        assertTrue("firstRunSeen" in fields && "homeKiosk" in fields)
    }

    @Test fun everySettingHasARowThatReadsAndWritesIt() {
        // the notice keeps firstRunSeen, Home keeps homeKiosk (the brief)
        for (field in fields - setOf("firstRunSeen", "homeKiosk")) {
            assertTrue(Regex("""\b${field}\b\s*=""").containsMatchIn(allCode), "$field is written by a row: `$field = ...` in a copy")
            assertTrue(Regex("""\.${field}\b""").containsMatchIn(allCode), "$field is read by a row: `.$field`")
        }
        for (kept in listOf("firstRunSeen", "homeKiosk")) assertFalse(kept in allCode, "$kept belongs to the notice and Home, not to this page")
    }

    // ---- Playback and captions -------------------------------------------------------------------------------------------

    @Test fun theQualityRowsOfWifiAndMobileDataOfferAutoAndEveryRungAndDefaultToAuto() {
        assertEquals(listOf(null, 2160, 1440, 1080, 720, 480, 360, 240, 144), VideoSettings.QUALITY_CHOICES)
        assertTrue("VideoSettings.QUALITY_CHOICES" in page, "both rows use the one list")
        assertTrue("video_set_quality_wifi" in page && "video_set_quality_mobile" in page)
        assertTrue("wifiQuality = " in page && "meteredQuality = " in page)
        assertTrue("SettingsChoice.WIFI_QUALITY" in page && "SettingsChoice.MOBILE_QUALITY" in page)
        val defaults = VideoSettings()
        assertNull(defaults.wifiQuality, "Auto on Wi-Fi by default")
        assertNull(defaults.meteredQuality, "Auto on mobile data by default")
    }

    @Test fun theCodecAndTheCaptionSizeAreSegmentedAndEverythingElseOpensASheet() {
        assertTrue(Regex("""CosmoSettingsSegmented\(\s*options = codecs""").containsMatchIn(page), "the codec is segmented")
        assertTrue(Regex("""CosmoSettingsSegmented\(\s*options = sizes""").containsMatchIn(page), "the caption size is segmented")
        assertEquals(2, Regex("""compact = true""").findAll(page).count(), "under a row: the compact one, twice")
        assertTrue("VideoSettings.CAPTION_SCALES" in page, "small, medium and large are the contract's scales")
        assertTrue("CodecPreference.entries" in page)
        for (choice in listOf("WIFI_QUALITY", "MOBILE_QUALITY", "SPEED", "DOUBLE_TAP", "CAPTIONS_LANGUAGE", "TITLE_CASE", "REGION", "CONTENT_LANGUAGE")) {
            assertTrue("SettingsChoice.$choice -> " in page, "the choice $choice opens a ChoiceSheet")
        }
        assertTrue("ChoiceSheet(" in page)
        assertTrue("VideoSettings.DOUBLE_TAP_CHOICES" in page, "5, 10, 15 and 30 seconds")
        assertTrue("SPEEDS" in page && "speedLabel" in page, "the default speed picks from the player's own list")
    }

    @Test fun theCaptionSizeShowsTheNearestOfTheThreeScales() {
        assertEquals(listOf(0.8f, 1f, 1.25f), VideoSettings.CAPTION_SCALES)
        assertEquals(0, nearestScale(0.8f))
        assertEquals(1, nearestScale(1f))
        assertEquals(2, nearestScale(1.25f))
        assertEquals(1, nearestScale(1.1f))
        assertEquals(2, nearestScale(2f))
        assertEquals(0, nearestScale(0f))
    }

    @Test fun theSwitchesAreRowsWithASwitchOrSubRowsAndTheyWriteTheirField() {
        for (field in listOf("autoplay", "resume", "background", "autoPip", "captionsBackground", "segmentsOnBar", "countSkips", "deArrowTitles", "deArrowThumbnails", "dislikes", "hideShorts", "sponsorBlock")) {
            assertTrue(Regex("""copy\($field = \w+\)""").containsMatchIn(allCode), "$field is switched: copy($field = value)")
        }
        assertTrue("CosmoSettingsSubRow(" in page + segments, "a switch that belongs to the option above it is a sub-row")
        assertTrue("CosmoSwitch(" in page, "a switch that stands alone sits at the end of its row")
        assertFalse(Regex("""\bSwitch\(""").containsMatchIn(allCode.replace("CosmoSwitch(", "")), "no Material switch")
    }

    @Test fun pictureInPictureIsOfferedOnlyWhereTheWindowCanEnterIt() {
        assertTrue("rememberPictureInPicture()" in page && "pip.supported" in page)
        assertTrue(Regex("""if \(pipSupported\) \{\s*SwitchRow\(VideoIcons\.Pip""").containsMatchIn(page), "the row stands inside the gate")
        assertTrue("Res.string.video_pip" in page && "Res.string.video_set_pip_when" in page, "the row says what it does: Picture in picture, when you leave the app")
    }

    // ---- SponsorBlock ----------------------------------------------------------------------------------------------------------

    @Test fun everyCategoryHasARowWithItsOwnTitleAndTheBehavioursItAllows() {
        assertTrue("SegmentCategory.entries" in segments, "one row per category")
        assertTrue("allowedBehaviours(" in segments)
        for (category in SegmentCategory.entries) {
            val key = "video_seg_title_${category.apiName}"
            assertTrue("name=\"$key\"" in strings, "$key is declared")
            assertTrue("Res.string.$key" in segments, "the row of ${category.name} says $key")
        }
        for (b in listOf("skip", "mute", "ask", "show", "off")) assertTrue("Res.string.video_seg_$b" in segments, "the label video_seg_$b")
        assertTrue("segmentBehaviours" in allCode && "defaultBehaviour" in segments, "a category not in the map shows its default")
        assertTrue("sponsorBlock = " in allCode && "segmentsOnBar = " in allCode && "countSkips = " in allCode)
        assertTrue("video_set_sponsorblock_count_summary" in allCode, "Count skips says what it shares")
        assertTrue("enabled = on" in segments, "the category rows are dimmed while SponsorBlock is off")
    }

    @Test fun theBehavioursACategoryAllowsFollowItsActions() {
        val all = listOf(SegmentBehaviour.SKIP, SegmentBehaviour.MUTE, SegmentBehaviour.ASK, SegmentBehaviour.SHOW, SegmentBehaviour.OFF)
        assertEquals(all, allowedBehaviours(SegmentCategory.SPONSOR))
        assertEquals(all, allowedBehaviours(SegmentCategory.INTRO))
        assertEquals(all, allowedBehaviours(SegmentCategory.FILLER))
        assertEquals(listOf(SegmentBehaviour.SKIP, SegmentBehaviour.ASK, SegmentBehaviour.SHOW, SegmentBehaviour.OFF), allowedBehaviours(SegmentCategory.MUSIC_OFFTOPIC), "no Mute")
        assertEquals(listOf(SegmentBehaviour.SHOW, SegmentBehaviour.OFF), allowedBehaviours(SegmentCategory.POI_HIGHLIGHT))
        assertEquals(listOf(SegmentBehaviour.SHOW, SegmentBehaviour.OFF), allowedBehaviours(SegmentCategory.EXCLUSIVE_ACCESS))
        for (category in SegmentCategory.entries) {
            val allowed = allowedBehaviours(category)
            assertTrue(category.defaultBehaviour in allowed, "${category.name}: its default is one of its choices")
            assertTrue(SegmentBehaviour.SHOW in allowed && SegmentBehaviour.OFF in allowed, "${category.name}: Show and Off are always there")
            assertEquals(SegmentAction.MUTE in category.actions, SegmentBehaviour.MUTE in allowed, "${category.name}: Mute only where the category can mute")
            assertEquals(SegmentAction.SKIP in category.actions, SegmentBehaviour.SKIP in allowed, "${category.name}: Skip only where the category can skip")
            assertEquals(SegmentAction.SKIP in category.actions || SegmentAction.MUTE in category.actions, SegmentBehaviour.ASK in allowed, "${category.name}: Ask where either")
            assertEquals(allowed.distinct(), allowed)
            assertEquals(allowed.sortedBy { it.ordinal }, allowed, "${category.name}: in the order Skip, Mute, Ask, Show, Off")
        }
    }

    // ---- The three attributions -----------------------------------------------------------------------------------------------

    @Test fun theThreeAttributionsAreLinks() {
        val links = mapOf(
            "video_set_using_sponsorblock" to "https://sponsor.ajay.app/",
            "video_set_using_dearrow" to "https://dearrow.ajay.app/",
            "video_set_dislikes_credit" to "https://returnyoutubedislike.com/",
        )
        // read as written: the comment stripper of the helper would cut an address at its two slashes
        val raw = names.joinToString("\n") { file(it) }
        for ((key, url) in links) {
            assertTrue("\"$url\"" in raw, "the link $url is in the page")
            assertTrue(Regex("""AttributionLink\(\s*text = stringResource\(Res\.string\.$key\),\s*url = [A-Z_]+\)""").containsMatchIn(page + segments), "$key is the text of a link to its own address")
        }
        assertEquals(3, Regex("""\bAttributionLink\(\s*text = """).findAll(page + segments).count(), "three attributions, three links")
        assertTrue("LocalUriHandler.current" in allCode && "openUri(" in allCode, "the link opens the system's browser")
        assertTrue("CosmoButtonKind.TEXT" in allCode, "a link is a text button: a button with its label and a 48 dp target")
        // each address stands behind a constant that the link names
        assertTrue("SPONSORBLOCK_URL = \"https://sponsor.ajay.app/\"" in raw)
        assertTrue("DEARROW_URL = \"https://dearrow.ajay.app/\"" in raw)
        assertTrue("DISLIKES_URL = \"https://returnyoutubedislike.com/\"" in raw)
        val urls = Regex("""https://[\w./-]+""").findAll(raw).map { it.value }.toSet()
        assertEquals(links.values.toSet(), urls, "no other address is linked")
    }

    // ---- Content and library ------------------------------------------------------------------------------------------------------

    @Test fun contentHasHideShortsTheRegionAndTheContentLanguage() {
        for (key in listOf("video_set_hide_shorts", "video_set_region", "video_set_language", "video_set_content")) assertTrue("Res.string.$key" in page, key)
        assertTrue("hideShorts = " in page && "region = " in page && "contentLanguage = " in page)
        assertTrue("regionChoices(" in page && "languageChoices(" in page)
        assertTrue("Locale.getISOCountries()" in sources.getValue("RegionList"))
        assertTrue("Locale.getISOLanguages()" in sources.getValue("RegionList"))
    }

    @Test fun theLibraryRowsPauseClearAndOpenImportAndExport() {
        for (key in listOf("video_pause_history", "video_clear_history", "video_set_clear_search", "video_set_import", "video_set_export", "video_set_library")) assertTrue("Res.string.$key" in page, key)
        assertTrue("historyPaused" in page && "setHistoryPaused" in page)
        assertEquals(1, Regex("""navigator\.push\(VideoRoute\.Import\)""").findAll(page).count(), "the one way to the Import page")
        assertEquals(2, Regex("""onClick = openImport\)""").findAll(page).count(), "Import and Export both go to the Import page, where export lives")
        // both clears ask first: the call is inside the dialog's confirm, never in the row's own click
        assertTrue(Regex("""onConfirm = \{ graph\.library\.clearSearches\(\) \}""").containsMatchIn(page), "clearing the searches waits for the confirmation")
        assertTrue(Regex("""onConfirm = \{ graph\.library\.clearHistory\(\) \}""").containsMatchIn(page), "clearing the history waits for the confirmation")
        assertEquals(1, Regex("""\.clearSearches\(\)""").findAll(page).count())
        assertEquals(1, Regex("""\.clearHistory\(\)""").findAll(page).count())
        assertTrue("onClick = { ask(Confirm.CLEAR_HISTORY) }" in page && "onClick = { ask(Confirm.CLEAR_SEARCHES) }" in page, "the rows only ask")
        assertTrue("video_set_clear_search_confirm" in page && "video_clear_history_confirm" in page)
        assertTrue("SettingsConfirmDialog(" in page)
    }

    // ---- Downloads ---------------------------------------------------------------------------------------------------------------

    @Test fun theDownloadsGroupHasTheBriefsRowsOnTheRightPlatforms() {
        for (key in listOf("video_set_dl_group", "video_set_dl_ask_short", "video_set_dl_wifi", "video_set_dl_battery_short", "video_set_dl_folder", "video_set_dl_summary", "video_set_dl_reset")) {
            assertTrue("Res.string.$key" in page, "$key is used")
        }
        // "Ask before each download" mirrors the field; turning it on or off keeps the remembered request
        assertTrue(Regex("""downloads\.copy\(askBeforeEach = \w+\)""").containsMatchIn(page), "only the switch changes: the remembered request stays")
        assertTrue("settings.downloads.askBeforeEach" in page)
        // the reset sets DownloadPrefs() again
        assertTrue(Regex("""downloads = DownloadPrefs\(\)""").containsMatchIn(page), "Ask again sets DownloadPrefs() again")
        assertTrue("enabled = prefs != DownloadPrefs()" in page, "and has nothing to do while it is the default")
        assertTrue("downloadWifiOnly = " in page && "pauseOnLowBattery = " in page && "downloadFolder = " in page)
        assertTrue("prefs.remembered.quality" in page && "prefs.remembered.subtitles" in page, "the summary says the remembered quality and subtitles")
        // the platform gates
        val platformFile = sources.getValue("SettingsPlatform")
        assertTrue("hasMeteredNetwork" in platformFile && "canChooseFolder" in platformFile)
        assertTrue("if (platform.hasMeteredNetwork) {" in page, "Wi-Fi only and the battery hold are Android's")
        assertTrue("if (platform.canChooseFolder) {" in page, "the folder is the desktop's")
        assertTrue("platform.chooseFolder(" in page)
        val android = page.indexOf("if (platform.hasMeteredNetwork) {")
        val folder = page.indexOf("if (platform.canChooseFolder) {")
        assertTrue(page.indexOf("Res.string.video_set_dl_wifi", android) in (android + 1) until folder, "Wi-Fi only is inside the Android gate")
        assertTrue(page.indexOf("Res.string.video_set_dl_battery_short", android) in (android + 1) until folder, "the battery hold is inside it too")
    }

    @Test fun thePlatformsAnswerTheGates() {
        val android = read("video/src/androidMain/kotlin/com/cosmotune/video/ui/settings/SettingsPlatform.android.kt")
        val desktop = read("video/src/jvmMain/kotlin/com/cosmotune/video/ui/settings/SettingsPlatform.jvm.kt")
        assertTrue("actual fun rememberSettingsPlatform()" in android && "actual fun rememberSettingsPlatform()" in desktop)
        assertTrue(Regex("""hasMeteredNetwork: Boolean get\(\) = true""").containsMatchIn(android))
        assertTrue(Regex("""canChooseFolder: Boolean get\(\) = false""").containsMatchIn(android))
        assertTrue(Regex("""hasMeteredNetwork: Boolean get\(\) = false""").containsMatchIn(desktop))
        assertTrue(Regex("""canChooseFolder: Boolean get\(\) = true""").containsMatchIn(desktop))
        assertTrue("JFileChooser.DIRECTORIES_ONLY" in desktop, "a folder chooser: directories only")
        assertTrue("\"Videos/Cosmotune\"" in desktop, "the default folder is ~/Videos/Cosmotune, where the download controller writes")
        assertTrue("Videos/Cosmotune" in read("video/src/jvmMain/kotlin/com/cosmotune/video/downloads/desktop/DesktopDownloadController.kt"))
    }

    // ---- About --------------------------------------------------------------------------------------------------------------------

    @Test fun aboutSaysTheNoticeAndWhatVideoModeContacts() {
        for (key in listOf("video_set_about", "video_disclaimer_title", "video_disclaimer_text", "video_set_contacts", "video_set_contacts_text")) assertTrue("Res.string.$key" in page, key)
        assertTrue("subtitleContent" in page, "the paragraphs are not cut at five lines")
    }

    @Test fun theChoiceSheetIsRadioRowsInAPartTwoSheetThatClosesOnAPick() {
        val sheet = sources.getValue("ChoiceSheet")
        assertTrue("SurfaceSheetSwitch(" in sheet && "rememberModalBottomSheetState(skipPartiallyExpanded = true)" in sheet)
        assertTrue("CosmoActionRow(" in sheet && "CosmoSheetHeading(" in sheet)
        assertTrue("Role.RadioButton" in sheet && "selectableGroup()" in sheet, "radio rows in a selectable group")
        assertTrue("LazyColumn(" in sheet && "heightIn(max = cap)" in sheet, "a long list (the countries) is lazy and capped")
        assertTrue("key = { index, _ -> index }" in sheet, "stable keys")
        assertTrue(Regex("""ChoiceBody\(title, options, selected\) \{\s*onSelect\(it\)\s*onDismiss\(\)\s*\}""").containsMatchIn(sheet), "a pick is saved and closes the sheet")
        assertTrue("CosmoIcons.Check" in sheet && "palette.accent" in sheet, "the pick shows the check in the accent")
    }

    @Test fun theConfirmationIsAPartTwoDialogCard() {
        val sheet = sources.getValue("ChoiceSheet")
        assertTrue("fun SettingsConfirmDialog(" in sheet)
        assertTrue("CosmoDialogCard(" in sheet && "Popup(" in sheet && "CosmoButton(" in sheet)
        assertTrue("dismissOnBackPress = true" in sheet)
        assertTrue("floating = true" in sheet, "its buttons are the floating (C) ones")
    }

    // ---- The look ---------------------------------------------------------------------------------------------------------------------

    @Test fun theLookIsTheBlocksOfPartTwoWithNoColourByHandAndNoPerFrameWork() {
        for ((name, code) in sources) {
            assertFalse(Regex("""Color\(0x""").containsMatchIn(code), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Black|White|Gray|Yellow|Cyan|Magenta)""").containsMatchIn(code), "$name picks a colour by hand")
            assertFalse("positionMs()" in code, "$name reads the playhead")
            assertFalse(Regex("""\blive\(""").containsMatchIn(code), "$name has no draw-phase colour to read")
            assertFalse(Regex("""(?i)\byoutube\b|\bYT\b|NewPipe|schabi""").containsMatchIn(code), "$name names the source")
            assertFalse(Regex("""(?i)cookie""").containsMatchIn(code), "$name mentions a cookie")
        }
        for (block in listOf("CosmoSettingsGroup(", "CosmoSettingsRow(", "CosmoSettingsSubRow(", "CosmoSettingsSegmented(", "CosmoRowDivider()", "CosmoPageHeader(")) {
            assertTrue(block in page + segments, "the page is built from $block")
        }
        assertTrue("verticalScroll(" in page && "padding(contentPadding)" in page, "the page scrolls under the bars: the padding is on the scrolling content")
        assertTrue("widthIn(max = if (wide) WIDE_MAX_WIDTH else Dp.Unspecified)" in page, "wide: a readable width")
        for (token in listOf("Text(\"", "contentDescription = \"", "text = \"")) assertFalse(token in allCode, "no text by hand: $token")
        // nothing reads the music account or sends the person anywhere on its own
        assertFalse("music" in page.lowercase().replace("com.cosmotune", ""), "the page never touches Music")
    }

    @Test fun everyTextIsAVideoKeyDeclaredInTheStringsFile() {
        val used = Regex("""\bRes\.string\.(\w+)""").findAll(allCode).map { it.groupValues[1] }.toSet()
        val declared = Regex("""name="(\w+)"""").findAll(strings).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 50, "the page uses many texts: ${used.size}")
        for (key in used) assertTrue(key.startsWith("video_") && key in declared, "$key is a video key declared in the video strings file")
        for (key in listOf("video_set_device", "video_set_clear_search_confirm")) assertTrue("name=\"$key\"" in strings, "$key was appended")
    }

    @Test fun theTitlesBesideASwitchFitOneLineAt360Dp() {
        // a settings row keeps its title to one line: beside a switch and a 40 dp tile that is about 20 characters of the 15.5 sp titles
        val short = Regex("""<string name="(video_set_\w+_short)">([^<]*)</string>""").findAll(strings).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(9, short.size, "the short titles: $short")
        for ((key, text) in short) assertTrue(text.length <= 20, "$key `$text` is ${text.length} characters: it does not fit beside a switch")
        assertTrue("name=\"video_set_pip_when\"" in strings)
        for ((key, _) in short) assertTrue("Res.string.$key" in allCode, "$key is used")
    }

    // ---- The lists, run ----------------------------------------------------------------------------------------------------------------

    private fun sortedLike(locale: Locale, names: List<String>): List<String> =
        names.sortedWith(Collator.getInstance(locale).also { it.strength = Collator.PRIMARY })

    @Test fun theRegionListIsDeviceFirstThenTheCountriesSortedByTheirNames() {
        val list = regionChoices(Locale.ENGLISH, "Device")
        assertNull(list.first().code)
        assertEquals("Device", list.first().name)
        val rest = list.drop(1)
        assertTrue(rest.size > 200, "the ISO countries: ${rest.size}")
        assertEquals(rest.size, rest.map { it.code }.toSet().size, "no country twice")
        assertTrue(rest.all { it.code?.length == 2 && it.name.isNotBlank() && it.name != it.code }, "every entry has a name of its own")
        assertEquals("India", rest.first { it.code == "IN" }.name)
        assertEquals("United Kingdom", rest.first { it.code == "GB" }.name)
        val names = rest.map { it.name }
        assertEquals(sortedLike(Locale.ENGLISH, names), names, "sorted by name")
        assertEquals(emptySet(), rest.map { it.code!! }.toSet() - Locale.getISOCountries().toSet(), "only ISO countries")
    }

    @Test fun theRegionListFollowsTheLanguageOfTheDevice() {
        val german = regionChoices(Locale.GERMAN, "Gerät")
        assertEquals("Gerät", german.first().name)
        assertEquals("Indien", german.drop(1).first { it.code == "IN" }.name)
        assertEquals("Vereinigtes Königreich", german.drop(1).first { it.code == "GB" }.name)
        val names = german.drop(1).map { it.name }
        assertEquals(sortedLike(Locale.GERMAN, names), names)
    }

    @Test fun theLanguageListIsTheFirstEntryThenLanguagesSortedByTheirNames() {
        val list = languageChoices(Locale.ENGLISH, "Off")
        assertNull(list.first().code)
        assertEquals("Off", list.first().name)
        val rest = list.drop(1)
        assertTrue(rest.size > 100, "the ISO languages: ${rest.size}")
        assertEquals(rest.size, rest.map { it.code }.toSet().size)
        assertEquals(rest.size, rest.map { it.name }.toSet().size, "no name twice")
        assertEquals("English", rest.first { it.code == "en" }.name)
        assertEquals("Hindi", rest.first { it.code == "hi" }.name)
        val names = rest.map { it.name }
        assertEquals(sortedLike(Locale.ENGLISH, names), names)
    }

    @Test fun aStoredCodeIsShownByName() {
        assertEquals("India", regionName("IN", Locale.ENGLISH))
        assertEquals("India", regionName("in", Locale.ENGLISH), "the case of a stored code does not matter")
        assertEquals("QQ", regionName("QQ", Locale.ENGLISH), "a code with no name is shown as it is")
        assertEquals("not a region", regionName("not a region", Locale.ENGLISH), "so is whatever the file held")
        assertEquals("English", languageName("en", Locale.ENGLISH))
        assertEquals("English (United Kingdom)", languageName("en-GB", Locale.ENGLISH), "a tag with a region says it")
        assertEquals("xx", languageName("xx", Locale.ENGLISH))
    }

    @Test fun qualitiesAreNamedByHeightWithAutoForNone() {
        assertEquals("Auto", qualityLabel(null, "Auto"))
        assertEquals("2160p", qualityLabel(2160, "Auto"))
        assertEquals("144p", qualityLabel(144, "Auto"))
    }

    @Test fun theRememberedChoiceIsPutInWords() {
        assertEquals("Auto", downloadQualityText(DownloadQuality.Auto, "Auto", "Audio only"))
        assertEquals("Audio only", downloadQualityText(DownloadQuality.AudioOnly, "Auto", "Audio only"))
        assertEquals("720p", downloadQualityText(DownloadQuality.Rung(720, 30), "Auto", "Audio only"))
        assertEquals("1080p60", downloadQualityText(DownloadQuality.Rung(1080, 60), "Auto", "Audio only"))
        val each = "Each original"
        assertNull(downloadSubtitleLanguage(SubtitlePick(false, null), each, Locale.ENGLISH), "no subtitles")
        assertNull(downloadSubtitleLanguage(SubtitlePick(false, "en"), each, Locale.ENGLISH), "off is off, whatever language was kept")
        assertEquals("English", downloadSubtitleLanguage(SubtitlePick(true, "en"), each, Locale.ENGLISH))
        assertEquals("English (United Kingdom)", downloadSubtitleLanguage(SubtitlePick(true, "en-GB"), each, Locale.ENGLISH))
        assertEquals(each, downloadSubtitleLanguage(SubtitlePick(true, null), each, Locale.ENGLISH), "each video's own")
        assertEquals(each, downloadSubtitleLanguage(SubtitlePick(true, ""), each, Locale.ENGLISH))
        // the default the reset goes back to
        val default = DownloadPrefs()
        assertTrue(default.askBeforeEach)
        assertEquals(DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null)), default.remembered)
    }
}
