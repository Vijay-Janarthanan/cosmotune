package com.cosmotune.ui.pip

/**
 * The players that can hold picture-in-picture: Video mode's, Watch mode's (films, series and live TV) and the song's music video in
 * Now Playing. One device has one picture-in-picture (the phone's and the TV's activity, the desktop's small window), so only one of
 * them holds it at a time, and [PipDecision] is the one place that says which.
 */
enum class PipOwner { VIDEO, WATCH, MUSIC_VIDEO }

/**
 * One player's standing in the decision. [ready]: its picture is on screen and playing (and its setting allows it), so leaving the
 * app may take it into picture-in-picture by itself. [since]: when it last became ready (a counter that only grows), so the newest
 * wish is known.
 */
data class PipClaim(val owner: PipOwner, val ready: Boolean, val since: Long)

/**
 * Who holds picture-in-picture now: pure, so the rules are tested without a window.
 *
 * 1. While picture-in-picture is showing, the player in it keeps it for as long as it still claims it: nobody takes the window
 *    from under it (a song's video starting behind a film does not swap the picture).
 * 2. A player whose button or menu asked for it now gets it, unless rule 1 holds.
 * 3. Otherwise the ready players decide: a film, a channel or a video (a picture with its own sound, which the person chose) before
 *    the song's music video (a picture over music that plays on without it), and between equals the one that became ready last.
 * 4. Nobody ready: nobody (leaving the app enters nothing).
 */
object PipDecision {
    fun owner(claims: Collection<PipClaim>, showing: PipOwner?, asked: PipOwner? = null): PipOwner? {
        if (showing != null && claims.any { it.owner == showing }) return showing
        if (asked != null) return asked
        return claims.filter { it.ready }.maxWithOrNull(compareBy<PipClaim>({ rank(it.owner) }, { it.since }))?.owner
    }

    /** A picture with its own sound ranks over the song's video. */
    fun rank(owner: PipOwner): Int = if (owner == PipOwner.MUSIC_VIDEO) 0 else 1
}
