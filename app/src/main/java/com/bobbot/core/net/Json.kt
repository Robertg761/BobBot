package com.bobbot.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = false
}

// Small, forgiving accessors for JsonElement trees. The server's shapes are loose;
// these keep call sites readable instead of chains of jsonObject/jsonPrimitive.
val JsonElement?.obj: JsonObject? get() = (this as? JsonObject)
val JsonElement?.arr: JsonArray? get() = (this as? JsonArray)
fun JsonElement?.str(key: String): String? = obj?.get(key)?.asString()
fun JsonElement?.int(key: String): Int? = obj?.get(key)?.asInt()
fun JsonElement?.long(key: String): Long? = obj?.get(key)?.asLong()
fun JsonElement?.dbl(key: String): Double? = obj?.get(key)?.asDouble()
fun JsonElement?.bool(key: String): Boolean? = obj?.get(key)?.asBool()
fun JsonElement?.child(key: String): JsonElement? = obj?.get(key)?.takeUnless { it is JsonNull }
fun JsonElement?.list(key: String): List<JsonElement> = obj?.get(key)?.arr?.toList() ?: emptyList()

fun JsonElement.asString(): String? = when (this) {
    is JsonPrimitive -> if (this is JsonNull) null else contentOrNull ?: content
    is JsonObject, is JsonArray -> toString()
}
fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
fun JsonElement.asLong(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonElement.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
fun JsonElement.asBool(): Boolean? = (this as? JsonPrimitive)?.let { p ->
    p.booleanOrNull ?: p.intOrNull?.let { it != 0 } ?: p.contentOrNull?.lowercase()?.let { it == "true" }
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.filter { it.second != null }.associate { (k, v) -> k to toJson(v) }
)

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    is Array<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}

fun JsonElement?.prettyText(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> contentOrNull ?: content
    else -> try { json.encodeToString(JsonElement.serializer(), this) } catch (_: Throwable) { toString() }
}
