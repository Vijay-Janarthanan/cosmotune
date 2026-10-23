package com.opencanvas.core.reframer

import com.opencanvas.core.filter.*
import com.opencanvas.core.models.*
import com.opencanvas.core.onnx.*
import java.io.File
import java.util.TreeMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.*

/** Feed uncropped decoded frames on one background worker, at about 4 Hz. Hosts own decoding only. */
class SmartFrameReframer(
    private val tracker: SubjectDetector = OnnxPeopleDetector(),
    private val sourceAspect: Float = 16f / 9f,
    private val targetAspect: Float = 9f / 16f,
    private val videoId: String = "",
    cacheDirectory: File? = null,
) : AutoCloseable {
    private val camera = SubjectCamera(sourceAspect, targetAspect)
    private val cuts = SceneCutDetector()
    private val samples = TreeMap<Double, CropKeyframe>()
    private var lastTime = Double.NaN
    private var thumbnail: FloatArray? = null
    private val cacheFile = cacheDirectory?.takeIf { videoId.matches(Regex("[A-Za-z0-9_-]{11}")) }
        ?.resolve("framing-yunet2026-yolox01-v1/${videoId}_${(sourceAspect * 1000).toInt()}_${(targetAspect * 1000).toInt()}.json")
    init {
        runCatching { cacheFile?.takeIf { it.isFile && it.length() < 1_000_000 }?.readText()?.let {
            Json.decodeFromString<CropTrajectory>(it).keyframes.forEach { key -> samples[key.t] = key }
        } }
    }

    fun analyze(argbPixels: IntArray, width: Int, height: Int, timestampSec: Double): FramingDecision {
        require(width > 0 && height > 0 && argbPixels.size >= width * height && timestampSec.isFinite())
        val cached = samples.floorEntry(timestampSec)?.value
        if (cached != null && timestampSec - cached.t < .12) {
            lastTime = timestampSec
            return FramingDecision(cached.cx, cached.cy, cached.fitFullFrame, cached.isSceneCut)
        }
        val histogram = FloatArray(16)
        val small = FloatArray(16 * 9)
        for (y in 0 until 9) for (x in 0 until 16) {
            val pixel = argbPixels[((y + .5f) * height / 9).toInt().coerceAtMost(height - 1) * width +
                ((x + .5f) * width / 16).toInt().coerceAtMost(width - 1)]
            val luma = (((pixel ushr 16) and 255) * .299f + ((pixel ushr 8) and 255) * .587f + (pixel and 255) * .114f) / 255
            small[y * 16 + x] = luma
            histogram[(luma * 15).toInt().coerceIn(0, 15)] += 1f / small.size
        }
        val seek = !lastTime.isFinite() || timestampSec < lastTime || timestampSec - lastTime > 1.5
        if (seek) cuts.reset()
        val visualCut = cuts.checkVisualDelta(histogram) ||
            (!seek && thumbnail?.let { old -> small.indices.sumOf { abs(small[it] - old[it]).toDouble() } / small.size > .3 } == true)
        thumbnail = small
        lastTime = timestampSec
        val subjects = tracker.detect(argbPixels, width, height)
        val frame = camera.update(subjects.faces, subjects.people, timestampSec, seek || visualCut)
        samples[timestampSec] = CropKeyframe(timestampSec, frame.cx, frame.cy, frame.sceneCut, frame.fitFullFrame)
        while (samples.size > 12_000) samples.pollFirstEntry()
        return frame
    }

    fun trajectory(): CropTrajectory = CropTrajectory(videoId, samples.values.toList())

    override fun close() {
        try {
            runCatching {
                cacheFile?.let { file ->
                    file.parentFile.mkdirs()
                    val temporary = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.tmp")
                    try {
                        temporary.writeText(Json.encodeToString(trajectory()))
                        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { temporary.delete() }
                }
            }
        } finally { tracker.close() }
    }
}
