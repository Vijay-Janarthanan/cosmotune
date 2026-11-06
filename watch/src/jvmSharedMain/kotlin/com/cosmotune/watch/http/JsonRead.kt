package com.cosmotune.watch.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Forgiving readers for the JSON plugins return: a missing or mistyped field reads as null, never throws. */
internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject
internal val JsonElement?.arr: JsonArray? get() = this as? JsonArray
internal val JsonElement?.prim: JsonPrimitive? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonElement?.str(name: String): String? = obj?.get(name).prim?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonElement?.long(name: String): Long? = obj?.get(name).prim?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.trim()?.toLongOrNull() }
internal fun JsonElement?.int(name: String): Int? = long(name)?.toInt()
internal fun JsonElement?.double(name: String): Double? = obj?.get(name).prim?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonElement?.bool(name: String): Boolean? = obj?.get(name).prim?.let { it.booleanOrNull ?: it.contentOrNull?.let { s -> s == "1" || s.equals("true", true) } }
internal fun JsonElement?.child(name: String): JsonElement? = obj?.get(name)?.takeIf { it !is JsonNull }
internal fun JsonElement?.list(name: String): List<JsonElement> = obj?.get(name).arr.orEmpty()
internal fun JsonElement?.strings(name: String): List<String> = list(name).mapNotNull { it.prim?.contentOrNull?.takeIf { s -> s.isNotBlank() } }

/** A path through objects: `root.at("MediaContainer", "Metadata")`. */
internal fun JsonElement?.at(vararg path: String): JsonElement? = path.fold(this) { e, k -> e.child(k) }

internal fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 }

internal fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
