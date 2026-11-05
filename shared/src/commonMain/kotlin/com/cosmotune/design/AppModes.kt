package com.cosmotune.design

/** Stored ordinals are stable: append new modes after the existing entries. */
enum class AppMode { MUSIC, VIDEO, MANGA, LIBRARY, PODIO, READ }

/**
 * Library is reserved; the other modes have working Android/desktop hosts. [AppMode.READ] (books, split from Manga's Books|Manga
 * page) is appended last so stored ordinals keep their meaning; both hosts route it to the books app.
 */
object AppModes {
    fun available(mode: AppMode): Boolean = mode == AppMode.MUSIC || mode == AppMode.VIDEO || mode == AppMode.MANGA || mode == AppMode.PODIO || mode == AppMode.READ

    /** The mode switcher's fixed order (DESIGN_SYSTEM.md 4.2): Music, Video, Podio, Read, Manga, Library. Independent of the ordinals. */
    val switcherOrder: List<AppMode> = listOf(AppMode.MUSIC, AppMode.VIDEO, AppMode.PODIO, AppMode.READ, AppMode.MANGA, AppMode.LIBRARY)

    /** The modes the switcher shows, in its order: the unavailable ones are hidden, not greyed. */
    fun shown(): List<AppMode> = switcherOrder.filter(::available)

    /** The mode `Ctrl`/`Cmd` + [digit] (1 to 6) switches to: the [digit]th of [shown], or null. */
    fun forShortcut(digit: Int): AppMode? = shown().getOrNull(digit - 1)
}
