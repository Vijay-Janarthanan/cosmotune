package com.cosmotune.providers

import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.*
import kotlinx.serialization.json.*

/** Long file work runs outside the VM thread; guest progress callbacks still run inside the VM. */
internal class ProviderHostWork(private val cancelHost: () -> Unit) : AutoCloseable {
    private class Work(val id: String, val callback: Long?) {
        val completed = CompletableFuture<JsonElement>()
        val events = ArrayDeque<JsonObject>()
        var task: FutureTask<Unit>? = null
    }
    private val lock = Any()
    private var current: Work? = null
    private var closed = false

    fun start(callback: Long?, operation: () -> JsonElement): JsonObject = synchronized(lock) {
        check(!closed && current == null) { "A provider file operation is already running" }
        require(callback == null || callback in 1..1_000_000)
        val work = Work(UUID.randomUUID().toString(), callback)
        val task = FutureTask<Unit> {
            try { work.completed.complete(operation()) }
            catch (error: Throwable) { work.completed.completeExceptionally(error) }
            Unit
        }
        work.task = task; current = work
        try { workers.execute(task) } catch (error: Exception) { current = null; throw error }
        buildJsonObject { put("pending", work.id) }
    }

    val isRunning: Boolean get() = synchronized(lock) { current != null }

    fun report(bytes: Long, total: Long?, completedSegments: Int? = null, totalSegments: Int? = null) = synchronized(lock) {
        val work = current ?: return@synchronized
        val callback = work.callback ?: return@synchronized
        if (work.completed.isDone) return@synchronized
        require(bytes in 0..512L * 1024 * 1024 && (total == null || total in bytes..512L * 1024 * 1024))
        while (work.events.size >= 16) work.events.removeFirst()
        work.events.addLast(buildJsonObject {
            put("callback", callback); putJsonArray("args") {
                add(bytes); add(total?.let(::JsonPrimitive) ?: JsonNull)
                if (completedSegments != null && totalSegments != null) {
                    require(completedSegments in 0..totalSegments && totalSegments in 1..4_096)
                    add(completedSegments); add(totalSegments)
                }
            }
        })
    }

    fun poll(id: String): JsonObject {
        val work = synchronized(lock) { requireNotNull(current?.takeIf { it.id == id }) { "Unknown provider file operation" } }
        if (!work.completed.isDone && synchronized(lock) { work.events.isEmpty() }) {
            try { work.completed.get(25, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            catch (_: ExecutionException) { /* Rethrown below, after releasing job ownership. */ }
        }
        return synchronized(lock) {
            check(current === work) { "Provider file operation changed" }
            val events = JsonArray(work.events.toList()); work.events.clear()
            if (!work.completed.isDone) buildJsonObject { put("pending", work.id); put("events", events) }
            else {
                current = null
                val value = try { work.completed.get() }
                    catch (error: ExecutionException) { throw (error.cause as? Exception ?: IllegalStateException("Provider file operation failed")) }
                buildJsonObject { put("value", value); put("events", events) }
            }
        }
    }

    fun cancel(id: String) {
        val work = synchronized(lock) {
            current?.takeIf { it.id == id }?.also { current = null }
        } ?: return
        cancelHost()
        work.completed.completeExceptionally(CancellationException("Provider file operation cancelled"))
        work.task?.cancel(true)
    }
    override fun close() {
        val work = synchronized(lock) { closed = true; current.also { current = null } }
        if (work != null) {
            cancelHost()
            work.completed.completeExceptionally(CancellationException("Provider file operation cancelled"))
            work.task?.cancel(true)
        }
    }
    companion object {
        private val workers = ThreadPoolExecutor(2, 2, 45, TimeUnit.SECONDS, ArrayBlockingQueue(4),
            { task -> Thread(task, "Provider file work").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()).apply {
            allowCoreThreadTimeOut(true)
        }
    }
}
