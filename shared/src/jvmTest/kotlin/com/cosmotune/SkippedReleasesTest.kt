package com.cosmotune

import kotlin.test.Test
import kotlin.test.assertEquals

/** A listener who goes from 1.0.31 straight to 1.0.33 is told what 1.0.32 brought too, newest first and short. */
class SkippedReleasesTest {
    private fun notes(vararg titles: String) =
        "# Cosmotune\n\n## Highlights\n\n" + titles.joinToString("\n") { "- <!-- icon: new --> **$it** — Something $it does." } + "\n"

    private val releases = listOf(
        "v1.0.30" to notes("Thirty"),
        "v1.0.33" to notes("A", "B", "C", "D"),
        "v1.0.31" to notes("Thirty-one"),
        "v1.0.32" to notes("X", "Y", "Z"),
    )

    @Test fun everySkippedReleaseIsShownNewestFirstAndCut() {
        val sets = SkippedReleases.of(releases, since = "1.0.31", current = "1.0.33")
        assertEquals(listOf("1.0.33", "1.0.32"), sets.map { it.version })
        assertEquals(listOf("A", "B", "C", "D"), sets[0].highlights.map { it.title })
        assertEquals(listOf("X", "Y"), sets[1].highlights.map { it.title }, "an earlier release gives its first two")
    }

    @Test fun aSingleStepOrAnUnknownStartShowsTheCurrentReleaseAlone() {
        assertEquals(listOf("1.0.33"), SkippedReleases.of(releases, since = "1.0.32", current = "1.0.33").map { it.version })
        assertEquals(listOf("1.0.33"), SkippedReleases.of(releases, since = null, current = "1.0.33").map { it.version })
    }

    @Test fun atMostThreeEarlierReleasesAreNamed() {
        val many = (20..33).map { "v1.0.$it" to notes("R$it") }
        val sets = SkippedReleases.of(many, since = "1.0.19", current = "1.0.33")
        assertEquals(listOf("1.0.33", "1.0.32", "1.0.31", "1.0.30"), sets.map { it.version })
    }

    @Test fun aReleaseWithNothingToShowIsLeftOut() {
        val sets = SkippedReleases.of(listOf("v1.0.33" to notes("A"), "v1.0.32" to "", "v1.0.31" to notes("Q")), since = "1.0.30", current = "1.0.33")
        assertEquals(listOf("1.0.33", "1.0.31"), sets.map { it.version })
    }
}
