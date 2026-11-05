package com.cosmotune.tv

/** Keep optional hardware effects on the live player session, releasing obsolete resources. */
internal class TvAudioEffectBinding(private val attach: (Int) -> Unit, private val release: () -> Unit) {
    private var attachedSession = 0
    fun update(session: Int, enabled: Boolean) {
        val next = session.takeIf { enabled && it > 0 } ?: 0
        if (next == attachedSession) return
        release()
        attachedSession = 0
        if (next > 0) { attach(next); attachedSession = next }
    }
}
