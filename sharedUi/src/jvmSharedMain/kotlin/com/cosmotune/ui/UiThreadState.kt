package com.cosmotune.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * A page's coroutine scope whose coroutines come back to the UI thread after every suspension.
 *
 * A page that loads over the network or from disk and then sets its state (a list, an error, a busy flag) must set it on the
 * thread that composes and lays the page out. In the app's windows the composition's own effect dispatcher already brings a
 * coroutine back there. Compose's desktop test harness runs effects on an unconfined dispatcher instead, and lays the page out on
 * the AWT thread, so a coroutine resumed by OkHttp or Dispatchers.IO goes on, and writes the page's state, on that worker while the
 * AWT thread lays the page out. Such a write races the layout's snapshot observer: the page can miss the change for good (a list
 * that never fills, an error that never shows) or Compose fails inside its own code. Dispatchers.Main.immediate is the UI thread on Android and on the desktop, and runs at once when already there.
 */
@Composable
fun rememberUiCoroutineScope(): CoroutineScope = rememberCoroutineScope { Dispatchers.Main.immediate }

/** [collectAsState] that takes each new value on the UI thread, not on the thread that set the flow (see [rememberUiCoroutineScope]). */
@Composable
fun <T> StateFlow<T>.collectAsUiState(): State<T> = collectAsState(Dispatchers.Main.immediate)
