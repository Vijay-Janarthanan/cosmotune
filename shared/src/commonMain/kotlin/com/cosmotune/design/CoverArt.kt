package com.cosmotune.design

import com.cosmotune.music.data.model.CARD_ART_PX
import com.cosmotune.music.data.model.HEADER_ART_PX
import com.cosmotune.music.data.model.PLAYER_ART_PX
import com.cosmotune.music.data.model.ROW_ART_PX
import kotlin.math.ceil

/**
 * The few picture sizes a cover asks the image cache for, so a list row, a card and the player share entries instead of each
 * fetching its own: upstream's ladder (`Models.kt`, row 160, card 480, header 720, player 1200) plus 320 between the first two.
 */
object CoverArt {
    val LADDER: List<Int> = listOf(ROW_ART_PX, 320, CARD_ART_PX, HEADER_ART_PX, PLAYER_ART_PX)

    /** The smallest rung that holds a square [sizeDp] wide at [density] pixels per dp; the largest rung for anything bigger. */
    fun px(sizeDp: Float, density: Float): Int {
        if (sizeDp <= 0f || density <= 0f) return LADDER.first()
        val needed = ceil(sizeDp * density).toInt()
        return LADDER.firstOrNull { it >= needed } ?: LADDER.last()
    }
}
