package com.cosmotune.music.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS over HTTPS (the idea from RiMusic and Kreate, see NOTICE.md): names are looked up through a public resolver over HTTPS
 * instead of the network's own DNS, so a network that blocks or rewrites lookups cannot. Each resolver's JSON API is asked for
 * A and AAAA records; the resolver's own name is reached at its fixed, published addresses, so no plain lookup is needed to get
 * there. Answers are kept for their time to live (at least a minute). A local name, an IP address or a failing resolver falls
 * back to the system's DNS, so the app keeps working.
 */
enum class PrivateDns(val label: String, val host: String, val path: String, val addresses: List<String>, val port: Int = 443) {
    OFF("Off", "", "", emptyList()),
    CLOUDFLARE("Cloudflare", "cloudflare-dns.com", "/dns-query", listOf("1.1.1.1", "1.0.0.1")),
    GOOGLE("Google", "dns.google", "/resolve", listOf("8.8.8.8", "8.8.4.4")),
    ADGUARD("AdGuard", "dns.adguard-dns.com", "/resolve", listOf("94.140.14.14", "94.140.15.15")),
    ;

    companion object {
        fun named(name: String): PrivateDns = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** The [Dns] [Http.client] uses: the system's while [PrivateDns.OFF], else the chosen resolver over HTTPS. */
object PrivateDnsResolver : Dns {
    @Volatile var choice: PrivateDns = PrivateDns.OFF
        set(value) { if (field != value) { field = value; cache.clear() } }

    private class Kept(val addresses: List<InetAddress>, val until: Long)
    private val cache = ConcurrentHashMap<String, Kept>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Swapped by tests: asks [resolver] for [name]'s records of [type] and answers the JSON body. */
    internal var ask: (PrivateDns, String, String) -> String? = ::query
    internal var clock: () -> Long = System::currentTimeMillis

    override fun lookup(hostname: String): List<InetAddress> {
        val resolver = choice
        if (resolver == PrivateDns.OFF || !worthResolving(hostname)) return Dns.SYSTEM.lookup(hostname)
        val key = "${resolver.name}:${hostname.lowercase()}"
        cache[key]?.takeIf { it.until > clock() }?.let { return it.addresses }
        val found = runCatching { resolve(resolver, hostname) }.getOrNull()
        if (found == null || found.first.isEmpty()) return Dns.SYSTEM.lookup(hostname)
        cache[key] = Kept(found.first, clock() + found.second.coerceAtLeast(60) * 1000L)
        return found.first
    }

    /** A real internet name: not an address, not this machine or the home network's own names. */
    internal fun worthResolving(hostname: String): Boolean {
        val name = hostname.trim().lowercase().trimEnd('.')
        if (name.isEmpty() || '.' !in name || ':' in name) return false
        if (Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(name)) return false
        return listOf(".local", ".lan", ".home", ".internal", ".localhost", ".home.arpa").none(name::endsWith)
    }

    /** IPv4 then IPv6 addresses for [hostname], with the shortest time to live among them. */
    private fun resolve(resolver: PrivateDns, hostname: String): Pair<List<InetAddress>, Int> {
        val v4 = ask(resolver, hostname, "A")?.let { parse(it, hostname, 1) }
        val v6 = ask(resolver, hostname, "AAAA")?.let { parse(it, hostname, 28) }
        val all = (v4?.first.orEmpty() + v6?.first.orEmpty())
        val ttl = listOfNotNull(v4?.second, v6?.second).minOrNull() ?: 60
        return all to ttl
    }

    /** The addresses in a DNS JSON answer for records of [type] (1 for A, 28 for AAAA), and their shortest time to live. */
    internal fun parse(body: String, hostname: String, type: Int): Pair<List<InetAddress>, Int>? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        if ((root["Status"]?.jsonPrimitive?.intOrNull ?: 0) != 0) return null
        val answers = (root["Answer"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.intOrNull == type }
        val addresses = answers.mapNotNull { answer ->
            val data = answer["data"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val literal = if (type == 1) Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(data) else ':' in data && data.all { it.isLetterOrDigit() || it == ':' || it == '.' }
            if (!literal) return@mapNotNull null
            runCatching { InetAddress.getByAddress(hostname, InetAddress.getByName(data).address) }.getOrNull()
        }
        val ttl = answers.mapNotNull { it["TTL"]?.jsonPrimitive?.intOrNull }.minOrNull() ?: 60
        return addresses to ttl
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    /** A client of its own that reaches each resolver at its published addresses (and never through itself). */
    private val bootstrap: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .dns { host ->
                PrivateDns.entries.firstOrNull { it.host == host }?.addresses?.map { InetAddress.getByAddress(host, InetAddress.getByName(it).address) }
                    ?: throw UnknownHostException(host)
            }
            .proxySelector(NetworkPolicy.selector)
            .addInterceptor(NetworkPolicy.offlineInterceptor)
            .build()
    }

    private fun query(resolver: PrivateDns, name: String, type: String): String? {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host(resolver.host).port(resolver.port).encodedPath(resolver.path)
            .addQueryParameter("name", name).addQueryParameter("type", type).build()
        val request = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        bootstrap.newCall(request).execute().use { response -> return if (response.isSuccessful) response.body?.string() else null }
    }

    internal fun clearForTests() { cache.clear() }
}
