package com.cosmotune.manga.data

import okhttp3.OkHttpClient
import okhttp3.Request

interface MangaImageTransport {
    val imageClient: OkHttpClient
    fun imageRequest(page: MangaPage): Request {
        require(page.requestMethod == "GET") { "Reload this chapter to refresh its image request" }
        return Request.Builder().url(requireNotNull(page.url)).apply { page.headers.forEach { (key, value) -> header(key, value) } }.build()
    }
}
