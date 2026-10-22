package com.opencanvas.core

import com.opencanvas.core.stream.AudioStream
import java.io.File
import java.security.MessageDigest

/**
 * Where the song's own audio can be read from, so its picture can be lined up with the music video.
 * Any player can use OpenCanvas: it does not matter whether the song comes from YouTube, a local
 * file or another service, as long as the audio is readable once.
 */
sealed interface SongAudio {

    /** Identifier under which a measured map is remembered. */
    val cacheId: String

    /** The song is a YouTube (Music) video whose audio the stream backend resolves. */
    data class YouTube(val videoId: String) : SongAudio {
        override val cacheId: String get() = videoId
    }

    /** The song is readable at [stream]: an `http(s)` URL that honours range requests, or a `file:` URI. */
    data class Stream(val stream: AudioStream) : SongAudio {
        constructor(file: File) : this(AudioStream(url = file.toURI().toString()))

        override val cacheId: String
            get() = "u" + MessageDigest.getInstance("SHA-1")
                .digest(stream.url.substringBefore('?').toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
    }
}
