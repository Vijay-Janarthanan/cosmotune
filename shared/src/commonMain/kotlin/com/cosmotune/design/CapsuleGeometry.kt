package com.cosmotune.design

/**
 * What the player that grows out of a capsule starts from, in the capsule's own words: the card's corner radius, where the
 * capsule's cover sits (its inset from the capsule's left and top edges), its size and corner radius, and where the capsule's
 * title and artist begin and end (their distance from the capsule's left and right edges). All dp.
 *
 * Two capsules exist: the phone's mini player ([PHONE]) and the desktop's player bar
 * ([PlayerBarMetrics.capsuleGeometry]). Each publishes its own, with its title and artist, in the shell state while it is on
 * screen, and the overlay's grow ([Morph]) starts from that, so the first frame of the grow is the capsule to the pixel, whichever
 * capsule it is. A capsule that says nothing is taken for the phone's: [PHONE] is what the overlay always assumed.
 */
data class CapsuleGeometry(
    /** The card's corners at the start: the capsule's own, run down to square as the card grows. */
    val cornerRadiusDp: Float,
    /** The cover's distance from the capsule's left edge. */
    val coverInsetLeftDp: Float,
    /** The cover's distance from the capsule's top edge. */
    val coverInsetTopDp: Float,
    /** The cover's side. */
    val coverSizeDp: Float,
    /** The cover's own corner radius in the capsule. */
    val coverRadiusDp: Float,
    /** Where the title and artist start, from the capsule's left edge. */
    val textStartDp: Float,
    /** Where they end, from the capsule's right edge (what the capsule keeps to the right of its words). */
    val textEndDp: Float,
) {
    companion object {
        /**
         * The phone's mini capsule (`.mini2`): 22 dp corners; a 48 dp cover with 14 dp corners, 8 dp in and 8 dp down (8 dp of
         * padding in a 64 dp capsule); the words from 68 dp (padding, cover and a 12 dp gap) to 112 dp short of the right edge
         * (next, 48 dp with its touch target, 8 dp, play, 48 dp, and 8 dp of padding).
         */
        val PHONE = CapsuleGeometry(
            cornerRadiusDp = ShellMetrics.CAPSULE_RADIUS_DP,
            coverInsetLeftDp = 8f,
            coverInsetTopDp = 8f,
            coverSizeDp = 48f,
            coverRadiusDp = 14f,
            textStartDp = 68f,
            textEndDp = 112f,
        )
    }
}
