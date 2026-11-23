package com.cosmotune.video.ui.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/**
 * The phone's and the TV's speech service (Google's on most phones and on Google TV, the remote's microphone button): one free-form
 * request through `RecognizerIntent`; the first answer becomes the search. Null when nothing on the device answers that request.
 */
@Composable
internal actual fun rememberVoiceInput(): VoiceInput? {
    val context = LocalContext.current
    val available = remember(context) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
    }
    val pending = remember { mutableStateOf<((String) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val words = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        } else null
        val deliver = pending.value
        pending.value = null
        if (!words.isNullOrEmpty()) deliver?.invoke(words)
    }
    val launch by rememberUpdatedState(launcher)
    if (!available) return null
    return remember {
        VoiceInput { onWords ->
            pending.value = onWords
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            runCatching { launch.launch(intent) }.onFailure { pending.value = null }
        }
    }
}
