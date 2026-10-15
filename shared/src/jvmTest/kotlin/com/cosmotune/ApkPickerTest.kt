package com.cosmotune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Cosmotune release carries four APKs (`-android-arm64-v8a`, `-android-armeabi-v7a`, `-android-x86_64` and
 * `-android-universal`), and the updater has to hand the phone the one that fits it: the first ABI in the
 * phone's own list that has an asset, else the universal one, else the first `.apk` (an older release with a
 * single file keeps working), else nothing. The candidates are (asset name, download URL) pairs that the
 * caller has already reduced to uploaded `.apk` assets; the answer is a URL.
 */
class ApkPickerTest {

    private val prefix = "Cosmotune-1.0.0-alpha1"

    private val arm64 = "$prefix-android-arm64-v8a.apk" to "https://example.test/arm64"
    private val armeabi = "$prefix-android-armeabi-v7a.apk" to "https://example.test/armeabi"
    private val x86 = "$prefix-android-x86_64.apk" to "https://example.test/x86_64"
    private val universal = "$prefix-android-universal.apk" to "https://example.test/universal"

    private val release = listOf(universal, arm64, armeabi, x86)

    // --- the four-asset release, one phone at a time -----------------------------------------------

    @Test
    fun anArm64PhoneGetsTheArm64File() {
        assertEquals(arm64.second, ApkPicker.choose(release, listOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun anX86_64EmulatorGetsTheX86_64File() {
        assertEquals(x86.second, ApkPicker.choose(release, listOf("x86_64")))
    }

    @Test
    fun anOlder32BitPhoneGetsTheArmeabiFile() {
        assertEquals(armeabi.second, ApkPicker.choose(release, listOf("armeabi-v7a", "armeabi")))
    }

    @Test
    fun thePhonesOwnOrderDecidesWhichAbiIsTriedFirst() {
        // A phone that lists 32-bit ARM ahead of 64-bit ARM gets the 32-bit file.
        assertEquals(armeabi.second, ApkPicker.choose(release, listOf("armeabi-v7a", "arm64-v8a")))
        assertEquals(arm64.second, ApkPicker.choose(release, listOf("arm64-v8a", "armeabi-v7a")))
    }

    // --- falling back ------------------------------------------------------------------------------

    @Test
    fun anAbiWithNoAssetFallsBackToTheUniversalFile() {
        assertEquals(universal.second, ApkPicker.choose(release, listOf("riscv64")))
    }

    @Test
    fun aLaterAbiWithAnAssetBeatsTheUniversalFile() {
        // No x86 file in this release, but the second ABI the phone lists has one.
        val noX86 = listOf(universal, arm64, armeabi)
        assertEquals(armeabi.second, ApkPicker.choose(noX86, listOf("x86_64", "armeabi-v7a")))
    }

    @Test
    fun anEmptyAbiListGivesTheUniversalFile() {
        // Build.SUPPORTED_ABIS is empty in a plain JVM test; the answer is still sensible.
        assertEquals(universal.second, ApkPicker.choose(release, emptyList()))
    }

    @Test
    fun aBlankAbiIsIgnored() {
        assertEquals(universal.second, ApkPicker.choose(release, listOf("")))
        assertEquals(arm64.second, ApkPicker.choose(release, listOf("", "arm64-v8a")))
    }

    @Test
    fun aSingleApkWithAnUnrelatedNameIsChosen() {
        val old = listOf("Cosmotune-0.9.0.apk" to "https://example.test/old")
        assertEquals("https://example.test/old", ApkPicker.choose(old, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("https://example.test/old", ApkPicker.choose(old, emptyList()))
    }

    @Test
    fun noApkGivesNull() {
        assertNull(ApkPicker.choose(emptyList(), listOf("arm64-v8a")))
        assertNull(ApkPicker.choose(emptyList(), emptyList()))
    }

    // --- the name has to match exactly, not just contain the ABI -----------------------------------

    @Test
    fun anAbiIsNotMatchedInsideAnotherName() {
        // armeabi-v7a and arm64-v8a both start with "arm"; x86 is the start of x86_64; v8a is the end of arm64-v8a.
        val onlyArmeabi = listOf(universal, armeabi)
        assertEquals(universal.second, ApkPicker.choose(onlyArmeabi, listOf("arm64-v8a")))

        val onlyX86_64 = listOf(universal, x86)
        assertEquals(universal.second, ApkPicker.choose(onlyX86_64, listOf("x86")))

        val onlyArm64 = listOf(universal, arm64)
        assertEquals(universal.second, ApkPicker.choose(onlyArm64, listOf("v8a")))
        assertEquals(universal.second, ApkPicker.choose(onlyArm64, listOf("arm")))
    }

    @Test
    fun theAbiMustBeTheEndOfTheFileName() {
        // "-android-arm64-v8a" in the middle of a name, not followed by .apk, is not an arm64 build.
        val odd = listOf(
            "$prefix-android-arm64-v8a-debug.apk" to "https://example.test/odd",
            universal,
        )
        assertEquals(universal.second, ApkPicker.choose(odd, listOf("arm64-v8a")))
    }

    @Test
    fun aDifferentlyPrefixedNameStillMatches() {
        val renamed = listOf(
            "SomethingElse-2.3-android-universal.apk" to "https://example.test/u",
            "SomethingElse-2.3-android-arm64-v8a.apk" to "https://example.test/a64",
        )
        assertEquals("https://example.test/a64", ApkPicker.choose(renamed, listOf("arm64-v8a")))
    }

    @Test
    fun matchingIgnoresCapitalLetters() {
        val shouting = listOf(
            "COSMOTUNE-1.0.0-ALPHA1-ANDROID-UNIVERSAL.APK" to "https://example.test/U",
            "COSMOTUNE-1.0.0-ALPHA1-ANDROID-ARM64-V8A.APK" to "https://example.test/A64",
        )
        assertEquals("https://example.test/A64", ApkPicker.choose(shouting, listOf("arm64-v8a")))
        assertEquals("https://example.test/A64", ApkPicker.choose(shouting, listOf("ARM64-V8A")))
        assertEquals("https://example.test/U", ApkPicker.choose(shouting, listOf("x86_64")))
    }

    @Test
    fun theAbiGivenInCapitalsStillMatchesALowerCaseName() {
        assertEquals(arm64.second, ApkPicker.choose(release, listOf("ARM64-V8A")))
    }

    // --- order and duplicates ----------------------------------------------------------------------

    @Test
    fun theOrderOfTheAssetListDoesNotChangeTheAnswer() {
        val phones = listOf(
            listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
            listOf("armeabi-v7a", "armeabi"),
            listOf("x86_64"),
            listOf("x86"),
            listOf("riscv64"),
            emptyList(),
        )
        val expected = phones.map { ApkPicker.choose(release, it) }
        for (order in permutations(release)) {
            for ((i, phone) in phones.withIndex()) {
                assertEquals(expected[i], ApkPicker.choose(order, phone), "order=${order.map { it.first }} phone=$phone")
            }
        }
        assertEquals(
            listOf(arm64.second, armeabi.second, x86.second, universal.second, universal.second, universal.second),
            expected,
        )
    }

    @Test
    fun theFirstOfTwoAssetsForTheSameAbiWins() {
        val twice = listOf(
            "$prefix-android-arm64-v8a.apk" to "https://example.test/first",
            universal,
            "Other-android-arm64-v8a.apk" to "https://example.test/second",
        )
        assertEquals("https://example.test/first", ApkPicker.choose(twice, listOf("arm64-v8a")))
    }

    @Test
    fun theSameAbiListedTwiceByThePhoneChangesNothing() {
        assertEquals(arm64.second, ApkPicker.choose(release, listOf("arm64-v8a", "arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun twoUniversalFilesGiveTheFirstOne() {
        val twice = listOf(
            "A-android-universal.apk" to "https://example.test/u1",
            "B-android-universal.apk" to "https://example.test/u2",
        )
        assertEquals("https://example.test/u1", ApkPicker.choose(twice, emptyList()))
    }

    // --- a name with no "-android-" part is the last resort ----------------------------------------

    @Test
    fun anUnrelatedNameLosesToTheUniversalFileAndToAMatchingAbi() {
        val plain = "app-release.apk" to "https://example.test/plain"
        assertEquals(universal.second, ApkPicker.choose(listOf(plain, universal), listOf("riscv64")))
        assertEquals(arm64.second, ApkPicker.choose(listOf(plain, arm64), listOf("arm64-v8a")))
        assertEquals(arm64.second, ApkPicker.choose(listOf(arm64, plain), listOf("arm64-v8a")))
        assertEquals(universal.second, ApkPicker.choose(listOf(universal, plain), emptyList()))
    }

    @Test
    fun theLastResortIsTheFirstApkInTheList() {
        val plain = "app-release.apk" to "https://example.test/plain"
        val other = "another.apk" to "https://example.test/another"
        assertEquals(plain.second, ApkPicker.choose(listOf(plain, other), listOf("arm64-v8a")))
        assertEquals(other.second, ApkPicker.choose(listOf(other, plain), listOf("arm64-v8a")))
    }

    @Test
    fun theAnswerIsTheUrlNotTheName() {
        assertEquals(
            "https://github.com/x/y/releases/download/v1.0.0-alpha1/$prefix-android-arm64-v8a.apk",
            ApkPicker.choose(
                listOf(
                    arm64.first to "https://github.com/x/y/releases/download/v1.0.0-alpha1/${arm64.first}",
                ),
                listOf("arm64-v8a"),
            ),
        )
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.indices.flatMap { i ->
            permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it }
        }
    }
}
