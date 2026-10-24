package com.cosmotune.manga.data

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Applied after transparent decompression, including when Content-Length is absent or misleading. */
internal class MangaResponseLimit : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        val type = body.contentType()
        val limit = if (type?.type == "image" || type?.subtype == "octet-stream" || chain.request().header("Accept") == "image/*") MangaArchive.MAX_PAGE_BYTES else 2L * 1024 * 1024
        if (body.contentLength() > limit) { response.close(); throw IOException("Source response exceeds the ${limit / 1024 / 1024} MB limit") }
        val bounded = object : ResponseBody() {
            private val input: BufferedSource by lazy {
                object : ForwardingSource(body.source()) {
                    private var consumed = 0L
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val count = super.read(sink, minOf(byteCount, limit - consumed + 1))
                        if (count > 0) consumed += count
                        if (consumed > limit) throw IOException("Source response exceeds the ${limit / 1024 / 1024} MB limit")
                        return count
                    }
                }.buffer()
            }
            override fun contentType() = type
            override fun contentLength() = body.contentLength()
            override fun source() = input
        }
        return response.newBuilder().body(bounded).build()
    }
}
