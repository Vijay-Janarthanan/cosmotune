package com.cosmotune.watch

import kotlinx.serialization.Serializable

/** The text colours subtitles can take, as ARGB. */
enum class SubtitleColour(val label: String, val argb: Long) {
    WHITE("White", 0xFFFFFFFF), YELLOW("Yellow", 0xFFFFE14D), CYAN("Cyan", 0xFF7FE7FF), GREEN("Green", 0xFF9BF59B), PINK("Pink", 0xFFFFB3D9),
}

/** What sits behind the words: nothing (a soft shadow keeps them readable), a box, or a band the width of the picture. */
enum class SubtitleBackground(val label: String) { NONE("None"), BOX("Box"), BAND("Band") }

/** The face subtitles are set in: the app's own, a serif, or a fixed-width one. */
/** The face subtitles are set in: the app's own, a serif, a fixed-width one, or a font file the viewer imported. */
enum class SubtitleFont(val label: String) { APP("App font"), SERIF("Serif"), MONO("Typewriter"), YOURS("Your font") }

/**
 * How Watch draws subtitles (the player draws them from the engine's cues, in every layout and on the TV): size as a percentage of
 * the normal size, colour, what is behind them and how strong, an outline, bold, the face, and how high above the foot of the picture
 * they sit, as a percentage of its height. Kept per viewer with the other Watch preferences.
 */
@Serializable
data class WatchSubtitleStyle(
    val sizePercent: Int = 100,
    val colour: SubtitleColour = SubtitleColour.WHITE,
    val background: SubtitleBackground = SubtitleBackground.BOX,
    val backgroundPercent: Int = 60,
    val outline: Boolean = false,
    val bold: Boolean = false,
    val font: SubtitleFont = SubtitleFont.APP,
    val liftPercent: Int = 8,
    /** The imported font file (TrueType or OpenType) kept on this device, and its name, when [font] is [SubtitleFont.YOURS]. */
    val fontPath: String? = null,
    val fontName: String? = null,
) {
    /** The same style with every number inside its range, for a file written by an older or edited copy. */
    fun coerced(): WatchSubtitleStyle = copy(
        sizePercent = sizePercent.coerceIn(SIZES.first(), SIZES.last()),
        backgroundPercent = backgroundPercent.coerceIn(BACKGROUNDS.first(), BACKGROUNDS.last()),
        liftPercent = liftPercent.coerceIn(LIFTS.first(), LIFTS.last()),
        font = if (font == SubtitleFont.YOURS && fontPath == null) SubtitleFont.APP else font,
    )

    /** The text size for a picture [pictureHeightDp] high: 4.6 % of the height at 100 % (between 14 and 34), times the size. */
    fun textSize(pictureHeightDp: Float): Float = (pictureHeightDp * 0.046f).coerceIn(14f, 34f) * coerced().sizePercent / 100f

    companion object {
        val SIZES = listOf(75, 100, 125, 150, 200)
        val BACKGROUNDS = listOf(20, 40, 60, 80, 100)
        val LIFTS = listOf(2, 8, 15, 25, 40)

        /** The step after [value] in [steps], wrapping round: the editor's buttons. */
        fun next(steps: List<Int>, value: Int, by: Int): Int {
            val index = steps.indexOfFirst { it >= value }.let { if (it < 0) steps.lastIndex else it }
            return steps[(index + by).coerceIn(0, steps.lastIndex)]
        }
    }
}
