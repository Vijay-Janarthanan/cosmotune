package com.cosmotune.providers

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.proxy.ProxyExecutable

private val providerDeadlines = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "Provider deadline").apply { isDaemon = true }
}

internal actual suspend fun createProviderScriptSession(code: String, config: JsonObject,
    bridge: (String, String) -> String, cancelHost: () -> Unit): ProviderScriptSession = providerRuntimeWork(cancelHost) {
    val context = Context.newBuilder("js").allowHostAccess(HostAccess.NONE).allowHostClassLookup { false }
        .allowCreateThread(false).allowNativeAccess(false)
        .resourceLimits(ResourceLimits.newBuilder().statementLimit(10_000_000, null).build())
        .option("engine.WarnInterpreterOnly", "false").build()
    val deadline = providerDeadlines.schedule({ cancelHost(); context.close(true) }, 30_000, TimeUnit.MILLISECONDS)
    try {
        context.getBindings("js").putMember("__cosmotuneBridge", ProxyExecutable { args ->
            require(args.size == 2)
            bridge(args[0].asString(), args[1].asString())
        })
        context.eval("js", "var __cosmotuneHost = { call: function(method, args) { return __cosmotuneBridge(method, args); } };")
        context.eval("js", ProviderScripts.start(code, config))
        val supported = Json.parseToJsonElement(context.eval("js", "__cosmotuneApi.methods()").asString())
            .jsonArray.map { it.jsonPrimitive.content }.toSet()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        object : ProviderScriptSession {
            private val lock = Mutex()
            @Volatile private var closed = false
            override val methods = supported
            override suspend fun invoke(method: String, args: JsonArray, timeoutMs: Long): String = lock.withLock {
                providerRuntimeWork(cancel = { cancelHost(); context.close(true) }) {
                    check(!closed) { "Provider runtime is closed" }
                    context.resetLimits()
                    val pending = providerDeadlines.schedule({ cancelHost(); context.close(true) }, timeoutMs.coerceIn(1, 30_000), TimeUnit.MILLISECONDS)
                    try {
                        context.eval("js", ProviderScripts.invoke(method, args))
                        val error = context.eval("js", "__cosmotuneFailure || null")
                        check(error.isNull) { error.asString() }
                        val result = context.eval("js", "__cosmotuneResult")
                        check(!result.isNull && !result.toString().equals("undefined")) { "Provider did not complete its result" }
                        ProviderScripts.validateResult(result.asString())
                    } catch (error: Throwable) { close(); throw error }
                    finally { pending.cancel(false) }
                }
            }
            override fun close() { if (!closed) { closed = true; cancelHost(); context.close(true) } }
        }
    } catch (error: Throwable) { cancelHost(); context.close(true); throw error }
    finally { deadline.cancel(false) }
}
