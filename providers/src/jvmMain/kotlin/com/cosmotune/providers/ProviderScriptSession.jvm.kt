package com.cosmotune.providers

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

private val providerDeadlines = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "Provider deadline").apply { isDaemon = true }
}

/**
 * The same QuickJS interpreter the phone runs providers in. Closing it from another thread interrupts a running script and waits for it
 * to stop, which is what the deadlines below rely on: a host call blocked on the network is released first, then the VM is closed.
 */
internal actual suspend fun createProviderScriptSession(code: String, config: JsonObject,
    bridge: (String, String) -> String, cancelHost: () -> Unit): ProviderScriptSession = providerRuntimeWork(cancelHost) {
    val engine = QuickJs.create(Dispatchers.IO)
    engine.memoryLimit = 64L * 1024 * 1024
    engine.maxStackSize = 512 * 1024L
    val deadline = providerDeadlines.schedule({ cancelHost(); engine.close() }, 30_000, TimeUnit.MILLISECONDS)
    try {
        engine.define("__cosmotuneHost") {
            function("call", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(args.size == 2)
                    return bridge(args[0]?.toString().orEmpty(), args[1]?.toString().orEmpty())
                }
            })
        }
        engine.evaluate<String>(ProviderScripts.start(code, config))
        val supported = Json.parseToJsonElement(engine.evaluate<String>("__cosmotuneApi.methods()"))
            .jsonArray.map { it.jsonPrimitive.content }.toSet()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        object : ProviderScriptSession {
            private val lock = Mutex()
            @Volatile private var closed = false
            override val methods = supported
            override suspend fun invoke(method: String, args: JsonArray, timeoutMs: Long): String = lock.withLock {
                // Closed before the host call is released: the script can then run to its end, and the worker would report nothing.
                providerRuntimeWork(cancel = { closed = true; cancelHost(); engine.close() }) {
                    check(!closed) { "Provider runtime is closed" }
                    val pending = providerDeadlines.schedule({ cancelHost(); engine.close() },
                        timeoutMs.coerceIn(1, if (method == "download") 120_000 else 30_000), TimeUnit.MILLISECONDS)
                    try {
                        engine.evaluate<String>(ProviderScripts.invoke(method, args))
                        val failure = engine.evaluate<String?>("__cosmotuneFailure || null")
                        check(failure == null) { failure.orEmpty() }
                        val result = engine.evaluate<String?>("__cosmotuneResult === undefined ? null : __cosmotuneResult")
                            ?: error("Provider did not complete its result")
                        ProviderScripts.validateResult(result)
                    } catch (error: Throwable) { close(); throw error }
                    finally { pending.cancel(false) }
                }
            }
            override fun close() { if (!closed) { closed = true; cancelHost(); engine.close() } }
        }
    } catch (error: Throwable) { cancelHost(); engine.close(); throw error }
    finally { deadline.cancel(false) }
}
