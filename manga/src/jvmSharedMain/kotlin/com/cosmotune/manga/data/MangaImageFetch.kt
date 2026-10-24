package com.cosmotune.manga.data

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.*
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/** Image IO closes a blocked call and joins its writer before cache cleanup or a retry. */
internal object MangaImageFetch {
    suspend fun fetch(client: OkHttpClient, request: Request, target: File) = supervisorScope {
        require(request.method in setOf("GET", "POST")) { "Unsupported image request method" }
        val parent = currentCoroutineContext()
        val active = AtomicReference<Call?>()
        val worker = async(Dispatchers.IO) {
            val body = request.body
            val bounded = body?.let { original ->
                require(original.contentLength() <= 256 * 1024) { "Image request exceeds 256 KB" }
                require(!original.isDuplex()) { "Duplex image requests are unsupported" }
                object : RequestBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun isOneShot() = original.isOneShot()
                    override fun isDuplex() = original.isDuplex()
                    override fun writeTo(sink: BufferedSink) {
                        var written = 0L
                        val limited = object : ForwardingSink(sink) {
                            override fun write(source: Buffer, byteCount: Long) {
                                parent.ensureActive()
                                require(byteCount >= 0 && byteCount <= 256 * 1024 - written) { "Image request exceeds 256 KB" }
                                written += byteCount
                                super.write(source, byteCount)
                            }
                        }.buffer()
                        original.writeTo(limited); limited.flush()
                    }
                }
            }
            val call = client.newCall(request.newBuilder().method(request.method, bounded).build())
            active.set(call)
            try {
                parent.ensureActive()
                call.execute().use { response ->
                    check(response.isSuccessful) { "Page server returned HTTP ${response.code}" }
                    require(response.body.contentLength() <= MangaArchive.MAX_PAGE_BYTES) { "Page exceeds the 32 MB limit" }
                    response.body.byteStream().use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var count = 0L
                        while (true) {
                            parent.ensureActive()
                            val size = input.read(buffer); if (size < 0) break
                            count += size; require(count <= MangaArchive.MAX_PAGE_BYTES) { "Page exceeds the 32 MB limit" }
                            output.write(buffer, 0, size)
                        }
                        check(count > 0) { "Page server returned an empty image" }
                    } }
                }
            } finally { active.compareAndSet(call, null) }
        }
        try { worker.await() }
        finally { if (!parent.isActive) withContext(NonCancellable) { active.get()?.cancel(); worker.cancelAndJoin() } }
    }
}
