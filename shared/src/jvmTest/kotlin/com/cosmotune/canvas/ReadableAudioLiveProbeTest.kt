package com.cosmotune.canvas

import com.opencanvas.core.stream.AudioFetcher
import com.opencanvas.core.stream.InnerTubeBackend
import com.opencanvas.core.sync.Mp4FrameSizes
import com.opencanvas.core.stream.StreamReader
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test

class ReadableAudioLiveProbeTest {
    @Test fun probe() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_READABLE_LIVE") == "1")
        val ids = (System.getenv("COSMOTUNE_READABLE_IDS") ?: "Wab5OO4uctU,pJ1NQZN_SyY,hfSR301sTPo,kIoPTTLeaoo,iVLaWnpAoB0,lgswIdwPXpw").split(',')
        val http = InnerTubeBackend.defaultHttpClient()
        val backends = listOf("android" to InnerTubeBackend.android(), "visionOs" to InnerTubeBackend.visionOs())
        for (id in ids) for ((name, backend) in backends) {
            val stream = runCatching { backend.resolveAudio(id) }
            val audio = stream.getOrNull()
            if (audio == null) { println("READABLE $id $name: no audio stream (${stream.exceptionOrNull()})"); continue }
            val mime = Regex("mime=([^&]+)").find(audio.url)?.groupValues?.get(1)
            val itag = Regex("itag=(\\d+)").find(audio.url)?.groupValues?.get(1)
            val frames = runCatching { Mp4FrameSizes.read(StreamReader(AudioFetcher(http), audio)) }
            println("READABLE $id $name: itag $itag $mime length ${audio.contentLength} -> " +
                (frames.exceptionOrNull()?.let { "failed: ${it.javaClass.simpleName}: ${it.message}" } ?: frames.getOrNull()?.let { "frames ${it.sizes.size}" } ?: "unreadable (null)"))
        }
    }
}
