package com.cosmotune.reading.ui

import com.cosmotune.reading.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class BookReadingModesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rulerParagraphAndPaperTurnScriptsAreWholeScripts() {
        val scripts = mapOf("ruler" to ReadingEpubScripts.ruler(2), "ruler-off" to ReadingEpubScripts.ruler(0), "paragraph" to ReadingEpubScripts.paragraph(1),
            "paragraph-off" to ReadingEpubScripts.paragraphOff, "curl" to ReadingEpubScripts.page(true, curl = true), "curl-back" to ReadingEpubScripts.page(false, curl = true),
            "slide" to ReadingEpubScripts.page(true, slide = true))
        // Kept for a syntax check outside the test (node --check); each is one expression that gives a string.
        val out = java.io.File("build/reading-scripts").apply { mkdirs() }
        scripts.forEach { (name, script) ->
            java.io.File(out, "$name.js").writeText("var r=$script;\n")
            assertEquals(script.count { it == '(' }, script.count { it == ')' }, name)
            assertEquals(script.count { it == '{' }, script.count { it == '}' }, name)
            assertTrue(script.startsWith("(function("), name)
        }
        assertContains(ReadingEpubScripts.page(true, curl = true), "rotateY")
        assertFalse(ReadingEpubScripts.page(true, slide = true).contains("rotateY"))
    }

    @Test fun batteryChargeIsReadFromEachDesktopsTool() {
        assertEquals(81, readingBatteryFrom("Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1)	81%; discharging; 4:10 remaining"))
        assertEquals(64, readingBatteryFrom("64\n\n"))
        assertNull(readingBatteryFrom(""))
        assertNull(readingBatteryFrom(null))
        assertNull(readingBatteryFrom("140"))
    }

    @Test fun fontFilesJoinTheirFamilyByName() {
        assertEquals(Triple("Literata", 700, true), ReadingFont.parse("Literata-BoldItalic"))
        assertEquals(Triple("Open Dyslexic", 400, false), ReadingFont.parse("OpenDyslexic-Regular"))
        assertEquals(Triple("Source Serif", 600, false), ReadingFont.parse("SourceSerif-SemiBold"))
        ReadingFonts.known = listOf(ReadingFont("a".repeat(32), "L", "ttf", "Literata", 400, false), ReadingFont("b".repeat(32), "L", "ttf", "Literata", 700, true))
        ReadingFonts.directory = temporary.root.also { java.io.File(it, "a".repeat(32) + ".ttf").writeBytes(ByteArray(80)) }
        val script = ReadingEpubScripts.preferences(ReadingPreferences(fontFamily = "font:" + "a".repeat(32)), false, "https://o/")
        assertTrue("font-weight:700;font-style:italic" in script && "font-weight:400;font-style:normal" in script)
        val eInk = ReadingEpubScripts.preferences(ReadingPreferences(theme = ReadingTheme.DARK, eInk = true), true)
        assertTrue("background:#ffffff" in eInk && "animation:none" in eInk)
        ReadingFonts.known = emptyList(); ReadingFonts.directory = null
    }

    @Test fun speedReadingWalksTheWordsWithTheirFocusLetter() {
        val words = speedWords(listOf(SpokenSentence(0, 9, "Call me Ishmael."), SpokenSentence(10, 20, "Some years ago")), 0)
        assertEquals(listOf("Call", "me", "Ishmael.", "Some", "years", "ago"), words)
        assertEquals(listOf(0, 1, 2), listOf("a", "word", "Ishmael").map(::focusLetter))
        assertEquals(listOf("Some", "years", "ago"), speedWords(listOf(SpokenSentence(0, 9, "Call me."), SpokenSentence(10, 20, "Some years ago")), 1))
    }

    @Test fun stylesAreSavedReplacedAndRemoved() = runBlocking<Unit> {
        val repository = ReadingRepository(temporary.newFolder())
        repository.saveProfile("Night", ReadingPreferences(theme = ReadingTheme.BLACK))
        repository.saveProfile("night", ReadingPreferences(theme = ReadingTheme.GREY, fontScale = 1.4f))
        assertEquals(listOf(ReadingTheme.GREY), repository.state.value.profiles.map { it.preferences.theme })
        repository.saveProfile("Night", ReadingPreferences(), remove = true)
        assertTrue(repository.state.value.profiles.isEmpty())
    }
}
