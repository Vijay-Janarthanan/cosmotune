package com.cosmotune.music.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from Video mode to show the Google sign-in.
 *
 * The sign-in screen is Music's (`webSession` in `MainActivity`), and Music and Video are never composed together: Video raises
 * this flag and the phone's mode host makes Music the mode, where `MainActivity` opens the screen and clears the flag with
 * [handled]. Like [com.cosmotune.music.playback.PlayerDeepLink], it is cleared by whoever acts on it so that a
 * recreated activity does not open the screen again.
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
