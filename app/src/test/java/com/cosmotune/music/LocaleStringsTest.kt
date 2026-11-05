package com.cosmotune.music

import com.cosmotune.music.ui.components.SUPPORTED_LANGUAGES
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every full-app language must translate every key in values/strings.xml. A missing key
 * doesn't crash, it silently falls back to English mid-screen, so this catches it at test time.
 * Explicit Manga-only offerings instead require every key in their scoped Manga catalogue.
 */
@RunWith(Parameterized::class)
class LocaleStringsTest(private val tag: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales(): List<String> = SUPPORTED_LANGUAGES.map { it.tag }.filter { it != "en" }

        private val resDir: File by lazy {
            val projectRoot = File(".").canonicalFile
            if (File(projectRoot, "app/src/main/res").exists()) {
                File(projectRoot, "app/src/main/res")
            } else {
                File(projectRoot, "src/main/res")
            }
        }

        private fun parse(file: File): Document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

        /** Keys that need translating: `translatable="false"` entries are excluded. */
        private fun translatableKeys(doc: Document): Set<String> {
            val keys = mutableSetOf<String>()
            for (tagName in listOf("string", "plurals", "string-array")) {
                val nodes = doc.getElementsByTagName(tagName)
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    if (element.getAttribute("translatable") == "false") continue
                    val name = element.getAttribute("name")
                    if (name.isNotEmpty()) keys.add(name)
                }
            }
            return keys
        }

        private val baseKeys: Set<String> by lazy {
            translatableKeys(parse(File(resDir, "values/strings.xml")))
        }
    }

    @Test
    fun strings_containsAllBaseKeys() {
        val qualifier = when (tag) {
            "zh-CN" -> "zh"
            "zh-TW" -> "zh-rTW"
            else -> tag
        }
        val mangaOnly = SUPPORTED_LANGUAGES.first { it.tag == tag }.mangaOnlyTranslation
        val catalogue = if (mangaOnly) "manga_strings.xml" else "strings.xml"
        val localeFile = File(resDir, "values-$qualifier/$catalogue")
        assertTrue("values-$tag/strings.xml must exist", localeFile.exists())

        val expected = if (mangaOnly) translatableKeys(parse(File(resDir, "values/manga_strings.xml"))) else baseKeys
        val missingKeys = expected - translatableKeys(parse(localeFile))
        assertTrue(
            "values-$tag/strings.xml is missing ${missingKeys.size} keys from values/strings.xml: $missingKeys",
            missingKeys.isEmpty(),
        )
    }
}
