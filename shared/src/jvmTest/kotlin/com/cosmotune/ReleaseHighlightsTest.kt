package com.cosmotune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The update and "what's new" cards show a release's Highlights and nothing else of its notes: [ReleaseHighlights] reads the
 * `## Highlights` section, and for older notes without one gives the first three top-level bullets as plain single lines.
 */
class ReleaseHighlightsTest {

    private val notes = """
        # Cosmotune 9.9.9

        A short introduction that is not a highlight.

        ## Highlights

        - <!-- icon: timer --> **Reliable sleep timer** — Stops on time, even with the screen off.
        - <!-- look --> **A whole new look** — Round covers and colours that follow your music.
        - <!-- icon: rocket --> **Unknown icon** — Falls back to the plain icon.
        - **No icon at all** - Still a highlight, with a hyphen.

        ## Details

        - The sleep timer now uses an exact alarm and a wake lock (internal).
        - CI caches Gradle.
    """.trimIndent()

    @Test
    fun readsTheHighlightsSectionAndNothingAfterIt() {
        val items = ReleaseHighlights.highlights(notes)!!
        assertEquals(
            listOf(
                ReleaseHighlight(HighlightIcon.TIMER, "Reliable sleep timer", "Stops on time, even with the screen off."),
                ReleaseHighlight(HighlightIcon.LOOK, "A whole new look", "Round covers and colours that follow your music."),
                ReleaseHighlight(HighlightIcon.NEW, "Unknown icon", "Falls back to the plain icon."),
                ReleaseHighlight(HighlightIcon.NEW, "No icon at all", "Still a highlight, with a hyphen."),
            ),
            items,
        )
        assertEquals(items, ReleaseHighlights.of(notes), "with a Highlights section, of() gives exactly the highlights")
    }

    @Test
    fun keepsAtMostFiveHighlights() {
        val many = "## Highlights\n\n" + (1..8).joinToString("\n") { "- <!-- icon: new --> **Item $it** — Sentence $it." }
        val items = ReleaseHighlights.highlights(many)!!
        assertEquals(ReleaseHighlights.MAX, items.size)
        assertEquals("Item 5", items.last().title)
    }

    @Test
    fun stopsAtTheFirstParagraphAfterTheItems() {
        val text = """
            ## Highlights

            - <!-- icon: sync --> **On all your devices** — Imported playlists sync.

            A paragraph of the rest of the notes.

            - A detail bullet that must not become a highlight.
        """.trimIndent()
        assertEquals(1, ReleaseHighlights.highlights(text)!!.size)
    }

    @Test
    fun aTitleWithoutBoldIsSplitAtTheDash() {
        val item = ReleaseHighlights.highlights("## Highlights\n- <!-- icon: watch --> Watch tries again — A film starts once a source appears.")!!.single()
        assertEquals(ReleaseHighlight(HighlightIcon.WATCH, "Watch tries again", "A film starts once a source appears."), item)
    }

    @Test
    fun notesWithoutHighlightsHaveNoSection() {
        assertNull(ReleaseHighlights.highlights("# Cosmotune 1.0.16\n\n- Something.\n"))
        assertEquals(emptyList(), ReleaseHighlights.of(null))
        assertEquals(emptyList(), ReleaseHighlights.of("  "))
    }

    @Test
    fun theFallbackIsTheFirstThreeTopLevelBulletsAsPlainSingleLines() {
        val old = """
            # Cosmotune 1.0.16

            ## Now Playing

            - Seek by **tapping** or dragging near the [artwork ring](https://example.com/ring). A gesture across the artwork leaves playback alone.
              - A nested bullet that is not top level.
            * Music videos are shown `whole` by default.
            - The third one.
            - The fourth one, never shown.

            [Download and installation guide](../../README.md#download)
        """.trimIndent()
        val items = ReleaseHighlights.of(old)
        assertEquals(
            listOf("Seek by tapping or dragging near the artwork ring.", "Music videos are shown whole by default.", "The third one."),
            items.map { it.text },
        )
        assertTrue(items.all { it.title == null && it.icon == HighlightIcon.NEW })
    }

    @Test
    fun theFallbackUsesParagraphsWhenThereAreNoBulletsAndSkipsLinkOnlyLines() {
        val old = """
            # Cosmotune 1.0.19

            **Read → Browse** adds historical EPUBs from Internet Archive. Supported EPUBs open in Cosmotune.

            **Watch and Live TV** can recover when a playable source arrives.

            [Audio transport details](../DSD_TRANSPORT_AND_EQ.md)
        """.trimIndent()
        assertEquals(
            listOf("Read → Browse adds historical EPUBs from Internet Archive.", "Watch and Live TV can recover when a playable source arrives."),
            ReleaseHighlights.fallback(old).map { it.text },
        )
    }

    @Test
    fun aLongFallbackLineIsCutAtAWord() {
        val long = "- " + List(40) { "word$it" }.joinToString(" ")
        val line = ReleaseHighlights.fallback(long).single().text
        assertTrue(line.endsWith("…"), line)
        assertTrue(line.length <= ReleaseHighlights.FALLBACK_LENGTH + 1, line)
        assertTrue(!line.contains("word3 …") && line.removeSuffix("…").split(' ').all { it.startsWith("word") }, line)
    }

    @Test
    fun plainDropsMarkdownButKeepsWords() {
        assertEquals("Open Settings → Live now, then pick snake_case teams.",
            ReleaseHighlights.plain("Open **Settings → _Live now_**, then [pick](https://x.y) `snake_case` teams. <!-- note -->"))
        assertEquals("", ReleaseHighlights.plain("![shot](a.png)"))
    }

    @Test
    fun everyIconKeywordIsUniqueAndLowercase() {
        val keywords = HighlightIcon.entries.map { it.keyword }
        assertEquals(keywords.distinct(), keywords)
        assertTrue(keywords.all { it == it.lowercase() && it.none(Char::isWhitespace) })
        assertEquals(HighlightIcon.TIMER, HighlightIcon.of(" Timer "))
        assertNull(HighlightIcon.of("rocket"))
    }
}
