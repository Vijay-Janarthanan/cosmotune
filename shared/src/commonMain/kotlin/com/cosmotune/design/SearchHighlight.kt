package com.cosmotune.design

/**
 * The bold part of a search suggestion (`searchTyping`, `screens-more-a.js:77-79`): what the user has typed so far is set in
 * weight 700 and the rest of the suggestion in the regular weight.
 */
object SearchHighlight {

    /**
     * Splits [suggestion] into the part that [typed] covers and the rest. The match is on the front of the suggestion only, it
     * ignores case, and [typed] is trimmed first; the first part keeps the suggestion's own letters (typing "MAZ" for "mazhai"
     * gives "maz" and "hai"). When nothing is typed, or the suggestion does not start with what was typed, nothing is bold:
     * the result is `("", suggestion)`. The two parts always add up to [suggestion].
     */
    fun split(suggestion: String, typed: String): Pair<String, String> {
        val prefix = typed.trim()
        if (prefix.isEmpty() || !suggestion.startsWith(prefix, ignoreCase = true)) return "" to suggestion
        return suggestion.substring(0, prefix.length) to suggestion.substring(prefix.length)
    }
}
