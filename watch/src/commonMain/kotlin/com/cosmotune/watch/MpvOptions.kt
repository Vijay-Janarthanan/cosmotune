package com.cosmotune.watch

/**
 * The computer player's own options, written as in an mpv.conf (`deband=yes`, `--sub-scale=1.2`, comments after `#`), for
 * listeners who know mpv. Options that would load or run files, write files, reach the network on their own or take the
 * player's picture and keys away from Cosmotune are refused, and so is anything not shaped like an option.
 */
object MpvOptions {
    data class Parsed(val options: Map<String, String>, val refused: List<String>)

    private val name = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val refusedNames = setOf(
        "include", "config", "config-dir", "use-filedir-conf", "load-scripts", "script", "scripts", "script-opts", "scripts-append",
        "input-ipc-server", "input-ipc-client", "input-conf", "input-default-bindings", "input-vo-keyboard", "log-file", "dump-stats",
        "stream-record", "record-file", "screenshot-directory", "screenshot-template", "watch-later-directory", "save-position-on-quit",
        "write-filename-in-watch-later-config", "ytdl", "ytdl-path", "ytdl-raw-options", "external-file", "external-files", "audio-file",
        "audio-files", "sub-file", "sub-files", "cover-art-file", "cover-art-files", "playlist", "vo", "ao", "gpu-api", "wid", "idle",
        "terminal", "msg-level", "osd-level", "o", "of", "ofopts", "ovc", "oac", "stream-lavf-o", "demuxer-lavf-o", "http-proxy",
        "tls-ca-file", "tls-verify", "cache-dir", "icc-profile", "glsl-shader", "glsl-shaders", "vf", "af", "lavfi-complex",
    )

    fun parse(text: String): Parsed {
        val options = linkedMapOf<String, String>()
        val refused = mutableListOf<String>()
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val bare = line.removePrefix("--")
            val key = bare.substringBefore('=').trim().lowercase()
            val value = if ('=' in bare) bare.substringAfter('=').trim().removeSurrounding("\"") else "yes"
            val ok = name.matches(key) && key !in refusedNames && !key.startsWith("script") && value.length <= 200 &&
                value.none { it == '\n' || it == '\r' } && !value.contains("://")
            if (ok) options[key] = value else refused += line
        }
        return Parsed(options, refused)
    }
}
