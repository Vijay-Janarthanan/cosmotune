package com.cosmotune.video.player

/**
 * mpv's EDL address for one video and one audio stream played together (spec 6.3; mpv `DOCS/edl-mpv.rst`): the desktop engine
 * cannot use the DASH manifest the Android engine plays (FFmpeg's DASH demuxer has no `SegmentBase`, spec 2.4), so it hands mpv
 * both streams as an `edl://` address, each a `!new_stream` without clipping, the video first.
 *
 * Every address is written in EDL's length-prefixed form `%<length>%<address>`, where the length counts the address's UTF-8
 * bytes: a comma or a semicolon inside an address (googlevideo addresses have commas in `sparams`) then cannot end the entry.
 * Pure string work, common code.
 */
object Edl {
    /** [url] in EDL's length-prefixed form: `%<UTF-8 byte count>%<url>`. */
    fun quote(url: String): String = "%${url.encodeToByteArray().size}%$url"

    /** The address that plays [videoUrl] and [audioUrl] together as two streams of one timeline. */
    fun join(videoUrl: String, audioUrl: String): String =
        "edl://!new_stream;!no_clip;${quote(videoUrl)};!new_stream;!no_clip;${quote(audioUrl)}"
}
