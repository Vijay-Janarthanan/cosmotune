package com.cosmotune.design

/** The three modes of the profile drawer's switch (spec section 11; the owner's ruling O1 names the second one "Video"). */
enum class AppMode { MUSIC, VIDEO, LIBRARY }

/** Which modes work today: only Music. Video and Library are shown with a "Later" tag and open a card that says when they come. */
object AppModes {
    fun available(mode: AppMode): Boolean = mode == AppMode.MUSIC
}
