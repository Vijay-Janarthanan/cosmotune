package com.cosmotune.video.source

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The listener's own proxy for Video (Settings, Content, Proxy): `http://host:port` or `socks5://host:port` (Tor's is
 * `socks5://127.0.0.1:9050`, Orbot's on a phone `socks5://127.0.0.1:9050` too). Every request Video makes (the source, the streams,
 * downloads, the feed, SponsorBlock and the other services) goes through it; blank is a direct connection.
 */
object VideoProxy {
    @Volatile private var current: Proxy = Proxy.NO_PROXY

    /** The proxy in [text], or null when it is not one (blank is [Proxy.NO_PROXY]). */
    fun parse(text: String?): Proxy? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return Proxy.NO_PROXY
        val uri = runCatching { URI(if ("://" in t) t else "http://$t") }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val type = when (uri.scheme?.lowercase()) {
            "http", "https" -> Proxy.Type.HTTP
            "socks", "socks5", "socks5h", "socks4" -> Proxy.Type.SOCKS
            else -> return null
        }
        val port = uri.port.takeIf { it in 1..65535 } ?: if (type == Proxy.Type.SOCKS) 1080 else 8080
        return Proxy(type, InetSocketAddress.createUnresolved(host, port))
    }

    /** Uses the proxy in [text] from now on (an unreadable one is a direct connection). */
    fun set(text: String?) {
        current = parse(text) ?: Proxy.NO_PROXY
    }

    /** What every Video client asks for each request: the proxy set now. */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(current)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    // The shared segment services (SponsorBlock, DeArrow, Return YouTube Dislike) go through the same proxy.
    init { com.cosmotune.media.segments.WebServicesHttp.proxy = selector }

    /** The proxy for mpv's own requests (its `http-proxy` takes only an HTTP proxy), or "" for none. */
    fun mpvHttpProxy(): String {
        val p = current
        val address = p.address() as? InetSocketAddress ?: return ""
        return if (p.type() == Proxy.Type.HTTP) "http://${address.hostString}:${address.port}" else ""
    }
}
