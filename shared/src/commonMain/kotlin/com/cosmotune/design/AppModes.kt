package com.cosmotune.design

/** The three modes of the profile drawer's switch (spec section 11; the owner's ruling O1 names the second one "Video"). */
enum class AppMode { MUSIC, VIDEO, LIBRARY }

/** Which modes work today: Music and Video. Library is shown with a "Later" tag and opens a card that says when it comes. */
object AppModes {
    fun available(mode: AppMode): Boolean = mode == AppMode.MUSIC || mode == AppMode.VIDEO
}
