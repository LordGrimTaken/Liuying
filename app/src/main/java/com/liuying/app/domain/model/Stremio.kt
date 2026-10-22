package com.liuying.app.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ------------------------------------------------------------------------------------------------
// Manifest
// ------------------------------------------------------------------------------------------------

/**
 * A resource declaration. In a manifest it is either a bare string ("stream") or an object
 * ({"name":"stream","types":["movie"],"idPrefixes":["tt"]}). [types]/[idPrefixes] null means
 * "inherit the manifest's top-level value".
 */
data class ManifestResource(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

object ManifestResourceSerializer : KSerializer<ManifestResource> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: ManifestResource) {
        val out = encoder as? JsonEncoder ?: throw SerializationException("Json only")
        if (value.types == null && value.idPrefixes == null) {
            out.encodeJsonElement(JsonPrimitive(value.name))
        } else {
            out.encodeJsonElement(buildJsonObject {
                put("name", value.name)
                value.types?.let { put("types", JsonArray(it.map(::JsonPrimitive))) }
                value.idPrefixes?.let { put("idPrefixes", JsonArray(it.map(::JsonPrimitive))) }
            })
        }
    }

    override fun deserialize(decoder: Decoder): ManifestResource {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: throw SerializationException("Json only")
        return when (el) {
            is JsonNull -> throw SerializationException("null resource")
            is JsonPrimitive -> ManifestResource(el.content)
            is JsonObject -> {
                val name = el["name"]?.flexString()?.takeIf { it.isNotBlank() }
                    ?: throw SerializationException("resource without name")
                ManifestResource(name, flexStrings(el["types"]), flexStrings(el["idPrefixes"]))
            }
            else -> throw SerializationException("bad resource")
        }
    }
}

object ResourceListSerializer : TolerantListSerializer<ManifestResource>(ManifestResourceSerializer)

@Serializable
data class CatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    @Serializable(FlexStringListSerializer::class) val options: List<String> = emptyList(),
    @Serializable(FlexIntSerializer::class) val optionsLimit: Int? = null,
)

object CatalogExtraListSerializer : TolerantListSerializer<CatalogExtra>(CatalogExtra.serializer())

@Serializable
data class ManifestCatalog(
    val type: String,
    val id: String,
    val name: String = "",
    @Serializable(CatalogExtraListSerializer::class) val extra: List<CatalogExtra> = emptyList(),
    /** Legacy (pre-`extra`) declarations. */
    @Serializable(FlexStringListSerializer::class) val extraRequired: List<String> = emptyList(),
    @Serializable(FlexStringListSerializer::class) val extraSupported: List<String> = emptyList(),
    /** Legacy genre option list. */
    @Serializable(FlexStringListSerializer::class) val genres: List<String> = emptyList(),
)

object CatalogListSerializer : TolerantListSerializer<ManifestCatalog>(ManifestCatalog.serializer())

@Serializable
data class ManifestBehaviorHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
)

@Serializable
data class Manifest(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val logo: String? = null,
    val background: String? = null,
    val contactEmail: String? = null,
    @Serializable(ResourceListSerializer::class) val resources: List<ManifestResource> = emptyList(),
    @Serializable(FlexStringListSerializer::class) val types: List<String> = emptyList(),
    @Serializable(FlexStringListSerializer::class) val idPrefixes: List<String> = emptyList(),
    @Serializable(CatalogListSerializer::class) val catalogs: List<ManifestCatalog> = emptyList(),
    val behaviorHints: ManifestBehaviorHints = ManifestBehaviorHints(),
)

// ------------------------------------------------------------------------------------------------
// Meta
// ------------------------------------------------------------------------------------------------

@Serializable
data class MetaPreview(
    val id: String,
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val year: String? = null,
    @Serializable(FlexStringListSerializer::class) val genres: List<String> = emptyList(),
) {
    val title: String get() = name.ifBlank { id }
    /** Best-effort year: releaseInfo ("2019", "2019-2022"), else year. */
    val displayYear: String? get() = (releaseInfo ?: year)?.takeIf { it.isNotBlank() }
}

object MetaPreviewListSerializer : TolerantListSerializer<MetaPreview>(MetaPreview.serializer())

@Serializable
data class Trailer(val source: String = "", val type: String? = null)

object TrailerListSerializer : TolerantListSerializer<Trailer>(Trailer.serializer())

@Serializable
data class MetaLink(val name: String = "", val category: String = "", val url: String = "")

object MetaLinkListSerializer : TolerantListSerializer<MetaLink>(MetaLink.serializer())

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    /** Some addons use `name` instead of `title`. */
    val name: String? = null,
    val released: String? = null,
    @Serializable(FlexIntSerializer::class) val season: Int? = null,
    @Serializable(FlexIntSerializer::class) val episode: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null,
    val available: Boolean? = null,
) {
    val displayTitle: String? get() = (title ?: name)?.takeIf { it.isNotBlank() }
    val displayOverview: String? get() = (overview ?: description)?.takeIf { it.isNotBlank() }
}

