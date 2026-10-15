package com.cosmotune

/**
 * Chooses which APK of a release the phone should download. A Cosmotune release carries four: one per CPU
 * family (`-android-arm64-v8a.apk`, `-android-armeabi-v7a.apk`, `-android-x86_64.apk`) and a universal one
 * (`-android-universal.apk`). The updater used to take the first `.apk` in the list, which is whichever the
 * server happened to list first and can be the wrong CPU.
 *
 * Only the choice lives here, as a pure function, so it can be tested without a phone: the caller passes
 * `Build.SUPPORTED_ABIS` in (it is empty or null in a plain JVM test) and has already reduced the release's
 * assets to uploaded files whose names end in `.apk`.
 */
object ApkPicker {

    private const val UNIVERSAL = "universal"

    /**
     * The download URL of the best APK among [candidates], or null when there is none.
     *
     * [candidates] are (asset name, download URL) pairs. [supportedAbis] is the phone's own list, most
     * preferred first (`Build.SUPPORTED_ABIS`). The rules, in order:
     *
     * 1. The first ABI in [supportedAbis] that has an asset wins, so the phone's own preference decides
     *    (a phone that lists `arm64-v8a` before `armeabi-v7a` gets the 64-bit file, even if the 32-bit one
     *    is listed first in the release).
     * 2. Otherwise the universal APK.
     * 3. Otherwise the first `.apk` in the list, so an older release with a single, differently named file
     *    keeps working.
     * 4. Otherwise null.
     *
     * An asset belongs to an ABI only when its name ends in exactly `-android-<abi>.apk`; a name that merely
     * contains the ABI does not count, so `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` never take each
     * other's file. Names are compared without regard to capital letters, and whatever comes before
     * `-android-` (the app name, the version) is ignored. If two assets carry the same ABI, the one listed
     * first is taken.
     */
    fun choose(candidates: List<Pair<String, String>>, supportedAbis: List<String>): String? {
        for (abi in supportedAbis) {
            if (abi.isBlank()) continue
            forAbi(candidates, abi)?.let { return it }
        }
        forAbi(candidates, UNIVERSAL)?.let { return it }
        return candidates.firstOrNull()?.second
    }

    private fun forAbi(candidates: List<Pair<String, String>>, abi: String): String? {
        val tail = "-android-$abi.apk"
        return candidates.firstOrNull { (name, _) -> name.endsWith(tail, ignoreCase = true) }?.second
    }
}
