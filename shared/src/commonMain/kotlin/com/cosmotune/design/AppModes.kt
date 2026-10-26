package com.cosmotune.design

/** Stored ordinals are stable: append new modes after the existing entries. */
enum class AppMode { MUSIC, VIDEO, MANGA, LIBRARY, PODIO }

/** Library is reserved; the other modes have working Android/desktop hosts. */
object AppModes {
    fun available(mode: AppMode): Boolean = mode == AppMode.MUSIC || mode == AppMode.VIDEO || mode == AppMode.MANGA || mode == AppMode.PODIO
}
