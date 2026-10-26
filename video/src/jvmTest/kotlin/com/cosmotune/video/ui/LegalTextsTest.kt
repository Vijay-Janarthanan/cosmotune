package com.cosmotune.video.ui

import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.notice.acknowledgeFirstRun
import com.cosmotune.video.ui.notice.firstRunNoticeShown
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The legal texts of Video mode (plan task Y-27): the README's two paragraphs (spec 11 and 12), the NOTICE, the first-run notice
 * (spec 5.14) and the maintenance notes (`docs/MAINTAINING.md`, "Video mode"). The texts are read as the files hold them, with
 * white space collapsed, so a line break in a paragraph does not matter and a changed word does.
 */
class LegalTextsTest {

    private val root = VideoSourceContract.root

    private fun flat(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private fun doc(path: String): String = VideoSourceContract.read(path)

    /** The part of [text] from the line `## [heading]` to the next `## ` line (or the end), white space collapsed. */
    private fun section(text: String, heading: String): String {
        val start = Regex("(?m)^## ${Regex.escape(heading)}\\s*$").find(text)?.range?.last ?: error("no `## $heading` in the file")
        val end = Regex("(?m)^## ").find(text, start + 1)?.range?.first ?: text.length
        return flat(text.substring(start + 1, end))
    }

    private val readme by lazy { doc("README.md") }
    private val notice by lazy { doc("NOTICE.md") }
    private val maintaining by lazy { doc("docs/MAINTAINING.md") }
    private val videoNotes by lazy { section(maintaining, "Video mode") }

    private fun noticeSource() = doc("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/notice/FirstRunNotice.kt")
    private fun phoneShell() = doc("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/CosmoVideoApp.kt")
    private fun desktopEntry() = doc("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/desktop/VideoDesktopEntry.kt")

    // The wording of spec 11 and spec 12, as the spec quotes it.
    private val contactsParagraph =
        "**Video mode** looks videos up on YouTube with no account and no cookies: search, video pages, comments, channel and " +
            "RSS feeds, thumbnails and the video files. When it is on, SponsorBlock gets the first four characters of a hash of each " +
            "video id you open (`sponsor.ajay.app`); DeArrow gets the same for titles and the video id for thumbnails " +
            "(`sponsor.ajay.app`, `dearrow-thumb.ajay.app`); Return YouTube Dislike gets the video id of each video page you open " +
            "(`returnyoutubedislikeapi.com`). Each can be switched off in Video settings. Your history, subscriptions and " +
            "playlists are kept on the device."

    private val disclaimer =
        "Cosmotune is not affiliated with, authorised or endorsed by YouTube or Google. Video mode reads YouTube's public " +
            "pages without an account, which YouTube's terms do not allow for third-party apps. Use it at your own risk."

    // ---- README -----------------------------------------------------------------------------------------------------------

    @Test fun theReadmeSaysWhatVideoModeContactsUnderWhatItContacts() {
        val contacts = section(readme, "What it contacts")
        assertTrue(contactsParagraph in contacts, "the paragraph of spec 11, word for word, is under `What it contacts`")
        assertTrue("The music video needs an internet connection." in contacts, "the music video's paragraph is still there")
        assertEquals(1, Regex(Regex.escape("**Video mode** looks videos up")).findAll(flat(readme)).count(), "said once")
    }

    @Test fun theReadmeHasAVideoModeParagraphUnderTheDisclaimer() {
        val text = section(readme, "Disclaimer")
        assertTrue("**Video mode.** $disclaimer The mode can be switched off by an update if asked." in text, "spec 12's disclaimer and the sentence about an update")
        assertTrue(text.startsWith("Cosmotune uses YouTube's own player endpoints"), "the music paragraph stays first")
        assertTrue(text.indexOf("Cosmotune uses YouTube's own player") < text.indexOf("**Video mode.**"), "the Video mode paragraph comes under it")
    }

    @Test fun theReadmeThanksTheNewPartsAndKeepsTheOldOnes() {
        val thanks = section(readme, "Acknowledgements")
        assertTrue("NewPipeExtractor" in thanks && "GPL-3.0-or-later" in thanks, "NewPipeExtractor, with its licence")
        assertTrue("plays video on the desktop with [libmpv]" in thanks, "libmpv, for desktop playback")
        for (name in listOf("SponsorBlock", "DeArrow", "Return YouTube Dislike")) assertTrue(name in thanks, "$name is thanked")
        for (kept in listOf("BitChord", "LibreTube", "SimpMusic", "Namida", "OpenCanvas", "NOTICE.md")) assertTrue(kept in thanks, "$kept is still there")
    }

    @Test fun theDisclaimerOfTheReadmeAndOfTheNoticeSayTheSameThings() {
        val key = Regex("""<string name="video_disclaimer_text">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(doc("video/src/commonMain/composeResources/values/strings.xml"))?.groupValues?.get(1) ?: error("video_disclaimer_text is declared")
        val card = flat(key)
        val readmeText = section(readme, "Disclaimer")
        for (claim in listOf(
            "not affiliated with, authorised or endorsed by YouTube or Google",
            "without an account",
            "third-party apps",
            "Use it at your own risk.",
        )) {
            assertTrue(claim in card, "the first-run card says: $claim")
            assertTrue(claim in readmeText, "the README says: $claim")
        }
    }

    // ---- NOTICE -----------------------------------------------------------------------------------------------------------

    @Test fun theNoticeNamesTheThreeServicesWithTheirLicence() {
        val text = flat(notice)
        assertTrue("Uses SponsorBlock data from https://sponsor.ajay.app/ under CC BY-NC-SA 4.0" in text)
        assertTrue("Uses DeArrow data from https://sponsor.ajay.app/ under CC BY-NC-SA 4.0" in text)
        assertTrue("Dislike counts come from Return YouTube Dislike (https://returnyoutubedislike.com/)" in text, "the dislike counts name their source")
        assertTrue("https://creativecommons.org/licenses/by-nc-sa/4.0/" in text, "the licence is linked")
    }

    @Test fun theNoticeNamesTheLibrariesAndTheirLicences() {
        val text = flat(notice)
        assertTrue(Regex("NewPipeExtractor[^#]*GPL-3\\.0-or-later").containsMatchIn(text), "NewPipeExtractor is GPL-3.0-or-later")
        assertTrue("libmpv" in text && "LGPL-2.1-or-later" in text, "libmpv, LGPL-2.1-or-later on Windows and macOS")
        assertTrue(Regex("GPL-3\\.0 on Linux").containsMatchIn(text), "and the GPL on Linux")
        assertTrue("mediamp" in text && "Apache-2.0" in text, "from the mediamp runtime packages, Apache-2.0")
        assertTrue("shared/src/jvmTest/resources/video/importers/" in text, "the importers' test files")
        assertTrue("NewPipe" in text && "GPL-3.0" in text)
    }

    // Y-34: what the NOTICE says is checked against the landed code, so a bump or a change of the settings shows here.

    @Test fun theNoticeNamesTheVersionsTheBuildDeclares() {
        val text = flat(notice)
        val mpv = Regex("""mediamp-mpv-runtime-<platform>`, version ([0-9.]+)""").find(text)?.groupValues?.get(1)
        assertEquals(com.cosmotune.video.player.desktop.MpvNatives.RUNTIME_VERSION, mpv, "the NOTICE's libmpv runtime is the one the engine falls back to")
        val declared = Regex("""mediamp-mpv-runtime-\${'$'}mpvPlatform:([0-9.]+)""").find(doc("video/build.gradle.kts"))?.groupValues?.get(1)
        assertEquals(declared, mpv, "and the one video/build.gradle.kts declares")
        val newPipe = Regex("""NewPipeExtractor:(v[0-9.]+)""").find(doc("shared/build.gradle.kts"))?.groupValues?.get(1)
        assertTrue(newPipe != null && "version $newPipe" in text && "at the tag $newPipe" in text, "the NOTICE names NewPipeExtractor $newPipe")
    }

    @Test fun theSettingsCreditTheThreeServicesAndEachCanBeSwitchedOffAsTheNoticeSays() {
        assertTrue("Video settings credit the three services, and each of them can be switched off there" in flat(notice))
        val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/settings"
        val settings = VideoSourceContract.code(doc("$dir/VideoSettingsPage.kt")) + VideoSourceContract.code(doc("$dir/SegmentSettings.kt"))
        for (link in listOf("url = SPONSORBLOCK_URL", "url = DEARROW_URL", "url = DISLIKES_URL")) assertTrue("AttributionLink(" in settings && link in settings, "the credit $link")
        for (field in listOf("sponsorBlock", "deArrowTitles", "deArrowThumbnails", "dislikes")) assertTrue("$field = " in settings, "a switch writes $field")
    }

    @Test fun theDesktopLicenceScreenListsWhatTheNoticeNames() {
        val licences = doc("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopLicenses.kt")
        for (name in listOf("\"NewPipeExtractor\"", "\"libmpv\"", "\"mediamp runtime packages\"", "\"SponsorBlock data\"", "\"DeArrow data\"", "\"Return YouTube Dislike\"")) {
            assertTrue(name in licences, "the desktop's licence screen lists $name")
        }
        assertTrue("https://mpv.io" in licences && "https://github.com/open-ani/mediamp" in licences && "CC BY-NC-SA 4.0" in licences)
    }

    @Test fun theNoticeKeepsEverythingItHadAndStaysGplv3() {
        for (heading in listOf("OpenCanvas", "Material Color Utilities", "Archivo and Hanken Grotesk")) {
            assertTrue(Regex("(?m)^## ${Regex.escape(heading)}\\s*$").containsMatchIn(notice), "`## $heading` is still there")
        }
        assertTrue(notice.startsWith("# Notice"))
        assertTrue("Cosmotune is free software under the GNU General Public License, version 3" in flat(notice))
    }

    // ---- The first-run notice ---------------------------------------------------------------------------------------------

    @Test fun theNoticeUsesTheDisclaimerKeysAndNothingElse() {
        val src = VideoSourceContract.code(noticeSource())
        val keys = Regex("""Res\.string\.(\w+)""").findAll(src).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("video_disclaimer_title", "video_disclaimer_text", "video_disclaimer_ok"), keys)
        assertFalse(Regex("stringResource\\(\\s*\"").containsMatchIn(src), "no text of its own")
        assertFalse("YouTube" in src || Regex("""\bYT\b""").containsMatchIn(src), "the name is in the resource only")
    }

