package com.cosmotune.providers

import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.select.Elements

/** Small Cheerio-compatible DOM boundary. Nodes stay in the host and only bounded handles/strings enter JS. */
internal class WatchDomBridge {
    private val selections = mutableMapOf<Int, Elements>()
    private var next = 0
    private var parsedChars = 0
    fun call(method: String, encoded: String): String = try {
        require(encoded.length <= ProviderScripts.MAX_RESULT_BYTES)
        val args = Json.parseToJsonElement(encoded).jsonArray
        fun text(index: Int) = args.getOrNull(index)?.jsonPrimitive?.content.orEmpty()
        fun nodes(index: Int = 0) = selections[args[index].jsonPrimitive.int] ?: error("Unknown DOM selection")
        fun keep(value: Elements): JsonObject {
            require(selections.size < 2048)
            val id = ++next
            selections[id] = Elements(value.take(20_000))
            return buildJsonObject { put("handle", id); put("length", selections.getValue(id).size) }
        }
        val value: JsonElement = when (method) {
            "dom.load" -> {
                val html = text(0); parsedChars += html.length
                require(parsedChars <= 2 * 1024 * 1024)
                keep(Elements(listOf(Jsoup.parse(html))))
            }
            "dom.find" -> {
                val selector = text(1)
                require(selector.length <= 500 && !selector.contains("matches", true)) { "Unsupported DOM selector" }
                keep(nodes().select(selector))
            }
            "dom.at" -> keep(Elements(listOfNotNull(nodes().getOrNull(args[1].jsonPrimitive.int))))
            "dom.text" -> JsonPrimitive(nodes().text())
            "dom.html" -> JsonPrimitive(nodes().firstOrNull()?.html().orEmpty())
            "dom.attr" -> JsonPrimitive(nodes().firstOrNull()?.attr(text(1)).orEmpty())
            "dom.parent" -> keep(nodes().parents())
            "dom.children" -> keep(Elements(nodes().flatMap { it.children() }))
            "dom.remove" -> { nodes().remove(); JsonNull }
            else -> error("Unsupported DOM operation")
        }
        buildJsonObject { put("value", value) }.toString().also { require(it.length <= ProviderScripts.MAX_BRIDGE_BYTES) }
    } catch (_: Exception) { "{\"failure\":\"Unsupported or oversized DOM operation\"}" }
}
