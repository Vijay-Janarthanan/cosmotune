package com.cosmotune.music.data

import com.cosmotune.settings.ProxySetting
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * How the app reaches the internet: through the listener's own proxy, or not at all while offline mode is on.
 *
 * Read on every request, so a change takes effect at once. [Http.client] follows it, and each app installs [selector] as
 * the process's default [ProxySelector] at start so the other HTTP clients built after that follow the proxy too. Local
 * addresses (this machine, the home network) always go direct and stay reachable offline: a Subsonic server, a DLNA
 * speaker or the phone remote are not "the internet".
 *
 * The proxy handling follows Mochy Music's `AppProxy` (GPL-3.0): an unresolved address so the proxy's own name is looked up
 * when connecting, a Basic login for HTTP proxies, and the JVM-wide authenticator for a SOCKS login.
 */
object NetworkPolicy {

    data class ProxyConfig(val type: Proxy.Type, val address: InetSocketAddress, val username: String, val password: String) {
        val hasLogin: Boolean get() = username.isNotEmpty() || password.isNotEmpty()
        val label: String get() = "${type.name.lowercase()}://${addressLabel(address)}"
    }

    class OfflineException(host: String) : IOException("Offline mode is on; $host was not contacted")

    @Volatile var offline: Boolean = false

    @Volatile var proxy: ProxyConfig? = null
        private set

    /**
     * Something else owns routing (the desktop's Tor mode): the listener's proxy is kept but not used, so traffic never
     * leaves the Tor tunnel for a proxy of its own.
     */
    @Volatile var lockedBy: String? = null

    @Volatile private var fallback: ProxySelector? = null

    /** Keeps what the platform had as its default, then puts [selector] in its place. Safe to call more than once. */
    fun installAsDefault() {
        val current = ProxySelector.getDefault()
        if (current !== selector) fallback = current
        ProxySelector.setDefault(selector)
    }

    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host.orEmpty()
            val chosen = proxy?.takeIf { lockedBy == null && !isLocalHost(host) }
            if (chosen != null) return listOf(Proxy(chosen.type, chosen.address))
            // With no proxy of ours, whatever the platform routes through still counts (the system proxy, Tor).
            val base = fallback ?: ProxySelector.getDefault()?.takeIf { it !== this }
            return base?.let { runCatching { it.select(uri) }.getOrNull() } ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, address: SocketAddress?, error: IOException?) = Unit
    }

    /** Answers an HTTP proxy's 407 with the listener's login, once. */
    val proxyAuthenticator: Authenticator = Authenticator { _, response ->
        val config = proxy
        if (config == null || config.type != Proxy.Type.HTTP || !config.hasLogin ||
            response.request.header(PROXY_AUTH) != null
        ) {
            null
        } else {
            response.request.newBuilder().header(PROXY_AUTH, Credentials.basic(config.username, config.password)).build()
        }
    }

    /** Refuses every request to a host that is not local while [offline] is on. */
    val offlineInterceptor: Interceptor = Interceptor { chain ->
        val host = chain.request().url.host
        if (offline && !isLocalHost(host)) throw OfflineException(host)
        chain.proceed(chain.request())
    }

    /** Throws [OfflineException] for a remote [url] while offline; for clients that do not go through [Http.client]. */
    fun checkAllowed(url: String) {
        if (!offline) return
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        if (!isLocalHost(host)) throw OfflineException(host)
    }

    fun allows(url: String): Boolean = runCatching { checkAllowed(url) }.isSuccess

    /** Uses [config], or goes direct when it is null. Idle connections that went the old way are dropped. */
    fun apply(config: ProxyConfig?) {
        if (config == proxy) return
        useSocksLogin(config)
        proxy = config
        runCatching { Http.client.connectionPool.evictAll() }
    }

    /** What a [ProxySetting] means, or null while its address is not `host:port`. */
    fun configOf(setting: ProxySetting): ProxyConfig? {
        val address = parseAddress(setting.address) ?: return null
        val type = if (setting.type == ProxySetting.Type.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return ProxyConfig(type, address, setting.username, setting.password)
    }

    /** Reads "1.2.3.4:8080", "proxy.example.com:1080" or "[2001:db8::1]:3128", skipping a pasted scheme or login. */
    fun parseAddress(text: String): InetSocketAddress? {
        val hostPort = text.trim().substringAfter("://").substringAfterLast('@').trimEnd('/')
        val port = hostPort.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']') else hostPort.substringBeforeLast(':')
        if (host.isBlank() || ' ' in host) return null
        return InetSocketAddress.createUnresolved(host, port)
    }

    fun addressLabel(address: InetSocketAddress): String =
        if (':' in address.hostString) "[${address.hostString}]:${address.port}" else "${address.hostString}:${address.port}"

    /**
     * This machine or the local network, judged from the name alone (nothing is looked up): loopback, private and link-local
     * addresses, a bare intranet name, and the `.local`, `.lan`, `.home.arpa` and `.internal` suffixes.
     */
    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase()
        if (host.isEmpty()) return true
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
        if (':' in host) {
            return host == "::1" || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") ||
                host.startsWith("feb") || host.startsWith("fc") || host.startsWith("fd")
        }
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = octets[0].toInt() to octets[1].toInt()
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        return '.' !in host
    }

    private fun useSocksLogin(config: ProxyConfig?) {
        val login = config?.takeIf { it.type == Proxy.Type.SOCKS && it.hasLogin }
        if (login == null && proxy?.let { it.type == Proxy.Type.SOCKS && it.hasLogin } != true) return
        java.net.Authenticator.setDefault(
            login?.let {
                object : java.net.Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(it.username, it.password.toCharArray())
                }
            },
        )
    }

    private const val PROXY_AUTH = "Proxy-Authorization"
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")
}
