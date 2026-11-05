package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Upstream keeps every language file at the same keys, and no test of theirs enforces it. Ours does for the keys we add:
 * each `cosmo_*` key of the base file must be in every locale folder (with the English text until it is translated).
 */
class CosmoStringsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val resources = File(root, "sharedUi/src/commonMain/composeResources")
    private val keyPattern = Regex("<string name=\"(cosmo_[a-z0-9_]+)\"")

    private fun keysOf(file: File): Set<String> =
        keyPattern.findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun everyLocaleHasEveryCosmoKey() {
        val base = keysOf(File(resources, "values/strings.xml"))
        assertTrue(base.isNotEmpty(), "the base file has no cosmo_ key")
        val folders = resources.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty().sortedBy { it.name }
        assertTrue(folders.isNotEmpty(), "no locale folder found under $resources")
        val problems = folders.mapNotNull { folder ->
            val file = File(folder, "strings.xml")
            if (!file.isFile) return@mapNotNull "${folder.name}: no strings.xml"
            val missing = base - keysOf(file)
            if (missing.isEmpty()) null else "${folder.name}: missing ${missing.sorted()}"
        }
        if (problems.isNotEmpty()) fail("Locales without every cosmo_ key (${problems.size}):\n" + problems.joinToString("\n"))
    }
}