    @Test fun theNoticeIsACGlassCardOfSuperellipse36WithAPrimaryButton() {
        val src = VideoSourceContract.code(noticeSource())
        assertTrue("SuperellipseShape(CosmoRadii.sheet)" in src, "a superellipse of 36 dp (CosmoRadii.sheet)")
        assertEquals(36f, com.cosmotune.ui.theme.CosmoRadii.sheet.value)
        assertTrue("cosmoGlass(hazeState = null" in src, "solid glass: nothing to blur over a page that scrolls and a video that plays")
        assertTrue("CosmoSpacing.s5" in src && com.cosmotune.ui.theme.CosmoSpacing.s5.value == 20f, "padding 20")
        assertTrue("kind = CosmoButtonKind.PRIMARY" in src && "floating = true" in src, "a primary button of layer C")
        assertTrue(Regex("""CosmoButton\(\s*ok,\s*onDismiss""").containsMatchIn(src), "Got it calls onDismiss")
    }

    @Test fun theNoticeTakesItsColoursFromThePaletteAndDrawsNothingPerFrame() {
        val src = VideoSourceContract.code(noticeSource())
        assertFalse(Regex("""Color\(0x""").containsMatchIn(src), "no colour picked by hand")
        assertEquals(1, Regex("""Color\.Black""").findAll(src).count(), "the one black is the scrim, the documented exception")
        assertTrue("LocalCosmoColors.current.palette" in src && "LocalCosmoType.current" in src)
        assertFalse("positionMs(" in src || ".live(" in src, "no position and no live colour")
        assertFalse("clickable(" in src, "the scrim is no button: it swallows touches without a role or a label")
    }

