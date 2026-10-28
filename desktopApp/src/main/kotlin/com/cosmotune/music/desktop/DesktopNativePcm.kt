package com.cosmotune.music.desktop

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Platform libraries are loaded only after explicit direct-output selection. No bundled OS engine. */
internal interface DesktopNativePcm : AutoCloseable {
    val format: DesktopPcmFormat
    val api: String
    val device: String
    val bufferBytes: Int
    fun write(samples: FloatArray, count: Int, gain: Float): Int
    fun framesPlayed(): Long
    fun pause()
    fun resume()
    fun flush()
    fun drain()
}

internal object DesktopUnixAudio {
    fun devices(): List<DesktopAudioDevice> = when {
        DesktopPlatform.isLinux -> alsaDevices()
        DesktopPlatform.isMac -> runCatching { CoreAudioPcm.devices() }.getOrDefault(emptyList())
        else -> emptyList()
    }

    private fun alsaDevices(): List<DesktopAudioDevice> = runCatching {
        File("/proc/asound/pcm").readLines().mapNotNull { line ->
            if (!line.contains("playback")) return@mapNotNull null
            val match = Regex("^(\\d+)-(\\d+):\\s*(.+)").find(line) ?: return@mapNotNull null
            DesktopAudioDevice("alsa:hw:${match.groupValues[1].toInt()},${match.groupValues[2].toInt()}", match.groupValues[3].substringBefore(": playback").trim(), "ALSA hardware · exact format; may be busy while PipeWire is using it")
        }
    }.getOrDefault(emptyList())

    fun open(requested: DesktopPcmFormat, selection: String): Result<DesktopNativePcm> = runCatching {
        when {
            DesktopPlatform.isLinux -> {
                val chosen = selection.takeIf { it.startsWith("alsa:hw:") } ?: alsaDevices().firstOrNull()?.id
                    ?: error("No ALSA playback hardware found")
                require(chosen.matches(Regex("alsa:hw:\\d+,\\d+")))
                negotiateDirectPcm(requested) { candidate -> runCatching { AlsaPcm(candidate, chosen.removePrefix("alsa:")) } }.getOrThrow().endpoint
            }
            DesktopPlatform.isMac -> CoreAudioPcm(requested.copy(bytesPerSample = 4, isFloat = true), selection)
            else -> error("No native PCM backend for this platform")
        }
    }
}

private class AlsaPcm(override val format: DesktopPcmFormat, override val device: String) : DesktopNativePcm {
    private val lib = NativeLibrary.getInstance("asound")
    private val pcm: Pointer
    private val submitted = AtomicLong()
    private var memory = Memory(1)
    override val api = "ALSA hardware (resampling disabled)"
    override var bufferBytes = 0
        private set
    private fun call(name: String, vararg args: Any?): Int = lib.getFunction(name).invokeInt(args)
    init {
        val ref = PointerByReference()
        check(call("snd_pcm_open", ref, device, 0, 0) >= 0) { "ALSA hardware unavailable or busy" }
        pcm = ref.value
        try {
            val name = when { format.isFloat -> "FLOAT_LE"; format.bytesPerSample == 4 -> "S32_LE"; format.bytesPerSample == 3 -> "S24_3LE"; else -> "S16_LE" }
            val encoding = call("snd_pcm_format_value", name)
            check(call("snd_pcm_set_params", pcm, encoding, 3, format.channels, format.sampleRate, 0, 200_000) >= 0) { "ALSA refused the exact PCM format" }
            val params = PointerByReference()
            check(call("snd_pcm_hw_params_malloc", params) >= 0)
            try {
                check(call("snd_pcm_hw_params_current", pcm, params.value) >= 0)
                val rate = IntByReference(); val channels = IntByReference(); val actual = IntByReference()
                check(call("snd_pcm_hw_params_get_rate", params.value, rate, IntByReference()) >= 0)
                check(call("snd_pcm_hw_params_get_channels", params.value, channels) >= 0)
                check(call("snd_pcm_hw_params_get_format", params.value, actual) >= 0)
                check(rate.value == format.sampleRate && channels.value == format.channels && actual.value == encoding) { "ALSA negotiated a different format" }
            } finally { lib.getFunction("snd_pcm_hw_params_free").invokeVoid(arrayOf(params.value)) }
            val frames = NativeLongByReference(); val period = NativeLongByReference()
            check(call("snd_pcm_get_params", pcm, frames, period) >= 0)
            bufferBytes = Math.toIntExact(frames.value.toLong() * format.frameBytes)
        } catch (failure: Throwable) { call("snd_pcm_close", pcm); throw failure }
    }
    override fun write(samples: FloatArray, count: Int, gain: Float): Int {
        val bytes = encodeDesktopPcm(samples, count, format, gain)
        if (memory.size() < bytes.size) { memory.close(); memory = Memory(bytes.size.toLong()) }
        memory.write(0, bytes, 0, bytes.size)
        var frames = 0L; var recoveries = 0
        val total = count / format.channels
        while (frames < total) {
            val wrote = lib.getFunction("snd_pcm_writei").invokeLong(arrayOf(pcm, memory.share(frames * format.frameBytes), NativeLong(total - frames)))
            if (wrote < 0) {
                check(recoveries++ < 2 && call("snd_pcm_recover", pcm, wrote.toInt(), 1) >= 0) { "ALSA output failed" }
            } else { check(wrote > 0) { "ALSA made no progress" }; frames += wrote; submitted.addAndGet(wrote) }
        }
        return Math.toIntExact(frames * format.frameBytes)
    }
    override fun framesPlayed(): Long {
        val delay = NativeLongByReference()
        return if (call("snd_pcm_delay", pcm, delay) >= 0) (submitted.get() - delay.value.toLong().coerceAtLeast(0)).coerceAtLeast(0) else submitted.get()
    }
    override fun pause() { if (call("snd_pcm_pause", pcm, 1) < 0) flush() }
    override fun resume() { if (call("snd_pcm_pause", pcm, 0) < 0) call("snd_pcm_prepare", pcm) }
    override fun flush() { val played = framesPlayed(); call("snd_pcm_drop", pcm); call("snd_pcm_prepare", pcm); submitted.set(played) }
    override fun drain() { check(call("snd_pcm_drain", pcm) >= 0); call("snd_pcm_prepare", pcm) }
    override fun close() { call("snd_pcm_drop", pcm); call("snd_pcm_close", pcm); memory.close() }
}