object VideoListSerializer : TolerantListSerializer<Video>(Video.serializer())

@Serializable
data class MetaBehaviorHints(
    val defaultVideoId: String? = null,
    val hasScheduledVideos: Boolean = false,
)

@Serializable
data class Meta(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val released: String? = null,
    val year: String? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
    @Serializable(FlexStringListSerializer::class) val genres: List<String> = emptyList(),
    /** Deprecated singular key some addons still emit. */
    @Serializable(FlexStringListSerializer::class) val genre: List<String> = emptyList(),
    @Serializable(FlexStringListSerializer::class) val cast: List<String> = emptyList(),
    @Serializable(FlexStringListSerializer::class) val director: List<String> = emptyList(),
    @Serializable(FlexStringListSerializer::class) val directors: List<String> = emptyList(),
    @Serializable(FlexStringListSerializer::class) val writer: List<String> = emptyList(),
    @Serializable(FlexStringListSerializer::class) val writers: List<String> = emptyList(),
    val country: String? = null,
    val language: String? = null,
    val awards: String? = null,
    val website: String? = null,
    @Serializable(TrailerListSerializer::class) val trailers: List<Trailer> = emptyList(),
    @Serializable(StreamListSerializer::class) val trailerStreams: List<Stream> = emptyList(),
    @Serializable(MetaLinkListSerializer::class) val links: List<MetaLink> = emptyList(),
    @Serializable(VideoListSerializer::class) val videos: List<Video> = emptyList(),
    val behaviorHints: MetaBehaviorHints = MetaBehaviorHints(),
) {
    val title: String get() = name.ifBlank { id }
    val allGenres: List<String> get() = (genres + genre).distinct()
    val allDirectors: List<String> get() = (directors + director).distinct()
    val allWriters: List<String> get() = (writers + writer).distinct()
    val displayYear: String? get() = (releaseInfo ?: year)?.takeIf { it.isNotBlank() }
    val isSeriesLike: Boolean get() = videos.any { it.season != null && it.episode != null }

    fun toPreview() = MetaPreview(
        id = id, type = type, name = name, poster = poster, posterShape = posterShape,
        background = background, logo = logo, description = description, releaseInfo = releaseInfo,
        imdbRating = imdbRating, runtime = runtime, year = year, genres = allGenres,
    )
}

// ------------------------------------------------------------------------------------------------
// Stream and subtitles
// ------------------------------------------------------------------------------------------------

@Serializable
data class Subtitle(
    val url: String,
    val id: String = "",
    val lang: String = "und",
    val label: String? = null,
)

object SubtitleListSerializer : TolerantListSerializer<Subtitle>(Subtitle.serializer())

@Serializable
data class ProxyHeaders(
    @Serializable(FlexStringMapSerializer::class) val request: Map<String, String> = emptyMap(),
    @Serializable(FlexStringMapSerializer::class) val response: Map<String, String> = emptyMap(),
)

@Serializable
data class StreamBehaviorHints(
    @Serializable(FlexStringListSerializer::class) val countryWhitelist: List<String> = emptyList(),
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val proxyHeaders: ProxyHeaders? = null,
    /** Non-standard but seen in the wild: plain request headers. */
    @Serializable(FlexStringMapSerializer::class) val headers: Map<String, String> = emptyMap(),
    val videoHash: String? = null,
    @Serializable(FlexLongSerializer::class) val videoSize: Long? = null,
    val filename: String? = null,
)

@Serializable
data class Stream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    @Serializable(FlexIntSerializer::class) val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    @Serializable(FlexStringListSerializer::class) val sources: List<String> = emptyList(),
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
    @Serializable(SubtitleListSerializer::class) val subtitles: List<Subtitle> = emptyList(),
) {
    /** Request headers the player must send (proxyHeaders.request wins over plain headers). */
    val requestHeaders: Map<String, String>
        get() = behaviorHints.headers + (behaviorHints.proxyHeaders?.request ?: emptyMap())
}

object StreamListSerializer : TolerantListSerializer<Stream>(Stream.serializer())

// ------------------------------------------------------------------------------------------------
// Response envelopes
// ------------------------------------------------------------------------------------------------

@Serializable
data class CatalogResponse(
    @Serializable(MetaPreviewListSerializer::class) val metas: List<MetaPreview> = emptyList(),
    @Serializable(FlexLongSerializer::class) val cacheMaxAge: Long? = null,
    val hasMore: Boolean? = null,
)

@Serializable
data class MetaResponse(val meta: Meta? = null)

@Serializable
data class StreamResponse(
    @Serializable(StreamListSerializer::class) val streams: List<Stream> = emptyList(),
)

@Serializable
data class SubtitlesResponse(
    @Serializable(SubtitleListSerializer::class) val subtitles: List<Subtitle> = emptyList(),
)
