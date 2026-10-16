package com.cosmotune.ui

import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.cosmo_song_count
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * "1 songs" (task P4-22): the song count of a page's header is a plural, so it takes the form its language gives to the number.
 *
 * The selection itself is the resource library's (it ships the CLDR rules), so the first half asks the library, through the real
 * `cosmo_song_count` of every language, for the quantities 0, 1, 2, 5 and 21 (and the ones where Russian and Polish turn: 11, 12
 * and 22). The second half reads the files: every language declares the categories its own rule can produce for a whole number.
 */
class SongCountPluralTest {

    /** The text of `cosmo_song_count` for [quantity] in the language [tag], as the library resolves it. */
    private fun count(tag: String, quantity: Int): String {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(tag))
        try {
            return runBlocking { getPluralString(Res.plurals.cosmo_song_count, quantity, quantity) }
        } finally {
            Locale.setDefault(saved)
        }
    }

    private fun assertCounts(tag: String, expected: Map<Int, String>) {
        for ((quantity, text) in expected) assertEquals(text, count(tag, quantity), "$tag, $quantity")
    }

    // ---- the selection, through the library --------------------------------------------------------------------------------------

    @Test fun englishHasOneSongAndTheRestAreSongs() = assertCounts(
        "en", mapOf(0 to "0 songs", 1 to "1 song", 2 to "2 songs", 5 to "5 songs", 21 to "21 songs"),
    )

    @Test fun russianTurnsOnTheLastDigitsAndKeepsElevenAndTheTeensInTheMany() = assertCounts(
        "ru", mapOf(0 to "0 песен", 1 to "1 песня", 2 to "2 песни", 5 to "5 песен", 11 to "11 песен", 12 to "12 песен", 21 to "21 песня", 22 to "22 песни"),
    )

    @Test fun polishHasItsFewAndItsMany() = assertCounts(
        "pl", mapOf(0 to "0 piosenek", 1 to "1 piosenka", 2 to "2 piosenki", 5 to "5 piosenek", 12 to "12 piosenek", 21 to "21 piosenek", 22 to "22 piosenki"),
    )

    @Test fun hebrewHasAOneAndATwo() = assertCounts(
        "he", mapOf(0 to "0 שירים", 1 to "שיר אחד", 2 to "2 שירים", 5 to "5 שירים", 21 to "21 שירים"),
    )

    @Test fun japaneseHasOnlyOneForm() = assertCounts(
        "ja", (listOf(0, 1, 2, 5, 21)).associateWith { "$it" + "曲" },
    )

    @Test fun chineseThaiVietnameseAndIndonesianHaveOnlyOneFormToo() {
        assertCounts("zh", listOf(0, 1, 2, 5, 21).associateWith { "$it 首歌曲" })
        assertCounts("zh-HK", listOf(0, 1, 2, 5, 21).associateWith { "$it 首歌曲" })
        assertCounts("zh-TW", listOf(0, 1, 2, 5, 21).associateWith { "$it 首歌曲" })
        assertCounts("th", listOf(1, 2).associateWith { "$it เพลง" })
        assertCounts("vi", listOf(1, 2).associateWith { "$it bài hát" })
        assertCounts("id", listOf(1, 2).associateWith { "$it lagu" })
    }

    @Test fun frenchCountsZeroAsOneAndGermanDoesNot() {
        assertCounts("fr", mapOf(0 to "0 chanson", 1 to "1 chanson", 2 to "2 chansons", 21 to "21 chansons"))
        assertCounts("de", mapOf(0 to "0 Songs", 1 to "1 Song", 2 to "2 Songs", 21 to "21 Songs"))
    }

    @Test fun theOtherLanguagesHaveTheirSingularToo() {
        assertCounts("es", mapOf(1 to "1 canción", 2 to "2 canciones"))
        assertCounts("it", mapOf(1 to "1 brano", 2 to "2 brani"))
        assertCounts("pt", mapOf(1 to "1 música", 2 to "2 músicas"))
        assertCounts("tr", mapOf(1 to "1 şarkı", 2 to "2 şarkı"))
        assertCounts("hi", mapOf(0 to "0 गाना", 1 to "1 गाना", 2 to "2 गाने"))
    }

    @Test fun aCategoryAFileDoesNotDeclareFallsBackToOtherInsteadOfFailing() {
        // CLDR gives Spanish, French, Italian and Portuguese a `many` for the millions that none of the files declare
        for (tag in listOf("es", "fr", "it", "pt")) assertTrue(count(tag, 1_000_000).startsWith("1000000"), "$tag at a million")
    }

    // ---- the files ----------------------------------------------------------------------------------------------------------------

    private val resources = File(SourceContract.root, "sharedUi/src/commonMain/composeResources")

    /** The categories each language's rule produces for a whole number (CLDR plural rules, integers only). */
    private val categories = mapOf(
        "values" to setOf("one", "other"),
        "values-de" to setOf("one", "other"), "values-es" to setOf("one", "other"), "values-fr" to setOf("one", "other"),
        "values-it" to setOf("one", "other"), "values-pt" to setOf("one", "other"), "values-tr" to setOf("one", "other"),
        "values-hi" to setOf("one", "other"),
        "values-ru" to setOf("one", "few", "many", "other"), "values-pl" to setOf("one", "few", "many", "other"),
        "values-he" to setOf("one", "two", "other"),
        "values-ja" to setOf("other"), "values-zh" to setOf("other"), "values-zh-rHK" to setOf("other"), "values-zh-rTW" to setOf("other"),
        "values-th" to setOf("other"), "values-vi" to setOf("other"), "values-id" to setOf("other"),
    )

    private fun declared(folder: String): List<Pair<String, String>> {
        val text = File(resources, "$folder/strings.xml").readText()
        val block = Regex("""<plurals name="cosmo_song_count">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(text)
            ?: fail("$folder has no plurals cosmo_song_count")
        return Regex("""<item quantity="(\w+)">([^<]*)</item>""").findAll(block.groupValues[1]).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    @Test fun everyLanguageDeclaresTheCountAsPluralsWithTheCategoriesOfItsRule() {
        val folders = resources.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty().map { it.name }.sorted()
        assertEquals(categories.keys.sorted(), folders, "the table above has every language folder")
        for (folder in folders) {
            val items = declared(folder)
            val names = items.map { it.first }
            assertEquals(names.toSet().size, names.size, "$folder declares a category twice")
            assertTrue(names.toSet().containsAll(categories.getValue(folder)), "$folder: ${categories.getValue(folder) - names.toSet()} missing")
            // a number in every text, except the one of a language that says "one song" in words
            for ((quantity, text) in items) {
                if (!(folder == "values-he" && quantity == "one")) assertTrue("%1$" + "d" in text, "$folder $quantity has no number: $text")
            }
        }
    }

    @Test fun theCountIsNoLongerAPlainStringAnywhere() {
        for (folder in categories.keys) {
            val text = File(resources, "$folder/strings.xml").readText()
            assertFalse("""<string name="cosmo_song_count">""" in text, "$folder still has the plain string")
        }
    }

    @Test fun bothPagesAskForThePluralWithTheCountAsItsQuantity() {
        val phone = SourceContract.code(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/pages/CosmoReleasePage.kt")).replace(Regex("\\s+"), " ")
        assertTrue("pluralStringResource(Res.plurals.cosmo_song_count, parts.songCount, parts.songCount)" in phone, "the phone's album and playlist page")
        val desktop = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopCollectionPage.kt")).replace(Regex("\\s+"), " ")
        assertTrue("pluralStringResource(SharedRes.plurals.cosmo_song_count, songs.size, songs.size)" in desktop, "the desktop's")
        for ((name, code) in listOf("phone" to phone, "desktop" to desktop)) assertFalse("string.cosmo_song_count" in code, "the $name page has no plain count left")
    }
}
