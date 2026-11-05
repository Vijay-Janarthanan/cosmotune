package com.cosmotune.video.source.newpipe

import com.cosmotune.video.source.newpipe.fixtures.FixtureScrubber
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nothing recorded from the network is committed with the values that tie it to a connection or a visitor (plan task Y-4,
 * step 6): no `ip=` followed by a digit, no `Set-Cookie`, no `VISITOR_INFO1_LIVE=` with a value (the fixed placeholder is not
 * one), and none of the other parameters the scrubber replaces. The checks read every file under a `resources/video`
 * folder of the repository's test resources, so a recording added by a later task is covered too.
 */
class FixturesAreScrubbedTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
    }

    private fun fixtureFiles(): List<File> {
        val root = repoRoot()
        val folders = listOf("shared", "video", "app", "desktopApp").flatMap { module ->
            File(root, "$module/src").listFiles().orEmpty().filter { it.isDirectory }.map { File(it, "resources/video") }
        }
        return folders.filter { it.isDirectory }.flatMap { folder -> folder.walkTopDown().filter { it.isFile }.toList() }
    }

    @Test
    fun theReplayFixturesExistAndAreFoundByTheCheck() {
        val files = fixtureFiles()
        assertTrue(files.any { it.name.startsWith("search_all_") }, "the recorded searches are under resources/video/newpipe")
        assertTrue(files.any { it.name.startsWith("search_videos_") })
    }

    @Test
    fun noRecordedFileHoldsAnAddressOfAConnectionACookieOrAVisitor() {
        val ip = Regex("ip=[0-9]")
        val setCookie = Regex("set-cookie", RegexOption.IGNORE_CASE)
        val visitorCookie = Regex("""VISITOR_INFO1_LIVE=(?!${FixtureScrubber.PLACEHOLDER})[^;"\\\s]+""")
        val bad = mutableListOf<String>()
        for (file in fixtureFiles()) {
            val text = file.readText(Charsets.UTF_8)
            if (ip.containsMatchIn(text)) bad += "${file.name}: ip= followed by a digit"
            if (setCookie.containsMatchIn(text)) bad += "${file.name}: Set-Cookie"
            if (visitorCookie.containsMatchIn(text)) bad += "${file.name}: VISITOR_INFO1_LIVE with a value"
            FixtureScrubber.problems(text).forEach { bad += "${file.name}: $it" }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    // The scrubber itself: it is what makes the files above clean.

    @Test
    fun theScrubberReplacesTheValuesOfAnAddress() {
        val dirty = "https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&ei=EEEE&ip=203.0.113.9&id=o-ABC&itag=137&sig=SIGSIG&lsig=LSIG&n=NNN&pot=POT&c=WEB"
        val clean = FixtureScrubber.scrub(dirty)
        assertEquals(
            "https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&ei=REDACTED&ip=REDACTED&id=o-ABC&itag=137&sig=REDACTED&lsig=REDACTED&n=REDACTED&pot=REDACTED&c=WEB",
            clean,
        )
        assertTrue(FixtureScrubber.problems(clean).isEmpty())
        assertFalse(FixtureScrubber.problems(dirty).isEmpty())
    }

    @Test
    fun theScrubberReplacesTheValuesInsideAnEscapedAnswer() {
        val separator = "\\" + "u0026" // how a JSON answer writes an ampersand inside an address
        val dirty = "\"url\":\"https://a.googlevideo.com/videoplayback?itag=18${separator}ei=EEEE${separator}ip=1.2.3.4${separator}n=abc${separator}sig=S\""
        val clean = FixtureScrubber.scrub(dirty)
        assertEquals("\"url\":\"https://a.googlevideo.com/videoplayback?itag=18${separator}ei=REDACTED${separator}ip=REDACTED${separator}n=REDACTED${separator}sig=REDACTED\"", clean)
    }

    @Test
    fun theScrubberReplacesVisitorValues() {
        val dirty = """{"responseContext":{"visitorData":"CgtSVVZUZkFNNm13WSiZq","serviceTrackingParams":[]},"x":"VISITOR_INFO1_LIVE=aBcDeF123; Path=/"}"""
        val clean = FixtureScrubber.scrub(dirty)
        assertEquals("""{"responseContext":{"visitorData":"REDACTED","serviceTrackingParams":[]},"x":"VISITOR_INFO1_LIVE=REDACTED; Path=/"}""", clean)
        assertTrue(FixtureScrubber.problems(clean).isEmpty(), FixtureScrubber.problems(clean).toString())
        assertFalse(FixtureScrubber.problems(dirty).isEmpty())
    }

    @Test
    fun theScrubberReplacesTheCountryTheSourceGuessed() {
        val dirty = """{"INNERTUBE_CONTEXT_GL":"x","gl":"IN","hl":"en-GB"}"""
        val clean = FixtureScrubber.scrub(dirty)
        assertEquals("""{"INNERTUBE_CONTEXT_GL":"x","gl":"GB","hl":"en-GB"}""", clean)
        assertFalse(FixtureScrubber.problems(dirty).isEmpty())
        assertTrue(FixtureScrubber.problems(clean).isEmpty())
    }

    @Test
    fun theScrubberDropsEveryHeaderButTheContentType() {
        val kept = FixtureScrubber.keptHeaders(
            mapOf(
                "Content-Type" to listOf("application/json; charset=UTF-8"),
                "Set-Cookie" to listOf("VISITOR_INFO1_LIVE=abc"),
                "X-Goog-Visitor-Id" to listOf("abc"),
                "Alt-Svc" to listOf("h3=\":443\""),
            ),
        )
        assertEquals(mapOf("Content-Type" to listOf("application/json; charset=UTF-8")), kept)
    }

    @Test
    fun scrubbingTwiceChangesNothingMore() {
        val dirty = "https://a.googlevideo.com/videoplayback?ei=E&ip=9.9.9.9&n=abc&pot=P"
        assertEquals(FixtureScrubber.scrub(dirty), FixtureScrubber.scrub(FixtureScrubber.scrub(dirty)))
    }

    @Test
    fun textWithoutSecretsIsLeftAlone() {
        val text = """{"title":"The ship=big tale","thumbnail":"https://i.ytimg.com/vi/abc/hq720.jpg?sqp=xyz&rs=AOn4","text":"n of m"}"""
        assertEquals(text, FixtureScrubber.scrub(text))
    }
}
