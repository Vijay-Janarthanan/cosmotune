package com.cosmotune.listening

/**
 * Keyboard shortcuts that reach Cosmotune while another app has the keyboard (the idea from th-ch's YouTube Music, see
 * NOTICE.md): Ctrl+Alt with a key, so they do not collide with an app's own shortcuts. The desktop registers them with the
 * system while Settings has them on.
 */
object GlobalShortcuts {
    enum class Action(val label: String) {
        PLAY_PAUSE("Play or pause"), NEXT("Next song"), PREVIOUS("Previous song"),
        VOLUME_UP("Volume up"), VOLUME_DOWN("Volume down"), LIKE("Like the song"),
    }

    /** A key with Ctrl+Alt: [keyName] as the listener reads it, [windowsKey] the Windows virtual-key code. */
    data class Binding(val action: Action, val keyName: String, val windowsKey: Int) {
        val label: String get() = "Ctrl+Alt+$keyName"
    }

    val BINDINGS = listOf(
        Binding(Action.PLAY_PAUSE, "Space", 0x20),
        Binding(Action.NEXT, "Right", 0x27),
        Binding(Action.PREVIOUS, "Left", 0x25),
        Binding(Action.VOLUME_UP, "Up", 0x26),
        Binding(Action.VOLUME_DOWN, "Down", 0x28),
        Binding(Action.LIKE, "L", 0x4C),
    )

    /** One step of the volume keys. */
    const val VOLUME_STEP = 0.05f

    /** The volume after a volume key, kept between silence and full. */
    fun stepVolume(volume: Float, action: Action): Float = when (action) {
        Action.VOLUME_UP -> (volume + VOLUME_STEP).coerceAtMost(1f)
        Action.VOLUME_DOWN -> (volume - VOLUME_STEP).coerceAtLeast(0f)
        else -> volume
    }.let { (it * 100).let(Math::round) / 100f }

    /** The settings line: every shortcut with what it does. */
    fun summary(): String = BINDINGS.joinToString(" · ") { "${it.label} ${it.action.label.lowercase()}" }
}
