package com.cosmotune.manga.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MangaNovelTextTest {
    private fun paragraphs(chapter: MangaNovelChapter) = chapter.blocks.map { "${it.kind.name[0]}:${it.text}" }

    @Test fun paragraphsHeadingsAndScenesComeOutInOrder() {
        val html = """
            <div class="chapter">
              <h2>Chapter 1 <small>The Old Sea-dog</small></h2>
              <p>SQUIRE   Trelawney, Dr. Livesey, and the
                 rest of these gentlemen</p>
              <p>I remember him as if it were yesterday&#160;&mdash; a tall man.</p>
              <hr>
              <p>* * *</p>
              <p>Fifteen men on the dead man's chest<br>Yo-ho-ho, and a bottle of rum!</p>
              <p> </p>
              <div>Loose text in a div <em>with</em> emphasis.</div>
            </div>
        """.trimIndent()
        val chapter = MangaNovelText.parse(html, "Chapter 1")
        assertEquals(listOf(
            "H:Chapter 1 The Old Sea-dog",
            "P:SQUIRE Trelawney, Dr. Livesey, and the rest of these gentlemen",
            "P:I remember him as if it were yesterday — a tall man.",
            "B:",
            "P:Fifteen men on the dead man's chest\nYo-ho-ho, and a bottle of rum!",
            "P:Loose text in a div with emphasis.",
        ), paragraphs(chapter))
    }

    @Test fun scriptsStylesAndSiteChromeAreLeftOut() {
        val html = """
            <style>.x{color:red}</style><script>var a = "not text";</script>
            <div class="ws-noexport"><a href="/">contents</a> Chapter 2 →</div>
            <p>Kept<sup class="reference">[1]</sup> words.</p>
            <nav>Next chapter</nav><button>Vote</button>
        """.trimIndent()
        val chapter = MangaNovelText.parse(html, "", remove = ".ws-noexport, sup.reference")
        assertEquals(listOf("P:Kept words."), paragraphs(chapter))
    }

    @Test fun textHiddenByThePagesOwnStylesIsLeftOut() {
        // Some sites slip a hidden line into each chapter, hidden only by a class their style sheet sets to display: none.
        val html = """
            <style>.cmVhZG{display: none; speak: never;} .shown, .also{color:#333} .mw .ok{display:none}</style>
            <p>First line.</p>
            <p class="cmVhZG">This story was taken without permission.</p>
            <p class="ok">Kept, the rule needs an ancestor.</p>
            <p style="display:none">Hidden inline.</p>
            <p>Last line.</p>
        """.trimIndent()
        assertEquals(listOf("P:First line.", "P:Kept, the rule needs an ancestor.", "P:Last line."), paragraphs(MangaNovelText.parse(html, "")))
    }

    @Test fun anEmptyPageGivesNoBlocksAndLongPagesAreCapped() {
        assertTrue(MangaNovelText.parse("<div>  </div>", "").blocks.isEmpty())
        val long = (1..30_000).joinToString("") { "<p>Line $it</p>" }
        assertEquals(MangaNovelText.MAX_BLOCKS, MangaNovelText.parse(long, "").blocks.size)
    }

    @Test fun aChapterSurvivesTheDiskRoundTrip() {
        val chapter = MangaNovelText.parse("<h1>One</h1><p>Two</p><hr>", "One")
        assertEquals(chapter, MangaNovelText.decode(MangaNovelText.encode(chapter)))
        assertFalse(MangaNovelText.encode(chapter).contains("<p>"))
    }
}
