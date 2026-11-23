package com.cosmotune.music.data.lyrics

/**
 * "Romanised lyrics only" (Player options): while romanisation is shown, each line is drawn in its romanised spelling alone, in
 * the line's own place and timing, instead of the original script with the romanisation small under it.
 */
object RomanisedLines {

    /**
     * [lines] with their text taken from [romanised] (index for index), timed by the original line; null when the two do not line
     * up, so the caller keeps the original with the romanisation under it. A line with no romanised text keeps its own.
     */
    fun replace(lines: List<LyricLine>, romanised: List<LyricLine>?): List<LyricLine>? {
        if (romanised == null || romanised.size != lines.size || lines.isEmpty()) return null
        return lines.mapIndexed { index, line ->
            val text = romanised[index].text.trim()
            if (text.isEmpty() || text == line.text.trim()) line
            else line.copy(text = text, words = emptyList(), timingSource = line.timingSource ?: line,
                background = line.background?.let { back -> romanised[index].background?.let { back.copy(text = it.text, words = emptyList(), timingSource = back) } ?: back })
        }
    }
}
