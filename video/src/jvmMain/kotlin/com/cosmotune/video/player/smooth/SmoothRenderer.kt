package com.cosmotune.video.player.smooth

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import kotlin.math.roundToInt

/**
 * The two ways to make a frame between two source frames, drawn with Skia (so on the GPU wherever Compose draws there):
 *
 * - [drawBlend]: the older frame, then the newer one over it at a weight. Two textured quads.
 * - [drawWarp]: one pass of an SkSL shader. For every output pixel it reads the motion of that spot from a small image (one pixel
 *   per 8 x 8 block of the analysis picture: red and green are the move, blue how far it is trusted), samples the older frame
 *   a share `phase` of the move backwards and the newer frame the rest of the move forwards, and mixes the two by `phase`.
 *   Where the two samples disagree (something was covered or uncovered) the nearer frame is taken alone, so nothing ghosts.
 *
 * Everything is in destination pixels: [dest] is where the whole frame goes, and the caller clips to the box. The shaders of the
 * images drawn are kept while the same images go to the same place, so a draw makes one new shader (the effect's) and a paint.
 */
internal class SmoothRenderer : AutoCloseable {
    private val effect: RuntimeEffect = RuntimeEffect.makeForShader(SKSL)
    private val builder = RuntimeShaderBuilder(effect)
    private val fill = Paint()
    private val older = Paint()
    private val newer = Paint()

    private var olderImage: Image? = null
    private var olderShader: Shader? = null
    private var newerImage: Image? = null
    private var newerShader: Shader? = null
    private var motionImage: Image? = null
    private var motionShader: Shader? = null
    private var placedFor: Rect? = null

