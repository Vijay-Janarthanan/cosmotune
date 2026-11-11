package com.cosmotune.diagnostics

import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One bounded local note. No automatic upload, media addresses, exception messages or credentials. */
object CrashNotes {
    private var file: File? = null
    private val mutable = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = mutable

    @Synchronized fun configure(directory: File) {
        file = File(directory, "last-crash.txt")
        mutable.value = runCatching { file?.takeIf { it.isFile && it.length() <= 32_768 }?.readText() }.getOrNull()
    }
    @Synchronized fun record(failure: Throwable) {
        val text = buildString {
            append("Cosmotune · ").append(Instant.now()).append('\n')
            var cause: Throwable? = failure
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (cause != null && seen.add(cause) && seen.size <= 6) {
                append(cause.javaClass.name).append('\n')
                cause.stackTrace.take(40).forEach { append("  at ").append(it).append('\n') }
                cause = cause.cause
            }
        }.take(16_384)
        runCatching { file?.let { target -> target.parentFile?.mkdirs(); target.writeText(text) } }
        mutable.value = text
    }
    @Synchronized fun clear() { file?.delete(); mutable.value = null }
}
