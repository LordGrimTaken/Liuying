package com.liuying.app.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The single Json configuration used for everything that touches Stremio-protocol payloads and
 * for storing manifest snapshots. Deliberately forgiving: unknown keys are ignored, nulls on
 * non-null fields fall back to defaults, unquoted/quoted primitives are accepted.
 */
val StremioJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
    allowTrailingComma = true
}

internal fun JsonElement.flexString(): String? =
    if (this is JsonPrimitive && this !is JsonNull) content else null

/** Turns "a", ["a","b"], [1,2], [{"name":"a"}] or null into a list of strings (null when absent/unusable). */
internal fun flexStrings(el: JsonElement?): List<String>? = when (el) {
    null -> null
    is JsonNull -> null
    is JsonArray -> el.mapNotNull {
        when (it) {
            is JsonNull -> null
            is JsonPrimitive -> it.content
            is JsonObject -> it["name"]?.flexString()
            else -> null
        }
    }
    is JsonPrimitive -> listOf(el.content)
    is JsonObject -> null
}

/** A string list that tolerates a single string, nulls, numbers and name-objects. */
object FlexStringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: List<String>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<String> {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return delegate.deserialize(decoder)
        return flexStrings(el) ?: emptyList()
    }
}

/** Int that accepts 3, "3", 3.0. Unparseable values fail the enclosing object (which tolerant lists drop). */
object FlexIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeInt()
        return (el as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.toInt()
            ?: throw SerializationException("Not a number: $el")
    }
}

object FlexLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexLong", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeLong()
        return (el as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.toLong()
            ?: throw SerializationException("Not a number: $el")
    }
}

/** Map<String,String> that drops entries whose value is not a primitive. */
object FlexStringMapSerializer : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Map<String, String>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Map<String, String> {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return delegate.deserialize(decoder)
        if (el !is JsonObject) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in el) v.flexString()?.let { out[k] = it }
        return out
    }
}

/** A list whose malformed elements are dropped one by one instead of failing the whole payload. */
open class TolerantListSerializer<T>(private val elem: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(elem)
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<T> {
        val jd = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val el = jd.decodeJsonElement()
        if (el !is JsonArray) return emptyList()
        return el.mapNotNull { runCatching { jd.json.decodeFromJsonElement(elem, it) }.getOrNull() }
    }
}