    /** The frame [image] alone, filling [dest]. */
    fun drawSingle(canvas: Canvas, image: Image, dest: Rect) {
        canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), dest, SamplingMode.LINEAR, older.apply { alpha = 255 }, true)
    }

    /** [older] under [newer] at [weight] (0 to 1, the newer one's share). */
    fun drawBlend(canvas: Canvas, olderFrame: Image, newerFrame: Image, dest: Rect, weight: Float) {
        if (weight >= 0.995f) return drawSingle(canvas, newerFrame, dest)
        if (weight <= 0.005f) return drawSingle(canvas, olderFrame, dest)
        drawSingle(canvas, olderFrame, dest)
        newer.alpha = (weight * 255f).roundToInt().coerceIn(0, 255)
        canvas.drawImageRect(newerFrame, Rect.makeWH(newerFrame.width.toFloat(), newerFrame.height.toFloat()), dest, SamplingMode.LINEAR, newer, true)
    }

    /**
     * The frame at [phase] (0 is [olderFrame], 1 is [newerFrame]) with each block moved along [motion]. [motionRange] is the
     * number the field's red and green were scaled by ([MotionFieldCodec.range]); [analysisWidth] x [analysisHeight] the picture
     * the motion was measured on; [mismatchBias] how hard a disagreement is resolved towards the nearer frame.
     */
    fun drawWarp(
        canvas: Canvas, olderFrame: Image, newerFrame: Image, motion: Image, dest: Rect, phase: Float,
        motionRange: Float, analysisWidth: Int, analysisHeight: Int, mismatchBias: Float,
    ) {
        place(olderFrame, newerFrame, motion, dest)
        builder.child("olderFrame", olderShader!!)
        builder.child("newerFrame", newerShader!!)
        builder.child("motion", motionShader!!)
        builder.uniform("phase", phase.coerceIn(0f, 1f))
        builder.uniform("stride", dest.width / analysisWidth, dest.height / analysisHeight)
        builder.uniform("motionRange", motionRange)
        builder.uniform("bias", mismatchBias.coerceIn(0f, 1f))
        val shader = builder.makeShader(null)
        fill.shader = shader
        canvas.drawRect(dest, fill)
        fill.shader = null
        shader.close()
    }

    /** Remakes the three image shaders when an image or the place changed. */
    private fun place(olderFrame: Image, newerFrame: Image, motion: Image, dest: Rect) {
        val moved = placedFor?.let { it.left != dest.left || it.top != dest.top || it.right != dest.right || it.bottom != dest.bottom } != false
        if (moved || olderImage !== olderFrame) {
            olderShader?.close()
            olderShader = shaderOf(olderFrame, dest, SamplingMode.LINEAR)
            olderImage = olderFrame
        }
        if (moved || newerImage !== newerFrame) {
            newerShader?.close()
            newerShader = shaderOf(newerFrame, dest, SamplingMode.LINEAR)
            newerImage = newerFrame
        }
        if (moved || motionImage !== motion) {
            motionShader?.close()
            motionShader = shaderOf(motion, dest, SamplingMode.LINEAR)
            motionImage = motion
        }
        placedFor = dest
    }

    private fun shaderOf(image: Image, dest: Rect, sampling: SamplingMode): Shader {
        val toDest = Matrix33.makeTranslate(dest.left, dest.top).makeConcat(Matrix33.makeScale(dest.width / image.width, dest.height / image.height))
        return image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, toDest)
    }

    override fun close() {
        olderShader?.close(); olderShader = null
        newerShader?.close(); newerShader = null
        motionShader?.close(); motionShader = null
        olderImage = null; newerImage = null; motionImage = null
        builder.close()
        effect.close()
        fill.close(); older.close(); newer.close()
    }

    companion object {
        /**
         * Where the whole of an image of [imageWidth] x [imageHeight] goes when its part [src] is drawn into [dst] (the plain
         * frame's placement, whatever it is: fit, fill, or a chosen aspect). A cropped part gives a rectangle larger than the box,
         * which the caller clips.
         */
        fun wholeImage(src: Rect, dst: Rect, imageWidth: Int, imageHeight: Int): Rect {
            val sx = dst.width / src.width
            val sy = dst.height / src.height
            return Rect.makeXYWH(dst.left - src.left * sx, dst.top - src.top * sy, imageWidth * sx, imageHeight * sy)
        }

        /**
         * The shader. `motion` is a [MotionFieldCodec] image: red and green are the move of the block in analysis pixels, scaled
         * so that a byte of 128 is none and 255 is `motionRange`; blue is its confidence. A move is applied only as far as it is
         * trusted.
         */
        private const val SKSL = """
            uniform shader olderFrame;
            uniform shader newerFrame;
            uniform shader motion;
            uniform float phase;
            uniform float2 stride;
            uniform float motionRange;
            uniform float bias;

            half4 main(float2 xy) {
                half4 m = motion.eval(xy);
                float2 v = (float2(m.r, m.g) - 128.0 / 255.0) * (255.0 / 127.0) * motionRange;
                v = v * stride * m.b;
                half4 a = olderFrame.eval(xy - v * phase);
                half4 b = newerFrame.eval(xy + v * (1.0 - phase));
                float miss = smoothstep(0.08, 0.30, dot(abs(a.rgb - b.rgb), half3(0.299, 0.587, 0.114)));
                float nearest = phase < 0.5 ? 0.0 : 1.0;
                float w = mix(phase, nearest, miss * bias);
                return half4(mix(a.rgb, b.rgb, half(w)), 1.0);
            }
        """
    }
}

/**
 * Packs a [MotionField] into the small image [SmoothRenderer.drawWarp] reads: one pixel per block, red and green the move
 * (128 is none, the ends are plus and minus [range] analysis pixels), blue the confidence. The byte array is reused; the
 * [Image] made from it is a copy, one per pair.
 */
internal class MotionFieldCodec(private val columns: Int, private val rows: Int, val range: Float) {
    private val bytes = ByteArray(columns * rows * 4)
    private val info = ImageInfo(columns, rows, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)

    fun toImage(field: MotionField): Image {
        var o = 0
        for (i in 0 until columns * rows) {
            bytes[o++] = encode(field.vx[i]).toByte()
            bytes[o++] = encode(field.vy[i]).toByte()
            bytes[o++] = (field.confidence[i].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            bytes[o++] = 255.toByte()
        }
        return Image.makeRaster(info, bytes, columns * 4)
    }

    private fun encode(move: Float): Int = 128 + (move / range).coerceIn(-1f, 1f).times(127f).roundToInt()
}