    @Test fun theLayerShowsWhileTheFlagIsFalseAndGotItSetsIt() {
        val src = VideoSourceContract.code(noticeSource())
        val layer = VideoSourceContract.functionBody(noticeSource(), "FirstRunNoticeLayer")
        assertTrue("LocalVideoGraph.current.settings" in layer, "the graph's settings")
        assertTrue("firstRunSeen" in layer || "firstRunNoticeShown(" in layer, "it reads the flag")
        assertTrue("FirstRunNotice(onDismiss = { acknowledgeFirstRun(settings) })" in layer.replace(Regex("\\s+"), " "), "Got it sets the flag")
        assertTrue("fun FirstRunNotice(onDismiss: () -> Unit" in src, "the card takes the callback, as the plan says")
    }

    @Test fun theFlagIsReadFromTheSettingsAndSetOnceWithoutTouchingTheOthers() {
        val fresh = VideoSettings()
        assertTrue(firstRunNoticeShown(fresh), "a new install shows it")
        assertFalse(firstRunNoticeShown(fresh.copy(firstRunSeen = true)), "a stored flag hides it")
        val repository = FakeVideoSettingsRepository(fresh.copy(hideShorts = true, wifiQuality = 720))
        assertTrue(firstRunNoticeShown(repository.settings.value))
        acknowledgeFirstRun(repository)
        assertFalse(firstRunNoticeShown(repository.settings.value), "Got it hides it")
        assertEquals(fresh.copy(hideShorts = true, wifiQuality = 720, firstRunSeen = true), repository.settings.value, "only the flag changed")
        acknowledgeFirstRun(repository)
        assertTrue(repository.settings.value.firstRunSeen, "again is harmless")
    }

