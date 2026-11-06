package com.cosmotune.design

/**
 * The ways Now Playing can show a song's video (the Orbit board, section 4). Nothing sits on the video in any of them.
 *  - [WHOLE]: the video at its own shape, never cut, with a soft glow of its colours in the space around it (the default);
 *  - [FILL]: cut to fill the frame it is given;
 *  - [CINEMA]: the whole window (on the phone, the phone on its side); the song card floats at the foot and hides;
 *  - [MINI]: a small floating video over the app (not built yet: the menu shows it as coming).
 * Only [WHOLE] and [FILL] are kept between visits.
 */
enum class VideoView {
    WHOLE, FILL, CINEMA, MINI;

    companion object {
        /** The kept view for a stored [ordinal]: Fill when it says Fill, Whole otherwise. */
        fun kept(ordinal: Int): VideoView = if (ordinal == FILL.ordinal) FILL else WHOLE

        /** The aspect a video is given before its own is known: most music videos are 16:9. */
        const val DEFAULT_ASPECT = 16f / 9f

        /**
         * The frame of a video of [aspect] (width / height) shown whole in a [width] x [height] box: as large as fits, centred.
         * Returns (x, y, w, h). A box or an aspect that is not positive gives the whole box.
         */
        fun whole(width: Float, height: Float, aspect: Float): FloatArray {
            if (width <= 0f || height <= 0f || !(aspect > 0f) || !aspect.isFinite()) return floatArrayOf(0f, 0f, maxOf(width, 0f), maxOf(height, 0f))
            val w = minOf(width, height * aspect)
            val h = w / aspect
            return floatArrayOf((width - w) / 2f, (height - h) / 2f, w, h)
        }
    }
}
