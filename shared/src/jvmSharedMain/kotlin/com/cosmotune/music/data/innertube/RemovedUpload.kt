package com.cosmotune.music.data.innertube

import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException

/**
 * Whether the extractor's refusal says the upload itself is gone: removed, taken down, or "not available" with
 * YouTube's own status behind it.
 *
 * NewPipe raises a plain [ContentNotAvailableException] for that, the base of the age-gate and region types that
 * [StreamResolver] already treats as verdicts, so it fell through as "worth another go": three extractions per play,
 * each about three seconds, for an answer settled by the first. Only the content statuses (`UNPLAYABLE`, `ERROR`)
 * count, and never a refusal that names this app or a sign-in, which is about the client asking rather than the video.
 */
object RemovedUpload {
    const val REASON = "This song's upload is no longer on YouTube"

    private val STATUS = Regex("""\b(?:UNPLAYABLE|ERROR)\b\s*:""")
    private val GONE = Regex("""(?i)\b(?:video|this video)\s+(?:is\s+)?(?:not available|unavailable|no longer available)\b|\bvideo unavailable\b|\bhas been removed\b|\bwas removed\b""")
    private val CLIENT = Regex("""(?i)on this app|sign in|not a bot|confirm you|latest version""")

    fun reason(error: Throwable): String? {
        if (error.javaClass != ContentNotAvailableException::class.java) return null
        val message = error.message ?: return null
        if (!STATUS.containsMatchIn(message) || CLIENT.containsMatchIn(message)) return null
        return if (GONE.containsMatchIn(message)) REASON else null
    }
}
