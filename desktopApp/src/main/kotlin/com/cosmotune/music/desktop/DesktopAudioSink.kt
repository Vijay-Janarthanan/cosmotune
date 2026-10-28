package com.cosmotune.music.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** The speakers, and the negotiation to reach them. */
internal class DesktopAudioSink {

    private var line: SourceDataLine? = null

    /** True while the Windows shared-mode sink owns the output instead of Java Sound. */
    private var windowsOutput = false

    private var windowsBufferBytes = 0
    private var nativePcm: DesktopNativePcm? = null
    private var openedDirect = false
    private var renderedOffset = 0L

    /** The selection for which [line] was opened, including the blank system-default choice. */
    private var openedForSelection: String? = null

    /** Reused conversion buffer; only the playback thread touches it. */
    private var staging = ByteArray(0)

    /** Kept short: enough to distinguish real samples from a pipeline producing silence. */
    private var diagnosticBlocks = 0

    /** The format the mixer accepted, valid once [open] has succeeded. */
    var format: DesktopPcmFormat = DesktopPcmFormat(44_100, 2, 2)
        private set

    /** Playback gain, applied to the samples rather than to a mixer control. */
    @Volatile
    var gain: Float = 1f

    val isOpen: Boolean get() = windowsOutput || nativePcm != null || line != null
    val isDirect: Boolean get() = nativePcm != null || (windowsOutput && DesktopWindowsAudio.exclusiveActive)

    /** Which output path is open: both are shared paths through the system mixer. */
    val outputApi: String?
        get() = when {
            windowsOutput -> if (DesktopWindowsAudio.exclusiveActive) "WASAPI exclusive" else "WASAPI shared"
            nativePcm != null -> nativePcm?.api
            line != null -> "Java Sound"
            else -> null
        }

    /** Whether the live line already belongs to the choice currently shown in the player. */
    fun isUsingSelection(id: String): Boolean = isOpen && openedForSelection == id && openedDirect == DesktopOutputMode.direct.value

