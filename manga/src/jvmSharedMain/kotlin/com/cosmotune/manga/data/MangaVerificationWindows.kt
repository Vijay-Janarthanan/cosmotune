package com.cosmotune.manga.data

interface MangaVerificationBrowser : AutoCloseable { suspend fun complete(): MangaBrowserProof }

/** The desktop host installs its isolated browser; Android uses its private Activity instead. */
object MangaVerificationWindows {
    @Volatile var launcher: suspend (MangaVerificationRequest) -> MangaVerificationBrowser = {
        error("An isolated source-verification browser is unavailable in this build.")
    }
}
