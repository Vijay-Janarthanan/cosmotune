package com.cosmotune.design

/** Persisted by name so adding a mode never changes an existing installation's selection. */
enum class AppMode { MUSIC, VIDEO, MANGA, LIBRARY }

/** Library is reserved; Music, Video and Manga have independent working hosts. */
object AppModes {
    fun available(mode: AppMode): Boolean = mode == AppMode.MUSIC || mode == AppMode.VIDEO || mode == AppMode.MANGA
}
