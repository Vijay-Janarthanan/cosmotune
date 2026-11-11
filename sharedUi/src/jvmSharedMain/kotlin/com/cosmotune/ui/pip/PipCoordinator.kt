package com.cosmotune.ui.pip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt

/**
 * The buttons picture-in-picture offers for a player: play and pause always; previous and next where the player has them (the
 * song's previous and next, the series' next episode, the next channel). On the desktop's window also [seekBy] and the time
 * ([positionMs], [durationMs]).
 */
class PipControls(
    val playPause: () -> Unit,
    val previous: (() -> Unit)? = null,
    val next: (() -> Unit)? = null,
    val seekBy: ((Long) -> Unit)? = null,
    val positionMs: () -> Long = { 0L },
    val durationMs: () -> Long = { 0L },
    /** Whether the player plays now, read live (a kept session's [PipWish.playing] is from when its page left). */
    val isPlaying: (() -> Boolean)? = null,
)

/**
 * What a player tells picture-in-picture, again whenever it changes (`PipHandle.update`).
 *
 * - [ready]: its picture is on screen and playing, so leaving the app may enter picture-in-picture by itself.
 * - [aspectWidth] to [aspectHeight]: the picture's shape; [sourceRect] where the picture is in the window, when known (the
 *   phone animates from it).
 * - [playing] and [controls]: the buttons (null: the system's own, expand and close).
 * - [title] and [picture]: what the desktop's window shows (the phone's window is the app itself).
 * - [onClosed]: picture-in-picture was closed (not expanded): Watch pauses, the song's video leaves the music playing.
 * - [onExpanded]: it was expanded back to the app.
 * - [ended]: the playback behind it has stopped: picture-in-picture closes.
 * - [keep]: the player's session, which stays in picture-in-picture when the page that drew it leaves (the person browses
 *   elsewhere, or switches mode, while the small window plays on); null where a page's leaving ends it.
 */
class PipWish(
    val ready: Boolean,
    val aspectWidth: Int = 16,
    val aspectHeight: Int = 9,
    val sourceRect: Rect? = null,
    val playing: Boolean = false,
    val controls: PipControls? = null,
    val title: String = "",
    val picture: (@Composable (Modifier) -> Unit)? = null,
    val onClosed: () -> Unit = {},
    val onExpanded: () -> Unit = {},
    val ended: () -> Boolean = { false },
    val keep: (() -> PipWish)? = null,
) {
    /** What the window's parameters are made of: a change here is sent to the system again, nothing else is. */
    internal val shape: List<Any?>
        get() = listOf(ready, aspectWidth, aspectHeight, sourceRect, playing, controls != null, controls?.previous != null, controls?.next != null)

    /** The same wish from [rect] (the picture's place in the window, which moves with the page). */
    fun withSourceRect(rect: Rect?): PipWish = if (rect == sourceRect) this else
        PipWish(ready, aspectWidth, aspectHeight, rect, playing, controls, title, picture, onClosed, onExpanded, ended, keep)

    companion object {
        /** A picture's shape in whole numbers for [PipWish.aspectWidth] : [PipWish.aspectHeight] (1000 high); 16:9 when not known. */
        fun aspectWidth(aspect: Float): Int = if (aspect > 0f && aspect.isFinite()) (aspect * ASPECT_BASE).roundToInt().coerceAtLeast(1) else 1778

        const val ASPECT_BASE = 1000
    }
}

/**
 * One device's picture-in-picture as the players see it: what each one wishes ([update], [withdraw]), who holds it now
 * ([owner], through [PipDecision]) and who is in it ([showing]). The phone keeps one per activity, the desktop one for the app.
 * Every call is on the UI thread, like composition; [showing] and the wishes are snapshot state, so a page or the desktop's
 * window that reads them follows them.
 */
@Stable
class PipCoordinator {
    private class Held(val speaker: Any, val wish: PipWish, val since: Long)

    /** The speaker of a kept session: no page speaks for it any more. */
    private object DETACHED

    private val held = mutableStateMapOf<PipOwner, Held>()
    private var counter = 0L

    /** The player in picture-in-picture now; null while it is not showing. */
    var showing: PipOwner? by mutableStateOf(null)
        private set

    /** A player whose button asked for picture-in-picture, until it is showing (or the attempt failed). */
    var asked: PipOwner? = null
        private set

    /** [speaker] (one caller of [owner]'s) tells what it wishes now; the last caller to speak for an owner is its voice. */
    fun update(speaker: Any, owner: PipOwner, wish: PipWish) {
        val before = held[owner]
        // A wish keeps its place in the queue while it stays as ready as it was; becoming ready again makes it the newest.
        val since = if (before != null && (before.wish.ready || !wish.ready)) before.since else ++counter
        held[owner] = Held(speaker, wish, since)
    }

