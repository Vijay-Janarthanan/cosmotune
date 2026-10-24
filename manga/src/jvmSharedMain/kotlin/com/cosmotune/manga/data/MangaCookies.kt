package com.cosmotune.manga.data

import java.io.File
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** HttpOnly cookies remain HTTP-only; this jar is never shared with music account storage. */
class MangaCookies(private val file: File, private val protection: MangaFileProtection = UnsealedMangaFiles) : CookieJar {
    private val values = ArrayList<Pair<String, Cookie>>()

    init {
        if (file.isFile && file.length() < 512 * 1024) runCatching { protection.open(file.readBytes()).toString(Charsets.UTF_8) }.getOrNull()?.lineSequence()?.forEach { line ->
            val url = line.substringBefore('\t')
            val cookie = line.substringAfter('\t', "")
            runCatching { url.toHttpUrl() }
                .getOrNull()?.let { parsed -> Cookie.parse(parsed, cookie)?.let { values.add(url to it) } }
        }
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.removeAll { it.second.expiresAt <= System.currentTimeMillis() }
        return values.map { it.second }.filter { it.matches(url) }
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            values.removeAll { (_, old) -> old.name == cookie.name && old.domain == cookie.domain && old.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis() && cookie.toString().length <= 8192) {
                val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
                values.add(origin to cookie)
            }
        }
        var size = values.sumOf { it.first.length + it.second.toString().length }
        while (values.size > 512 || size > 256 * 1024) { val removed = values.removeAt(0); size -= removed.first.length + removed.second.toString().length }
        persist()
    }

    @Synchronized fun clear() { values.clear(); persist() }

    private fun persist() {
        if (values.none { it.second.persistent }) { if (file.exists()) check(file.delete()) { "Cannot clear saved source cookies" }; return }
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile("cookies-", ".part", file.parentFile)
        try {
            temporary.ownerOnly()
            temporary.writeBytes(protection.seal(values.filter { it.second.persistent }.joinToString("\n") { "${it.first}\t${it.second}" }.toByteArray(Charsets.UTF_8)))
            MangaArchive.move(temporary, file)
        } finally { temporary.delete() }
    }
}
