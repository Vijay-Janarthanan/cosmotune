package com.cosmotune.music.data.replay

/**
 * The timing of a Replay story video: each of [cards] is shown for [holdMs], growing very slightly as it is, then slides
 * off to the left as the next comes in from the right over [slideMs]. The last card stays. Story bars along the top fill
 * card by card. Both the phone and the desktop draw their frames from [at], so the two videos move the same way.
 */
class ReplayReel(val cards: Int, val holdMs: Long = 3_200, val slideMs: Long = 450) {

    init { require(cards > 0) { "A reel needs at least one card" } }

    /** One moment of the reel: the [card] on screen, the [next] one sliding in (or null), how far ([slide], 0..1). */
    data class Moment(val card: Int, val next: Int?, val slide: Float, val zoom: Float, val progress: List<Float>)

    val durationMs: Long = cards * holdMs + (cards - 1) * slideMs

    fun frameCount(fps: Int): Int = (durationMs * fps / 1_000).toInt().coerceAtLeast(1)

    fun at(timeMs: Long): Moment {
        val t = timeMs.coerceIn(0, durationMs)
        val step = holdMs + slideMs
        val card = (t / step).toInt().coerceAtMost(cards - 1)
        val into = t - card * step
        val held = into.coerceAtMost(holdMs)
        val zoom = 1f + GROWTH * (held.toFloat() / holdMs)
        val sliding = card < cards - 1 && into > holdMs
        val slide = if (sliding) ease((into - holdMs).toFloat() / slideMs) else 0f
        val progress = List(cards) { i ->
            when {
                i < card -> 1f
                i > card -> 0f
                else -> held.toFloat() / holdMs
            }
        }
        return Moment(card, if (sliding) card + 1 else null, slide, zoom, progress)
    }

    companion object {
        /** How much a card grows while it is shown (4 %). */
        const val GROWTH = 0.04f

        /** Where the top song starts playing under a reel of [reelMs]: about a third in, or 45 s when its length is unknown. */
        fun clipStartMs(songMs: Long, reelMs: Long): Long = when {
            songMs <= 0 -> 45_000
            else -> (songMs / 3).coerceAtMost((songMs - reelMs).coerceAtLeast(0))
        }

        private fun ease(x: Float): Float {
            val v = x.coerceIn(0f, 1f)
            return v * v * (3 - 2 * v)
        }
    }
}
