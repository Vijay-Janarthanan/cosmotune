// Derived from Tsuki 89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7; GPL-3.0.
// See manga/third-party/TSUKI_SOURCE_API.json and LICENSE.tsuki.
package eu.kanade.tachiyomi.network

import android.content.Context
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers

/**
 * Util for evaluating JavaScript in sources.
 *
 * Uses QuickJS (with Rhino fallback) to execute JavaScript code.
 * This provides compatibility with Mihon extensions that use JavaScriptEngine.
 *
 * @since extensions-lib 1.4
 */
class JavaScriptEngine(private val context: Context) {

    /**
     * Evaluate arbitrary JavaScript code and get the result as a primitive type
     * (e.g., String, Int).
     *
     * @param script JavaScript to execute.
     * @return Result of JavaScript code as a primitive type.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> evaluate(script: String): T {
        require(script.length <= 512 * 1024) { "Source script exceeds the execution limit" }
        return QuickJs.create(jobDispatcher = Dispatchers.Default).use { qjs ->
            qjs.maxStackSize = 1L shl 20  // 1MB
            qjs.memoryLimit = 64L shl 20   // 64MB soft limit
            qjs.evaluationTimeoutMillis = 10_000
            val result = qjs.evaluate<Any?>(script)
            result as T
        }
    }
}
