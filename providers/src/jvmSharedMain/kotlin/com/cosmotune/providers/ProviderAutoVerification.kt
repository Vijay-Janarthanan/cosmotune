package com.cosmotune.providers

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where a provider's verification stands, for the search page's "Search in" chips. */
enum class ProviderVerificationStatus {
    /** The provider's page is being prepared (a challenge is being requested from its operator). */
    OPENING,
    /** The verification window or browser is open. */
    OPEN,
    /** The session was established; the pending search for it is asked again. */
    VERIFIED,
    /** The listener closed the window, or automatic opening is off or already used: a tap retries. */
    NEEDS_USER,
    /** The page could not be prepared or the result was not accepted. */
    FAILED,
}

/** A verification window the platform host should show now. Opened once; [ProviderVerificationCoordinator.finish] ends it. */
data class ProviderVerificationRequest(val id: Long, val provider: String, val target: ProviderVerificationTarget, val automatic: Boolean)

/** What preparing a provider's verification page produced. */
sealed interface ProviderVerificationPreparation {
    /** Show [target]. [lease] (a loopback callback listener) is closed when the window ends. */
    class Ready(val target: ProviderVerificationTarget, val lease: AutoCloseable? = null) : ProviderVerificationPreparation
    /** The operator issued a session without a human check. */
    data object AlreadyVerified : ProviderVerificationPreparation
    /** Nothing can be opened automatically (the package has its own sign-in action); the listener goes to Sources. */
    data object Unavailable : ProviderVerificationPreparation
}

/** How a verification window ended, as its host saw it. */
enum class ProviderVerificationOutcome { VERIFIED, CLOSED, FAILED }

/**
 * Opens a provider's verification page by itself when a search (or a stream) against it reports that verification is required,
 * the way Lokal does, and re-runs the search when it succeeds.
 *
 * Rules, which `ProviderVerificationCoordinatorTest` pins:
 * - Automatic opening happens at most once per provider per app session, only while a host says the app is in the foreground,
 *   only while [autoOpen] is on, and never while another verification is open (a later one waits its turn, and is dropped if the
 *   listener closes the current window instead of completing it).
 * - Closing the window without completing marks the provider [ProviderVerificationStatus.NEEDS_USER]; it is not reopened
 *   automatically. [retry] (a tap on "Verify to search") opens it again on purpose.
 * - Completion is read from the provider's own protected state ([isVerified] after its auth record changed), so whichever way the
 *   window delivered the result — the Android Activity, the desktop helper's loopback, a system browser's callback — the window
 *   is closed (the [request] becomes null) and [verified] tells the search to ask that provider again.
 * - Nothing here relaxes the window's own checks: the target is the manager's [ProviderVerificationTarget], with its domain
 *   allow-list, state and expiry, and the grant is still exchanged by [ProviderManager.completeAuthentication].
 */
