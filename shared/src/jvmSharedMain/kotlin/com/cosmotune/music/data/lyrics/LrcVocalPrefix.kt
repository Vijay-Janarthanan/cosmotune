package com.cosmotune.music.data.lyrics

private val vocalPrefix = Regex("""^\s*v([0-9]{1,4})\s*[:：]\s*""", RegexOption.IGNORE_CASE)

/** LRC exporters can put the vocal label before the lyric or in its first timed run. */
internal fun splitLrcVocalPrefix(body: String, wordStamp: Regex? = null): Pair<String, String?> {
    vocalPrefix.find(body)?.let { tag ->
        return body.substring(tag.value.length) to "v${tag.groupValues[1].toInt()}"
    }
    if (wordStamp == null) return body to null
    var first = 0
    while (first < body.length) {
        if (body[first].isWhitespace()) { first++; continue }
        val stamp = wordStamp.matchAt(body, first) ?: break
        first = stamp.range.last + 1
    }
    if (body.getOrNull(first)?.lowercaseChar() != 'v') return body to null

    // A marker may itself span several stamps. Find it in the visible text, then
    // carry forward the timestamp belonging to the first surviving sung character.
    val tag = vocalPrefix.find(body.replace(wordStamp, "")) ?: return body to null
    var lyricStart = tag.value.length
    var leadingStamp = ""
    for (stamp in wordStamp.findAll(body)) {
        if (stamp.range.first > lyricStart) break
        lyricStart += stamp.value.length
        leadingStamp = stamp.value
    }
    val lyric = if (lyricStart == body.length) "" else leadingStamp + body.substring(lyricStart)
    return lyric to "v${tag.groupValues[1].toInt()}"
}

internal fun List<Pair<LyricLine, String?>>.withLrcVocalAlignments(): List<LyricLine> {
    if (none { it.second != null }) return map { it.first }
    val sides = lineAlignments(map { (line, singer) -> singer.takeUnless { line.isGap } }, emptyMap())
    return mapIndexed { index, (line, singer) ->
        if (singer == null || line.isGap || line.alignment == LyricAlignment.End) line
        else line.copy(alignment = sides[index])
    }
}
