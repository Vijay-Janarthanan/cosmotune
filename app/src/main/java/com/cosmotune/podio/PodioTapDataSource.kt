package com.cosmotune.podio

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Hands the bytes the player reads for the station that plays to [PodioRecorder], so a recording costs no second connection. It
 * sits on the network side of the player's chain, where the stream still carries its song-title blocks and its own headers say
 * where they are (icy-metaint). Everything else passes through untouched.
 */
@UnstableApi
class PodioTapDataSource(private val upstream: DataSource) : DataSource {
    private var tap: PodioRecorder.Tap? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        val scheme = dataSpec.uri.scheme
        tap = if (scheme == "http" || scheme == "https") runCatching {
            val headers = upstream.responseHeaders
            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
            PodioRecorder.tap(dataSpec.uri.toString(), header("Content-Type"), header("icy-metaint")?.trim()?.toIntOrNull())
        }.getOrNull() else null
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = upstream.read(buffer, offset, length)
        if (read > 0) tap?.let { runCatching { it.write(buffer, offset, read) } }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() { tap = null; upstream.close() }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = PodioTapDataSource(upstream.createDataSource())
    }
}
