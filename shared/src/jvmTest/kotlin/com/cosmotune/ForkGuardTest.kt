package com.cosmotune

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps Cosmotune apart from the project it was forked from. Each test fails with the list of
 * offending files and lines, so a merge that brings an upstream address or name back is caught at once.
 */
class ForkGuardTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val self = "shared/src/jvmTest/kotlin/com/cosmotune/ForkGuardTest.kt"

    /** Folders that never hold our own sources, wherever they are. */
    private val skippedDirs = setOf(".git", ".gradle", "build", "node_modules", ".idea", ".kotlin", ".cxx")

    /** Skipped folders, plus the notes folder `docs` at the top of the repository only. */
    private fun skip(dir: File): Boolean =
        dir != root && (dir.name in skippedDirs || (dir.name == "docs" && dir.parentFile == root))

    private fun rel(f: File) = f.relativeTo(root).invariantSeparatorsPath

    private fun files(vararg extensions: String, names: Set<String> = emptySet()): List<File> =
        root.walkTopDown()
            .onEnter { !skip(it) }
            .filter { it.isFile && (it.extension in extensions || it.name in names) && rel(it) != self }
            .toList()

    private fun read(path: String): String = File(root, path).readText()

    private fun hit(path: String, line: Int, text: String) = "$path:$line: ${text.trim().take(120)}"

    private fun assertNone(problem: String, offenders: List<String>) {
        if (offenders.isNotEmpty()) fail("$problem (${offenders.size}):\n" + offenders.joinToString("\n"))
    }

    // --- Reading source text -------------------------------------------------------------------

    /** The text without comments, with every line kept in place so that line numbers stay right. */
    private fun codeOnly(text: String): String =
        text.replace(Regex("(?m)^\\s*/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> "\n".repeat(m.value.count { it == '\n' }) }
            .lines()
            .joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }

    private fun isCommentLine(trimmed: String) =
        trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")

    /** The credit line ("Based on BitChord by ...") may keep the name; it is how the fork thanks its source. */
    private fun isCredit(text: String) = text.contains("based on BitChord", ignoreCase = true)

    /**
     * Every piece of a line between two quote marks, inside and outside strings alike. Pairing the quotes
     * from the left goes wrong on """ raw strings and on quotes nested in a string template: the text then
     * lands in the "outside" pieces. So the checks look at all pieces and do not guess which are text.
     */
    private fun pieces(line: String): List<String> = line.split('"').map { it.trimEnd('\\') }

    /**
     * The pieces of a line that can be a name: the odd ones are inside quotes when the quotes pair up, the
     * even ones are code. A name can still hide in an even piece (after """ or inside a quoted template), so
     * those count too, but only when they look like a bare name: code such as `com.music.bitchord.Foo.bar(`
     * has parentheses, spaces or the like and is not a name.
     */
    private fun namesIn(line: String): List<String> = pieces(line).filterIndexed { k, piece ->
        k % 2 == 1 || piece.none { it.isWhitespace() || it in "(){}[],=;:" }
    }

    /** The line without a trailing // comment; a // inside a string (an address) stays. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inString) i++
            else if (c == '"') inString = !inString
            else if (!inString && c == '/' && line.getOrNull(i + 1) == '/') return line.substring(0, i)
            i++
        }
        return line
    }

    /** Log.d(...), TrackLog.w(...), log.Printf(...): text for whoever reads the log, not for people using the app. */
    private val logCall = Regex("Log\\.|\\blog\\.")
    private val tagDeclaration = Regex("\\b\\w*TAG\\s*=")

    /** A line such as `"BitChord",` that is the log tag on its own line, right under `TrackLog.d(`. */
    private fun isLogArgument(lines: List<String>, i: Int): Boolean {
        val above = lines.getOrNull(i - 1)?.trim() ?: return false
        return logCall.containsMatchIn(above) && above.endsWith("(")
    }

    /** A source set name such as test, jvmTest, commonTest, androidTest or androidUnitTest. */
    private fun isTestPath(path: String): Boolean {
        val set = Regex("(?:^|/)src/([^/]+)/").find(path)?.groupValues?.get(1) ?: return false
        return set == "test" || set.endsWith("Test")
    }

    private fun isProductSource(path: String) =
        listOf("app/src/", "desktopApp/src/", "sharedUi/src/", "shared/src/").any { path.startsWith(it) } && !isTestPath(path)

    /**
     * The name as people read it: "BitChord" in any capitalisation (Bitchord, BITCHORD, BitChords), or the
     * lowercase "bitchord 1.8 is ready" of the update texts (lowercase counts only as a word of a sentence:
     * followed by a space or punctuation). The lowercase word alone, in an address (bitchord://), a property
     * (bitchord.version), a file or thread name (bitchord-cookies, bitchord_window), an environment variable
     * (BITCHORD_LASTFM_API_KEY) or a resource key is an identifier, not a name people read.
     * Only ASCII letters count as "part of a longer word": Japanese and Chinese run the name straight
     * into the next word (BitChordの), and that is still the name.
     */
    private val visibleName = Regex(
        "(?<![A-Za-z0-9_])(?!bitchord)(?i:bitchord)[sS]?(?![A-Za-z0-9_])" +
            "|(?<![\\w.-])bitchord(?=\\s|[<,;!?)'\u2019]|\\.(?![A-Za-z0-9]))",
    )

    /** Files that may name BitChord's servers: the credit and the ledger explain the fork. */
    private val mayNameUpstream = setOf("README.md", "NOTICE.md", "FORK.md", "UPSTREAM.md", "LICENSE")

    /**
     * Upstream's two server deployment scripts, kept exactly as they were. Phase 9 (our own server) rewrites
     * them; until then they may name upstream's hosts. Nothing else under backend/deploy is exempt.
     */
    private val upstreamDeployScripts = setOf("backend/deploy/bitchord-deploy.sh", "backend/deploy/setup.sh")

    @Test
    fun nothingTalksToTheUpstreamServers() {
        val forbidden = listOf(
            "api.bitchord.kushagrasingh.in",
            "bitchord.kushagrasingh.in",
            "bitchord-listen-together.onrender.com",
            "bitchord-67v8.onrender.com",
            "repos/kushagrasinghx/BitChord",
            "raw.githubusercontent.com/kushagrasinghx",
            "github.com/kushagrasinghx/BitChord", // the plain repository address: only the credit files may link it
            "1411019391843172514", // BitChord's Discord application id
            "discord.gg/pDdKfrdHY6", // BitChord's Discord server
        )
        val offenders = files(
            "kt", "kts", "java", "xml", "yml", "yaml", "md", "sh", "go", "properties", "json",
            "py", "service", "txt", "cmake", "cpp", "pro",
            names = setOf("Caddyfile"),
        )
            .filter { rel(it) !in mayNameUpstream && rel(it) !in upstreamDeployScripts }
            .flatMap { f ->
                f.readLines().withIndex().flatMap { (i, line) ->
                    forbidden.filter { line.contains(it, ignoreCase = true) }.map { "${rel(f)}:${i + 1}: $it" }
                }
            }
        assertNone("These still point at the upstream project", offenders)
    }

    @Test
    fun userFacingTextDoesNotNameTheUpstreamApp() {
        val offenders = mutableListOf<String>()

        // Text resources: Android, desktop (loaded at run time by DesktopStrings) and shared UI.
        // The resource key (name="...") is not text, so it is cut out before looking.
        val textResource = Regex("^app/src/[^/]+/res/.+/strings[^/]*\\.xml$")
        val composeResource = Regex("^[^/]+/src/[^/]+/composeResources/values[^/]*/[^/]+\\.xml$")
        files("xml")
            .filter {
                val p = rel(it)
                !isTestPath(p) && (textResource.matches(p) || composeResource.matches(p) || p.startsWith("desktopApp/src/main/resources/strings/"))
            }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val text = line.replace(Regex("\\bname=\"[^\"]*\""), "")
                    if (!line.trim().startsWith("<!--") && !isCredit(line) && visibleName.containsMatchIn(text)) offenders += hit(rel(f), i + 1, line)
                }
            }

        // The name under the app icon, in every manifest.
        val label = Regex("android:label=\"([^\"]*)\"")
        files("xml").filter { it.name == "AndroidManifest.xml" && rel(it).startsWith("app/src/") }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (label.findAll(line).any { visibleName.containsMatchIn(it.groupValues[1]) }) offenders += hit(rel(f), i + 1, line)
            }
        }

        // Build files: the name of the app, the installer, the portable archive and the launcher script.
        // (The copyright line is left out on purpose: it may keep crediting BitChord's authors.)
        val displaySetting = Regex(
            "resValue\\(\\s*\"string\"|^\\s*(packageName|description|vendor|menuGroup|dockName)\\s*=|archiveFileName|\\.dir\\(|File\\(target,|appendLine\\(",
        )
        listOf("app/build.gradle.kts", "desktopApp/build.gradle.kts").forEach { path ->
            codeOnly(read(path)).lines().forEachIndexed { i, line ->
                if (displaySetting.containsMatchIn(line) && !isCredit(line) && visibleName.containsMatchIn(line)) offenders += hit(path, i + 1, line)
            }
        }

        // The server's pages (backend/main.go holds the invitation page as a text template). Import paths,
        // log lines and comments are not text people read.
        files("go").filter { rel(it).startsWith("backend/") && !it.name.endsWith("_test.go") }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (!isCommentLine(t) && "github.com/" !in t && "://" !in t && !logCall.containsMatchIn(t) && !isCredit(t) &&
                    visibleName.containsMatchIn(t)
                ) {
                    offenders += hit(rel(f), i + 1, line)
                }
            }
        }

        // Kotlin: a line with a quote mark that contains the name (the whole line is looked at, not only the
        // pieces that look like strings: see pieces()). Comments, imports, addresses (://), log lines and log
        // tags, and the credit line ("Based on BitChord") are not text people read; the name inside an
        // identifier (BitChordIcons) is not the name either: see visibleName.
        files("kt").filter { isProductSource(rel(it)) }.forEach { f ->
            val lines = f.readLines()
            lines.forEachIndexed { i, line ->
                val t = line.trim()
                if (!isCommentLine(t) && !t.startsWith("import ") && !t.startsWith("package ") &&
                    "://" !in t && !logCall.containsMatchIn(t) && !tagDeclaration.containsMatchIn(t) && !isLogArgument(lines, i) &&
                    !isCredit(t) && '"' in t && visibleName.containsMatchIn(withoutTrailingComment(t))
                ) {
                    offenders += hit(rel(f), i + 1, line)
                }
            }
        }
        assertNone("These still show the name BitChord to people", offenders)
    }

    @Test
    fun windowsInstallerDoesNotReplaceTheUpstreamApp() {
        val gradle = codeOnly(read("desktopApp/build.gradle.kts")) // a comment that says upgradeUuid does not count
        val problems = mutableListOf<String>()
        val uuid = Regex("upgradeUuid\\s*=\\s*\"([^\"]+)\"").find(gradle)?.groupValues?.get(1)
        when {
            uuid == null -> problems += "the Windows installer needs an upgradeUuid (an assignment, not a comment)"
            runCatching { UUID.fromString(uuid) }.isFailure -> problems += "upgradeUuid is not a valid UUID: $uuid"
            uuid.equals("8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913", ignoreCase = true) ->
                problems += "upgradeUuid is still BitChord's: installing Cosmotune would upgrade (replace) an installed BitChord"
        }
        gradle.lines().forEachIndexed { i, line ->
            if (Regex("\\b(packageName|menuGroup)\\s*=\\s*\"BitChord\"").containsMatchIn(line)) {
                problems += hit("desktopApp/build.gradle.kts", i + 1, line) +
                    "  <- still BitChord's name: the installer would share its folder and Start menu group"
            }
        }
        assertNone("The Windows installer would replace or share files with an installed BitChord", problems)
    }

    @Test
    fun desktopStateIsKeptApartFromTheUpstreamApp() {
        // Settings areas, folders and ids an installed BitChord also uses.
        val shared = listOf(
            "node(\"com.music.bitchord",
            "node(\"com.dev.bitchord",
            "Path.of(base, \"bitchord\"",
            "Path.of(state, \"bitchord\"",
            "\"Music\", \"BitChord\"",
            "\"application\" to \"bitchord\"", // the keyring attribute the saved YouTube Music session is filed under
            "org.mpris.MediaPlayer2.bitchord", // the media-key bus name
            "variant(\"bitchord\")", // MPRIS desktop entry
            "Variant(\"bitchord\")", // status-notifier id
        )
        // A quoted name that is, or ends in, the whole lowercase word: "bitchord", ".bitchord", "bitchord/media",
        // "org.mpris.MediaPlayer2.bitchord", "com.music.bitchord.desktop". Property names such as
        // bitchord.version, bitchord.lastfm.key and bitchord.listentogether.server, and names such as
        // bitchord-cookies or bitchord_window (temporary files, threads, native libraries), do not match:
        // they are internal and are kept so that upstream merges stay free of conflicts. The capitalised
        // name shown to people ("BitChord") is the business of userFacingTextDoesNotNameTheUpstreamApp.
        val stateName = Regex("^(?:com\\.(?:music|dev)\\.bitchord(?:\\..*)?|(?:.*[./\\\\])?bitchord(?:[/\\\\].*)?)$")

        val offenders = mutableListOf<String>()
        files("kt")
            .filter { rel(it).startsWith("desktopApp/src/main/") || (rel(it).startsWith("shared/src/") && !isTestPath(rel(it))) }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val t = line.trim()
                    if (!isCommentLine(t) && (shared.any { it in t } || '"' in t && namesIn(withoutTrailingComment(t)).any { stateName.matches(it) })) {
                        offenders += hit(rel(f), i + 1, line)
                    }
                }
            }
        // The macOS bundle id is where macOS keeps an app's settings and saved state.
        val bundleId = Regex("bundleID\\s*=\\s*\"[^\"]*bitchord", RegexOption.IGNORE_CASE)
        codeOnly(read("desktopApp/build.gradle.kts")).lines().forEachIndexed { i, line ->
            if (bundleId.containsMatchIn(line)) offenders += hit("desktopApp/build.gradle.kts", i + 1, line)
        }
        assertNone("These would read and write the same settings, folders or ids as an installed BitChord", offenders)
    }

    @Test
    fun androidIdsAreOurs() {
        val ids = Regex("applicationId\\s*=\\s*\"([^\"]+)\"").findAll(codeOnly(read("app/build.gradle.kts"))).map { it.groupValues[1] }.toList()
        assertTrue(ids.isNotEmpty(), "no applicationId found in app/build.gradle.kts")
        assertTrue(
            ids.all { it == "com.cosmotune" || it.startsWith("com.cosmotune.") },
            "application ids must be com.cosmotune or start with com.cosmotune. but are $ids",
        )
    }

    @Test
    fun signingSecretsAreNeverTracked() {
        val ignored = read(".gitignore").lines().map { it.trim() }
        listOf("keystore.properties", "*.jks", "local.properties").forEach {
            assertTrue(it in ignored, ".gitignore must list $it so a signing key or secret is never committed")
        }
        val git = try {
            ProcessBuilder("git", "ls-files").directory(root).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return // git cannot be started on this machine: the ignore rules above are all we can check
        }
        val listing = git.inputStream.bufferedReader().readText()
        val exit = git.waitFor()
        if (exit != 0) fail("git ls-files failed with exit code $exit, so the tracked files could not be checked:\n$listing")
        val secretExtensions = setOf("jks", "keystore", "p12", "pem")
        val tracked = listing.lines().map { it.trim().trim('"') }.filter { path ->
            val name = path.substringAfterLast('/')
            name == "keystore.properties" || name.substringAfterLast('.', "").lowercase() in secretExtensions
        }
        assertTrue(tracked.isEmpty(), "These signing files are tracked by git: $tracked")
    }

    @Test
    fun workflowsOnlyRunWhenStartedByHand() {
        val workflows = File(root, ".github/workflows").listFiles { f -> f.extension in setOf("yml", "yaml") }.orEmpty().sortedBy { it.name }
        val offenders = workflows.flatMap { f -> forbiddenWorkflowTriggers(f.readText()).map { "${f.name}: $it" } }
        assertNone("These workflows start on their own; use workflow_dispatch or a push of tags only", offenders)
    }
}

