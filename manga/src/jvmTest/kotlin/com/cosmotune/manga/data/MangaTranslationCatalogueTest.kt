package com.cosmotune.manga.data

import com.cosmotune.manga.ui.mangaTextKey
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class MangaTranslationCatalogueTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun strings(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return document.getElementsByTagName("string").let { values -> (0 until values.length).map { values.item(it) }.associate { item ->
            item.attributes.getNamedItem("name").nodeValue to item.textContent.replace("\\'", "'")
        } }.filterKeys { it.startsWith("manga_") }
    }
    @Test fun androidAndDesktopShareKeysAndTranslatedIndianControlsWithEnglishFallback() {
        val english = strings(File(root, "app/src/main/res/values/manga_strings.xml"))
        assertEquals("Library", english[mangaTextKey("Library")])
        for (language in listOf("hi", "ta", "te", "kn", "ml", "bn", "mr", "gu", "pa", "or")) {
            val android = strings(File(root, "app/src/main/res/values-$language/manga_strings.xml"))
            val desktop = strings(File(root, "desktopApp/src/main/resources/strings/$language.xml"))
            assertEquals(english.keys, android.keys, language)
            assertEquals(android, desktop, language)
            assertTrue(android.count { (key, value) -> value != english[key] } >= 45, "$language should translate the real control labels")
            assertTrue(android[mangaTextKey("Manage collections")].orEmpty().isNotBlank())
        }
    }
}
