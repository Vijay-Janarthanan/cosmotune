package com.cosmotune.ui.room

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.IconBuilder

private fun icon(name: String, block: IconBuilder.() -> Unit): Lazy<ImageVector> = lazy {
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).also { IconBuilder(it).block() }.build()
}

private const val MIC_HEAD = "M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3z"
private const val HEADPHONES = "M4.5 17v-4a7.5 7.5 0 0 1 15 0v4"
private const val SPEAKER = "M4 9.5h3l4.5-4v13L7 14.5H4z"

/**
 * The room's own glyphs, drawn on the kit's 24 grid at its 2 px round stroke so they sit beside [com.cosmotune.ui.components.CosmoIcons]
 * without looking borrowed. They stay here until another screen needs them; then they move into the kit's set.
 */
internal object RoomIcons {
    val Mic by icon("room-mic") { stroke(MIC_HEAD); stroke("M5.5 11.5a6.5 6.5 0 0 0 13 0"); stroke("M12 18v2.5") }
    val MicOff by icon("room-mic-off") { stroke(MIC_HEAD); stroke("M5.5 11.5a6.5 6.5 0 0 0 13 0"); stroke("M12 18v2.5"); stroke("M4 4l16 16") }
    val Headphones by icon("room-headphones") { stroke(HEADPHONES); rect(3.5f, 13.5f, 4f, 6.5f, 1.5f); rect(16.5f, 13.5f, 4f, 6.5f, 1.5f) }
    val HeadphonesOff by icon("room-headphones-off") {
        stroke(HEADPHONES); rect(3.5f, 13.5f, 4f, 6.5f, 1.5f); rect(16.5f, 13.5f, 4f, 6.5f, 1.5f); stroke("M4 4l16 16")
    }
    /** A speaker with a cross: a person muted for this device only (their own mic may still be live). */
    val MutedHere by icon("room-muted-here") { stroke(SPEAKER); stroke("m15.5 9.5 5 5"); stroke("m20.5 9.5-5 5") }
    val Volume by icon("room-volume") { stroke(SPEAKER); stroke("M15.5 9a4 4 0 0 1 0 6"); stroke("M18 6.5a7.5 7.5 0 0 1 0 11") }
    val Send by icon("room-send") { stroke("M4.5 11.5 19.5 4.5 13 19.5l-2.2-6.3z"); stroke("m10.8 13.2 8.7-8.7") }
    val Copy by icon("room-copy") {
        rect(8.5f, 8.5f, 11f, 11f, 2f)
        stroke("M15.5 8.5V6a1.5 1.5 0 0 0-1.5-1.5H6A1.5 1.5 0 0 0 4.5 6v8A1.5 1.5 0 0 0 6 15.5h2.5")
    }
    val Leave by icon("room-leave") {
        stroke("M13.5 4.5H6.5A1.5 1.5 0 0 0 5 6v12a1.5 1.5 0 0 0 1.5 1.5h7"); stroke("M10 12h10"); stroke("m16.5 8.5 3.5 3.5-3.5 3.5")
    }
    val RemovePerson by icon("room-remove-person") { circle(10f, 8.5f, 3.5f); stroke("M4 19.5a6 6 0 0 1 12 0"); stroke("M16.5 11.5h5") }
    /** The room itself: one screen and two people in front of it, for the dock and for "Play in room". */
    val Room by icon("room") {
        rect(3.5f, 3f, 17f, 9.5f, 2f)
        circle(8.25f, 15.5f, 1.9f); circle(15.75f, 15.5f, 1.9f)
        stroke("M4.5 21a3.75 3.75 0 0 1 7.5 0"); stroke("M12 21a3.75 3.75 0 0 1 7.5 0")
    }
    val Chat by icon("room-chat") {
        stroke("M5 5.5h14A1.5 1.5 0 0 1 20.5 7v8.5A1.5 1.5 0 0 1 19 17h-9l-4 3v-3H5a1.5 1.5 0 0 1-1.5-1.5V7A1.5 1.5 0 0 1 5 5.5z")
    }
}