    @Test fun bothShellsShowItOverTheirPage() {
        val call = "com.cosmotune.video.ui.notice.FirstRunNoticeLayer()"
        val phone = phoneShell()
        assertEquals(1, Regex(Regex.escape(call)).findAll(phone).count(), "the phone shell calls it once")
        assertFalse("// Y-27: the first-run notice goes here" in phone, "the marker is replaced")
        val shell = VideoSourceContract.functionBody(phone, "VideoPhoneShell")
        assertTrue(shell.indexOf("VideoBottomChrome(") in 0 until shell.indexOf(call), "after the chrome, so it is drawn over it")
        val desktop = desktopEntry()
        assertEquals(1, Regex(Regex.escape(call)).findAll(desktop).count(), "the desktop page calls it once")
        assertFalse("// Y-27: the first-run notice goes here (the desktop page)" in desktop)
        val body = VideoSourceContract.functionBody(desktop, "VideoDesktopPageBody")
        assertTrue(call in body && body.indexOf("VideoNavHost(") < body.indexOf(call), "inside the desktop page, over the nav host")
        for (src in listOf(phone, desktop)) {
            val lines = src.lines()
            val at = lines.indexOfFirst { call in it }
            assertTrue(lines[at + 1].isBlank(), "the blank line after the marker's place is kept")
        }
    }

    // ---- docs/MAINTAINING.md ----------------------------------------------------------------------------------------------

    @Test fun maintainingHasTheFiveParts() {
        for (heading in listOf(
            "Hooks into upstream files", "Removing Video mode", "Bumping NewPipeExtractor", "Bumping the libmpv runtime",
            "The recorded test files",
        )) {
            assertTrue(Regex("(?m)^### ${Regex.escape(heading)}\\s*$").containsMatchIn(maintaining), "`### $heading`")
        }
        assertTrue(Regex("(?m)^## Video mode\\s*$").containsMatchIn(maintaining))
    }

