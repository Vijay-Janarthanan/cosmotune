/*
 * Replaces NewPipeExtractor v0.26.3's org.schabi.newpipe.extractor.utils.JavaScript, which runs YouTube's signature and
 * throttling functions in Rhino. The same two entry points run them in QuickJS, the engine the app already ships for
 * InnerTubeX's cipher and for source modules, so Rhino does not have to ship at all.
 *
 * NewPipe's own class is stripped from its jar on every build that ships this one (see stripNewPipeExtractorUtils in
 * app/build.gradle.kts and tv/build.gradle.kts, and targetNewPipeExtractor in desktopApp/build.gradle.kts): two
 * definitions of one class cannot both reach dex merging, and on the desktop class path the first one found would win.
 * Behaviour matches upstream: compileOrThrow only parses, and run evaluates the function source in a fresh global
 * scope and returns the named function's result for the given string arguments, as a string.
 */
package org.schabi.newpipe.extractor.utils

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

object JavaScript {
    /** Parses [function] without running it; throws if it is not valid JavaScript. */
    @JvmStatic
    fun compileOrThrow(function: String) {
        session { it.compile(function, "decryptionCode", false) }
    }

    /** Evaluates [function], then calls [functionName] with [parameters] and returns the result as a string. */
    @JvmStatic
    fun run(function: String, functionName: String, vararg parameters: String): String = session { js ->
        require(functionName.matches(NAME)) { "Not a JavaScript function name: $functionName" }
        js.evaluate<Any?>(function, "decryptionCode", false)
        val arguments = parameters.joinToString(",") { JsonPrimitive(it).toString() }
        js.evaluate<String>("String($functionName($arguments))", "call", false)
    }

    private val NAME = Regex("[A-Za-z_$][A-Za-z0-9_$.]*")

    /** One short-lived interpreter per call, as Rhino's Context was: nothing leaks from one player script to the next. */
    private fun <T> session(block: suspend (QuickJs) -> T): T = runBlocking {
        val js = QuickJs.create(Dispatchers.Unconfined)
        js.maxStackSize = 1024 * 1024L
        js.memoryLimit = 64L * 1024 * 1024
        js.evaluationTimeoutMillis = 10_000
        try { block(js) } finally { js.close() }
    }
}
