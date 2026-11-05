package com.cosmotune.music.playback.cast

import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import com.google.android.gms.common.api.ResultCallback
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Upstream's Chromecast crash (8636423e): waiting on a Cast request called
 * PendingResult's own blocking `await()`, which throws on the main thread, from
 * inside the framework's session callback. These hold the suspending wait to
 * the callback path, against a fake request that behaves like the real one.
 */
class CastAwaitResultTest {

    private class Answer : Result {
        override fun getStatus(): Status = Status(0)
    }

    /** A Cast request: its blocking wait throws as the framework's does on the main thread. */
    private class FakeRequest<R : Result> : PendingResult<R>() {
        var callback: ResultCallback<in R>? = null
        var cancelled = false
        override fun await(): R = throw IllegalStateException("await must not be called on the UI thread")
        override fun await(time: Long, unit: TimeUnit): R = await()
        override fun cancel() { cancelled = true }
        override fun isCanceled(): Boolean = cancelled
        override fun setResultCallback(callback: ResultCallback<in R>) { this.callback = callback }
        override fun setResultCallback(callback: ResultCallback<in R>, time: Long, unit: TimeUnit) = setResultCallback(callback)
        fun answer(result: R) = checkNotNull(callback) { "nobody is waiting" }.onResult(result)
    }

    @Test
    fun `the wait is answered through the callback and never blocks`() = runBlocking {
        val request = FakeRequest<Answer>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { request.awaitResult() }
        assertFalse(waiting.isCompleted)
        val answer = Answer()
        request.answer(answer)
        assertSame(answer, waiting.await())
        assertFalse(request.cancelled)
    }

    @Test
    fun `giving up on a wait cancels the Cast request`() = runBlocking {
        val request = FakeRequest<Answer>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { request.awaitResult() }
        waiting.cancel()
        yield()
        assertTrue(request.cancelled)
        assertTrue(waiting.isCancelled)
    }

    @Test
    fun `no Cast request in the cast package is waited on with the blocking member`() {
        val dir = File("src/main/java/com/cosmotune/music/playback/cast")
        val sources = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("cast sources not found from ${File(".").absolutePath}", sources.isNotEmpty())
        val offenders = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> Regex("""\)\s*\.await\(\s*\)""").containsMatchIn(line) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
