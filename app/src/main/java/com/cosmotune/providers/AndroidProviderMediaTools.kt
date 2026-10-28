package com.cosmotune.providers

import java.io.File
import java.util.function.BooleanSupplier
import kotlinx.serialization.json.*

/** Shares the app's audio decoder library; no executable or additional codec bundle. */
@androidx.annotation.Keep
object AndroidProviderMediaTools : ProviderMediaTools {
    private val loaded by lazy { System.loadLibrary("ffmpegJNI"); true }
    private external fun probeNative(input: String, cancelled: BooleanSupplier): String
    private external fun remuxNative(input: String, output: String, key: String?, maximumBytes: Long, cancelled: BooleanSupplier)
    private external fun convertPcmNative(input: String, output: String, bits: Int, maximumBytes: Long, cancelled: BooleanSupplier)
    private external fun encodeFlacNative(inputWave: String, output: String, maximumBytes: Long, cancelled: BooleanSupplier)
    private fun cancellation(cancelled: () -> Boolean) = BooleanSupplier { cancelled() || Thread.currentThread().isInterrupted }
    override fun probe(input: File, cancelled: () -> Boolean): ProviderMediaInfo {
        check(loaded)
        val raw = Json.parseToJsonElement(probeNative(input.absolutePath, cancellation(cancelled))).jsonObject
        return ProviderMediaInfo(raw.getValue("codec").jsonPrimitive.content, raw.getValue("rate").jsonPrimitive.int,
            raw.getValue("channels").jsonPrimitive.int, raw.getValue("depth").jsonPrimitive.int,
            raw.getValue("duration").jsonPrimitive.long, raw.getValue("bitrate").jsonPrimitive.long)
    }
    override fun remux(input: File, output: File, key: String?, maximumBytes: Long, cancelled: () -> Boolean) {
        check(loaded)
        remuxNative(input.absolutePath, output.absolutePath, key, maximumBytes, cancellation(cancelled))
    }
    override fun convertPcm(input: File, output: File, bits: Int, maximumBytes: Long, cancelled: () -> Boolean) {
        require(bits in setOf(16, 24)); check(loaded)
        convertPcmNative(input.absolutePath, output.absolutePath, bits, maximumBytes, cancellation(cancelled))
    }
    override fun convertFlac(input: File, output: File, maximumBytes: Long, cancelled: () -> Boolean) {
        check(loaded)
        val source = probe(input, cancelled)
        require(source.codec in setOf("flac", "alac", "wavpack", "ape", "truehd", "tta") || source.codec.startsWith("pcm_")) { "FLAC encoding requires a verified lossless source" }
        require(source.bitDepth in 1..24) { "FLAC encoding requires a confirmed source depth of at most 24 bits" }
        val bits = if (source.bitDepth in 1..16) 16 else 24
        val pcm = File.createTempFile("encoding-", ".wav", output.parentFile)
        try {
            convertPcmNative(input.absolutePath, pcm.absolutePath, bits, maximumBytes, cancellation(cancelled))
            val available = maximumBytes - pcm.length(); require(available > 0)
            encodeFlacNative(pcm.absolutePath, output.absolutePath, available, cancellation(cancelled))
        } finally { pcm.delete() }
    }
}
