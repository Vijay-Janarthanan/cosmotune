package com.cosmotune.ui.chrome

import com.cosmotune.design.AppMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Settings/account screens belong to Music; a mode menu hands them off without losing its return destination. */
object ModeNavigationRequest {
    enum class Destination { SETTINGS, SOURCES, ACCOUNT }
    data class Request(val destination: Destination, val returnMode: AppMode, val sequence: Long)

    private val sequence = AtomicLong()
    private val mutablePending = MutableStateFlow<Request?>(null)
    val pending = mutablePending.asStateFlow()

    fun open(destination: Destination, returnMode: AppMode) {
        require(returnMode == AppMode.MANGA || returnMode == AppMode.READ || returnMode == AppMode.PODIO)
        mutablePending.value = Request(destination, returnMode, sequence.incrementAndGet())
    }

    /** A stale composition cannot consume a newer tap. */
    fun handled(request: Request) { mutablePending.compareAndSet(request, null) }
}
