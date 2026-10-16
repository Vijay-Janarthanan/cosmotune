package com.cosmotune.video.settings

import com.cosmotune.video.library.DebouncedFileWriter
import com.cosmotune.video.library.LibraryCodec
import com.cosmotune.video.library.LibraryFile
import com.cosmotune.video.library.LibraryFiles
import com.cosmotune.video.library.libraryLog
import com.cosmotune.video.library.loadDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * Video settings kept in `settings.json` (plan task Y-7, step 4; spec 5.11, 8.1). Loaded when the store is made (a missing
 * file is the defaults: Auto quality on both networks, Shorts shown); [update] changes the state on the caller's thread and
 * writes the file 500 ms after the last change, on [ioDispatcher]. A file that cannot be read is set aside as
 * `settings.json.broken-<time>` (named from [clock]) and the defaults are used. Call [flush] when the app goes away.
 */
class VideoSettingsStore(
    files: LibraryFiles,
    scope: CoroutineScope,
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    log: (String) -> Unit = ::libraryLog,
) : VideoSettingsRepository {
    private val flow = MutableStateFlow(
        loadDocument(files, LibraryFile.SETTINGS, clock, log, LibraryCodec::decodeSettings, VideoSettings()),
    )
    private val writer = DebouncedFileWriter(files, scope, ioDispatcher, log, encode = { LibraryCodec.encodeSettings(flow.value) })

    override val settings: StateFlow<VideoSettings> = flow.asStateFlow()

    override fun update(transform: (VideoSettings) -> VideoSettings) {
        while (true) {
            val old = flow.value
            val new = transform(old)
            if (new == old) return
            if (flow.compareAndSet(old, new)) break
        }
        writer.changed(LibraryFile.SETTINGS.fileName)
    }

    /** Writes a change that is still waiting for its 500 ms now, on the calling thread. Call it when the app goes away. */
    fun flush() = writer.flush()
}
