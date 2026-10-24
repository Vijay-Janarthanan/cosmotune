package com.cosmotune.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable data class StoredProviderCookie(val origin: String, val value: String)

/** Per-package jar. Session cookies survive VM retirement, persistent cookies survive app restart. */
class ProviderCookieJar internal constructor(initial: List<Cookie> = emptyList(), private val persist: (List<Cookie>) -> Unit = {}) {
    data class RequestCookies(val generation: Long, val header: String?)
    private val lock = Any()
    private var generation = 0L
    private val cookies = initial.filter { it.expiresAt > System.currentTimeMillis() && it.toString().length <= 8_192 }.toMutableList().apply {
        while (size > 512 || sumOf { it.toString().length } > 256 * 1024) removeAt(0)
    }
    fun request(url: HttpUrl): RequestCookies = synchronized(lock) {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        RequestCookies(generation, cookies.filter { it.matches(url) }.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" })
    }
    fun receive(received: List<Cookie>, requestGeneration: Long) = synchronized(lock) {
        if (generation != requestGeneration || received.isEmpty()) return@synchronized
        val now = System.currentTimeMillis()
        val next = cookies.filterNot { previous -> previous.expiresAt <= now || received.any { it.name == previous.name && it.domain == previous.domain && it.path == previous.path } }.toMutableList()
        next.addAll(received.filter { it.expiresAt > now && it.toString().length <= 8_192 })
        while (next.size > 512 || next.sumOf { it.toString().length } > 256 * 1024) next.removeAt(0)
        persist(next.filter { it.persistent })
        cookies.clear(); cookies.addAll(next)
    }
    fun clear() = synchronized(lock) {
        persist(emptyList())
        generation++
        cookies.clear()
    }
    internal companion object {
        fun forProvider(install: ProviderInstall, store: ProviderStore): ProviderCookieJar {
            if (!install.manifest.permissions.storage) return ProviderCookieJar()
            val name = install.manifest.name
            return store.cookieJars.computeIfAbsent(name) {
                val initial = store.state.value.cookies[name].orEmpty().take(512).mapNotNull { saved ->
                    if (saved.value.length > 8_192) null else saved.origin.toHttpUrlOrNull()?.let { Cookie.parse(it, saved.value) }
                }.filter { it.persistent && it.expiresAt > System.currentTimeMillis() }
                ProviderCookieJar(initial) { cookies ->
                    val saved = cookies.map { cookie ->
                        val origin = HttpUrl.Builder().scheme(if (cookie.secure) "https" else "http").host(cookie.domain).build()
                        StoredProviderCookie(origin.toString(), cookie.toString())
                    }
                    runBlocking { store.update { old -> if (saved == old.cookies[name].orEmpty()) old else old.copy(cookies = old.cookies + (name to saved)) } }
                }
            }
        }
        fun clear(store: ProviderStore, name: String) {
            store.cookieJars[name]?.clear()
            runBlocking { store.update { it.copy(cookies = it.cookies - name) } }
        }
    }
}
