package com.cosmotune.extras

import com.cosmotune.audio.PitchResampler
import com.cosmotune.audio.VolumeCap
import com.cosmotune.music.data.NetworkPolicy
import com.cosmotune.music.data.about.WikipediaAbout
import com.cosmotune.settings.ExtrasSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.settings.ProxySetting
import java.net.Proxy
import java.net.URI
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackExtrasTest {

    private class MapStore : KeyValueStore {
        val values = HashMap<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getString(key: String, default: String) = values[key] as? String ?: default
        override fun putString(key: String, value: String) { values[key] = value }
    }

    @AfterTest
    fun clearNetwork() {
        NetworkPolicy.offline = false
        NetworkPolicy.apply(null)
    }

    @Test
    fun `settings are kept and clamped`() {
        val store = MapStore()
        val settings = ExtrasSettings(store)
        settings.setMaxVolumePercent(3)
        settings.setPitchSemitones(40)
        assertEquals(ExtrasSettings.MIN_VOLUME_PERCENT, settings.maxVolumePercent.value)
        assertEquals(12, settings.pitchSemitones.value)
        assertEquals(2f, settings.pitchFactor, 1e-4f)
        val again = ExtrasSettings(store)
        assertEquals(ExtrasSettings.MIN_VOLUME_PERCENT, again.maxVolumePercent.value)
        assertEquals(12, again.pitchSemitones.value)
    }

    @Test
    fun `the proxy setting reaches the network policy`() {
        val settings = ExtrasSettings(MapStore())
        settings.setProxy(ProxySetting(enabled = true, type = ProxySetting.Type.SOCKS, address = "proxy.example.com:1080"))
        val chosen = NetworkPolicy.selector.select(URI("https://music.youtube.com/"))
        assertEquals(Proxy.Type.SOCKS, chosen.single().type())
        // The home network goes direct even with a proxy on.
        assertEquals(Proxy.NO_PROXY, NetworkPolicy.selector.select(URI("http://192.168.1.20:4533/rest")).single())
        settings.setProxy(settings.proxy.value.copy(enabled = false))
        assertNull(NetworkPolicy.proxy)
    }

    @Test
    fun `offline mode refuses the internet and keeps the local network`() {
        val settings = ExtrasSettings(MapStore())
        settings.setOffline(true)
        assertFailsWith<NetworkPolicy.OfflineException> { NetworkPolicy.checkAllowed("https://www.youtube.com/watch?v=x") }
        NetworkPolicy.checkAllowed("http://nas.local:8080/song.flac")
        NetworkPolicy.checkAllowed("http://10.0.0.4/rest")
        settings.setOffline(false)
        NetworkPolicy.checkAllowed("https://www.youtube.com/")
    }

    @Test
    fun `local hosts are told from the name alone`() {
        listOf(
            "localhost", "127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.0.1", "169.254.3.3", "::1", "[fe80::1]", "fd12::3",
            "printer", "nas.local", "box.lan", "router.home.arpa",
        ).forEach { assertTrue(NetworkPolicy.isLocalHost(it), it) }
        listOf("www.youtube.com", "8.8.8.8", "172.32.0.1", "2001:db8::1", "lrclib.net")
            .forEach { assertFalse(NetworkPolicy.isLocalHost(it), it) }
    }

    @Test
    fun `proxy addresses are read the way people paste them`() {
        assertEquals(8080, NetworkPolicy.parseAddress("http://user:pw@1.2.3.4:8080/")?.port)
        assertEquals("2001:db8::1", NetworkPolicy.parseAddress("[2001:db8::1]:3128")?.hostString)
        assertNull(NetworkPolicy.parseAddress("proxy.example.com"))
        assertNull(NetworkPolicy.parseAddress("host:99999"))
    }

    @Test
    fun `the volume cap ramps to its target and leaves an uncapped block alone`() {
        val cap = VolumeCap()
        val untouched = FloatArray(8) { 0.5f }
        cap.process(untouched, untouched.size, 1f)
        assertTrue(untouched.all { it == 0.5f })
        val block = FloatArray(10_000) { 1f }
        cap.process(block, block.size, 0.5f)
        assertTrue(block[0] < 1f && block[0] > 0.99f, "starts near the old gain")
        assertEquals(0.5f, block.last(), 1e-6f)
    }

    @Test
    fun `the resampler moves a tone by the pitch factor`() {
        val rate = 48_000
        val frequency = 440.0
        val frames = rate
        val input = FloatArray(frames * 2) { i -> sin(2 * PI * frequency * (i / 2) / rate).toFloat() }
        val resampler = PitchResampler(channels = 2)
        resampler.ratio = 1.5
        // In uneven blocks, as the engine hands them over.
        val out = ArrayList<Float>()
        var at = 0
        var odd = false
        while (at < input.size) {
            val count = minOf(if (odd) 2_222 else 1_000, input.size - at)
            odd = !odd
            val chunk = input.copyOfRange(at, at + count)
            val produced = resampler.process(chunk, count)
            for (i in 0 until resampler.outputCount) out += produced[i]
            at += count
        }
        val left = out.filterIndexed { index, _ -> index % 2 == 0 }
        // Two thirds as many frames, and the tone 1.5 times higher: count rising zero crossings.
        assertEquals(frames / 1.5, left.size.toDouble(), 8.0)
        val crossings = left.zipWithNext().count { (a, b) -> a < 0f && b >= 0f }
        val measured = crossings / (left.size.toDouble() / rate)
        assertTrue(abs(measured - frequency * 1.5) < 5, "measured $measured Hz")
    }

    @Test
    fun `the resampler at ratio one is a pass-through`() {
        val resampler = PitchResampler(channels = 2)
        val block = FloatArray(64) { it.toFloat() }
        assertTrue(resampler.process(block, 64) === block)
        assertEquals(64, resampler.outputCount)
    }

    @Test
    fun `album pages are tried from the most precise name`() {
        val names = WikipediaAbout.candidates("Bombay", "A. R. Rahman").map { it.title }
        assertEquals("Bombay (A. R. Rahman album)", names.first())
        assertTrue("Bombay (soundtrack)" in names)
        assertEquals("Bombay", names.last())
    }

    @Test
    fun `a page summary is read and a disambiguation page is not`() {
        val summary = WikipediaAbout.parse(
            """{"type":"standard","title":"Bombay (soundtrack)","description":"1995 soundtrack album by A. R. Rahman",
               "extract":"Bombay is the soundtrack album to the 1995 film.","thumbnail":{"source":"https://upload/x.jpg"},
               "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Bombay_(soundtrack)"}}}""",
        )
        assertNotNull(summary)
        assertEquals("https://en.wikipedia.org/wiki/Bombay_(soundtrack)", summary.pageUrl)
        assertTrue(WikipediaAbout.accept(summary, precise = false, artist = "A. R. Rahman"))
        assertNull(WikipediaAbout.parse("""{"type":"disambiguation","title":"Bombay","extract":"Bombay may refer to:"}"""))
        val town = WikipediaAbout.Summary("Bombay", "City in India", "Mumbai, formerly Bombay, is a city.", "u", null)
        assertFalse(WikipediaAbout.accept(town, precise = false, artist = "A. R. Rahman"))
    }
}
