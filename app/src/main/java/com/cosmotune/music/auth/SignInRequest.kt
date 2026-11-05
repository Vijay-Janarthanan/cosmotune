package com.cosmotune.music.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from Video mode to show the Google sign-in.
 *
 * The sign-in screen is the app's one ([YouTubeSignInOverlay]). On the phone, Video draws it in place over its own screen while
 * this is pending (`PhoneVideo` in the mode host), so signing in from Video no longer switches the app to Music; if Music is the
 * mode, `MainActivity` opens its own copy. Whoever shows it clears the flag with [handled], so that a recreated activity does not open
 * the screen again.
 */
object SignInRequest {
    private val _pending = MutableStateFlow(false)

    /** Whether a request is outstanding. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun handled() {
        _pending.value = false
    }
}
