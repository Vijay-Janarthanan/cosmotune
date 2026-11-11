package com.cosmotune.watch

/** Pause/exit checkpoints flush sooner than the five-minute background progress cadence. No account is created here. */
object WatchSyncSignals {
    private val mutable = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changes: kotlinx.coroutines.flow.SharedFlow<Unit> = mutable
    fun checkpoint() { mutable.tryEmit(Unit) }
}
