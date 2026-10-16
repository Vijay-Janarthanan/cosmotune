package com.cosmotune.video.source.newpipe

import com.cosmotune.video.model.PageToken
import com.cosmotune.video.source.VideoSourceError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.schabi.newpipe.extractor.Page
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NewPipe's `Page` (an address, an id, ids, cookies and body bytes, whichever the extractor needs) as the opaque
 * [PageToken] the rest of Video passes around (plan task Y-4, step 5): a JSON object with the body in base64.
 *
 * Keys: `v` the version (1), `u` the address, `i` the id, `d` the ids, `c` the cookies, `b` the body in base64, and `x` an
 * optional context string of the adapter's own (the replies token of a comment carries its video's id, which NewPipe's
 * page does not). Absent keys mean null, so a null stays null and an empty list stays empty. The same page always gives
 * the same token (cookies are written in key order), so tokens can be compared and used as keys.
 */
internal object PageCodec {
    private const val VERSION = 1

    /** A decoded token: the page to give NewPipe and the adapter's context, if the token carried one. */
    class Decoded(val page: Page, val context: String?)

    fun encode(page: Page, context: String? = null): PageToken {
        val json = buildJsonObject {
            put("v", VERSION)
            page.url?.let { put("u", it) }
            page.id?.let { put("i", it) }
            page.ids?.let { ids -> put("d", JsonArray(ids.map { JsonPrimitive(it) })) }
            page.cookies?.let { cookies ->
                put("c", JsonObject(cookies.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            }
            page.body?.let { put("b", encodeBase64(it)) }
            context?.let { put("x", it) }
        }
        return PageToken(Json.encodeToString(JsonObject.serializer(), json))
    }

    /** The page and context a token was made from; a token that is not one of ours is a [VideoSourceError.Parsing]. */
    fun decode(token: PageToken): Decoded {
        try {
            val json = Json.parseToJsonElement(token.value).jsonObject
            require(json["v"]?.jsonPrimitive?.intOrNull == VERSION) { "unknown token version" }
            val url = json["u"]?.jsonPrimitive?.contentOrNull
            val id = json["i"]?.jsonPrimitive?.contentOrNull
            val ids = json["d"]?.jsonArray?.map { it.jsonPrimitive.content }
            val cookies = json["c"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
            val body = json["b"]?.jsonPrimitive?.content?.let { decodeBase64(it) }
            val page = Page(url, id, ids, cookies, body)
            require(Page.isValid(page)) { "token without an address or ids" }
            return Decoded(page, json["x"]?.jsonPrimitive?.contentOrNull)
        } catch (e: VideoSourceError) {
            throw e
        } catch (e: Exception) {
            throw VideoSourceError.Parsing("Not a page token of this source", e)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeBase64(bytes: ByteArray): String = Base64.Default.encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(text: String): ByteArray = Base64.Default.decode(text)
}