/** AudioQueue writes real float32 PCM; hog ownership and nominal rate must both be accepted. */
private class CoreAudioPcm(override val format: DesktopPcmFormat, selection: String) : DesktopNativePcm {
    private val hardware = NativeLibrary.getInstance("/System/Library/Frameworks/CoreAudio.framework/CoreAudio")
    private val audio = NativeLibrary.getInstance("/System/Library/Frameworks/AudioToolbox.framework/AudioToolbox")
    private val id = selection.removePrefix("coreaudio:").toIntOrNull() ?: propertyInt(hardware, 1, "dOut")
    private val owner = ProcessHandle.current().pid().toInt()
    private val originalRate = property(hardware, id, "nsrt", 8).getDouble(0)
    private var queue: Pointer? = null
    private val free = ArrayBlockingQueue<Pointer>(3)
    private val buffers = mutableListOf<Pointer>()
    private val pending = ConcurrentHashMap<Pointer, Int>()
    private val completed = AtomicLong()
    private var running = false
    private var ownsHog = false
    override val api = "CoreAudio AudioQueue · hog ownership"
    override val device = name(hardware, id)
    override val bufferBytes = (format.byteRate / 10).coerceAtLeast(format.frameBytes) / format.frameBytes * format.frameBytes
    private interface OutputCallback : Callback { fun invoke(user: Pointer?, queue: Pointer?, buffer: Pointer?) }
    private val callback = object : OutputCallback {
        override fun invoke(user: Pointer?, queue: Pointer?, buffer: Pointer?) {
            if (buffer != null) { completed.addAndGet((pending.remove(buffer) ?: 0).toLong()); if (!free.contains(buffer)) free.offer(buffer) }
        }
    }
    private fun call(name: String, vararg args: Any?): Int = audio.getFunction(name).invokeInt(args)
    init {
        try {
            check(id != 0) { "No CoreAudio output device" }
            val hog = propertyInt(hardware, id, "oink")
            check(hog == -1 || hog == owner) { "CoreAudio device is owned by another app" }
            setProperty(hardware, id, "oink", Memory(4).also { it.setInt(0, owner) }, 4)
            ownsHog = true
            check(propertyInt(hardware, id, "oink") == owner) { "CoreAudio refused hog ownership" }
            setProperty(hardware, id, "nsrt", Memory(8).also { it.setDouble(0, format.sampleRate.toDouble()) }, 8)
            check(kotlin.math.abs(property(hardware, id, "nsrt", 8).getDouble(0) - format.sampleRate) < .01) { "CoreAudio refused the source sample rate" }
            val description = Memory(40).also {
                it.clear(); it.setDouble(0, format.sampleRate.toDouble()); it.setInt(8, fourCC("lpcm")); it.setInt(12, 1 or 8)
                it.setInt(16, format.frameBytes); it.setInt(20, 1); it.setInt(24, format.frameBytes); it.setInt(28, format.channels); it.setInt(32, 32)
            }
            val ref = PointerByReference()
            check(call("AudioQueueNewOutput", description, callback, null, null, null, 0, ref) == 0) { "CoreAudio could not create the PCM queue" }
            queue = ref.value
            val uid = property(hardware, id, "uid ", Native.POINTER_SIZE)
            try { check(call("AudioQueueSetProperty", queue, fourCC("aqcd"), uid, Native.POINTER_SIZE) == 0) { "CoreAudio could not select this device" } }
            finally { releaseString(uid.getPointer(0)) }
            repeat(3) {
                val buffer = PointerByReference()
                check(call("AudioQueueAllocateBuffer", queue, bufferBytes, buffer) == 0)
                buffers.add(buffer.value); free.add(buffer.value)
            }
        } catch (failure: Throwable) { close(); throw failure }
    }
    override fun write(samples: FloatArray, count: Int, gain: Float): Int {
        val bytes = encodeDesktopPcm(samples, count, format, gain)
        var at = 0
        while (at < bytes.size) {
            val buffer = free.poll(2, TimeUnit.SECONDS) ?: error("CoreAudio queue stopped accepting audio")
            val length = minOf(bufferBytes, bytes.size - at)
            buffer.getPointer(8).write(0, bytes, at, length)
            buffer.setInt(16, length)
            pending[buffer] = length / format.frameBytes
            check(call("AudioQueueEnqueueBuffer", queue, buffer, 0, null) == 0)
            if (!running) { check(call("AudioQueueStart", queue, null) == 0); running = true }
            at += length
        }
        return at
    }
    override fun framesPlayed(): Long = completed.get()
    override fun pause() { queue?.let { check(call("AudioQueuePause", it) == 0) }; running = false }
    override fun resume() { queue?.let { check(call("AudioQueueStart", it, null) == 0) }; running = true }
    override fun flush() {
        queue?.let { call("AudioQueueStop", it, 1); call("AudioQueueReset", it) }
        running = false; pending.clear(); free.clear(); buffers.forEach { free.offer(it) }
    }
    override fun drain() { val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); while (pending.isNotEmpty() && System.nanoTime() < until) Thread.sleep(5) }
    override fun close() {
        queue?.let { call("AudioQueueStop", it, 1); call("AudioQueueDispose", it, 1) }; queue = null
        if (ownsHog) {
            runCatching { setProperty(hardware, id, "nsrt", Memory(8).also { it.setDouble(0, originalRate) }, 8) }
            runCatching { setProperty(hardware, id, "oink", Memory(4).also { it.setInt(0, -1) }, 4) }
            ownsHog = false
        }
    }
    companion object {
        private fun fourCC(value: String): Int = value.fold(0) { n, c -> (n shl 8) or c.code }
        private fun address(selector: String, output: Boolean = false) = Memory(12).also { it.setInt(0, fourCC(selector)); it.setInt(4, fourCC(if (output) "outp" else "glob")); it.setInt(8, 0) }
        private fun property(lib: NativeLibrary, id: Int, selector: String, bytes: Int): Memory {
            val result = Memory(bytes.toLong())
            check(lib.getFunction("AudioObjectGetPropertyData").invokeInt(arrayOf(id, address(selector), 0, null, IntByReference(bytes), result)) == 0) { "CoreAudio property $selector unavailable" }
            return result
        }
        private fun propertyInt(lib: NativeLibrary, id: Int, selector: String) = property(lib, id, selector, 4).getInt(0)
        private fun setProperty(lib: NativeLibrary, id: Int, selector: String, value: Memory, bytes: Int) {
            check(lib.getFunction("AudioObjectSetPropertyData").invokeInt(arrayOf(id, address(selector), 0, null, bytes, value)) == 0) { "CoreAudio refused $selector" }
        }
        private fun releaseString(value: Pointer?) { if (value != null) NativeLibrary.getInstance("CoreFoundation").getFunction("CFRelease").invokeVoid(arrayOf(value)) }
        private fun name(lib: NativeLibrary, id: Int): String {
            val value = property(lib, id, "lnam", Native.POINTER_SIZE).getPointer(0)
            try {
                val bytes = Memory(1024)
                return if (NativeLibrary.getInstance("CoreFoundation").getFunction("CFStringGetCString").invokeInt(arrayOf(value, bytes, NativeLong(1024), 0x08000100)) != 0) bytes.getString(0, "UTF-8") else "CoreAudio output"
            } finally { releaseString(value) }
        }
        fun devices(): List<DesktopAudioDevice> {
            val lib = NativeLibrary.getInstance("/System/Library/Frameworks/CoreAudio.framework/CoreAudio")
            val size = IntByReference()
            check(lib.getFunction("AudioObjectGetPropertyDataSize").invokeInt(arrayOf(1, address("dev#"), 0, null, size)) == 0)
            if (size.value <= 0) return emptyList()
            val data = property(lib, 1, "dev#", size.value)
            return (0 until size.value / 4).mapNotNull { index ->
                val id = data.getInt(index * 4L)
                val streams = IntByReference()
                val ok = lib.getFunction("AudioObjectGetPropertyDataSize").invokeInt(arrayOf(id, address("stm#", true), 0, null, streams)) == 0
                if (ok && streams.value > 0) DesktopAudioDevice("coreaudio:$id", name(lib, id), "CoreAudio output · requires hog ownership and source rate") else null
            }
        }
    }
}