    /** What each landed hook looks like in the source: the file and a line that is there today. The notes must quote the same text. */
    private val landedHooks = listOf(
        "settings.gradle.kts" to "include(\":video\")",
        "app/build.gradle.kts" to "implementation(project(\":video\"))",
        "desktopApp/build.gradle.kts" to "implementation(project(\":video\"))",
        "desktopApp/build.gradle.kts" to "\"jdk.httpserver\"",
        "shared/build.gradle.kts" to "api(\"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2\")",
        "shared/build.gradle.kts" to "implementation(\"org.xerial:sqlite-jdbc:3.47.1.0\")",
        "shared/build.gradle.kts" to "implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2\")",
        "shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/innertube/StreamResolver.kt" to
            "com.cosmotune.video.source.newpipe.NewPipeHost.install(OkHttpDownloader())",
        "app/src/main/java/com/cosmotune/music/MainActivity.kt" to
            "com.cosmotune.video.host.PhoneModeHost(windowWidth = maxWidth, windowHeight = maxHeight) {",
        "app/src/main/AndroidManifest.xml" to "android:supportsPictureInPicture=\"true\"",
        "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt" to
            "if (com.cosmotune.video.host.desktopVideoShown()) return@DesktopFrame com.cosmotune.video.host.DesktopVideoPage(contentPadding)",
        "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt" to
            "if (com.cosmotune.video.host.DesktopModeHost.active.or(com.cosmotune.video.host.DesktopModeHost.mangaActive).or(com.cosmotune.video.host.DesktopModeHost.podioActive)) return@onPreviewKeyEvent false",
    )

    @Test fun theHookTableQuotesEveryHookAsTheSourceHasItToday() {
        for ((path, line) in landedHooks) {
            assertTrue(line in doc(path).replace("\r\n", "\n"), "$path still holds `$line`")
            assertTrue(path.substringAfterLast('/') in videoNotes, "the notes name ${path.substringAfterLast('/')}")
            assertTrue(flat(line) in videoNotes, "the notes quote `$line`")
        }
    }

    @Test fun theHookTableAlsoHasTheLinkWithMusic() {
        for (word in listOf(
            "CanvasArtwork.kt", "DesktopCanvas.kt", "DesktopCanvasVideo.kt", "DesktopCanvasSources.kt", "videoId: String? = null",
            "LocalModeLinks", "rememberDesktopModeLinks()", "LocalDesktopPlaySong", "cosmo_watch_music_video",
        )) assertTrue(word in videoNotes, "the notes name $word")
    }

    @Test fun everyHookRowHasFourColumnsAndTheHeaderOfTheTable() {
        val start = Regex("(?m)^## Video mode\\s*$").find(maintaining)!!.range.last
        val end = Regex("(?m)^## ").find(maintaining, start + 1)?.range?.first ?: maintaining.length
        val rows = maintaining.substring(start, end).lines().filter { it.startsWith("| ") }
        assertTrue(rows.size >= 14, "the table rows were found: ${rows.size}")
        for (row in rows) assertEquals(4, row.trim().trim('|').split("|").size, "four columns: $row")
        assertEquals("| File | Anchor (quoted) | Change | How to re-apply |", rows.first(), "the header")
    }

