package com.opencanvas.core.sync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place radix-2 complex FFT of a fixed power-of-two [size], on split real/imaginary [DoubleArray]s.
 *
 * Both directions take and return natural order. [forward] computes `X[k] = sum x[n] e^(-2 pi i k n / size)`;
 * [inverse] undoes it, including the `1 / size` scale.
 */
internal class Fft(val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two >= 2, was $size" }
    }

    // e^(-2 pi i k / size) = cos - i sin, for k in 0 until size / 2
    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }

    private val reversed = IntArray(size).also { table ->
        val bits = size.countTrailingZeroBits()
        for (i in 1 until size) table[i] = (table[i shr 1] shr 1) or ((i and 1) shl (bits - 1))
    }

    /** Transforms [re]/[im] to the spectrum, in place. */
    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, direction = -1.0)

    /** Transforms a spectrum back to the signal, in place. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, direction = 1.0)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    /** Bit-reversal permutation, then radix-2 butterflies; [direction] is the sign of the exponent, -1 or +1. */
    private fun transform(re: DoubleArray, im: DoubleArray, direction: Double) {
        require(re.size == size && im.size == size) { "arrays must have $size entries" }
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var half = 1
        while (half < size) {
            val stride = size / (2 * half)
            for (block in 0 until size step 2 * half) {
                for (k in 0 until half) {
                    val wr = cosTable[k * stride]
                    val wi = direction * sinTable[k * stride]
                    val a = block + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            half = half shl 1
        }
    }
}

/**
 * Locates short probes inside one long signal by normalised cross-correlation (NCC).
 *
 * The spectrum and the sliding-window energies of the long signal are computed once, so each further
 * probe costs one forward and one inverse FFT. Everything is double precision: the maths mirrors
 * numpy's, so the peaks match the reference implementation to rounding.
 *
 * @param signal The long signal; needs at least 2 samples.
 * @param minLocalRms Slices of [signal] quieter than this RMS (after removing the signal's mean)
 *   score 0: their normalisation would only amplify noise.
 */
internal class VideoCorrelator(signal: FloatArray, private val minLocalRms: Double) {

    /** Number of samples in the long signal. */
    val size: Int = signal.size

    private val fft: Fft
    private val spectrumRe: DoubleArray
    private val spectrumIm: DoubleArray
    private val sumPrefix = DoubleArray(size + 1) // sumPrefix[n] = sum of the first n mean-removed samples
    private val squaresPrefix = DoubleArray(size + 1)
    private val workRe: DoubleArray
    private val workIm: DoubleArray

    init {
        require(size >= 2) { "signal must have at least 2 samples, had $size" }
        // c[k] = sum_n p[n] v[n + k] needs no wrap-around for valid k when the transform is at least as long as v
        var length = 2
        while (length < size) length = length shl 1
        fft = Fft(length)
        spectrumRe = DoubleArray(length)
        spectrumIm = DoubleArray(length)
        workRe = DoubleArray(length)
        workIm = DoubleArray(length)

        val mean = signal.sumOf { it.toDouble() } / size
        for (i in 0 until size) {
            val centred = signal[i] - mean
            spectrumRe[i] = centred
            sumPrefix[i + 1] = sumPrefix[i] + centred
            squaresPrefix[i + 1] = squaresPrefix[i] + centred * centred
        }
        fft.forward(spectrumRe, spectrumIm)
    }

    /**
     * NCC of `probe[start until start + length]` against every position of the signal: `curve[k]` is the
     * correlation when the first probe sample sits at sample `k` of the signal, in `-1..1`.
     *
     * @return `size - length + 1` values, or null when the probe is shorter than 2 samples, longer than
     *   the signal, or flat (it carries no information).
     */
    fun curve(probe: FloatArray, start: Int, length: Int): DoubleArray? {
        if (length < 2 || length > size) return null
        var sum = 0.0
        for (i in start until start + length) sum += probe[i]
        val mean = sum / length
        var squares = 0.0
        workRe.fill(0.0)
        workIm.fill(0.0)
        for (i in 0 until length) {
            val centred = probe[start + i] - mean
            workRe[i] = centred
            squares += centred * centred
        }
        val probeNorm = sqrt(squares)
        if (probeNorm < MIN_PROBE_NORM) return null

        fft.forward(workRe, workIm)
        for (k in 0 until fft.size) { // spectrum * conj(probe spectrum)
            val pr = workRe[k]
            val pi = workIm[k]
            workRe[k] = spectrumRe[k] * pr + spectrumIm[k] * pi
            workIm[k] = spectrumIm[k] * pr - spectrumRe[k] * pi
        }
        fft.inverse(workRe, workIm)

        val lags = size - length + 1
        val floor = length * minLocalRms * minLocalRms
        return DoubleArray(lags) { k ->
            val localSum = sumPrefix[k + length] - sumPrefix[k]
            // energy of the slice once its own mean is removed
            val energy = squaresPrefix[k + length] - squaresPrefix[k] - localSum * localSum / length
            if (energy > floor) min(1.0, max(-1.0, workRe[k] / (sqrt(energy) * probeNorm))) else 0.0
        }
    }

    private companion object {
        /** A probe whose mean-removed norm is below this is flat. */
        const val MIN_PROBE_NORM = 1e-9
    }
}
