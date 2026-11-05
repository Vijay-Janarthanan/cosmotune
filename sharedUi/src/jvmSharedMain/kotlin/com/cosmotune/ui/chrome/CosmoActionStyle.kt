package com.cosmotune.ui.chrome

import androidx.compose.runtime.compositionLocalOf

/**
 * How the icon buttons of a bar's actions slot are drawn. The phone's top bar composes its page actions (update, history, sort,
 * share, downloads) under [GLASS]: they are Material `IconButton`s of the old screens, and the bridge's `IconButton`
 * (`com.cosmotune.ui.bridge`) turns each into a glass circle next to the glass avatar, 40 dp with a 48 dp touch target. Null where
 * nothing provides a style (everywhere else): the bridge is then Material's own button.
 */
enum class CosmoActionStyle { GLASS }

val LocalCosmoActionStyle = compositionLocalOf<CosmoActionStyle?> { null }