class ProviderVerificationCoordinator(
    private val scope: CoroutineScope,
    private val prepare: suspend (String) -> ProviderVerificationPreparation,
    private val isVerified: (String) -> Boolean = { false },
    private val authSnapshot: (String) -> Any? = { null },
    private val changes: () -> Flow<Any?> = { emptyFlow() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val _autoOpen = MutableStateFlow(true)
    /** The "Open verification automatically" setting. */
    val autoOpen: StateFlow<Boolean> = _autoOpen.asStateFlow()
    private val _request = MutableStateFlow<ProviderVerificationRequest?>(null)
    /** The window the host should have open; null closes it. */
    val request: StateFlow<ProviderVerificationRequest?> = _request.asStateFlow()
    private val _statuses = MutableStateFlow<Map<String, ProviderVerificationStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ProviderVerificationStatus>> = _statuses.asStateFlow()
    private val _verified = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Providers whose verification just completed, for the search to ask them again. */
    val verified: SharedFlow<String> = _verified.asSharedFlow()

    private val attempted = HashSet<String>()
    private val queue = LinkedHashSet<String>()
    private var preparing: String? = null
    private var lease: AutoCloseable? = null
    private var watcher: Job? = null
    private var nextId = 1L
    private val foreground = MutableStateFlow(false)
    private var onAutoOpenChanged: (Boolean) -> Unit = {}

    fun setAutoOpen(value: Boolean) {
        if (_autoOpen.value == value) return
        _autoOpen.value = value
        onAutoOpenChanged(value)
    }

    internal fun restoreAutoOpen(value: Boolean, persist: (Boolean) -> Unit) {
        _autoOpen.value = value
        onAutoOpenChanged = persist
    }

    /** Whether a host last said the app is in front. */
    val foregrounded: Boolean get() = foreground.value

    /** Told by the platform host whenever the app comes to or leaves the foreground. */
    fun setForeground(value: Boolean) {
        foreground.value = value
        if (value) advance()
    }

    /** A search or stream for [provider] needs verification: open it if the automatic rules allow. */
    fun accessRequired(provider: String) = request(provider, automatic = true)

    /** The listener asked for [provider]'s verification ("Verify to search"). */
    fun retry(provider: String) = request(provider, automatic = false)

    fun status(provider: String): ProviderVerificationStatus? = _statuses.value[provider]

    /** Whether a window for [provider] was opened (or queued) by this call. */
    fun request(provider: String, automatic: Boolean): Boolean {
        synchronized(lock) {
            if (provider == preparing || _request.value?.provider == provider) return true
            if (automatic && (!_autoOpen.value || provider in attempted)) {
                mark(provider, ProviderVerificationStatus.NEEDS_USER)
                return false
            }
            // In the background, or behind another verification: it waits its turn and opens when the app is in front again.
            if (preparing != null || _request.value != null || !foreground.value) {
                queue += provider
                if (automatic) attempted += provider
                mark(provider, ProviderVerificationStatus.OPENING)
                return true
            }
            attempted += provider
            preparing = provider
            mark(provider, ProviderVerificationStatus.OPENING)
        }
        scope.launch { open(provider, automatic) }
        return true
    }

    /** The host reports how request [id]'s window ended. Unknown or stale ids are ignored. */
    fun finish(id: Long, outcome: ProviderVerificationOutcome) {
        val ended = synchronized(lock) {
            val current = _request.value?.takeIf { it.id == id } ?: return
            _request.value = null
            lease?.let { runCatching { it.close() } }; lease = null
            watcher?.cancel(); watcher = null
            val result = if (outcome != ProviderVerificationOutcome.VERIFIED && isVerified(current.provider)) ProviderVerificationOutcome.VERIFIED else outcome
            when (result) {
                ProviderVerificationOutcome.VERIFIED -> mark(current.provider, ProviderVerificationStatus.VERIFIED)
                ProviderVerificationOutcome.FAILED -> mark(current.provider, ProviderVerificationStatus.FAILED)
                ProviderVerificationOutcome.CLOSED -> {
                    mark(current.provider, ProviderVerificationStatus.NEEDS_USER)
                    // Closing a window is a "not now": the ones waiting behind it are not opened one after another.
                    queue.forEach { mark(it, ProviderVerificationStatus.NEEDS_USER) }
                    queue.clear()
                }
            }
            current.provider to result
        }
        if (ended.second == ProviderVerificationOutcome.VERIFIED) _verified.tryEmit(ended.first)
        advance()
    }

    private suspend fun open(provider: String, automatic: Boolean) {
        val prepared = try { prepare(provider) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        var verifiedNow = false
        synchronized(lock) {
            preparing = null
            when {
                prepared == null -> mark(provider, ProviderVerificationStatus.FAILED)
                prepared is ProviderVerificationPreparation.AlreadyVerified -> { mark(provider, ProviderVerificationStatus.VERIFIED); verifiedNow = true }
                prepared is ProviderVerificationPreparation.Unavailable -> mark(provider, ProviderVerificationStatus.NEEDS_USER)
                prepared is ProviderVerificationPreparation.Ready && !foreground.value -> {
                    // The app went to the background while the operator was asked: wait for it to come back (below) rather
                    // than pop a window over another app. It stays the one being prepared meanwhile.
                    preparing = provider
                }
                prepared is ProviderVerificationPreparation.Ready -> {
                    val request = ProviderVerificationRequest(nextId++, provider, prepared.target, automatic)
                    lease = prepared.lease
                    val before = authSnapshot(provider)
                    mark(provider, ProviderVerificationStatus.OPEN)
                    _request.value = request
                    watcher = watch(request, before)
                    return
                }
            }
        }
        if (prepared is ProviderVerificationPreparation.Ready) {
            val back = withTimeoutOrNull((prepared.target.expiresAt - now() - 15_000).coerceAtLeast(0)) { foreground.first { it } }
            synchronized(lock) {
                preparing = null
                if (back != null && _request.value == null) {
                    val request = ProviderVerificationRequest(nextId++, provider, prepared.target, automatic)
                    lease = prepared.lease
                    val before = authSnapshot(provider)
                    mark(provider, ProviderVerificationStatus.OPEN)
                    _request.value = request
                    watcher = watch(request, before)
                    return
                }
                runCatching { prepared.lease?.close() }
                mark(provider, ProviderVerificationStatus.NEEDS_USER)
            }
        }
        if (verifiedNow) _verified.tryEmit(provider)
        advance()
    }

    /**
     * Waits for [provider]'s current verification to end: true once it was verified, false when it was closed, failed or could
     * not open, or after [timeoutMs]. Used to retry an operation exactly once after a successful grant.
     */
    suspend fun awaitVerification(provider: String, timeoutMs: Long = OVERALL_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) {
            statuses.first { map ->
                map[provider] == ProviderVerificationStatus.VERIFIED || map[provider] == ProviderVerificationStatus.FAILED ||
                    map[provider] == ProviderVerificationStatus.NEEDS_USER
            }[provider] == ProviderVerificationStatus.VERIFIED
        } ?: false

    /** Ends [request] when the provider's state (changed since [before]) shows it verified, or when its target expires. */
    private fun watch(request: ProviderVerificationRequest, before: Any?): Job = scope.launch {
        // [before] is read when the request is published, so a result saved before this coroutine runs still counts.
        launch {
            // The challenge's own lifetime, and never longer than three minutes overall.
            delay(minOf((request.target.expiresAt - now()).coerceAtLeast(0) + 1_000, OVERALL_TIMEOUT_MS))
            finish(request.id, ProviderVerificationOutcome.CLOSED)
        }
        // A host without a state stream relies on the window's own report (and the timer above).
        changes().firstOrNull { authSnapshot(request.provider) != before && isVerified(request.provider) } ?: return@launch
        finish(request.id, ProviderVerificationOutcome.VERIFIED)
    }

    private fun advance() {
        val next = synchronized(lock) {
            if (!foreground.value || preparing != null || _request.value != null || queue.isEmpty()) return
            queue.first().also { queue.remove(it); attempted += it; preparing = it; mark(it, ProviderVerificationStatus.OPENING) }
        }
        scope.launch { open(next, automatic = true) }
    }

    private fun mark(provider: String, status: ProviderVerificationStatus) = _statuses.update { it + (provider to status) }

    companion object {
        /** How long one verification may stay open before it is treated as closed. */
        const val OVERALL_TIMEOUT_MS = 180_000L
        /** When the host offers its help sheet (open again, copy the link, paste the callback). */
        const val HELP_AFTER_MS = 20_000L
    }
}

/** The app's [ProviderVerificationCoordinator], over [ProviderHub]'s manager. */
object ProviderAutoVerification {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val coordinator = ProviderVerificationCoordinator(
        scope = scope,
        prepare = { provider -> prepareVerification(provider) },
        isVerified = { provider -> manager()?.let { connected(it, provider) } == true },
        authSnapshot = { provider -> manager()?.store?.state?.value?.auth?.get(provider) },
        changes = { manager()?.store?.state ?: emptyFlow() },
    )

    val autoOpen: StateFlow<Boolean> get() = coordinator.autoOpen
    val request: StateFlow<ProviderVerificationRequest?> get() = coordinator.request
    val statuses: StateFlow<Map<String, ProviderVerificationStatus>> get() = coordinator.statuses
    val verified: SharedFlow<String> get() = coordinator.verified

    fun setAutoOpen(value: Boolean) = coordinator.setAutoOpen(value)
    fun setForeground(value: Boolean) = coordinator.setForeground(value)
    fun accessRequired(provider: String) { coordinator.accessRequired(provider) }
    fun retry(provider: String) { coordinator.retry(provider) }
    fun finish(id: Long, outcome: ProviderVerificationOutcome) = coordinator.finish(id, outcome)

    /** Reads (and from then on writes) the setting from a plain file beside the provider store; it holds no secret. */
    internal fun configure(file: File) {
        val saved = runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        coordinator.restoreAutoOpen(saved != "false") { value ->
            runCatching { file.parentFile?.mkdirs(); file.writeText(value.toString()) }
        }
    }

    private fun manager(): ProviderManager? = ProviderHub.state.value.manager

    private fun connected(manager: ProviderManager, provider: String): Boolean {
        val snapshot = manager.store.state.value
        val install = snapshot.installed.find { it.manifest.name == provider } ?: return false
        return providerAccessState(install, snapshot.auth[provider]) == ProviderAccessState.CONNECTED
    }

    private suspend fun prepareVerification(provider: String): ProviderVerificationPreparation {
        val manager = requireNotNull(manager())
        val install = requireNotNull(manager.store.state.value.installed.find { it.manifest.name == provider && it.enabled })
        fun target() = runCatching { manager.verificationTarget(provider) }.getOrNull()
        var target = target()
        if (target == null) {
            if (install.manifest.signedSession == null || !install.manifest.permissions.storage) return ProviderVerificationPreparation.Unavailable
            // Asks the operator for a session or a fresh challenge, exactly as the Sources button does.
            if (manager.verifyProviderAccess(provider)) return ProviderVerificationPreparation.AlreadyVerified
            target = target() ?: return ProviderVerificationPreparation.Unavailable
        }
        val pending = manager.store.state.value.auth[provider]?.get("pending") as? JsonObject
        // The loopback listener lets a system browser (no embedded window available) still return the result by itself.
        val lease = if (pending?.get("completed") != JsonPrimitive(true)) runCatching { manager.prepareBrowserAuthentication(provider) }.getOrNull() else null
        return ProviderVerificationPreparation.Ready(target, lease)
    }
}
