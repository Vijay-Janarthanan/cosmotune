package com.cosmotune.reading.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private class AndroidReadingSpeech(context: android.content.Context) : ReadingSpeech {
    private val ready = CompletableDeferred<Boolean>()
    private val waiting = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
    private val engine = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finish(utteranceId) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { finish(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId) }
        })
    }
    private fun finish(id: String?) { id?.let(waiting::remove)?.let { if (it.isActive) it.resume(Unit) } }
    override suspend fun voices(): List<ReadingVoiceInfo> {
        if (!ready.await()) return emptyList()
        return runCatching { engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired }.map {
            ReadingVoiceInfo(it.name, it.name.replace('-', ' ').replace('_', ' '), it.locale.toLanguageTag())
        }.sortedBy { it.language + it.name } }.getOrDefault(emptyList())
    }
    override suspend fun speak(text: String, voice: String, rate: Float) {
        check(ready.await()) { "This device has no text-to-speech voice. Install one in the system settings." }
        if (voice.isNotBlank()) runCatching { engine.voices?.firstOrNull { it.name == voice }?.let { engine.voice = it } }
        engine.setSpeechRate(rate.coerceIn(0.5f, 3f))
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { continuation ->
            waiting[id] = continuation
            continuation.invokeOnCancellation { waiting.remove(id); engine.stop() }
            if (engine.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
        }
    }
    override fun stop() { engine.stop() }
    override fun close() { engine.stop(); engine.shutdown() }
}

@Composable internal actual fun rememberReadingSpeech(): ReadingSpeech? {
    val context = LocalContext.current
    val speech = remember(context) { runCatching { AndroidReadingSpeech(context) }.getOrNull() }
    DisposableEffect(speech) { onDispose { speech?.close() } }
    return speech
}
