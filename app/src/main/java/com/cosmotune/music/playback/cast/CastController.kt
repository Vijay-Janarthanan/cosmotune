package com.cosmotune.music.playback.cast

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Which receivers are out there, and which one the music is on.
 *
 * The half of casting the screen talks to. Choosing a receiver is the Cast
 * framework's own business — selecting its route starts a session — so this
 * only lists routes, asks for one to be selected, and reports what the session
 * does about it. What *plays* on the receiver is [CastPlayback]'s, which is
 * handed each session as it begins.
 *
 * Everything here is main-thread, like the router it wraps.
 */
object CastController {
    private const val TAG = "CosmotuneCast"

    data class Device(
        val id: String,
        val name: String,
        val connecting: Boolean,
        val connected: Boolean,
        /** The receiver's address on the network, when its route says. */
        val host: String? = null,
    )

    data class State(
        /** False until the framework is up, and for good where Play Services is missing. */
        val supported: Boolean = false,
        val devices: List<Device> = emptyList(),
        val connectedName: String? = null,
        val connecting: Boolean = false,
        val error: String? = null,
    ) {
        val casting: Boolean get() = connectedName != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The receiver's own volume, 0..1. */
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var started = false
    private var playback: CastPlayback? = null
    private var appContext: Context? = null

    /** The session whose volume is being followed, so it can be let go of. */
    private var watched: CastSession? = null
    private var pending: CastSession? = null
    private var disconnectEpoch = 0L

    /**
     * Someone other than the music asking for a receiver: Watch's video. The next session that
     * starts is handed to it instead of to [CastPlayback], and the music never sees it.
     */
    interface Guest {
        fun onSessionBegan(session: CastSession)
        fun onSessionEnded()
    }
    private var guestClaim: Guest? = null
    private var guestSession: CastSession? = null
    private var guestOwner: Guest? = null
    /** A route to select once the session already on it has ended. */
    private var selectAfterEnd: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val volumeListener = object : Cast.Listener() {
        override fun onVolumeChanged() {
            watched?.let { _volume.value = it.volume.toFloat().coerceIn(0f, 1f) }
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            if (guestClaim != null) { pending = session; return }
            disconnectEpoch++; pending = session; update { it.copy(connecting = true, error = null) }
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) =
            began(session, resumed = false)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            if (pending !== session) return
            pending = null
            guestClaim?.let { guest -> guestClaim = null; guest.onSessionEnded(); return }
            Log.w(TAG, "session start failed: $error")
            update { it.copy(connecting = false) }
            playback?.onConnectFailed()
        }

        override fun onSessionEnding(session: CastSession) {
            if (guestSession === session) return
            if (watched !== session) return
            playback?.onSessionEnding(session)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            if (guestSession === session) {
                val owner = guestOwner
                guestSession = null; guestOwner = null
                owner?.onSessionEnded()
                publishDevices()
                return
            }
            if (watched !== session) return
            stopWatching()
            update { it.copy(connecting = false, connectedName = null) }
            playback?.onSessionEnded(error)
            publishDevices()
            selectAfterEnd?.let { id -> selectAfterEnd = null; router?.routes?.firstOrNull { it.id == id }?.let { router?.selectRoute(it) } }
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            if (watched != null && watched !== session) return
            pending = session
            update { it.copy(connecting = true) }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) =
            began(session, resumed = true)

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            if (pending !== session) return
            pending = null
            update { it.copy(connecting = false) }
        }

