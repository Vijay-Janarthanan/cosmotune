/*
 * The three Rhino helpers NewPipeExtractor v0.26.3's JavaScript lexer (utils.jsextractor.EcmaScriptTokenStream) calls,
 * so that the lexer keeps working without Rhino on the class path. NewPipe uses the lexer to cut YouTube's signature and
 * throttling functions out of the player script; the functions themselves run in QuickJS (see
 * org.schabi.newpipe.extractor.utils.JavaScript). Each one is a small fact of the ECMAScript grammar.
 */
package org.mozilla.javascript

object Kit {
    /** An internal invariant failed: the lexer reached a state it never should. */
    @JvmStatic
    fun codeBug(): RuntimeException = throw IllegalStateException("FAILED ASSERTION")

    /** [accumulator] shifted one hex digit left plus the value of hex digit [c], or -1 when [c] is not a hex digit. */
    @JvmStatic
    fun xDigitToInt(c: Int, accumulator: Int): Int {
        val digit = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            else -> return -1
        }
        return (accumulator shl 4) or digit
    }
}

object ScriptRuntime {
    /** The four ECMAScript line terminators: LF, CR, LINE SEPARATOR and PARAGRAPH SEPARATOR. */
    @JvmStatic
    fun isJSLineTerminator(c: Int): Boolean = c == '\n'.code || c == '\r'.code || c == 0x2028 || c == 0x2029
}
