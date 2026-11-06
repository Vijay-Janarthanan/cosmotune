package com.cosmotune.music.desktop

import com.cosmotune.music.data.NetworkPolicy
import com.cosmotune.settings.Extras
import com.cosmotune.settings.KeyValueStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Proxy
import java.util.prefs.Preferences

/** The shared [com.cosmotune.settings.ExtrasSettings] on the desktop, under their own preferences node. */
internal object DesktopExtras {

    /** The album (and its artist) whose Wikipedia summary a song menu asked for. */
    val aboutAlbum = MutableStateFlow<Pair<String, String?>?>(null)

    /** The song a song menu asked to play on a DLNA renderer. */
    val castSong = MutableStateFlow<com.cosmotune.music.data.model.Song?>(null)

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
        com.cosmotune.lyrics.autosync.LyricsAutoOffset.configure(DesktopMediaCache.directory.resolve("lyrics").toFile())
        com.cosmotune.music.data.recognition.SongRecognition.microphone = DesktopMicrophone
    }

    /**
     * Tells lyrics auto offset how to read [videoId]'s audio: a second decode of [stream], asked for as stereo at the
     * analyser's own 16 kHz so FFmpeg does the resampling. Only for PCM streams; DoP and the like are left alone.
     */
    fun offerAudioForLyricsOffset(videoId: String, stream: DesktopStream) {
        if (videoId.isBlank() || stream.isDolbyAtmos) return
        com.cosmotune.lyrics.autosync.LyricsAutoOffset.audioReady(videoId) { feed ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val decoder = DesktopAudioDecoder()
                val rate = 16_000
                val opened = decoder.open(
                    url = stream.url,
                    headers = stream.headers,
                    requested = DesktopPcmFormat(rate, channels = 2, bytesPerSample = 4, isFloat = true),
                    windowed = stream.windowedReads,
                    transport = stream.transport,
                )
                if (opened.isFailure) {
                    decoder.close()
                    return@withContext false
                }
                try {
                    var frames = 0L
                    var left = FloatArray(0)
                    var right = FloatArray(0)
                    while (true) {
                        ensureActive()
                        val block = decoder.readSamples() ?: break
                        val count = decoder.sampleCount / 2
                        if (count <= 0) continue
                        if (left.size < count) { left = FloatArray(count); right = FloatArray(count) }
                        for (i in 0 until count) { left[i] = block[2 * i]; right[i] = block[2 * i + 1] }
                        feed(left, right, count, rate, frames * 1_000_000L / rate)
                        frames += count
                    }
                    frames > 0
                } finally {
                    decoder.close()
                }
            }
        }
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

/** The default capture device for song recognition: mono 16-bit at 44.1 kHz through Java Sound. */
internal object DesktopMicrophone : com.cosmotune.music.data.recognition.SongRecognition.Microphone {
    override suspend fun record(millis: Long): com.cosmotune.music.data.recognition.SongRecognition.Recording? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rate = 44_100f
            val format = javax.sound.sampled.AudioFormat(rate, 16, 1, true, false)
            val line = runCatching {
                (javax.sound.sampled.AudioSystem.getLine(javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine::class.java, format))
                    as javax.sound.sampled.TargetDataLine).apply { open(format) }
            }.getOrNull() ?: return@withContext null
            val total = (rate * millis / 1_000).toInt() * 2
            val bytes = ByteArray(total)
            var filled = 0
            try {
                line.start()
                while (filled < total) {
                    ensureActive()
                    val read = line.read(bytes, filled, minOf(8_192, total - filled))
                    if (read <= 0) break
                    filled += read
                }
            } finally {
                runCatching { line.stop() }
                line.close()
            }
            if (filled < 2) return@withContext null
            val samples = ShortArray(filled / 2) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xFF)).toShort() }
            com.cosmotune.music.data.recognition.SongRecognition.Recording(samples, rate.toInt())
        }
}

/** Draws whichever Extras dialog a menu asked for; placed once at the top of the window. */
@androidx.compose.runtime.Composable
internal fun DesktopExtrasHost() {
    val about by DesktopExtras.aboutAlbum.collectAsState()
    about?.let { (album, artist) ->
        com.cosmotune.ui.extras.AlbumAboutDialog(album, artist, onDismiss = { DesktopExtras.aboutAlbum.value = null })
    }
    val cast by DesktopExtras.castSong.collectAsState()
    cast?.let { song ->
        com.cosmotune.ui.extras.DlnaCastDialog(song, onDismiss = { DesktopExtras.castSong.value = null })
    }
}
