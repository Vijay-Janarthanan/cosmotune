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
 * also keeps the Windows jar checks for both native libraries in both workflows.
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
