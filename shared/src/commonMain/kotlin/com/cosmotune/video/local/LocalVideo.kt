package com.cosmotune.video.local

import com.cosmotune.video.model.VideoItem

/**
 * Video files on the device, played by the local player (no source call of any kind). The pure rules live here: which ids are a
 * local file's, how a file's id and title come from its path, which files are videos and which are subtitles, and the video item
 * the player is given for a file.
 *
 * An id is [ID_PREFIX] and sixteen hexadecimal digits (a 64-bit FNV-1a of the file's address), so the same file has the same id in every
 * session and the resume position and the recent list find it again.
 */
object LocalVideo {
    /** The start of every local file's id; no video of the source has an id that starts like it. */
    const val ID_PREFIX = "local:"

    /** The extensions the pickers and the drop target accept as videos. */
    val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "mts", "3gp", "3g2", "ogv", "ogg", "vob", "divx",
    )

    /** The extensions of the subtitle files the player reads. */
    val SUBTITLE_EXTENSIONS: Set<String> = setOf("srt", "vtt", "ass", "ssa")

    /** Whether [id] is a local file's (null: no). */
    fun isLocal(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)

    /** The id of the file at [source] (a path, or an address such as `content://...`); `\` and `/` count the same. */
    fun idFor(source: String): String {
        var hash = FNV_OFFSET
        for (c in source.replace('\\', '/')) {
            hash = (hash xor c.code.toLong()) * FNV_PRIME
        }
        return ID_PREFIX + hash.toULong().toString(16).padStart(16, '0')
    }

    /**
     * The file name at the end of [source]: the part after the last `/` or `\`, without a query or fragment, and with the
     * `%XX` escapes of an address read back (UTF-8). Empty when there is none.
     */
    fun fileName(source: String): String {
        val trimmed = source.substringBefore('?').substringBefore('#').trimEnd('/', '\\')
        val last = trimmed.substringAfterLast('/').substringAfterLast('\\')
        // `content://.../document/primary%3AMovies%2Fclip.mp4`: the name is after the last (escaped) slash.
        val decoded = if ('%' in last) percentDecode(last) else last
        return decoded.substringAfterLast('/').substringAfterLast('\\')
    }

    /** [name] without its extension (only a real one: one to five letters or digits after the last dot). */
    fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return name
        val extension = name.substring(dot + 1)
        return if (extension.length in 1..MAX_EXTENSION && extension.all { it.isLetterOrDigit() }) name.substring(0, dot) else name
    }

    /** The lower-case extension of [name], or an empty text. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.lastIndex) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** The title shown for the file at [sourceOrName]: its name without the extension, with `_` read as a space; [FALLBACK_TITLE] when empty. */
    fun titleOf(sourceOrName: String): String {
        val name = fileName(sourceOrName)
        val title = stem(name).replace('_', ' ').trim()
        return title.ifEmpty { FALLBACK_TITLE }
    }

    /** Whether [name] looks like a video file by its extension. */
    fun isVideoName(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    /** Whether [name] looks like a subtitle file by its extension. */
    fun isSubtitleName(name: String): Boolean = extension(name) in SUBTITLE_EXTENSIONS

    /** The video item the player is given for a recent [entry]: no channel, no picture, the known length. */
    fun itemFor(entry: LocalRecent): VideoItem = VideoItem(
        id = entry.id,
        title = entry.title,
        channelId = null,
        channelName = null,
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSec = entry.durationMs.takeIf { it > 0 }?.let { it / 1000 },
        viewCount = null,
        uploadedAtMs = null,
        uploadedText = null,
    )

    /**
     * Where a resumed file starts: [positionMs] when remembering is on and the file was left part-way (not in its first few seconds,
     * not within [END_MARGIN_MS] of the end), else the beginning.
     */
    fun resumeAt(positionMs: Long, durationMs: Long, remember: Boolean): Long {
        if (!remember || positionMs < MIN_RESUME_MS) return 0
        if (durationMs > 0 && positionMs > durationMs - END_MARGIN_MS) return 0
        return positionMs
    }

    private fun percentDecode(text: String): String {
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val high = if (c == '%' && i + 2 <= text.lastIndex) hex(text[i + 1]) else -1
            val low = if (high >= 0) hex(text[i + 2]) else -1
            if (high >= 0 && low >= 0) {
                bytes.add(((high shl 4) or low).toByte())
                i += 3
            } else {
                for (b in c.toString().encodeToByteArray()) bytes.add(b)
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** What a file with no usable name is called. */
    const val FALLBACK_TITLE = "Video"

    /** A file left in its first seconds starts from the beginning. */
    const val MIN_RESUME_MS = 5_000L

    /** A file left within this of its end starts from the beginning. */
    const val END_MARGIN_MS = 10_000L

    private const val MAX_EXTENSION = 5
    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L
}
