package com.cosmotune.video.player

/** The remote's buttons the player leaves free for the listener (the arrows and OK keep moving through the controls). */
enum class RemoteButton { CHANNEL_UP, CHANNEL_DOWN, RED, GREEN, YELLOW, BLUE, FAST_FORWARD, REWIND }

/** What one of them does while a video plays. */
enum class RemoteAction { NONE, PLAY_PAUSE, SKIP_FORWARD, SKIP_BACK, NEXT_CHAPTER, PREVIOUS_CHAPTER, SPEED_UP, SPEED_DOWN, CAPTIONS, NEXT_VIDEO, PREVIOUS_VIDEO }

/** The listener's mapping of [RemoteButton]s (Settings, Playback, Remote buttons), stored by name. */
object RemoteButtons {
    /** The mapping before the listener changes anything: fast forward and rewind skip by the double-tap step. */
    val DEFAULTS: Map<RemoteButton, RemoteAction> = mapOf(RemoteButton.FAST_FORWARD to RemoteAction.SKIP_FORWARD, RemoteButton.REWIND to RemoteAction.SKIP_BACK)

    fun actionOf(stored: Map<String, String>, button: RemoteButton): RemoteAction =
        stored[button.name]?.let { name -> RemoteAction.entries.firstOrNull { it.name == name } } ?: DEFAULTS[button] ?: RemoteAction.NONE

    /** [stored] with [button] set to [action] (a default is not written down). */
    fun with(stored: Map<String, String>, button: RemoteButton, action: RemoteAction): Map<String, String> =
        if ((DEFAULTS[button] ?: RemoteAction.NONE) == action) stored - button.name else stored + (button.name to action.name)

    /** The player's key action for [action], skipping by [stepSeconds]; null for none. */
    fun keyAction(action: RemoteAction, stepSeconds: Int): PlayerKeyAction? = when (action) {
        RemoteAction.NONE -> null
        RemoteAction.PLAY_PAUSE -> PlayerKeyAction.TogglePlay
        RemoteAction.SKIP_FORWARD -> PlayerKeyAction.SeekBy(stepSeconds * 1000L)
        RemoteAction.SKIP_BACK -> PlayerKeyAction.SeekBy(-stepSeconds * 1000L)
        RemoteAction.NEXT_CHAPTER -> PlayerKeyAction.NextChapter
        RemoteAction.PREVIOUS_CHAPTER -> PlayerKeyAction.PreviousChapter
        RemoteAction.SPEED_UP -> PlayerKeyAction.Speed(PlayerKeyMap.SPEED_STEP)
        RemoteAction.SPEED_DOWN -> PlayerKeyAction.Speed(-PlayerKeyMap.SPEED_STEP)
        RemoteAction.CAPTIONS -> PlayerKeyAction.Captions
        RemoteAction.NEXT_VIDEO -> PlayerKeyAction.Next
        RemoteAction.PREVIOUS_VIDEO -> PlayerKeyAction.Previous
    }
}
