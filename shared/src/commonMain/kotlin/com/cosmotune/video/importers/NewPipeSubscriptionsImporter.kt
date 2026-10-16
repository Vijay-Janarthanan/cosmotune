package com.cosmotune.video.importers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * NewPipe's subscriptions export (spec 2.7, 8.2), also what FreeTube writes in "NewPipe format":
 * `{"app_version": "...", "app_version_int": n, "subscriptions": [{"service_id": 0, "url": "...", "name": "..."}]}`.
 *
 * - Only `service_id == 0` is read (an entry of another service is skipped; an entry with no `service_id` is read by its
 *   address, because all three top-level keys and every field are optional in the exports other apps write).
 * - `/channel/UC...` addresses give the channel id directly. `/user/<name>`, `/c/<name>` and `/@handle` addresses (old exports
 *   hold the first kind) go to [ImportPreview.unresolvedChannels] for the import screen to resolve through `VideoSource`.
 * - The same channel twice counts once; the repeat is skipped.
 */
object NewPipeSubscriptionsImporter {
    /** Reads the export in [json]. [nowMs] is the time the channels are followed at (the file has none). */
    fun parse(json: String, nowMs: Long): ImportPreview {
        val root = try {
            Json.parseToJsonElement(json.removePrefix("\uFEFF"))
        } catch (e: Exception) {
            return ImportPreview.failed(ImportReason.UNREADABLE)
        }
        if (root !is JsonObject) return ImportPreview.failed(ImportReason.UNREADABLE)
        val collector = SubscriptionCollector(nowMs)
        for (element in root["subscriptions"].asArray().orEmpty()) {
            val entry = element.asObject()
            val service = entry?.get("service_id").asLong()
            val url = entry?.get("url").asText()
            if (entry == null || url == null || (service != null && service != 0L)) {
                collector.skip()
                continue
            }
            collector.addByAddress(url, entry["name"].asText())
        }
        return collector.preview()
    }
}