    /** Opens the best line the mixer will give for [requested]. */
    fun open(requested: DesktopPcmFormat, systemFallback: Boolean = false): Result<DesktopPcmFormat> = runCatching {
        val selectedDevice = DesktopAudioDevices.selected.value
        val wantDirect = DesktopOutputMode.direct.value && !systemFallback
        nativePcm?.close(); nativePcm = null
        openedDirect = DesktopOutputMode.direct.value
        renderedOffset = 0L
        if (DesktopPlatform.isWindows) {
            var accepted = requested
            var directFailure: String? = null
            var native: Result<Int> = Result.failure(IllegalStateException("No exclusive format accepted"))
            if (wantDirect) {
                val exact = negotiateDirectPcm(requested) { candidate -> DesktopWindowsAudio.open(selectedDevice, candidate, exclusive = true) }
                if (exact.isSuccess) {
                    val offer = exact.getOrThrow(); accepted = offer.format; native = Result.success(offer.endpoint)
                } else directFailure = exact.exceptionOrNull()?.message
            }
            if (!wantDirect || native.isFailure) native = DesktopWindowsAudio.open(selectedDevice, requested)
            if (native.isSuccess) {
                val previous = line
                line = null
                windowsOutput = true
                windowsBufferBytes = native.getOrThrow()
                // The native sink moves with the Windows default or an unplugged device;
                // its cached label is refreshed on the playback thread, never by the UI.
                openedOn = null
                openedForSelection = selectedDevice
                format = accepted
                DesktopOutputMode.status.value = if (DesktopWindowsAudio.exclusiveActive) "WASAPI exclusive · exact accepted format" else if (wantDirect) "Shared fallback · ${directFailure ?: "exclusive unavailable"}" else "WASAPI shared"
                diagnosticBlocks = 0
                closeLine(previous)
                DesktopTrackLog.log(
                    "audio output opened: ${deviceName ?: "Windows system default"} · " +
                        "${accepted.sampleRate} Hz · ${accepted.channels} ch · $outputApi",
                )
                return@runCatching accepted
            }
            DesktopTrackLog.log("WASAPI output unavailable; trying Java Sound: ${native.exceptionOrNull()?.message}")
        }

        if (windowsOutput) {
            DesktopWindowsAudio.close()
            windowsOutput = false
            windowsBufferBytes = 0
        }
        if (wantDirect && !DesktopPlatform.isWindows) {
            closeLine(line); line = null
            val attempt = DesktopUnixAudio.open(requested, selectedDevice)
            if (attempt.isSuccess) {
                val opened = attempt.getOrThrow()
                closeLine(line); line = null; nativePcm = opened
                openedForSelection = selectedDevice; openedOn = opened.device
                format = opened.format; diagnosticBlocks = 0
                DesktopOutputMode.status.value = opened.api
                return@runCatching format
            }
            DesktopOutputMode.status.value = "System fallback · ${attempt.exceptionOrNull()?.message ?: "direct output unavailable"}"
            DesktopTrackLog.log("direct output unavailable; using system mixer: ${attempt.exceptionOrNull()?.message}")
        }
        val ladder = buildList {
            add(requested)
            if (requested.isFloat) add(requested.copy(bytesPerSample = 2, isFloat = false))
            // A device that will not take the source's own rate is rare, but a 44.1k-only or
            // 48k-only card does exist and silence is not the right answer for it.
            for (rate in intArrayOf(48_000, 44_100)) {
                if (rate != requested.sampleRate) {
                    add(requested.copy(sampleRate = rate))
                    add(requested.copy(sampleRate = rate, bytesPerSample = 2, isFloat = false))
                }
            }
            if (requested.channels != 2) add(requested.copy(channels = 2, bytesPerSample = 2, isFloat = false))
        }

        // The chosen device, when there is one and it is still plugged in; otherwise whatever the
        // system calls the default.
        val mixer = DesktopAudioDevices.mixerFor(selectedDevice)
        val supports: (DesktopPcmFormat) -> Boolean = { candidate ->
            if (mixer == null) {
                AudioSystem.isLineSupported(infoFor(candidate))
            } else {
                runCatching { mixer.isLineSupported(infoFor(candidate)) }.getOrDefault(false)
            }
        }
        val accepted = ladder.firstOrNull(supports)
            ?: error("no audio line for any supported format")

        val opened = if (mixer == null) {
            AudioSystem.getLine(infoFor(accepted)) as SourceDataLine
        } else {
            mixer.getLine(infoFor(accepted)) as SourceDataLine
        }
        try {
            // Roughly a fifth of a second in the device's hands.
            opened.open(accepted.toAudioFormat(), accepted.byteRate / 5)
            opened.start()
        } catch (failure: Throwable) {
            runCatching { opened.close() }
            throw failure
        }

        // Do not tear down the working route until its replacement is known to have opened. A
        // Bluetooth device can disappear between enumeration and this call; that must not turn a
        // failed switch into silent playback on every other device too.
        val previous = line
        line = opened
        openedOn = mixer?.mixerInfo?.name ?: runCatching { AudioSystem.getMixer(null).mixerInfo?.name }.getOrNull()
        openedForSelection = selectedDevice
        format = accepted
        if (!wantDirect) DesktopOutputMode.status.value = "Java Sound · system output"
        diagnosticBlocks = 0
        closeLine(previous)
        DesktopTrackLog.log(
            "audio output opened: ${deviceName ?: "system default"} · " +
                "${accepted.sampleRate} Hz · ${accepted.channels} ch · " +
                (if (accepted.isFloat) "32-bit float" else "${accepted.bytesPerSample * 8}-bit PCM"),
        )
        accepted
    }

    /**
     * What the samples are actually being written to.
     *
     * The mixer's own name, not `lineInfo`, which describes the line's capabilities — "interface
     * SourceDataLine supporting 36 audio formats" is not a device.
     */
    val deviceName: String?
        get() = runCatching {
            if (windowsOutput) {
                DesktopWindowsAudio.deviceName()
            } else if (nativePcm != null) {
                nativePcm?.device
            } else {
                openedOn
            }
        }.getOrNull()

    /** The mixer this line was actually opened on, when it was not the system default. */
    @Volatile private var openedOn: String? = null

    /** How much audio the device is holding, in bytes; 0 before the line is open. */
    val bufferBytes: Int
        get() = if (windowsOutput) windowsBufferBytes else nativePcm?.bufferBytes ?: runCatching { line?.bufferSize ?: 0 }.getOrDefault(0)

