package com.cosmotune.music.desktop

import com.cosmotune.music.data.NetworkPolicy
import com.cosmotune.settings.Extras
import com.cosmotune.settings.KeyValueStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Proxy
import java.util.prefs.Preferences

/** The shared [com.cosmotune.settings.ExtrasSettings] on the desktop, under their own preferences node. */
internal object DesktopExtras {

    /** The album (and its artist) whose Wikipedia summary a song menu asked for. */
    val aboutAlbum = MutableStateFlow<Pair<String, String?>?>(null)

    private class Store(private val node: Preferences) : KeyValueStore {
        override fun getBoolean(key: String, default: Boolean) = node.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) = node.putBoolean(key, value).also { runCatching { node.flush() } }
        override fun getInt(key: String, default: Int) = node.getInt(key, default)
        override fun putInt(key: String, value: Int) = node.putInt(key, value).also { runCatching { node.flush() } }
        override fun getString(key: String, default: String) = node.get(key, default) ?: default
        override fun putString(key: String, value: String) = node.put(key, value).also { runCatching { node.flush() } }
    }

    /** After Tor has chosen the routing and before any client is built: Tor, when on, keeps the traffic. */
    fun install() {
        if (com.cosmotune.desktop.TorConnection.active) NetworkPolicy.lockedBy = "Tor"
        NetworkPolicy.installAsDefault()
        Extras.install(Store(Preferences.userRoot().node("com.cosmotune.desktop").node("extras")))
    }

    /**
     * The proxy the desktop's own Ktor and FFmpeg clients should use, as a URL: Tor's while Tor is on, otherwise the
     * listener's HTTP proxy. Those clients take only HTTP proxies; a SOCKS proxy reaches them through the JVM default only.
     */
    val httpProxyUrl: String?
        get() = com.cosmotune.desktop.TorConnection.proxyUrl
            ?: NetworkPolicy.proxy?.takeIf { it.type == Proxy.Type.HTTP && NetworkPolicy.lockedBy == null }?.let { proxy ->
                val login = if (proxy.hasLogin) {
                    "${java.net.URLEncoder.encode(proxy.username, "UTF-8")}:${java.net.URLEncoder.encode(proxy.password, "UTF-8")}@"
                } else {
                    ""
                }
                "http://$login${NetworkPolicy.addressLabel(proxy.address)}"
            }
}

/** The shared Extras dialog with the rows only the desktop has. */
@androidx.compose.runtime.Composable
internal fun DesktopExtrasDialog(onDismiss: () -> Unit) {
    com.cosmotune.ui.extras.ExtrasSettingsDialog(onDismiss = onDismiss)
}

/** Draws whichever Extras dialog a menu asked for; placed once at the top of the window. */
@androidx.compose.runtime.Composable
internal fun DesktopExtrasHost() {
    val about by DesktopExtras.aboutAlbum.collectAsState()
    about?.let { (album, artist) ->
        com.cosmotune.ui.extras.AlbumAboutDialog(album, artist, onDismiss = { DesktopExtras.aboutAlbum.value = null })
    }
}
