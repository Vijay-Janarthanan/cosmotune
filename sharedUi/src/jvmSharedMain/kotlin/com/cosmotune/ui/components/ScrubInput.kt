package com.cosmotune.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import com.cosmotune.design.SliderMetrics
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics

/**
 * Touch and mouse for a horizontal bar that means a fraction from 0 to 1 (the wavy scrubber, the thick slider): a tap jumps,
 * a horizontal drag follows the pointer (vertical movement is left to a scrolling parent). [onChange] gets the fraction as it
 * moves, [onFinish] is called when the pointer lets go (or the gesture is cancelled). A light tick plays at every tenth
 * crossed, and a "landed" beat at the end of a drag or a tap (`Haptic.Select`: "the end of a scrub").
 */
@Composable
internal fun Modifier.scrubGestures(onChange: (Float) -> Unit, onFinish: () -> Unit): Modifier {
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish)
    val haptics = rememberHaptics()
    return this
        .pointerInput(Unit) {
            var lastTick = -1
            detectHorizontalDragGestures(
                onDragStart = {
                    val f = SliderMetrics.valueAt(it.x, size.width.toFloat())
                    lastTick = SliderMetrics.tickOf(f)
                    change(f)
                },
                onDragEnd = { finish(); haptics.play(Haptic.Select) },
                onDragCancel = { finish() },
            ) { pointer, _ ->
                val f = SliderMetrics.valueAt(pointer.position.x, size.width.toFloat())
                val tick = SliderMetrics.tickOf(f)
                if (tick != lastTick) { lastTick = tick; haptics.play(Haptic.Tick) }
                change(f)
            }
        }
        .pointerInput(Unit) {
            detectTapGestures {
                change(SliderMetrics.valueAt(it.x, size.width.toFloat()))
                finish()
                haptics.play(Haptic.Select)
            }
        }
}

/**
 * Keyboard for the same bar (the desktop; a switch-access user on the phone): the arrow keys move one hundredth, Shift with an
 * arrow or Page Up and Down one tenth, Home and End go to the ends. [value] is the current fraction; every key is one change
 * and one finish. Put it before `focusable()` in the chain.
 */
@Composable
internal fun Modifier.scrubKeys(value: () -> Float, onChange: (Float) -> Unit, onFinish: () -> Unit): Modifier {
    val read by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish)
    return onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val next = when (event.key) {
            Key.DirectionRight, Key.DirectionUp -> SliderMetrics.stepped(read(), 1, event.isShiftPressed)
            Key.DirectionLeft, Key.DirectionDown -> SliderMetrics.stepped(read(), -1, event.isShiftPressed)
            Key.PageUp -> SliderMetrics.stepped(read(), 1, big = true)
            Key.PageDown -> SliderMetrics.stepped(read(), -1, big = true)
            Key.MoveHome -> 0f
            Key.MoveEnd -> 1f
            else -> return@onKeyEvent false
        }
        change(next)
        finish()
        true
    }
}
