package com.opencanvas.core.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.opencanvas.core.models.DetectionBox
import java.nio.FloatBuffer
import kotlin.math.*

data class Subjects(val faces: List<DetectionBox>, val people: List<DetectionBox>)

interface SubjectDetector : AutoCloseable {
    fun detect(pixels: IntArray, width: Int, height: Int): Subjects
}

/** YuNet faces plus YOLOX-Nano people. Sessions are owned by one analysis worker. */
class OnnxPeopleDetector : SubjectDetector {
    private val faces = DetectorModel("/models/yunet.onnx", 320, bgr = true, normalized = false)
    private val people = try { DetectorModel("/models/yolox-nano.onnx", 416, bgr = true, normalized = false) }
        catch (failure: Throwable) { faces.close(); throw failure }

    override fun detect(pixels: IntArray, width: Int, height: Int): Subjects {
        require(width > 0 && height > 0 && pixels.size >= width * height)
        val faceBoxes = faces.run(pixels, width, height) { output, scale, padX, padY ->
            val boxes = mutableListOf<DetectionBox>()
            for (stride in listOf(8, 16, 32)) {
                val cls = output.floats("cls_$stride")
                val obj = output.floats("obj_$stride")
                val loc = output.floats("bbox_$stride")
                val columns = 320 / stride
                for (i in cls.indices) {
                    val score = sqrt(cls[i].coerceIn(0f, 1f) * obj[i].coerceIn(0f, 1f))
                    if (score < .6f) continue
                    val cx = (i % columns + loc[i * 4]) * stride
                    val cy = (i / columns + loc[i * 4 + 1]) * stride
                    val bw = exp(loc[i * 4 + 2]) * stride
                    val bh = exp(loc[i * 4 + 3]) * stride
                    boxes += box(cx, cy, bw, bh, score, scale, padX, padY, width, height)
                }
            }
            nms(boxes)
        }
        val personBoxes = people.run(pixels, width, height) { output, scale, padX, padY ->
            val values = (output[0] as OnnxTensor).floatBuffer.let { buffer -> FloatArray(buffer.remaining()).also(buffer::get) }
            val boxes = mutableListOf<DetectionBox>()
            var anchor = 0
            for (stride in listOf(8, 16, 32)) {
                val columns = 416 / stride
                repeat(columns * columns) { i ->
                    val at = anchor++ * 85
                    val score = values[at + 4] * values[at + 5] // COCO class 0: person.
                    if (score >= .5f) {
                        val cx = (values[at] + i % columns) * stride
                        val cy = (values[at + 1] + i / columns) * stride
                        val bw = exp(values[at + 2]) * stride
                        val bh = exp(values[at + 3]) * stride
                        boxes += box(cx, cy, bw, bh, score, scale, padX, padY, width, height)
                    }
                }
            }
            nms(boxes)
        }
        return Subjects(faceBoxes, personBoxes)
    }

    override fun close() { try { faces.close() } finally { people.close() } }

    private fun box(cx: Float, cy: Float, width: Float, height: Float, confidence: Float, scale: Float,
                    padX: Float, padY: Float, sourceW: Int, sourceH: Int) = DetectionBox(
        ((cx - width / 2 - padX) / scale / sourceW).coerceIn(0f, 1f),
        ((cy - height / 2 - padY) / scale / sourceH).coerceIn(0f, 1f),
        ((cx + width / 2 - padX) / scale / sourceW).coerceIn(0f, 1f),
        ((cy + height / 2 - padY) / scale / sourceH).coerceIn(0f, 1f), confidence)

    private fun nms(boxes: List<DetectionBox>): List<DetectionBox> {
        val kept = mutableListOf<DetectionBox>()
        for (box in boxes.filter { it.area > 0 }.sortedByDescending { it.confidence }) {
            if (kept.none { it.iou(box) > .4f }) kept += box
        }
        return kept
    }
}

private fun OrtSession.Result.floats(name: String): FloatArray =
    (get(name).get() as OnnxTensor).floatBuffer.let { buffer -> FloatArray(buffer.remaining()).also(buffer::get) }

private class DetectorModel(path: String, private val size: Int, private val bgr: Boolean, private val normalized: Boolean) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        val bytes = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing detector $path" }.use { it.readBytes() }
        environment.createSession(bytes, options)
    }

    fun <T> run(pixels: IntArray, width: Int, height: Int, parse: (OrtSession.Result, Float, Float, Float) -> T): T {
        val scale = min(size.toFloat() / width, size.toFloat() / height)
        val scaledW = (width * scale).roundToInt()
        val scaledH = (height * scale).roundToInt()
        // YOLOX's reference preprocessing pads on the bottom/right; YuNet uses the same transform.
        val data = FloatArray(size * size * 3) { 114f }
        for (y in 0 until scaledH) for (x in 0 until scaledW) {
            val pixel = pixels[(y / scale).toInt().coerceAtMost(height - 1) * width + (x / scale).toInt().coerceAtMost(width - 1)]
            for (channel in 0..2) {
                val shift = if (bgr) channel * 8 else (2 - channel) * 8
                val value = ((pixel ushr shift) and 255).toFloat()
                data[channel * size * size + y * size + x] = if (normalized) (value - 127) / 128 else value
            }
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { parse(it, scale, 0f, 0f) }
        }
    }
    override fun close() = session.close()
}
