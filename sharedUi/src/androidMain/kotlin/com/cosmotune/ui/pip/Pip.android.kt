package com.cosmotune.ui.pip

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Picture-in-picture on the phone and the TV: the activity's own window, shared by every player in it through one
 * [PipCoordinator], so Video, Watch and the song's music video never fight over it.
 *
 * - [PipHandle.supported]: a `ComponentActivity` is found from `LocalContext` and the device has `FEATURE_PICTURE_IN_PICTURE` (the
 *   phone's `MainActivity` and the TV's `TvActivity` declare `android:supportsPictureInPicture`).
 * - The holder's wish ([PipCoordinator.owner]) is the window's: its shape clamped to between 1:2.39 and 2.39:1, its source
 *   rectangle, its buttons (previous, play or pause, next) and, on Android 12 and later, `setAutoEnterEnabled` while it is ready;
 *   on Android 8 to 11 the activity's user-leave hint enters with those parameters instead.
 * - The buttons are broadcasts to this app alone (`RECEIVER_NOT_EXPORTED`), handed to the holder's [PipControls].
 * - Leaving picture-in-picture: with the activity stopping it was closed ([PipWish.onClosed]), with it resuming it was
 *   expanded ([PipWish.onExpanded]); either may come first, so the one still to come decides.
 */
@Composable
actual fun rememberPip(owner: PipOwner): PipHandle {
    val context = LocalContext.current
    val activity = remember(context) { context.componentActivity() }
    if (activity == null) return NoPip
    val handle = remember(activity, owner) { ActivityPip.handle(activity, owner) }
    DisposableEffect(handle) {
        handle.acquire()
        onDispose { handle.release() }
    }
    return handle
}

private tailrec fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
}

/**
 * One caller's handle on its activity's picture-in-picture: [acquire] when it enters composition, [release] when it leaves (its
 * wish goes with it). Video's own `rememberPictureInPicture` holds one too, for [PipOwner.VIDEO].
 */
@Stable
class ActivityPipHandle internal constructor(private val shared: ActivityPip, val owner: PipOwner) : PipHandle {
    override val supported: Boolean get() = shared.supported

    /** In picture-in-picture with this owner in it (a window entered with nobody known in it is Video's, as it was before). */
    override val active: Boolean get() = shared.inPip && shared.board.showing.let { it == owner || (it == null && owner == PipOwner.VIDEO) }

    override fun update(wish: PipWish) = shared.update(this, owner, wish)
    override fun enter(): Boolean = shared.enter(owner)

    fun acquire() = shared.acquire()
    fun release() = shared.leave(this)
}

/**
 * The picture-in-picture of one activity: its [board], its listeners and the parameters last sent. Main thread only, like
 * composition. Video's own handle (`rememberPictureInPicture`) and the shared one ([rememberPip]) both come here.
 */
class ActivityPip private constructor(private val activity: ComponentActivity) {

    val supported: Boolean = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Who wishes what, who holds the window and who is in it. */
    val board = PipCoordinator()

    /** Whether the activity is in picture-in-picture now: snapshot state, so pages follow it. */
    var inPip by mutableStateOf(activity.isInPictureInPictureMode)
        private set

    private var users = 0
    private var sent: List<Any?>? = null
    private var params: PictureInPictureParams? = null

    /** Picture-in-picture was left and it is not known yet whether it was closed or expanded: who was in it. */
    private var leaving: PipOwner? = null
    private var leavingWish: PipWish? = null

    private val action = activity.packageName + ".PIP_CONTROL"

    private val modeListener = Consumer<PictureInPictureModeChangedInfo> { info -> modeChanged(info.isInPictureInPictureMode) }

