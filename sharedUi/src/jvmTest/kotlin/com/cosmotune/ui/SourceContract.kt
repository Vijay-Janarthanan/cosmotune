package com.cosmotune.ui

import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Helpers for the contract tests of the new screens: what cannot be seen without a window (there is no Compose UI test harness
 * here and none is to be added) is pinned by reading the source. They are the ones `NowPlayingContractTest` wrote for its own
 * use, gathered so the other screens' tests share one reading of "the parameters of a function", "its body" and "its first
 * statement": the seams of the switch (same parameter list as the old screen, every argument passed by name in both branches)
 * and the delegate (the first statement of the upstream function).
 *
 * All of them read the source as text; none of them parses Kotlin. A function is found as `fun name(`, optionally with type
 * parameters or a receiver before the name; an expression body (`= ...`) has no braces and is refused with a message.
 */
object SourceContract {

    /** The repository root: the nearest folder up from the test's working directory that holds `settings.gradle.kts`. */
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** The file at [path] (from [root], forward slashes) as text with its line endings turned into `\n`. */
    fun read(path: String): String = File(root, path).readText().replace("\r\n", "\n")

    /** The file without its comments (blocks and line comments), so a rule about code is not tripped by a sentence about it. */
    fun code(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    /** The index of the `(` that opens the parameter list of `fun [name]` in [text] (comments already removed), or fails. */
    private fun parameterListStart(text: String, name: String): Int {
        val match = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?${Regex.escape(name)}\s*\(""").find(text)
        return (match ?: fail("fun $name( is in the file")).range.last
    }

    /** The index of the `)` that matches the `(` at [open]. */
    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        fail("the parenthesis at $open is never closed")
    }

    /** The parameters of `fun [name](...)` in [src], each as `name: type = default` with its white space collapsed. */
    fun parameters(src: String, name: String): List<String> {
        val text = code(src).replace("->", "→")
        val open = parameterListStart(text, name)
        val close = matchingParen(text, open)
        assertTrue(close > open, "the parameter list of $name is closed")
        val inside = text.substring(open + 1, close)
        val parts = ArrayList<String>()
        var level = 0
        var from = 0
        for (i in inside.indices) {
            when (inside[i]) {
                '(', '<', '{' -> level++
                ')', '>', '}' -> level--
                ',' -> if (level == 0) { parts += inside.substring(from, i); from = i + 1 }
            }
        }
        parts += inside.substring(from)
        // the arrows were hidden from the angle-bracket count above: give them back
        return parts.map { it.replace(Regex("\\s+"), " ").replace("→", "->").trim() }.filter { it.isNotEmpty() }
    }

    /** The names of [params] (what [parameters] returned): everything before the first colon. */
    fun names(params: List<String>): List<String> = params.map { it.substringBefore(":").trim() }

    /**
     * The body of `fun [name]` in [src], comments removed, from its opening `{` to its matching `}` (both included). Braces
     * inside string and character literals do not count.
     */
    fun functionBody(src: String, name: String): String {
        val text = code(src)
        val close = matchingParen(text, parameterListStart(text, name))
        // after the parameters comes an optional return type, then the body: a block, not an expression
        var open = -1
        for (i in close + 1 until text.length) {
            if (text[i] == '{') { open = i; break }
            if (text[i] == '=') fail("fun $name has an expression body, not a block")
        }
        assertTrue(open > 0, "fun $name has a body")
        var depth = 0
        var i = open
        while (i < text.length) {
            when (val c = text[i]) {
                '"', '\'' -> i = endOfLiteral(text, i, c)
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        fail("the body of $name is never closed")
    }

    /** The index of the quote that closes the string or character literal opened at [start] (a raw string is not handled). */
    private fun endOfLiteral(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                quote -> return i
                '\n' -> return i
            }
            i++
        }
        return text.length - 1
    }

    /**
     * The first statement of [body] (what [functionBody] returned, with or without its braces), its white space collapsed. A
     * statement ends at the first line break outside every bracket, unless the next line goes on with it (`else`, a `.` call, an
     * operator): so `if (a) { ... } else { ... }` and a delegate call with its arguments on several lines are each one statement.
     */
    fun firstStatement(body: String): String {
        var text = body.trim()
        if (text.startsWith("{")) text = text.removePrefix("{")
        text = text.trimStart()
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '"', '\'' -> i = endOfLiteral(text, i, c)
                '(', '[', '{' -> depth++
                ')', ']' -> depth--
                '}' -> { if (depth == 0) break; depth-- }
                '\n' -> if (depth == 0 && !continues(text.substring(i + 1))) break
            }
            i++
        }
        return text.substring(0, i.coerceAtMost(text.length)).replace(Regex("\\s+"), " ").trim()
    }

    private fun continues(rest: String): Boolean {
        val next = rest.trimStart()
        return next.startsWith("else") || next.startsWith(".") || next.startsWith("?.") || next.startsWith("?:") ||
            next.startsWith("&&") || next.startsWith("||") || next.startsWith("+") || next.startsWith("->")
    }

    /** Fails unless [callText] has every one of [names] passed as `name = name`, each by name, as a whole word. */
    fun assertPassesEveryParameter(callText: String, names: List<String>) {
        val missing = names.filterNot { Regex("""\b${Regex.escape(it)} = ${Regex.escape(it)}\b""").containsMatchIn(callText) }
        assertTrue(missing.isEmpty(), "every parameter is passed as `name = name`; missing: $missing")
    }
}
