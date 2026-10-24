package com.cosmotune.manga.data

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig as ParserConfig

class MangaBrowserRequired(val address: String) : Exception("Open the source website to complete its verification, then retry")

class ParserContext(root: File, protection: MangaFileProtection = UnsealedMangaFiles, private val configurations: () -> List<MangaSourceConfig>) : MangaLoaderContext() {
    override val cookieJar: CookieJar = MangaCookies(File(root, "cookies.txt"), protection)
    override val httpClient: OkHttpClient = OkHttpClient.Builder().cookieJar(cookieJar)
        .addInterceptor(MangaResponseLimit())
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()
    override fun getDefaultUserAgent(): String = USER_AGENT
    override fun getConfig(source: MangaSource): ParserConfig = object : ParserConfig {
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: ConfigKey<T>): T {
            val settings = configurations().find { it.id == "kotatsu:${source.name}" }?.settings.orEmpty()
            val value = settings[key.key] ?: return key.defaultValue
            return when (key.defaultValue) {
                is Boolean -> value.toBooleanStrictOrNull() ?: key.defaultValue
                is String, null -> value
                else -> key.defaultValue
            } as T
        }
    }
    @Deprecated("Use base URL")
    override suspend fun evaluateJs(script: String): String? = evaluateMangaScript(script, 10_000)
    override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = evaluateMangaScript(script, timeout.coerceIn(1, 30_000))
    override fun requestBrowserAction(parser: MangaParser, url: String): Nothing = throw MangaBrowserRequired(url)
    // Sources that read their pages through a hidden page script need an in-app browser engine; say so instead of a generic failure.
    override suspend fun interceptWebViewRequests(url: String, config: org.koitharu.kotatsu.parsers.webview.InterceptionConfig): List<org.koitharu.kotatsu.parsers.webview.InterceptedRequest> =
        throw MangaSourceFailure(BROWSER_ENGINE_REQUIRED)
    override suspend fun captureWebViewUrls(pageUrl: String, urlPattern: Regex, timeout: Long): List<String> = throw MangaSourceFailure(BROWSER_ENGINE_REQUIRED)
    override fun createBitmap(width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0 && width.toLong() * height <= 40_000_000) { "Page dimensions exceed the decoding limit" }
        return newMangaBitmap(width, height)
    }
    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response {
        val body = response.body ?: return response
        require(body.contentLength() <= MangaArchive.MAX_PAGE_BYTES) { "Page exceeds the decoding limit" }
        val bytes = body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size().toLong() + count <= MangaArchive.MAX_PAGE_BYTES) { "Page exceeds the decoding limit" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val output = encodeMangaBitmap(redraw(decodeMangaBitmap(bytes)))
        return response.newBuilder().removeHeader("Content-Encoding").removeHeader("Content-Length")
            .body(output.toResponseBody("image/png".toMediaType())).build()
    }
    companion object {
        const val BROWSER_ENGINE_REQUIRED = "This source runs its pages in a browser engine, which Cosmotune does not provide for built-in sources. Choose another source."
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}

internal expect fun newMangaBitmap(width: Int, height: Int): Bitmap
internal expect fun decodeMangaBitmap(data: ByteArray): Bitmap
internal expect fun encodeMangaBitmap(bitmap: Bitmap): ByteArray
internal expect suspend fun evaluateMangaScript(script: String, timeoutMs: Long): String?