        // A dropped connection the framework is still trying to win back.
        // Nothing to do: the receiver keeps playing its queue meanwhile.
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    /**
     * Brings the framework up, once. Safe to call from anywhere, any number of
     * times; where Google Play Services is absent it settles on unsupported and
     * the Cast row never appears.
     */
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
            Log.i(TAG, "Play Services unavailable; casting disabled")
            return
        }
        try {
            // The task form, so the framework's start-up work is not on the
            // main thread of whichever screen happened to ask first.
            CastContext.getSharedInstance(app, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx -> onReady(app, ctx) }
                .addOnFailureListener {
                    Log.w(TAG, "Cast framework unavailable", it)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Cast framework unavailable", e)
        }
    }

    private fun onReady(app: Context, ctx: CastContext) {
        castContext = ctx
        router = MediaRouter.getInstance(app)
        selector = ctx.mergedSelector
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        update { it.copy(supported = true) }
        // A session that outlived the process: the framework restores it on its
        // own and reports it through the listener, but one already live by the
        // time this runs would be reported to nobody.
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { began(it, resumed = true) }
    }

    /** Binds the service's playback half; it is handed any session already live. */
    fun attach(target: CastPlayback) {
        if (playback !== target) disconnectEpoch++
        playback = target
        castContext?.sessionManager?.currentCastSession
            ?.takeIf { it.isConnected }
            ?.let { target.onSessionBegan(it, resumed = true) }
    }

    fun detach(target: CastPlayback) {
        if (playback === target) { disconnectEpoch++; playback = null }
    }

    /**
     * Keeps [State.devices] current until the returned function is called.
     * [active] asks the router to scan rather than merely listen, which finds a
     * receiver in a couple of seconds instead of a minute — worth it only while
     * somebody is looking at the list.
     */
    fun discover(active: Boolean): () -> Unit {
        val router = router ?: return {}
        val selector = selector ?: return {}
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteSelected(
                router: MediaRouter,
                selected: MediaRouter.RouteInfo,
                reason: Int,
            ) = publishDevices()

            override fun onRouteUnselected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) = publishDevices()
        }
        val flags = MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
            if (active) MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN else 0
        router.addCallback(selector, callback, flags)
        publishDevices()
        return { router.removeCallback(callback) }
    }

    /**
     * Starts a session on [deviceId] for [guest] (Watch's video). Music already casting is
     * stopped first; the guest hears [Guest.onSessionEnded] if the session never starts.
     */
    fun connectGuest(deviceId: String, guest: Guest) {
        val router = router ?: return guest.onSessionEnded()
        val route = router.routes.firstOrNull { it.id == deviceId } ?: return guest.onSessionEnded()
        guestOwner?.takeIf { it !== guest }?.let { endGuest(it) }
        guestClaim = guest
        val live = castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected && it !== guestSession }
        if (live != null && watched === live) {
            selectAfterEnd = route.id
            disconnect(resumeHere = false)
        } else {
            router.selectRoute(route)
        }
    }

    /** Ends [guest]'s session (stopping what the receiver plays) or its pending claim. */
    fun endGuest(guest: Guest) {
        if (guestClaim === guest) { guestClaim = null; selectAfterEnd = null }
        if (guestOwner !== guest) return
        val session = guestSession
        guestSession = null; guestOwner = null
        if (session != null && castContext?.sessionManager?.currentCastSession === session) {
            castContext?.sessionManager?.endCurrentSession(true)
        }
    }

    fun connect(deviceId: String) {
        val router = router ?: return
        val route = router.routes.firstOrNull { it.id == deviceId } ?: return
        disconnectEpoch++
        update { it.copy(connecting = true) }
        router.selectRoute(route)
    }

    /**
     * Ends the session. [resumeHere] says the listener asked to bring the music
     * back to this phone, rather than the receiver having gone away — see
     * [CastPlayback.onSessionEnded].
     */
    fun disconnect(resumeHere: Boolean, afterStop: ((Boolean) -> Unit)? = null) {
        val ending = watched ?: return
        val owner = playback ?: return
        val token = ++disconnectEpoch
        scope.launch {
            val confirmed = owner.prepareDisconnect(ending)
            if (disconnectEpoch != token || watched !== ending || playback !== owner ||
                castContext?.sessionManager?.currentCastSession !== ending) return@launch
            owner.resumeHereOnEnd = resumeHere && confirmed
            update { it.copy(error = if (!confirmed) "The receiver did not confirm Stop. Playback returns here paused; the receiver may still be playing." else null) }
            castContext?.sessionManager?.endCurrentSession(confirmed)
            afterStop?.invoke(confirmed)
        }
    }

    fun setVolume(level: Float) {
        val value = level.coerceIn(0f, 1f)
        _volume.value = value
        try {
            castContext?.sessionManager?.currentCastSession?.volume = value.toDouble()
        } catch (e: Exception) {
            Log.w(TAG, "could not set receiver volume", e)
        }
    }

    private fun began(session: CastSession, resumed: Boolean) {
        if (pending != null && pending !== session) return
        if (castContext?.sessionManager?.currentCastSession !== session) return
        pending = null
        val guest = guestClaim
        if (guest != null && !resumed) {
            guestClaim = null
            guestSession = session; guestOwner = guest
            guest.onSessionBegan(session)
            publishDevices()
            return
        }
        // A video session left over from a closed Watch player is not the music's to take.
        if (resumed && guestSession === session) return
        disconnectEpoch++
        watch(session)
        update {
            it.copy(
                connecting = false,
                error = null,
                connectedName = session.castDevice?.friendlyName ?: it.connectedName ?: "Cast",
            )
        }
        publishDevices()
        playback?.onSessionBegan(session, resumed)
    }

    private fun watch(session: CastSession) {
        stopWatching()
        watched = session
        session.addCastListener(volumeListener)
        _volume.value = session.volume.toFloat().coerceIn(0f, 1f)
    }

    private fun stopWatching() {
        watched?.removeCastListener(volumeListener)
        watched = null
    }

    private fun publishDevices() {
        val router = router
        val selector = selector
        val devices = if (router == null || selector == null) {
            emptyList()
        } else {
            router.routes
                .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
                .map {
                    Device(
                        id = it.id,
                        name = it.name,
                        connecting = it.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                        connected = it.isSelected,
                        host = runCatching { com.google.android.gms.cast.CastDevice.getFromBundle(it.extras)?.inetAddress?.hostAddress }.getOrNull(),
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
        update { it.copy(devices = devices) }
    }

    private inline fun update(change: (State) -> State) {
        val previous = _state.value
        val next = change(previous)
        _state.value = next
        if (next.error != null && next.error != previous.error) appContext?.let {
            android.widget.Toast.makeText(it, next.error, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