    /** Android 8 to 11: leaving the app enters picture-in-picture when the holder is ready (12 and later enter by themselves). */
    private val leaveHintListener = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || inPip) return@Runnable
        val wish = board.current() ?: return@Runnable
        if (wish.ready) params?.let { wanted -> runCatching { activity.enterPictureInPictureMode(wanted) } }
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> settleLeaving(closed = false)
            Lifecycle.Event.ON_STOP -> settleLeaving(closed = true)
            else -> Unit
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val controls = (board.showing?.let(board::wish) ?: board.current())?.controls ?: return
            when (intent.getIntExtra(EXTRA_CONTROL, -1)) {
                CONTROL_PREVIOUS -> controls.previous?.invoke()
                CONTROL_PLAY_PAUSE -> controls.playPause()
                CONTROL_NEXT -> controls.next?.invoke()
            }
        }
    }

    internal fun acquire() {
        if (users++ > 0) return
        activity.addOnPictureInPictureModeChangedListener(modeListener)
        activity.addOnUserLeaveHintListener(leaveHintListener)
        activity.lifecycle.addObserver(lifecycleObserver)
        runCatching { ContextCompat.registerReceiver(activity, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED) }
        inPip = activity.isInPictureInPictureMode
    }

    /** A caller leaves composition: its owner's wish goes (when it spoke last for it), and the window follows the next holder. */
    internal fun leave(handle: ActivityPipHandle) {
        withdraw(handle, handle.owner)
        if (--users > 0) return
        activity.removeOnPictureInPictureModeChangedListener(modeListener)
        activity.removeOnUserLeaveHintListener(leaveHintListener)
        activity.lifecycle.removeObserver(lifecycleObserver)
        runCatching { activity.unregisterReceiver(receiver) }
    }

    /** [speaker] (a caller of [owner]'s) wishes [wish] now. */
    fun update(speaker: Any, owner: PipOwner, wish: PipWish) {
        if (!supported) return
        board.update(speaker, owner, wish)
        apply()
    }

    /** [speaker] has nothing to wish any more (its page left). */
    fun withdraw(speaker: Any, owner: PipOwner) {
        board.withdraw(speaker, owner)
        apply()
    }

    /** [owner]'s button: enters now with its shape and buttons; false when another player is in it or the system refused. */
    fun enter(owner: PipOwner): Boolean {
        if (!supported || !board.ask(owner)) return false
        apply()
        val wanted = params ?: build(board.wish(owner))
        val entered = runCatching { activity.enterPictureInPictureMode(wanted) }.getOrDefault(false)
        if (!entered) board.cancelAsk()
        return entered
    }

    private fun modeChanged(now: Boolean) {
        if (now == inPip) return
        inPip = now
        if (now) {
            leaving = null
            leavingWish = null
            board.entered()
        } else {
            val was = board.left()
            leaving = was
            leavingWish = was?.let(board::wish)
            // Stopped already: the window was closed. Otherwise the next of resume (expanded) and stop (closed) says.
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) settleLeaving(closed = true)
        }
        apply()
    }

    private fun settleLeaving(closed: Boolean) {
        val who = leaving ?: return
        val wish = board.wish(who) ?: leavingWish
        leaving = null
        leavingWish = null
        runCatching { if (closed) wish?.onClosed?.invoke() else wish?.onExpanded?.invoke() }
    }

    /** Sends the holder's parameters to the system when they changed. Refused shapes leave the window as it was rather than crash. */
    private fun apply() {
        if (!supported) return
        val wish = board.current()
        val shape = listOf(board.owner()) + (wish?.shape ?: emptyList())
        if (shape == sent) return
        sent = shape
        val built = build(wish)
        params = built
        runCatching { activity.setPictureInPictureParams(built) }
    }

    private fun build(wish: PipWish?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(clampedAspect(wish?.aspectWidth ?: 16, wish?.aspectHeight ?: 9))
        wish?.sourceRect?.let {
            builder.setSourceRectHint(android.graphics.Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt()))
        }
        builder.setActions(actions(wish))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(wish?.ready == true)
            // A video's frames change every frame: the system's cross-fade on resize is for still content.
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    /** Previous, play or pause, next: as many as the window takes, play and pause first among them. */
    private fun actions(wish: PipWish?): List<RemoteAction> {
        val controls = wish?.controls ?: return emptyList()
        val most = runCatching { activity.maxNumPictureInPictureActions }.getOrDefault(3).coerceAtLeast(1)
        val wanted = buildList {
            add(CONTROL_PLAY_PAUSE)
            if (controls.next != null) add(CONTROL_NEXT)
            if (controls.previous != null) add(CONTROL_PREVIOUS)
        }.take(most).toSet()
        return listOf(CONTROL_PREVIOUS, CONTROL_PLAY_PAUSE, CONTROL_NEXT).filter { it in wanted }.map { control ->
            val (icon, label) = when (control) {
                CONTROL_PREVIOUS -> android.R.drawable.ic_media_previous to "Previous"
                CONTROL_NEXT -> android.R.drawable.ic_media_next to "Next"
                else -> if (wish.playing) android.R.drawable.ic_media_pause to "Pause" else android.R.drawable.ic_media_play to "Play"
            }
            val intent = Intent(action).setPackage(activity.packageName).putExtra(EXTRA_CONTROL, control)
            val pending = PendingIntent.getBroadcast(activity, control, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            RemoteAction(Icon.createWithResource("android", icon), label, label, pending)
        }
    }

    companion object {
        private const val EXTRA_CONTROL = "control"
        private const val CONTROL_PREVIOUS = 1
        private const val CONTROL_PLAY_PAUSE = 2
        private const val CONTROL_NEXT = 3

        /** The picture's widest and tallest shapes Android takes: 2.39:1 and 1:2.39. */
        private val WIDEST = Rational(239, 100)
        private val TALLEST = Rational(100, 239)

        private val shared = WeakHashMap<ComponentActivity, ActivityPip>()

        /** The one picture-in-picture of [activity]. */
        fun of(activity: ComponentActivity): ActivityPip = shared.getOrPut(activity) { ActivityPip(activity) }

        /** A new caller's handle for [owner] in [activity]; [ActivityPipHandle.acquire] it while it is composed. */
        fun handle(activity: ComponentActivity, owner: PipOwner): ActivityPipHandle = ActivityPipHandle(of(activity), owner)

        /** [width] to [height] clamped to between 1:2.39 and 2.39:1; 16:9 when the size is not known yet. */
        fun clampedAspect(width: Int, height: Int): Rational {
            if (width <= 0 || height <= 0) return Rational(16, 9)
            val ratio = width.toFloat() / height
            return when {
                ratio > WIDEST.toFloat() -> WIDEST
                ratio < TALLEST.toFloat() -> TALLEST
                else -> Rational(width, height)
            }
        }
    }
}