    /** Writes [count] interleaved samples, blocking until the device takes them. */
    fun write(samples: FloatArray, count: Int): Int {
        DesktopVisualAudio.publish(samples, count, gain)
        nativePcm?.let { route ->
            try { return route.write(samples, count, gain) }
            catch (failure: Exception) {
                val rendered = runCatching { framesPlayed() }.getOrDefault(renderedOffset)
                val previous = format
                nativePcm = null
                runCatching { route.close() }
                open(previous, systemFallback = true).getOrThrow()
                check(format.sampleRate == previous.sampleRate && format.channels == previous.channels) { "Output changed its rate/layout during recovery" }
                renderedOffset = rendered
                DesktopOutputMode.status.value = "System fallback · direct device disconnected or stopped"
                DesktopTrackLog.log("direct output stopped; recovered with system output: ${failure.message}")
                return write(samples, count)
            }
        }
        if (windowsOutput) {
            val written = DesktopWindowsAudio.write(samples, count, gain)
            diagnose(samples, count, written)
            return written
        }
        val target = line ?: return 0
        if (count <= 0) return 0
        val bytes = convert(samples, count)
        val written = target.write(bytes, 0, count * format.bytesPerSample)
        diagnose(samples, count, written)
        return written
    }

    private fun diagnose(samples: FloatArray, count: Int, written: Int) {
        if (diagnosticBlocks < DIAGNOSTIC_BLOCK_LIMIT) {
            diagnosticBlocks++
            val peak = (0 until minOf(count, samples.size)).maxOfOrNull { kotlin.math.abs(samples[it]) } ?: 0f
            if (peak > AUDIBLE_SIGNAL_FLOOR || diagnosticBlocks == DIAGNOSTIC_BLOCK_LIMIT) {
                DesktopTrackLog.log(
                    "audio output signal: peak=${"%.4f".format(java.util.Locale.ROOT, peak)} " +
                        "gain=${"%.2f".format(java.util.Locale.ROOT, gain)} wrote=$written bytes",
                )
                diagnosticBlocks = DIAGNOSTIC_BLOCK_LIMIT
            }
        }
    }

    /** Floats to whatever the device accepted, scaled by [gain]. */
    private fun convert(samples: FloatArray, count: Int): ByteArray {
        staging = encodeDesktopPcm(samples, count, format, gain, staging)
        return staging
    }

    /** Frames the device has actually rendered — the honest playback position. */
    fun framesPlayed(): Long =
        renderedOffset + (if (windowsOutput) DesktopWindowsAudio.framesPlayed() else nativePcm?.framesPlayed() ?: line?.longFramePosition ?: 0L)

    fun pause() {
        if (windowsOutput) DesktopWindowsAudio.pause() else if (nativePcm != null) nativePcm?.pause() else line?.stop()
    }

    fun resume() {
        if (windowsOutput) DesktopWindowsAudio.resume() else if (nativePcm != null) nativePcm?.resume() else line?.start()
    }

    /** Throws away what is queued, so a seek is heard at once. */
    fun flush() {
        if (windowsOutput) DesktopWindowsAudio.flush() else if (nativePcm != null) nativePcm?.flush() else line?.flush()
    }

    /** Waits for the queue to finish, so the end of a track is not clipped. */
    fun drain() {
        if (windowsOutput) DesktopWindowsAudio.drain() else if (nativePcm != null) nativePcm?.drain() else runCatching { line?.drain() }
    }

    fun close() {
        renderedOffset = 0L
        nativePcm?.let { nativePcm = null; it.close(); openedOn = null; openedForSelection = null }
        if (windowsOutput) {
            DesktopWindowsAudio.close()
            windowsOutput = false
            windowsBufferBytes = 0
            openedOn = null
            openedForSelection = null
            diagnosticBlocks = 0
            return
        }
        val target = line ?: return
        line = null
        openedOn = null
        openedForSelection = null
        diagnosticBlocks = 0
        closeLine(target)
    }

    private fun closeLine(target: SourceDataLine?) {
        if (target == null) return
        runCatching {
            target.stop()
            target.flush()
            target.close()
        }
    }

    private fun infoFor(candidate: DesktopPcmFormat) =
        DataLine.Info(SourceDataLine::class.java, candidate.toAudioFormat())

    private companion object {
        const val DIAGNOSTIC_BLOCK_LIMIT = 50
        const val AUDIBLE_SIGNAL_FLOOR = 0.001f
    }
}

/** The same format in JavaSound's vocabulary. */
internal fun DesktopPcmFormat.toAudioFormat(): AudioFormat = AudioFormat(
    if (isFloat) AudioFormat.Encoding.PCM_FLOAT else AudioFormat.Encoding.PCM_SIGNED,
    sampleRate.toFloat(),
    bytesPerSample * 8,
    channels,
    frameBytes,
    sampleRate.toFloat(),
    false,
)
