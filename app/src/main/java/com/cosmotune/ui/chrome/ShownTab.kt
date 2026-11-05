package com.cosmotune.ui.chrome

import androidx.compose.runtime.MutableIntState
import kotlin.reflect.KProperty

/**
 * The delegate of the phone root's `selectedTab`: a read gives the tab that is *shown* ([shown] applied to the saved tab), a write
 * is the saved tab. The root's code keeps reading and writing `selectedTab` as before; only the read is passed through the rule (the
 * new look's Explore without a mood page is Search, `TabRedirect`), so no frame ever composes the page of a tab that has no door.
 * It holds no state of its own: [stored] is the `rememberSaveable` state, and reading it in composition subscribes the reader.
 */
class ShownTab(private val stored: MutableIntState, private val shown: (Int) -> Int) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = shown(stored.intValue)
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { stored.intValue = value }
}
