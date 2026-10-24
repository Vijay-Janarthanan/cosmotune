package com.cosmotune

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The README's download links point at `releases/latest/download/<name>`, which only works for a file the Release
 * workflow actually publishes under that exact name. This reads `.github/workflows/release.yml` the way the jobs build
 * names (the `dist/...` copies, `$abi` from the Android splits, `$arch` from the macOS matrix, and the publish job's
 * rule that drops the version from `Cosmotune-<version>-<platform>-...`) and checks every README link against it. It
 * also keeps the Windows jar checks for the native libraries in both workflows, the signing-secret check and the
 * release body taken from `docs/releases/<version>.md`.
 */
class ReleaseWorkflowTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun read(path: String) = File(root, path).readText()

    private val release = read(".github/workflows/release.yml")

    private fun abis(module: String): List<String> {
        val include = Regex("""include\(([^)]*)\)""").find(read("$module/build.gradle.kts").substringAfter("splits {"))
            ?: error("no ABI splits in $module")
        return Regex("\"([^\"]+)\"").findAll(include.groupValues[1]).map { it.groupValues[1] }.toList() + "universal"
    }

    /** Every file name the workflow writes into a `dist` folder, with the version left as `$v`. */
    private fun published(): Set<String> {
        val text = release.replace("\${{ steps.version.outputs.value }}", "\$v")
        val arches = Regex("""arch: (\S+)""").findAll(text).map { it.groupValues[1] }.toList()
        val abis = (abis("app") + abis("tv")).distinct()
        val raw = Regex("""dist/((?:Cosmotune)[A-Za-z]*[-.][^"\s`]+)""").findAll(text).map { it.groupValues[1] }.toSet()
        val expanded = raw.flatMap { name ->
            when {
                "\$abi" in name -> abis.map { name.replace("\$abi", it) }
                "\$arch" in name -> arches.map { name.replace("\$arch", it) }
                else -> listOf(name)
            }
        }.toMutableSet()
        // The publish job's stable names: Cosmotune-<version>-(android|windows|linux|macos)-rest -> Cosmotune-<platform>-rest.
        assertTrue("""s/^Cosmotune-.*-(android|windows|linux|macos)-/Cosmotune-\1-/""" in release, "the stable-name rule changed")
        expanded += expanded.mapNotNull { name ->
            Regex("""^Cosmotune-\${'$'}v-(android|windows|linux|macos)-(.+)$""").find(name)
                ?.let { "Cosmotune-${it.groupValues[1]}-${it.groupValues[2]}" }
        }
        return expanded
    }

    @Test
    fun everyReadmeDownloadLinkIsAFileTheReleasePublishes() {
        val links = Regex("""releases/latest/download/([^)\s"]+)""").findAll(read("README.md"))
            .map { it.groupValues[1] }.toSet()
        assertTrue(links.size >= 20, "expected the phone, watch, TV, desktop and browser links, found $links")
        val published = published()
        assertEquals(emptySet(), links - published, "README links the Release workflow does not publish")
        for (name in listOf("CosmotuneWear.apk", "CosmotuneTV-android-universal.apk", "Cosmotune-helper-chrome.zip",
            "Cosmotune-helper-firefox.zip", "Cosmotune-web.zip")) {
            assertTrue(name in links, "README should offer $name")
        }
    }

    @Test
    fun bothWorkflowsRequireTheWindowsNativeLibrariesInTheJar() {
        for (workflow in listOf(".github/workflows/release.yml", ".github/workflows/check.yml")) {
            val text = read(workflow)
            for (library in listOf("cosmotune_analysis.dll", "cosmotune_smtc.dll", "cosmotune_window.dll")) {
                assertTrue("grep -q 'native/$library'" in text, "$workflow does not check $library")
            }
        }
    }

    /**
     * The in-app update dialogs show the GitHub release body, so the Publish job must use `docs/releases/<version>.md`
     * of the tagged commit when it exists (GitHub's generated notes only when it does not), with the notes' relative
     * links made absolute. The workflow cannot run here, so this pins the wiring and checks that the current version's
     * notes exist and that every relative link in them is one the rewrite covers and points at a file that exists.
     */
    @Test
    fun thePublishJobUsesTheNotesFileForTheTagAsTheReleaseBody() {
        val job = release.substringAfter("\n  release:")
        val checkout = job.indexOf("name: Check out the release notes")
        val choose = job.indexOf("name: Choose the release body")
        val stable = job.indexOf("name: Add file names that stay the same from one version to the next")
        val publish = job.indexOf("name: Publish the release")
        assertTrue(checkout >= 0 && choose > checkout && stable >= 0 && publish > choose && publish > stable,
            "the Publish job must check out the notes, choose the body and keep the stable names before publishing")

        val checkoutStep = job.substring(checkout, job.indexOf("\n      - ", checkout))
        for (line in listOf("uses: actions/checkout@v4", "path: notes", "sparse-checkout: docs/releases", "persist-credentials: false")) {
            assertTrue(line in checkoutStep, "the notes checkout lost `$line`")
        }
        val chooseStep = job.substring(choose, publish)
        for (line in listOf("id: notes", "TAG: \${{ github.ref_name }}", "notes=\"notes/docs/releases/\${TAG#v}.md\"",
            "if [ -f \"\$notes\" ]; then", "echo \"path=\$RUNNER_TEMP/release-body.md\" >> \"\$GITHUB_OUTPUT\"",
            "echo \"generated=false\" >> \"\$GITHUB_OUTPUT\"", "echo \"generated=true\" >> \"\$GITHUB_OUTPUT\"")) {
            assertTrue(line in chooseStep, "the body step lost `$line`")
        }
        val rewrites = listOf("../../" to "", "../" to "docs/")
        for ((from, to) in rewrites) {
            val sed = "-e \"s#](${from.replace(".", "\\.")}#](\$base/$to#g\""
            assertTrue(sed in chooseStep, "the body step no longer rewrites `$from` links: $sed")
        }

        val publishStep = job.substring(publish)
        assertTrue("body_path: \${{ steps.notes.outputs.path }}" in publishStep, "the release body is not the notes file")
        assertTrue("generate_release_notes: \${{ steps.notes.outputs.generated == 'true' }}" in publishStep,
            "generated notes must only be the fallback")
        assertTrue("generate_release_notes: true" !in publishStep, "generated notes would be appended to the notes file")
        assertTrue("files: dist/*" in publishStep, "the release files are no longer published")

        val version = Regex("""cosmotuneVersionName"] = providers\.gradleProperty\("cosmotune\.version"\)\.getOrElse\("([^"]+)"\)""")
            .find(read("build.gradle.kts"))?.groupValues?.get(1) ?: error("No cosmotuneVersionName default in build.gradle.kts")
        val notes = File(root, "docs/releases/$version.md")
        assertTrue(notes.isFile, "docs/releases/$version.md is missing, so the $version release would get generated notes")
        val targets = Regex("""\]\(([^)\s]+)\)""").findAll(notes.readText()).map { it.groupValues[1] }
            .filterNot { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("#") }
            .toList()
        assertTrue(targets.isNotEmpty(), "expected relative links in the notes")
        for (target in targets) {
            val (from, to) = rewrites.firstOrNull { target.startsWith(it.first) }
                ?: error("`$target` in the notes is relative but not ../ or ../../, so it breaks on the release page")
            val path = to + target.removePrefix(from).substringBefore('#')
            assertTrue(File(root, path).isFile, "`$target` in the notes points at $path, which does not exist")
        }
    }

    @Test
    fun theAndroidReleaseJobStopsWhenASigningSecretIsMissing() {
        val job = release.substringAfter("  android:").substringBefore("\n  web:")
        val check = job.indexOf("The signing secrets are set")
        assertTrue(check >= 0 && check < job.indexOf("Build the signed APKs"), "no secret check before the signed build")
        for (secret in listOf("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")) {
            assertTrue("$secret: \${{ secrets.$secret }}" in job, "$secret is not checked")
        }
    }
}
