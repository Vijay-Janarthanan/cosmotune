package com.cosmotune.providers

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ProviderScriptSessionTest {
    private val config = buildJsonObject {
        putJsonObject("permissions") { putJsonArray("network") { add("example.com") }; put("storage", false); put("file", false) }
        putJsonObject("settings") { put("region", "IN") }
    }
    private val host: (String, String) -> String = { method, _ ->
        require(method == "http.request")
        """{"value":{"status":200,"ok":true,"body":"{\"title\":\"Fixture\"}","headers":{}}}"""
    }

    @Test fun registeredAsyncMethodsUseSettingsAndTheStringOnlyHostBridge() = runBlocking<Unit> {
        val code = """
            registerExtension({
              search: async function(query) { var r = fetch('https://example.com/'); return {query:query, region:settings.region, title:r.json().title}; },
              hostAccess: function() { try { Java.type('java.lang.Runtime'); return false; } catch (error) { return true; } }
            });
        """.trimIndent()
        createProviderScriptSession(code, config, host) {}.use { runtime ->
            assertEquals(setOf("search", "hostAccess"), runtime.methods)
            val result = Json.parseToJsonElement(runtime.invoke("search", buildJsonArray { add("Tamil songs") })).jsonObject
            assertEquals("IN", result.getValue("region").jsonPrimitive.content)
            assertEquals("Fixture", result.getValue("title").jsonPrimitive.content)
            assertEquals("Tamil songs", result.getValue("query").jsonPrimitive.content)
            assertEquals("true", runtime.invoke("hostAccess", JsonArray(emptyList())))
        }
    }

    @Test fun aDeadlineClosesAnUnboundedGuestAndCannotLeaveItAvailableForAnotherCall() = runBlocking<Unit> {
        val runtime = createProviderScriptSession("registerExtension({loop:function(){while(true){}}});", config, host) {}
        try {
            assertFails { runtime.invoke("loop", JsonArray(emptyList()), 100) }
            assertFailsWith<IllegalStateException> { runtime.invoke("loop", JsonArray(emptyList()), 100) }
        } finally { runtime.close() }
    }

    @Test fun concurrentCallsDoNotOverwriteAnotherCallsResult() = runBlocking<Unit> {
        createProviderScriptSession("registerExtension({echo:async function(value){return value;}});", config, host) {}.use { runtime ->
            val replies = coroutineScope { (1..20).map { value -> async { runtime.invoke("echo", buildJsonArray { add(value) }) } }.awaitAll() }
            assertEquals((1..20).map { it.toString() }, replies)
        }
    }

    @Test fun cancellingARequestReleasesTheBlockingHostBeforeTheRuntimeCanBeReused() = runBlocking<Unit> {
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val runtime = createProviderScriptSession("registerExtension({wait:function(){return http.get('https://example.com/');}});", config,
            bridge = { _, _ ->
                entered.countDown()
                check(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
                """{"value":{"status":200,"body":"done"}}"""
            }, cancelHost = { released.countDown() })
        try {
            val request = async(Dispatchers.Default) { runtime.invoke("wait", JsonArray(emptyList())) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(2_000) { request.cancelAndJoin() }
            assertEquals(0, released.count)
            assertFailsWith<IllegalStateException> { runtime.invoke("wait", JsonArray(emptyList())) }
        } finally { released.countDown(); runtime.close() }
    }

    @Test fun cancellingTopLevelInitializationAlsoReleasesItsHost() = runBlocking<Unit> {
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val task = async(Dispatchers.Default) {
            createProviderScriptSession("http.get('https://example.com/'); registerExtension({echo:function(){return true;}});", config,
                bridge = { _, _ ->
                    entered.countDown()
                    check(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    """{"value":{"status":200,"body":"done"}}"""
                }, cancelHost = { released.countDown() }).close()
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertEquals(0, released.count)
        } finally { released.countDown(); task.cancelAndJoin() }
    }
}
