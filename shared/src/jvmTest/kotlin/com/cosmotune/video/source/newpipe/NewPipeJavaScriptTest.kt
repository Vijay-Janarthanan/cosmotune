package com.cosmotune.video.source.newpipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.schabi.newpipe.extractor.utils.JavaScript
import org.schabi.newpipe.extractor.utils.jsextractor.JavaScriptExtractor

/**
 * NewPipe's player-script helpers without Rhino: the functions run in QuickJS through shared's JavaScript class, and the
 * lexer NewPipe cuts them out with runs on the three helpers shared provides in Rhino's place. This test's class path has
 * no Rhino at all (see shared/build.gradle.kts), so a path that still needed it would fail here with NoClassDefFoundError.
 */
class NewPipeJavaScriptTest {
    @Test
    fun rhinoIsNotOnTheClassPath() {
        assertNull(javaClass.classLoader.getResource("org/mozilla/javascript/Context.class"))
        assertTrue(JavaScript::class.java.protectionDomain.codeSource.location.path.contains("shared"))
    }

    @Test
    fun runCallsTheNamedFunctionWithStringArguments() {
        // The shape of YouTube's signature function: helpers in an object, string in, string out.
        val code = """
            var Xy={a:function(a,b){a.splice(0,b)},r:function(a){a.reverse()},s:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};
            var deobf=function(a){a=a.split("");Xy.r(a,1);Xy.s(a,3);Xy.a(a,2);return a.join("")};
        """.trimIndent()
        assertEquals("egcba", run(code, "deobf", "abcdefg"))
        assertEquals("he said \"hi\"\n", JavaScript.run("function echo(a,b){return a+b}", "echo", "he said \"hi\"", "\n"))
        assertEquals("3", JavaScript.run("function count(a){return a.length}", "count", "abc"))
    }

    @Test
    fun eachRunStartsFromAFreshScope() {
        JavaScript.run("var leaked = 1; function f(){return 'x'}", "f")
        assertEquals("undefined", JavaScript.run("function g(){return typeof leaked}", "g"))
    }

    @Test
    fun compileOrThrowParsesWithoutRunning() {
        JavaScript.compileOrThrow("function ok(a){ return a.split('').reverse().join('') }")
        // Valid code that would throw if it ran is only parsed.
        JavaScript.compileOrThrow("throw new Error('ran')")
        assertFailsWith<Exception> { JavaScript.compileOrThrow("function broken(a){ return a.split(''") }
    }

    @Test
    fun runFailsOnAnUnknownFunction() {
        assertFailsWith<Exception> { JavaScript.run("var a = 1;", "missing", "x") }
    }

    @Test
    fun theLexerCutsAFunctionBodyOutOfPlayerScript() {
        // NewPipe's lexer, which calls the Rhino helpers shared now provides (hex escapes, line terminators, regex literals).
        val script = "var x = 1;\nvar sig=function(a){var b=\"\\x7b\";if(/[}]/.test(b)){return a+b}\u2028return '}'+a};var after = 2;"
        val body = JavaScriptExtractor.matchToClosingBrace(script, "var sig=function(a)")
        assertEquals("{var b=\"\\x7b\";if(/[}]/.test(b)){return a+b}\u2028return '}'+a}", body)
    }

    private fun run(code: String, name: String, vararg args: String) = JavaScript.run(code, name, *args)
}
