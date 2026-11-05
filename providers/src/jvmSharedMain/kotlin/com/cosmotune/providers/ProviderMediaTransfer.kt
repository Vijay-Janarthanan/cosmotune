package com.cosmotune.providers

import java.io.File
import kotlinx.serialization.json.*
import com.cosmotune.music.data.downloads.ValidatedMusicTransfer

/** Provider bytes keep their declared-host transport; range validation is shared with native downloads. */
internal class ProviderMediaTransfer(private val network: ProviderNetwork, private val maxBytes: Long = 512L * 1024 * 1024) {
    fun download(url: String, destination: File, headers: JsonObject = JsonObject(emptyMap()),
        onProgress: (Long, Long?) -> Unit = { _, _ -> }): File = ValidatedMusicTransfer(
        exchange = { range, read ->
            val request = buildJsonObject { putJsonObject("headers") {
                headers.forEach { (name, value) -> if (!name.equals("Range", true) && !name.equals("If-Range", true)) put(name, value) }
                range.forEach { (name, value) -> put(name, value) }
            } }
            network.withResponse(url, request, media = true, consume = read)
        }, checkCancelled = { check(!network.isCancelled) { "Provider media cancelled" } },
        protect = { it.ownerOnly() }, maxBytes = maxBytes,
    ).download(url, destination, onProgress)
}