    @Test fun theRemovalListNamesEveryFileOutsideTheVideoFoldersThatMentionsThePackage() {
        val removal = section(maintaining, "Video mode") // the whole part; the removal steps are inside it
        val skippedDirs = setOf("build", ".git", ".gradle", ".idea", ".kotlin", "node_modules", ".superpowers")
        val found = root.walkTopDown()
            .onEnter { it.name !in skippedDirs }
            .filter { it.isFile && it.extension in setOf("kt", "kts", "xml") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { path ->
                path.startsWith("video/") ||
                    Regex("^(shared|app|desktopApp)/src/[^/]+/(kotlin|java)/com/cosmotune/video/").containsMatchIn(path)
            }
            .filter { path -> "com.cosmotune.video" in File(root, path).readText() }
            .toList()
        assertTrue(found.isNotEmpty(), "the walk found the hooks")
        for (known in listOf(
            "app/src/main/java/com/cosmotune/music/MainActivity.kt",
            "shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/innertube/StreamResolver.kt",
            "desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopSidebar.kt",
        )) assertTrue(known in found, "the walk sees $known")
        assertTrue(found.none { it.startsWith("video/") || it.contains("/com/cosmotune/video/") }, "the Video folders are left out of the walk")
        val unnamed = found.filterNot { it.substringAfterLast('/') in removal }
        assertTrue(unnamed.isEmpty(), "docs/MAINTAINING.md (Video mode) must name these files, which still mention the package after the Video folders are deleted: $unnamed")
    }

    @Test fun theRemovalStepsAreTheOnesOfTheSpec() {
        val removal = section(maintaining, "Video mode")
        assertTrue("AppModes.available" in removal && "VIDEO" in removal, "step 1: the mode is made unreachable with one line")
        for (dir in listOf(
            "video/", "shared/src/*/kotlin/com/cosmotune/video/", "shared/src/jvmTest/resources/video/",
            "app/src/*/java/com/cosmotune/video/", "desktopApp/src/*/kotlin/com/cosmotune/video/",
        )) assertTrue(dir in removal, "the removal list names $dir")
        assertTrue("NewPipe.init(OkHttpDownloader())" in removal, "the StreamResolver line goes back to what it was")
        assertTrue("README" in removal, "the README sections go")
    }

    @Test fun theNewPipeBumpIsOneVersionForTheWholeProcess() {
        val bump = flat(maintaining.substringAfter("### Bumping NewPipeExtractor").substringBefore("\n### "))
        for (word in listOf(
            "app/build.gradle.kts", "desktopApp/build.gradle.kts", "shared/build.gradle.kts", "stripNewPipeExtractorUtils",
            "prodReleaseRuntimeClasspath", "Utils.java", "ten Music tracks", "three videos", "VideoModuleStructureTest", "NewPipeRoutingTest",
        )) assertTrue(word in bump, "the NewPipe bump names $word")
        assertTrue(Regex("(?i)record").containsMatchIn(bump), "the result is recorded")
    }

    @Test fun theMpvBumpNamesTheVersionInTheBuildAndTheFallbackConstant() {
        val bump = flat(maintaining.substringAfter("### Bumping the libmpv runtime").substringBefore("\n### "))
        for (word in listOf("video/build.gradle.kts", "mediamp-mpv-runtime", "MpvNatives.RUNTIME_VERSION", "MpvNativesTest", "LGPL")) {
            assertTrue(word in bump, "the libmpv bump names $word")
        }
        // The version the notes talk about is the version the build declares.
        val declared = Regex("""mediamp-mpv-runtime-\${'$'}mpvPlatform:([0-9.]+)""").find(doc("video/build.gradle.kts"))?.groupValues?.get(1)
        assertTrue(declared != null && declared in bump, "the notes name the declared version $declared")
    }

    @Test fun theRecordedFilesAreAccountedFor() {
        val files = flat(maintaining.substringAfter("### The recorded test files"))
        for (word in listOf(
            "shared/src/jvmTest/resources/video/newpipe/", "FixtureScrubber", "FixturesAreScrubbedTest", "RecorderKt", "--rescrub",
            "shared/src/jvmTest/resources/video/feed/", "shared/src/jvmTest/resources/video/segments/",
            "shared/src/jvmTest/resources/video/importers/", "ThirdPartyFixturesTest", "VIDEO_IMPORT_FIXTURES",
            "ip=", "visitorData", "Set-Cookie",
        )) assertTrue(word in files, "the notes about the recorded files name $word")
        for (path in listOf("newpipe", "feed", "segments", "importers")) {
            assertTrue(File(root, "shared/src/jvmTest/resources/video/$path").isDirectory, "$path exists")
        }
        assertTrue(File(root, "shared/src/jvmTest/kotlin/com/cosmotune/video/source/newpipe/fixtures/Recorder.kt").isFile, "the recorder exists")
    }

    @Test fun theLegalFilesNameNoOneElsesAccountOrKey() {
        // A last look at the three documents: nothing that looks like a secret or a personal address was pasted into them.
        for ((name, text) in listOf("README.md" to readme, "NOTICE.md" to notice, "MAINTAINING.md" to maintaining)) {
            assertFalse(Regex("(?i)(api[_-]?key|secret|password)\\s*[:=]\\s*\\S{8,}").containsMatchIn(text), "$name holds no key")
            assertFalse(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[a-z]{2,}").containsMatchIn(text), "$name holds no e-mail address")
        }
    }
}
