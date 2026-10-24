package com.cosmotune

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The public version is set once, in the root build file (`cosmotuneVersionName`), but a few places must repeat
 * it as a literal: run-time fallbacks for a desktop build started without `-Dcosmotune.version`, the provider
 * runtime's user agent, the browser helper's package and the web check job. A release bump that misses one of
 * them ships a build that reports an old version (and the desktop updater then offers the user the build they
 * already have), so every literal must equal the root default.
 */
class ReleaseVersionPinsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun read(path: String) = File(root, path).readText()

    private val version: String =
        Regex("""cosmotuneVersionName"] = providers\.gradleProperty\("cosmotune\.version"\)\.getOrElse\("([^"]+)"\)""")
            .find(read("build.gradle.kts"))?.groupValues?.get(1)
            ?: error("No cosmotuneVersionName default in build.gradle.kts")

    @Test
    fun theRootVersionIsAPlainRelease() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(version), "root version $version")
        assertTrue("""extra["cosmotuneBetaSuffix"] = """"" in read("build.gradle.kts"), "a beta suffix is set")
    }

    @Test
    fun theDesktopDefaultAndEveryRunTimeFallbackMatchTheRootVersion() {
        val desktopDefault = Regex("""\?: "([^"]+)"""").find(
            read("desktopApp/build.gradle.kts").substringAfter("val appVersion: String"),
        )?.groupValues?.get(1)
        assertEquals(version, desktopDefault, "desktopApp/build.gradle.kts appVersion default")

        val fallback = Regex("""System\.getProperty\("cosmotune\.version"\) \?: "([^"]+)"""")
        val found = File(root, "desktopApp/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> fallback.findAll(file.readText()).map { file.name to it.groupValues[1] } }
            .toList()
        assertTrue(found.size >= 3, "expected the updater, settings footer and add-on fallbacks, found $found")
        assertEquals(emptyList(), found.filter { it.second != version }, "fallbacks that differ from $version")
    }

    @Test
    fun theProviderRuntimeAndBrowserPackagesReportTheRootVersion() {
        val providers = File(root, "providers/src/jvmSharedMain/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file ->
                Regex(""""Cosmotune/(\d[^"]*)"|"appVersion" -> JsonPrimitive\("([^"]+)"\)""").findAll(file.readText())
                    .map { file.name to (it.groupValues[1].ifEmpty { it.groupValues[2] }) }
            }.toList()
        assertTrue(providers.isNotEmpty(), "no provider user agent found")
        assertEquals(emptyList(), providers.filter { it.second != version }, "provider literals that differ from $version")

        val helper = Regex(""""version": "([^"]+)"""").find(read("web/extension/package.json"))?.groupValues?.get(1)
        assertEquals(version, helper, "web/extension/package.json")

        val check = Regex("""package-web\.py --version (\S+)""").find(read(".github/workflows/check.yml"))?.groupValues?.get(1)
        assertEquals(version, check, ".github/workflows/check.yml web packaging version")
    }
}
