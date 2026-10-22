package com.cosmotune

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.fail

/**
 * The upstream project's name, in any capitalisation and inside any word, appears nowhere in the repository except
 * on the short allowlist below (FORK.md, the rename row). A cherry-pick from upstream that brings the name back (a
 * file in the old package, an old resource key, an old property) fails here with file and line.
 *
 * Each allowlist entry is a path (a folder when it ends in `/`, every file when it is `*`) and, when only some lines
 * of it may keep the name, a piece of text those lines hold. Every entry says why.
 */
class OldNameScanTest {

    private class Allowed(val path: String, val line: String?, val reason: String) {
        fun covers(file: String, text: String): Boolean {
            val pathMatches = path == "*" || file == path || (path.endsWith("/") && file.startsWith(path))
            return pathMatches && (line == null || text.contains(line, ignoreCase = true))
        }
    }

    private val allowlist = listOf(
        // Credit and history.
        Allowed("README.md", null, "the credit to the upstream project"),
        Allowed("UPSTREAM.md", null, "names the upstream project and the last commit taken from it"),
        Allowed("NOTICE.md", null, "the notice that credits the upstream project and its authors"),
        Allowed("FORK.md", null, "the ledger of upstream files; it names the old names to explain the rename"),
        Allowed("docs/BASELINE.md", null, "facts about upstream's unchanged code, recorded before the fork changed it"),
        Allowed("tools/upstream-digest.sh", null, "reads the upstream repository by name"),
        Allowed("licenses/", null, "licence texts as their authors wrote them"),
        Allowed("*", "kushagrasinghx/BitChord", "the upstream repository's address (github.com/...)"),
        Allowed("*", "Kushagra Singh (BitChord adaptation)", "a copyright line of the upstream author"),
        Allowed("*", "here into BitChord -- a work licensed under the GNU", "the AGPL combination notice under that copyright line"),
        Allowed("*", "Copyright (C) 2026 BitChord Project", "a copyright line of the upstream project"),
        Allowed("*", "Vendored by BitChord from androidx/media", "the Apache-2.0 notice of who changed the vendored file"),
        Allowed("*", "Modified for BitChord (2026)", "the Apache-2.0 notice of who changed the vendored file"),
        Allowed("*", "based on BitChord", "the credit line in Settings and the installer's copyright"),
        Allowed("video/src/jvmTest/kotlin/com/cosmotune/video/ui/LegalTextsTest.kt", "listOf(\"BitChord\", \"LibreTube\"", "checks that the credit stays"),
        // Guards that look for the name.
        Allowed("shared/src/jvmTest/kotlin/com/cosmotune/OldNameScanTest.kt", null, "this test"),
        Allowed("shared/src/jvmTest/kotlin/com/cosmotune/ForkGuardTest.kt", null, "the upstream names and servers it looks for"),
        // People's data stored under the old names.
        Allowed("app/src/main/java/com/cosmotune/music/LegacyDataMigration.kt", null, "copies the stores of the old names once"),
        Allowed("app/src/test/java/com/cosmotune/music/LegacyDataMigrationTest.kt", null, "its test"),
        Allowed("app/src/main/res/xml/backup_rules.xml", "path=\"bitchord_", "the old encrypted stores stay on the device and out of backups"),
        Allowed("app/src/main/res/xml/data_extraction_rules.xml", "path=\"bitchord_", "the same, Android 12 and newer"),
        Allowed(
            "shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/lyrics/LrcWriter.kt",
            "LEGACY_WORD_LYRICS_FIELD = \"BITCHORD_LYRICS\"",
            "audio files saved before the rename carry the word-timed lyrics under this tag name",
        ),
        // Deployed services (renaming one needs a new deployment).
        Allowed("backend/deploy/", null, "the systemd unit, user, folders and deploy hook of the deployed server"),
        Allowed("backend/README.md", null, "documents those deployed names"),
        Allowed("backend/render.yaml", "bitchord-listen-together", "the Render service name"),
        Allowed("backend/main.go", "bitchord-listen-together", "the name the deployed server reports"),
    )

    private val name = Regex("bitchord", RegexOption.IGNORE_CASE)

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** The tracked files; without git, every file outside build and tool folders. */
    private fun files(): List<String> {
        val listed = try {
            val git = ProcessBuilder("git", "ls-files", "-z").directory(root).start()
            val out = git.inputStream.readBytes().decodeToString()
            if (git.waitFor() == 0) out.split('\u0000').filter { it.isNotEmpty() } else null
        } catch (e: IOException) {
            null
        }
        if (listed != null) return listed
        val skipped = setOf(".git", ".gradle", "build", "node_modules", ".idea", ".kotlin", ".cxx")
        return root.walkTopDown().onEnter { it == root || it.name !in skipped }.filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }

    @Test
    fun theOldNameAppearsOnlyWhereTheAllowlistSays() {
        val offenders = mutableListOf<String>()
        val used = mutableSetOf<Allowed>()
        for (path in files()) {
            val file = File(root, path)
            if (!file.isFile) continue // deleted in the working tree
            if (name.containsMatchIn(path)) {
                val entry = allowlist.firstOrNull { it.line == null && it.covers(path, "") }
                if (entry == null) offenders += "$path (the file's own name)" else used += entry
            }
            val bytes = file.readBytes()
            if (bytes.contains(0)) continue // binary
            if (!name.containsMatchIn(String(bytes, Charsets.ISO_8859_1))) continue
            bytes.toString(Charsets.UTF_8).lines().forEachIndexed { i, line ->
                if (!name.containsMatchIn(line)) return@forEachIndexed
                val entry = allowlist.firstOrNull { it.covers(path, line) }
                if (entry == null) offenders += "$path:${i + 1}: ${line.trim().take(140)}" else used += entry
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "The upstream name is back outside the allowlist (${offenders.size}); rename it, or add an entry with " +
                    "its reason to OldNameScanTest:\n" + offenders.joinToString("\n"),
            )
        }
        val stale = allowlist.filter { it !in used && it.path != "*" && !it.path.endsWith("/") }
        if (stale.isNotEmpty()) {
            fail("These allowlist entries match nothing any more; remove them:\n" + stale.joinToString("\n") { "${it.path}: ${it.reason}" })
        }
    }

    private fun ByteArray.contains(value: Byte): Boolean = any { it == value }
}
