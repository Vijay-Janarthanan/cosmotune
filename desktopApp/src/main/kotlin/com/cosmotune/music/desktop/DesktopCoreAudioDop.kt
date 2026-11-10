package com.cosmotune.music.desktop

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Direct HAL IO on a single exact-format USB output stream. No AudioQueue or sample converter. */
internal class CoreAudioDop(
    override val format: DesktopPcmFormat,
    selection: String,
) : DesktopNativePcm {
    private val hal = CoreAudioDopHal
    private val id = selection.removePrefix("coreaudio:").toInt()
    private val owner = ProcessHandle.current().pid().toInt()
    private val deviceUid = requireNotNull(deviceIdentity(selection)) { "DoP requires a CoreAudio USB DAC" }
    private val stream = hal.outputStream(id)
    private val originalPhysical = hal.read(stream, "pft ", 40)
    private val originalVirtual = hal.read(stream, "sfmt", 40)
    private val originalHog = hal.int(id, "oink")
    private var ownsHog = false
    private var changedFormats = false
    private var proc: Pointer? = null
    private var running = false
    private val listeners = mutableListOf<Pair<Int, String>>()
    @Volatile private var invalid = false
    private val fifo = DoPCarrierFifo(format.channels, capacity = (format.sampleRate / 5).coerceAtLeast(4096))
    private val callbackBuffer = ByteArray(65_536 * format.frameBytes)
    override val api = "CoreAudio HAL · exclusive DoP"
    override val device = hal.string(id, "lnam") ?: "CoreAudio USB DAC"
    override val bufferBytes = fifo.capacity * format.frameBytes

    private interface Render : Callback {
        fun invoke(device: Int, now: Pointer?, input: Pointer?, inputTime: Pointer?, output: Pointer?, outputTime: Pointer?, context: Pointer?): Int
    }

    private interface Changed : Callback {
        fun invoke(objectId: Int, count: Int, addresses: Pointer?, context: Pointer?): Int
    }

    private val listener = object : Changed {
        override fun invoke(objectId: Int, count: Int, addresses: Pointer?, context: Pointer?): Int {
            invalid = true
            return 0
        }
    }

    private val render = object : Render {
        override fun invoke(device: Int, now: Pointer?, input: Pointer?, inputTime: Pointer?, output: Pointer?, outputTime: Pointer?, context: Pointer?): Int {
            if (output == null) { invalid = true; return 0 }
            val buffers = output.getInt(0)
            // AudioBufferList places its first AudioBuffer at offset 8 on supported 64-bit Macs.
            if (buffers != 1) { invalid = true; return 0 }
            val channels = output.getInt(8)
            val size = output.getInt(12)
            val destination = output.getPointer(16)
            if (destination == null || size !in 0..callbackBuffer.size || channels != format.channels || size % format.frameBytes != 0) {
                invalid = true
                return 0
            }
            if (invalid) {
                destination.setMemory(0, size.toLong(), 0)
                return 0
            }
            fifo.render(callbackBuffer, size / format.frameBytes)
            destination.write(0, callbackBuffer, 0, size)
            return 0
        }
    }

    init {
        try {
            check(DesktopOutputMode.validateDopDevice(selection)) { "The confirmed USB DAC changed" }
            check(originalHog == -1 || originalHog == owner) { "This DAC is in use by another app" }
            if (originalHog != owner) {
                hal.setInt(id, "oink", owner)
                ownsHog = true
            }
            check(hal.int(id, "oink") == owner) { "The DAC did not grant exclusive access" }
            Memory(40).use { exact ->
                exact.clear()
                exact.setDouble(0, format.sampleRate.toDouble())
                exact.setInt(8, hal.fourCC("lpcm"))
                exact.setInt(12, 4 or 8 or 64) // signed integer, packed, nonmixable; little-endian and interleaved
                exact.setInt(16, format.frameBytes); exact.setInt(20, 1)
                exact.setInt(24, format.frameBytes); exact.setInt(28, format.channels); exact.setInt(32, 24)
                changedFormats = true
                if (!matches(originalPhysical)) hal.set(stream, "pft ", exact)
                waitForFormat("pft ")
                if (!hal.read(stream, "sfmt", 40).use(::matches)) {
                    hal.set(stream, "sfmt", exact)
                }
                waitForFormat("sfmt")
            }
            for ((objectId, selector) in listOf(stream to "pft ", stream to "sfmt", id to "oink", id to "livn")) {
                hal.address(selector).use { address ->
                    check(hal.call("AudioObjectAddPropertyListener", objectId, address, listener, null) == 0) {
                        "Could not monitor the DAC for format or ownership changes"
                    }
                }
                listeners += objectId to selector
            }
            verifyRoute()
            val ref = PointerByReference()
            check(hal.call("AudioDeviceCreateIOProcID", id, render, null, ref) == 0) { "Could not open the CoreAudio stream" }
            proc = requireNotNull(ref.value)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun matches(description: Pointer): Boolean =
        description.getDouble(0) == format.sampleRate.toDouble() &&
            description.getInt(8) == hal.fourCC("lpcm") &&
            description.getInt(12) == (4 or 8 or 64) &&
            description.getInt(16) == format.frameBytes && description.getInt(20) == 1 &&
            description.getInt(24) == format.frameBytes && description.getInt(28) == format.channels &&
            description.getInt(32) == 24

    private fun waitForFormat(selector: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        do {
            if (hal.read(stream, selector, 40).use(::matches)) return
            Thread.sleep(10)
        } while (System.nanoTime() < until)
        error("The DAC did not accept exact 24-bit nonmixable DoP")
    }

    private fun verifyRoute() {
        check(!invalid && hal.int(id, "oink") == owner && hal.int(id, "livn") != 0 &&
            hal.read(stream, "pft ", 40).use(::matches) && hal.read(stream, "sfmt", 40).use(::matches)) {
            "CoreAudio changed the DAC's format or exclusive ownership"
        }
    }

    override fun write(samples: FloatArray, count: Int, gain: Float): Int {
        check(gain == 1f && count % format.channels == 0)
        verifyRoute()
        val bytes = encodeDesktopPcm(samples, count, format, gain)
        val frames = count / format.channels
        var at = 0
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (at < frames) {
            check(!invalid && System.nanoTime() < until) { "CoreAudio stopped accepting DoP frames" }
            at += fifo.append(bytes, at, frames - at)
            if (!running) resume()
            if (at < frames) Thread.sleep(2)
        }
        return bytes.size
    }

    override fun framesPlayed() = fifo.framesPlayed()

    override fun pause() {
        if (running) {
            check(hal.call("AudioDeviceStop", id, proc) == 0) { "Could not pause the CoreAudio DAC" }
            running = false
        }
    }

    override fun resume() {
        if (!running) {
            verifyRoute()
            check(hal.call("AudioDeviceStart", id, proc) == 0) { "Could not start the CoreAudio DAC" }
            running = true
        }
    }

    override fun flush() { pause(); fifo.clear() }

    override fun drain() {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!fifo.empty()) {
            check(!invalid && running && System.nanoTime() < until) { "Could not drain the CoreAudio DAC" }
            Thread.sleep(2)
        }
    }

    override fun close() {
        invalid = true
        proc?.let {
            if (running) hal.call("AudioDeviceStop", id, it)
            hal.call("AudioDeviceDestroyIOProcID", id, it)
        }
        proc = null
        running = false
        listeners.forEach { (objectId, selector) ->
            hal.address(selector).use { hal.call("AudioObjectRemovePropertyListener", objectId, it, listener, null) }
        }
        listeners.clear()
        val sameDevice = runCatching { hal.string(id, "uid ") == deviceUid && hal.int(id, "oink") == owner }.getOrDefault(false)
        if (sameDevice && changedFormats) {
            runCatching { hal.set(stream, "pft ", originalPhysical) }
            runCatching { hal.set(stream, "sfmt", originalVirtual) }
        }
        if (sameDevice && ownsHog) runCatching { hal.setInt(id, "oink", originalHog) }
        ownsHog = false
        changedFormats = false
        originalPhysical.close()
        originalVirtual.close()
    }

    companion object {
        fun deviceIdentity(selection: String): String? = runCatching {
            val id = selection.takeIf { it.startsWith("coreaudio:") }?.removePrefix("coreaudio:")?.toIntOrNull() ?: return null
            val hal = CoreAudioDopHal
            if (hal.int(id, "tran") != hal.fourCC("usb ") || hal.int(id, "livn") == 0) return null
            hal.string(id, "uid ")
        }.getOrNull()
    }
}

/** Single playback writer and single HAL consumer. Hardware callbacks never wait for the writer. */
internal class DoPCarrierFifo(private val channels: Int, val capacity: Int) {
    private val frameBytes = channels * 3
    private val ring = ByteArray(capacity * frameBytes)
    private val readAt = AtomicLong()
    private val writeAt = AtomicLong()
    private val rendered = AtomicLong()
    private var firstMarker = true

    fun append(source: ByteArray, at: Int, frames: Int): Int {
        val write = writeAt.get()
        val index = (write % capacity).toInt()
        val count = minOf(frames, capacity - (write - readAt.get()).toInt(), capacity - index)
        source.copyInto(ring, index * frameBytes, at * frameBytes, (at + count) * frameBytes)
        writeAt.set(write + count)
        return count
    }

    fun render(target: ByteArray, frames: Int) {
        val read = readAt.get()
        val count = minOf(frames.toLong(), writeAt.get() - read).toInt()
        for (frame in 0 until frames) {
            val source = ((read + frame) % capacity).toInt() * frameBytes
            val marker = if (firstMarker) 0x05 else 0xfa
            for (channel in 0 until channels) {
                val dest = frame * frameBytes + channel * 3
                val offset = source + channel * 3
                target[dest] = if (frame < count) ring[offset] else 0x69
                target[dest + 1] = if (frame < count) ring[offset + 1] else 0x69
                target[dest + 2] = marker.toByte()
            }
            firstMarker = !firstMarker
        }
        rendered.addAndGet(count.toLong())
        readAt.set(read + count)
    }

    fun empty() = readAt.get() == writeAt.get()
    fun framesPlayed() = rendered.get()
    fun clear() { readAt.set(writeAt.get()); firstMarker = true }
}

private object CoreAudioDopHal {
    private val library = NativeLibrary.getInstance("/System/Library/Frameworks/CoreAudio.framework/CoreAudio")
    private val foundation by lazy { NativeLibrary.getInstance("CoreFoundation") }
    fun call(name: String, vararg args: Any?): Int = library.getFunction(name).invokeInt(args)
    fun fourCC(value: String): Int = value.fold(0) { word, c -> (word shl 8) or c.code }
    fun address(selector: String, scope: String = "glob") = Memory(12).also {
        it.setInt(0, fourCC(selector)); it.setInt(4, fourCC(scope)); it.setInt(8, 0)
    }

    fun read(id: Int, selector: String, bytes: Int, scope: String = "glob"): Memory {
        val result = Memory(bytes.toLong())
        try {
            val actual = IntByReference(bytes)
            val status = address(selector, scope).use { call("AudioObjectGetPropertyData", id, it, 0, null, actual, result) }
            check(status == 0 && actual.value == bytes) { "CoreAudio property ${selector.trim()} unavailable" }
            return result
        } catch (failure: Throwable) {
            result.close()
            throw failure
        }
    }

    fun set(id: Int, selector: String, value: Memory) {
        val status = address(selector).use { call("AudioObjectSetPropertyData", id, it, 0, null, value.size().toInt(), value) }
        check(status == 0) { "CoreAudio refused ${selector.trim()}" }
    }

    fun int(id: Int, selector: String) = read(id, selector, 4).use { it.getInt(0) }
    fun setInt(id: Int, selector: String, value: Int) = Memory(4).use { it.setInt(0, value); set(id, selector, it) }

    fun outputStream(device: Int): Int {
        val size = IntByReference()
        val status = address("stm#", "outp").use { call("AudioObjectGetPropertyDataSize", device, it, 0, null, size) }
        check(status == 0 && size.value == 4) { "DoP requires one interleaved output stream" }
        return read(device, "stm#", 4, "outp").use { it.getInt(0) }
    }

    fun string(id: Int, selector: String): String? {
        val text = read(id, selector, com.sun.jna.Native.POINTER_SIZE).use { it.getPointer(0) } ?: return null
        return try {
            Memory(1024).use {
                val ok = foundation.getFunction("CFStringGetCString").invokeInt(arrayOf(text, it, NativeLong(it.size()), 0x08000100))
                if (ok != 0) it.getString(0, "UTF-8") else null
            }
        } finally {
            foundation.getFunction("CFRelease").invokeVoid(arrayOf(text))
        }
    }
}