/**
 * What in the `on:` block of a workflow makes it start without a person: every trigger except
 * workflow_dispatch, and push unless it is limited to tags. Comments are ignored. Anything that cannot be
 * read with certainty is reported as a problem too, so that an odd way of writing a trigger cannot hide it.
 * (Top level and internal so that ForkGuardTriggerParserTest can check it with sample workflows.)
 */
internal fun forbiddenWorkflowTriggers(yaml: String): List<String> {
    val lines = yaml.lines().map { it.replace(Regex("(^|\\s)#.*$"), "").trimEnd() }
    val start = lines.indexOfFirst { Regex("^[\"']?on[\"']?\\s*:").containsMatchIn(it) }
    if (start < 0) return listOf("no on: block found, so it cannot be told when this workflow runs")

    // on: workflow_dispatch   or   on: [workflow_dispatch]
    val inline = lines[start].substringAfter(":").trim()
    if (inline.isNotEmpty()) {
        val names = Regex("[A-Za-z_]+").findAll(inline).map { it.value }.toList()
        return if (names.isEmpty()) listOf("on: $inline (cannot be read)")
        else names.filter { it != "workflow_dispatch" }.map { "$it (written inline; write the triggers as a block)" }
    }

    val body = lines.drop(start + 1).takeWhile { it.isBlank() || it.first().isWhitespace() || it.startsWith("-") }.filter { it.isNotBlank() }
    if (body.isEmpty()) return listOf("empty on: block")
    fun indent(s: String) = s.indexOfFirst { !it.isWhitespace() }
    val eventIndent = indent(body.first())
    // "push:", push:, 'push':, "- push", "- push:"; the key may be quoted
    val event = Regex("^\\s*(?:-\\s+)?[\"']?([A-Za-z0-9_]+)[\"']?\\s*(?::\\s*(.*))?$")

    // Split the block into one group per trigger: its name and everything written under it.
    val unreadable = mutableListOf<String>()
    val groups = mutableListOf<Pair<String, StringBuilder>>()
    body.forEach { line ->
        val m = event.matchEntire(line)
        when {
            indent(line) == eventIndent && m != null -> groups += m.groupValues[1] to StringBuilder(m.groupValues[2])
            indent(line) <= eventIndent -> unreadable += "cannot read this line of the on: block: ${line.trim()}"
            groups.isEmpty() -> unreadable += "cannot read this line of the on: block: ${line.trim()}"
            else -> groups.last().second.append('\n').append(line)
        }
    }
    if (groups.isEmpty() && unreadable.isEmpty()) unreadable += "no trigger could be read in the on: block"
    return unreadable + groups.mapNotNull { (name, rest) ->
        val text = rest.toString()
        when {
            name == "workflow_dispatch" -> null
            name == "push" && Regex("\\btags\\s*:").containsMatchIn(text) &&
                !Regex("\\b(branches|branches-ignore|paths|paths-ignore)\\s*:").containsMatchIn(text) -> null
            name == "push" -> "push (only a tags: filter is allowed; no branches or paths, and not unfiltered)"
            else -> name
        }
    }
}
