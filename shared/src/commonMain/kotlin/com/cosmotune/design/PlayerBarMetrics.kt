package com.cosmotune.design

/**
 * The desktop player capsule (`V2.deskPlayer`, `screens-v2.css:225-233`), in dp: the designer's numbers, and what the
 * capsule drops when its window leaves it less room than the board's 1440 dp frame did. The board draws one arrangement;
 * a window with the lyrics or queue column open leaves the capsule 376 dp less than a window without it, so the numbers of
 * the board are the [Tier.FULL] plan and the narrower plans take things away in a fixed order: the volume bar, then the
 * shuffle and repeat buttons (and a shorter title), then the scrubber and the like button. The lyrics and queue buttons are
 * in every plan: they are how the column is put away again.
 */
object PlayerBarMetrics {
    const val HEIGHT_DP = 80f
    const val RADIUS_DP = 28f
    const val PADDING_DP = 16f
    const val GAP_DP = 12f
    const val COVER_DP = 48f

    /** The cover's own corner radius in the bar (`CosmoRadii.coverDesktopBar`; a test keeps the two in step). */
    const val COVER_RADIUS_DP = 12f
    const val TEXT_MAX_DP = 220f
    const val BUTTON_DP = 40f
    const val PLAY_DP = 48f

    /** A 40 dp button reserves a 48 dp touch target, so it takes 48 dp of a row. */
    const val TOUCH_DP = 48f
    const val TRANSPORT_GAP_DP = 8f
    const val TIME_DP = 44f
    const val SCRUB_GAP_DP = 8f
    const val SCRUB_MAX_DP = 560f
    const val WAVE_HEIGHT_DP = 16f

    /** The least the scrubber's wave is given between its two times. */
    const val WAVE_MIN_DP = 80f
    const val VOLUME_DP = 92f
    const val VOLUME_MARGIN_DP = 6f

    /** The title column of the narrowest plan: the least it keeps, it takes what the row leaves. */
    const val MINIMAL_TEXT_DP = 96f

    /** From the board's full capsule to the narrowest one. */
    enum class Tier { FULL, MEDIUM, COMPACT, MINIMAL }

    /**
     * What a [tier] draws. [textDp] is the title column's width (in [Tier.MINIMAL] its least width: it takes what is left).
     * Without [scrubber] the play button stands alone where the middle column was.
     */
    data class Plan(
        val tier: Tier,
        val textDp: Float,
        val like: Boolean,
        val shuffleRepeat: Boolean,
        val scrubber: Boolean,
        val output: Boolean,
        val volume: Boolean,
    )

    private val full = Plan(Tier.FULL, TEXT_MAX_DP, like = true, shuffleRepeat = true, scrubber = true, output = true, volume = true)
    private val medium = Plan(Tier.MEDIUM, 160f, like = true, shuffleRepeat = true, scrubber = true, output = true, volume = false)
    private val compact = Plan(Tier.COMPACT, 120f, like = true, shuffleRepeat = false, scrubber = true, output = true, volume = false)
    private val minimal = Plan(Tier.MINIMAL, MINIMAL_TEXT_DP, like = false, shuffleRepeat = false, scrubber = false, output = false, volume = false)

    fun planFor(tier: Tier): Plan = when (tier) {
        Tier.FULL -> full
        Tier.MEDIUM -> medium
        Tier.COMPACT -> compact
        Tier.MINIMAL -> minimal
    }

    /** The richest plan that fits in a capsule [widthDp] wide; the narrowest one when none does. */
    fun plan(widthDp: Float): Plan {
        for (tier in Tier.entries) {
            val candidate = planFor(tier)
            if (requiredDp(candidate) <= widthDp) return candidate
        }
        return minimal
    }

    /**
     * The least width [plan] fits in: its padding, the title column and what goes with it, the middle column with its least
     * wave, the right group, and the gaps between the three. Buttons count at their 48 dp touch size.
     */
    fun requiredDp(plan: Plan): Float {
        val left = COVER_DP + GAP_DP + plan.textDp + if (plan.like) GAP_DP + TOUCH_DP else 0f
        val middle = if (plan.scrubber) {
            // Four 40 dp buttons round the play button with 4 dp more on each side of it, or two without shuffle and repeat.
            val transport = (if (plan.shuffleRepeat) 4 else 2) * TOUCH_DP + PLAY_DP + TRANSPORT_GAP_DP
            val scrub = 2 * TIME_DP + 2 * SCRUB_GAP_DP + WAVE_MIN_DP
            maxOf(transport, scrub)
        } else {
            PLAY_DP
        }
        val right = (if (plan.output) 3 else 2) * TOUCH_DP + if (plan.volume) VOLUME_MARGIN_DP + VOLUME_DP else 0f
        return 2 * PADDING_DP + left + GAP_DP + middle + GAP_DP + right
    }

    /**
     * What the bar looks like to the player that grows out of it ([CapsuleGeometry]), at [widthDp] wide in [plan]: its 28 dp
     * corners, the cover 16 dp in and centred in the 80 dp height, and the title column from the end of the cover's gap to the
     * column's own width (the plan's [Plan.textDp], or in [Tier.MINIMAL] what the row leaves: the right group of lyrics and
     * queue, the play button and two gaps).
     */
    fun capsuleGeometry(plan: Plan, widthDp: Float): CapsuleGeometry {
        val textStart = PADDING_DP + COVER_DP + GAP_DP
        val textEnd = if (plan.tier == Tier.MINIMAL) {
            PADDING_DP + 2 * TOUCH_DP + GAP_DP + PLAY_DP + GAP_DP
        } else {
            (widthDp - textStart - plan.textDp).coerceAtLeast(0f)
        }
        return CapsuleGeometry(
            cornerRadiusDp = RADIUS_DP,
            coverInsetLeftDp = PADDING_DP,
            coverInsetTopDp = (HEIGHT_DP - COVER_DP) / 2f,
            coverSizeDp = COVER_DP,
            coverRadiusDp = COVER_RADIUS_DP,
            textStartDp = textStart,
            textEndDp = textEnd,
        )
    }
}