    /**
     * [speaker] leaves: [owner]'s wish goes with it, unless another of its callers has spoken since. A player in picture-in-picture
     * now whose wish has a session ([PipWish.keep]) stays there: the page went, the playback did not, and the session is kept
     * ([detached]) until picture-in-picture is left, the playback ends or another player's button takes the window.
     */
    fun withdraw(speaker: Any, owner: PipOwner) {
        val h = held[owner]
        if (h?.speaker !== speaker) return
        val kept = if (showing == owner) h.wish.keep?.invoke() else null
        if (kept != null) held[owner] = Held(DETACHED, kept, h.since) else held.remove(owner)
        if (asked == owner) asked = null
    }

    /** Whether [owner]'s wish is a kept session, its page gone. */
    fun detached(owner: PipOwner): Boolean = held[owner]?.speaker === DETACHED

    /** Picture-in-picture is over for [owner]: a kept session goes (there is no page left to show it). */
    fun dropDetached(owner: PipOwner) {
        if (detached(owner)) held.remove(owner)
    }

    /** Whether [owner] has a wish standing. */
    fun claims(owner: PipOwner): Boolean = held.containsKey(owner)

    fun claims(): List<PipClaim> = held.map { (owner, h) -> PipClaim(owner, h.wish.ready, h.since, detached = h.speaker === DETACHED) }

    /** The player that holds picture-in-picture now (see [PipDecision]); null for nobody. */
    fun owner(): PipOwner? = PipDecision.owner(claims(), showing, asked)

    /** [owner]'s latest wish, or null. */
    fun wish(owner: PipOwner): PipWish? = held[owner]?.wish

    /** The wish of the player that holds it now. */
    fun current(): PipWish? = owner()?.let(::wish)

    /** [owner]'s button asks for picture-in-picture now: false when another player is in it with its page still there. */
    fun ask(owner: PipOwner): Boolean {
        val other = showing
        if (other != null && other != owner && claims(other) && !detached(other)) return false
        asked = owner
        return true
    }

    /** The ask did not end in picture-in-picture (the window refused). */
    fun cancelAsk() {
        asked = null
    }

    /**
     * Picture-in-picture is showing now: it belongs to the player that asked, else to the decision's, else to the last that spoke
     * (a window entered some other way still has someone in it). Returns that player.
     */
    fun entered(): PipOwner? {
        showing = asked ?: owner() ?: held.maxByOrNull { it.value.since }?.key
        asked = null
        return showing
    }

    /** Picture-in-picture is gone: returns who was in it. */
    fun left(): PipOwner? {
        val was = showing
        showing = null
        return was
    }
}

/**
 * One player's handle on picture-in-picture ([rememberPip]).
 * - [supported]: this device and window can show it at all.
 * - [active]: this player is in picture-in-picture now (the phone: the window is small and shows it; the desktop: the small window
 *   holds its picture), so its page shows only the picture, or (the desktop) what stands in for it.
 * - [manual]: [enter] opens a window of its own (the desktop); on the phone the system's window is the app.
 */
@Stable
interface PipHandle {
    val supported: Boolean
    val active: Boolean
    val manual: Boolean get() = false

    /** Tells picture-in-picture what this player wishes now (call again whenever it changes; nothing is sent when nothing did). */
    fun update(wish: PipWish)

    /** Enters picture-in-picture now (the player's button, the menu's item); false when it cannot or another player is in it. */
    fun enter(): Boolean

    /** Leaves picture-in-picture (the desktop's window closes; the phone's is the system's to close). */
    fun exit() {}
}

/**
 * The handle of [owner] in this window. The phone and the TV follow the activity (one decision for the activity: auto-enter, the
 * shape and the buttons are the holder's); the desktop opens its small always-on-top window through the host's
 * [PipWindowHost]. A caller that leaves composition takes its wish with it.
 */
@Composable
expect fun rememberPip(owner: PipOwner): PipHandle

/**
 * The desktop's picture-in-picture window, as the host provides it (the desktop app's mode host installs Video's always-on-top
 * window here, so Watch and Music reach it without naming Video). [open] shows [owner]'s picture, reading what it shows from [wish]
 * whenever that changes; [close] closes it.
 */
interface PipWindowHost {
    fun open(owner: PipOwner, wish: () -> PipWish?): Boolean

    fun close(returnToApp: Boolean)
}

/** A handle that can do nothing (no window to follow: a preview, a test). */
object NoPip : PipHandle {
    override val supported: Boolean get() = false
    override val active: Boolean get() = false
    override fun update(wish: PipWish) = Unit
    override fun enter(): Boolean = false
}

/**
 * The phone's and the TV's picture-in-picture window is the app itself: while a player's session is in it after its page has gone
 * (a link switched the mode, say), the host draws that session's picture over everything, so the window keeps showing the film or
 * the song's video. The desktop's window draws its own; there this draws nothing.
 */
@Composable
expect fun PipKeptPicture()
