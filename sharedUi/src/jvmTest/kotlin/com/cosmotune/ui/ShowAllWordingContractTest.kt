package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** A shelf's way to the rest of its list says the same words in every mode: "Show all", never "See all" or "View all". */
class ShowAllWordingContractTest {
    @Test fun everyModeSaysShowAll() {
        val other = Regex(""""(See all|View all)"|>(See all|View all)<""")
        val offenders = mutableListOf<String>()
        for (module in listOf("sharedUi", "video", "manga", "watch", "desktopApp", "app", "tv")) {
            File(SourceContract.root, module).walkTopDown()
                .filter { it.isFile && "${File.separator}build${File.separator}" !in it.path && "${File.separator}src${File.separator}" in it.path }
                .filter { (it.extension == "kt" && "Test" !in it.name) || (it.name == "strings.xml" && it.parentFile.name == "values") || (it.name == "en.xml") }
                .forEach { file ->
                    val text = if (file.extension == "kt") SourceContract.code(file.readText()) else file.readText()
                    other.find(text)?.let { offenders += "${file.name}: ${it.value}" }
                }
        }
        assertTrue(offenders.isEmpty(), "say \"Show all\": $offenders")
        assertTrue("seeAllLabel: String = \"Show all\"" in SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/kit/KitHeaders.kt"), "the kit's shelf heading")
    }
}
