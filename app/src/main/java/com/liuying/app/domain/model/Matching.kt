package com.liuying.app.domain.model

/** Resource names from the protocol. */
object Resources {
    const val CATALOG = "catalog"
    const val META = "meta"
    const val STREAM = "stream"
    const val SUBTITLES = "subtitles"
}

object Extras {
    const val SEARCH = "search"
    const val SKIP = "skip"
    const val GENRE = "genre"
}

internal fun idMatchesPrefixes(prefixes: List<String>, id: String): Boolean =
    prefixes.isEmpty() || prefixes.any { id.startsWith(it) }

/**
 * Spec rule: a source is asked for a resource only if it declares that resource, the requested type is among
 * its types for that resource, and the id starts with one of its idPrefixes (when any are declared; none = any id).
 * An object-form resource's own types/idPrefixes win over the manifest's top-level ones. If no types are
 * declared anywhere we are lenient and accept any type.
 */
fun Manifest.declares(resource: String, type: String, id: String?): Boolean =
    resources.any { r ->
        if (!r.name.equals(resource, ignoreCase = true)) return@any false
        val types = r.types?.takeIf { it.isNotEmpty() } ?: this.types
        val prefixes = r.idPrefixes?.takeIf { it.isNotEmpty() } ?: this.idPrefixes
        val typeOk = types.isEmpty() || types.any { it.equals(type, ignoreCase = true) }
        val idOk = id == null || idMatchesPrefixes(prefixes, id)
        typeOk && idOk
    }

/** True if the manifest lists the resource at all (ignoring type/id). */
fun Manifest.declaresAny(resource: String): Boolean =
    resources.any { it.name.equals(resource, ignoreCase = true) }

/** Catalogs only count when the manifest declares the catalog resource (or declares no resources at all). */
val Manifest.usableCatalogs: List<ManifestCatalog>
    get() = if (resources.isEmpty() || declaresAny(Resources.CATALOG)) catalogs else emptyList()

val Manifest.providesCatalogs: Boolean get() = usableCatalogs.isNotEmpty()
val Manifest.providesStreams: Boolean get() = declaresAny(Resources.STREAM)
val Manifest.providesSubtitles: Boolean get() = declaresAny(Resources.SUBTITLES)
val Manifest.providesMeta: Boolean get() = declaresAny(Resources.META)

// ---- catalog extras -------------------------------------------------------------------------

private fun ManifestCatalog.extraDef(name: String): CatalogExtra? = extra.firstOrNull { it.name == name }

val ManifestCatalog.displayName: String get() = name.ifBlank { id }

/** All extra names this catalog understands (new `extra` array plus legacy extraSupported/extraRequired). */
val ManifestCatalog.supportedExtras: Set<String>
    get() = (extra.map { it.name } + extraSupported + extraRequired).toSet()

val ManifestCatalog.requiredExtras: Set<String>
    get() = (extra.filter { it.isRequired }.map { it.name } + extraRequired).toSet()

val ManifestCatalog.supportsSearch: Boolean get() = Extras.SEARCH in supportedExtras
val ManifestCatalog.supportsSkip: Boolean get() = Extras.SKIP in supportedExtras
val ManifestCatalog.supportsGenre: Boolean get() = Extras.GENRE in supportedExtras
val ManifestCatalog.searchRequired: Boolean get() = Extras.SEARCH in requiredExtras
val ManifestCatalog.genreRequired: Boolean get() = Extras.GENRE in requiredExtras

val ManifestCatalog.genreOptions: List<String>
    get() = extraDef(Extras.GENRE)?.options?.takeIf { it.isNotEmpty() } ?: genres

fun ManifestCatalog.optionsFor(extraName: String): List<String> = extraDef(extraName)?.options.orEmpty()

/**
 * Extra params needed to browse this catalog without user input (e.g. a required genre gets its first
 * option), or null if it cannot be browsed without user input (search-required, or a required extra
 * without a fixed option list). `skip` is never included here.
 */
fun ManifestCatalog.requiredExtraDefaults(): Map<String, String>? {
    val out = LinkedHashMap<String, String>()
    for (name in requiredExtras) {
        when (name) {
            Extras.SKIP -> Unit
            Extras.SEARCH -> return null
            Extras.GENRE -> out[name] = genreOptions.firstOrNull() ?: return null
            else -> out[name] = optionsFor(name).firstOrNull() ?: return null
        }
    }
    return out
}

/** Browsable on Home/See-all without user input. */
val ManifestCatalog.isHomeEligible: Boolean get() = requiredExtraDefaults() != null

/** Spec: catalogs requiring an extra the app cannot supply are flagged "needs input" in source detail. */
val ManifestCatalog.needsInput: Boolean get() = !searchRequired && requiredExtraDefaults() == null

val Manifest.isAdult: Boolean get() = behaviorHints.adult
