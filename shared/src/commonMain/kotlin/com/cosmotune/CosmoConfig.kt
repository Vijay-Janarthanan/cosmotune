package com.cosmotune

/**
 * Everything that tells Cosmotune apart from the project it was forked from, in one file that
 * upstream never touches, so a merge from upstream cannot undo it.
 */
object CosmoConfig {
    const val APP_NAME = "Cosmotune"
    const val PROJECT_REPO = "Vijay-Janarthanan/cosmotune"
    const val PROJECT_URL = "https://github.com/$PROJECT_REPO"
    const val SITE_URL = "https://cosmotune.github.io"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$PROJECT_REPO/releases/latest"

    /** The name of the folder and settings area Cosmotune keeps its own data in. */
    const val STORAGE_NAME = "cosmotune"

    /** The custom link scheme for Listen Together invites, for example cosmotune://party/ABC123. */
    const val URL_SCHEME = "cosmotune"

    /** Discord Rich Presence is not part of Cosmotune (it needs a Discord user token, which Discord forbids). */
    const val DISCORD_ENABLED = false

    /** Cosmotune tells no server that it is open. Upstream's does, to its own server. */
    const val REPORT_PRESENCE = false

    /**
     * Whether the new interface is on for a fresh install: on since part two of the new look. A device that stored a choice keeps it;
     * Settings > Appearance > New look switches back to the old look.
     */
    const val NEW_LOOK_DEFAULT = true
}
