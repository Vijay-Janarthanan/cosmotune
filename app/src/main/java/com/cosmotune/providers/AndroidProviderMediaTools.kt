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
}
