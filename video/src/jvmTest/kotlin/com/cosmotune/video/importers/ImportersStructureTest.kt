package com.cosmotune.video.importers

import com.cosmotune.video.ui.VideoSourceContract
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen from a unit test is pinned by reading the source (as the other structure tests of this module do): the
 * Android reader, the rules the importers keep (only `newpipe.db` is read, nothing is deserialised, no network, no NewPipe types)
 * and that common code stays common.
 */
class ImportersStructureTest {
    private val commonDir = "shared/src/commonMain/kotlin/com/cosmotune/video/importers"
    private val jvmDir = "shared/src/jvmSharedMain/kotlin/com/cosmotune/video/importers"
    private val androidDir = "video/src/androidMain/kotlin/com/cosmotune/video/importers"
    private val desktopDir = "video/src/jvmMain/kotlin/com/cosmotune/video/importers"

    private fun sources(dir: String): Map<String, String> =
        File(VideoSourceContract.root, dir).listFiles { f -> f.extension == "kt" }.orEmpty().sortedBy { it.name }
            .associate { it.name to VideoSourceContract.code(VideoSourceContract.read("$dir/${it.name}")) }

    private val everything: Map<String, String> by lazy { listOf(commonDir, jvmDir, androidDir, desktopDir).flatMap { sources(it).map { (name, code) -> "$it/$name" to code } }.toMap() }

    @Test
    fun theFilesOfTheTaskAreThere() {
        val common = setOf(
            "Csv.kt", "ImportDetector.kt", "ImportResult.kt", "ImportSupport.kt", "NewPipeDatabaseImporter.kt", "NewPipeSubscriptionsImporter.kt",
            "OpmlImporter.kt", "SqliteRows.kt", "TakeoutArchiveImporter.kt", "TakeoutHistoryImporter.kt", "TakeoutPlaylistsImporter.kt",
            "TakeoutSubscriptionsImporter.kt",
        )
        assertTrue(sources(commonDir).keys.containsAll(common), "missing: ${common - sources(commonDir).keys}")
        assertTrue(sources(jvmDir).keys.containsAll(setOf("TempFileSqliteOpener.kt", "ZipInput.kt")))
        assertTrue("AndroidSqliteRows.kt" in sources(androidDir).keys)
        assertTrue("JdbcSqliteRows.kt" in sources(desktopDir).keys)
    }

    @Test
    fun theAndroidReaderOpensTheFileReadOnlyAndReleasesIt() {
        val src = sources(androidDir).getValue("AndroidSqliteRows.kt")
        assertTrue(src.contains("class AndroidSqliteRows(path: String) : SqliteRows"))
        assertTrue(src.contains("SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)"))
        assertFalse(src.contains("OPEN_READWRITE") || src.contains("CREATE_IF_NECESSARY") || src.contains("execSQL") || src.contains("writableDatabase"))
        assertTrue(src.contains("database.close()"))
        assertTrue(src.contains("rawQuery(sql, null).use"), "the cursor is closed")
        for (type in listOf("FIELD_TYPE_NULL", "FIELD_TYPE_INTEGER", "FIELD_TYPE_FLOAT", "FIELD_TYPE_BLOB")) assertTrue(src.contains(type), type)
    }

    @Test
    fun theDesktopReaderOpensTheFileReadOnlyAndReleasesIt() {
        val src = sources(desktopDir).getValue("JdbcSqliteRows.kt")
        assertTrue(src.contains("class JdbcSqliteRows(path: String) : SqliteRows"))
        assertTrue(src.contains("setReadOnly(true)"))
        assertTrue(src.contains("jdbc:sqlite:"))
        assertTrue(src.contains("connection.close()"))
    }

    @Test
    fun newpipeSettingsIsNeverOpenedAndNothingIsDeserialised() {
        for ((file, code) in everything) {
            // the detector only compares the name of a ZIP's first entry; it opens nothing
            if (!file.endsWith("/ImportDetector.kt")) assertFalse(code.contains("newpipe.settings"), "$file names newpipe.settings in code")
            assertFalse(code.contains("ObjectInputStream") || code.contains("readObject") || code.contains("Serializable"), "$file deserialises")
        }
        val detector = everything.getValue("$commonDir/ImportDetector.kt")
        assertFalse(detector.contains(".read(") || detector.contains("ZipEntries") || detector.contains("ZipInput"), "the detector reads no archive")
        val importer = everything.getValue("$commonDir/NewPipeDatabaseImporter.kt")
        assertEquals(listOf("zip.read(entry)"), Regex("""zip\.read\([^)]*\)""").findAll(importer).map { it.value }.toList(), "the one entry the importer reads")
        assertTrue(importer.contains("it == DATABASE_ENTRY || it.endsWith(\"/\$DATABASE_ENTRY\")"), "and it is the one named newpipe.db")
        assertTrue(importer.contains("const val DATABASE_ENTRY = \"newpipe.db\""))
    }

    @Test
    fun noNetworkAndNoExtractorTypes() {
        for ((file, code) in everything) {
            assertFalse(code.contains("org.schabi"), "$file imports the extractor")
            assertFalse(Regex("""okhttp|java\.net\.|ktor|HttpURLConnection|URL\(""").containsMatchIn(code), "$file touches the network")
            assertFalse(code.contains("CookieJar"), file)
        }
    }

    @Test
    fun commonCodeUsesNoJvmOnlyApi() {
        // :shared's common source set is compiled together with the platform classpath, so nothing else would catch these
        for ((file, code) in everything.filterKeys { it.startsWith(commonDir) }) {
            assertFalse(Regex("""import java\.|import javax\.|import android\.""").containsMatchIn(code), "$file imports a platform class")
            assertFalse(Regex("""String\.format|\.format\(|System\.|Charsets\.|toCharArray|appendCodePoint|Character\.|Pattern\.|Matcher""").containsMatchIn(code), "$file uses a JVM-only call")
        }
    }

    @Test
    fun theImportersCarryNoUserFacingText() {
        // user-facing text is a video_* key (spec 12); the importers return data and reasons, and name the service only as a
        // product to match in a file ("YouTube") and in addresses (lower case)
        val literal = Regex("\"([^\"\n]*)\"")
        for ((file, code) in everything) {
            for (match in literal.findAll(code)) {
                val text = match.groupValues[1]
                assertTrue(!text.contains("YouTube") || text == "YouTube", "$file: \"$text\"")
            }
        }
    }
}
