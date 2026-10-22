package com.liuying.app.data.remote

enum class AddonErrorKind {
    InvalidUrl, NotManifestUrl, NoConnection, Timeout, Tls, HttpStatus, NotJson,
    MissingIdentity, Incompatible, Empty, Unknown,
}

/** Carried inside kotlin.Result.failure; the client never throws (except coroutine cancellation). */
class AddonFailure(
    val kind: AddonErrorKind,
    override val message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Short, user-presentable explanation. */
    val userMessage: String
        get() = when (kind) {
            AddonErrorKind.InvalidUrl -> "That does not look like a valid link."
            AddonErrorKind.NotManifestUrl -> "The link should end with /manifest.json - copy the full manifest link from the source."
            AddonErrorKind.NoConnection -> "No connection, or the source could not be reached."
            AddonErrorKind.Timeout -> "The source took too long to respond."
            AddonErrorKind.Tls -> "A secure connection could not be established with this source."
            AddonErrorKind.HttpStatus -> "The source answered with an error (HTTP ${httpCode ?: "?"})."
            AddonErrorKind.NotJson -> if (message.startsWith("This link does not look")) message else "The source did not return valid data."
            AddonErrorKind.MissingIdentity -> "The manifest is missing an id or a name."
            AddonErrorKind.Incompatible -> "This source does not offer anything this app can use."
            AddonErrorKind.Empty -> "The source had nothing for this."
            AddonErrorKind.Unknown -> message
        }

    companion object {
        /** Maps any unexpected exception to a diagnosable failure (never a bare "Unexpected error"). */
        fun fromUnexpected(e: Throwable): AddonFailure {
            runCatching { android.util.Log.w("Liuying", "Unexpected addon error", e) }
            val name = e.javaClass.simpleName.ifBlank { e.javaClass.name }
            return when (e) {
                is AddonFailure -> e
                is kotlinx.serialization.SerializationException, is IllegalArgumentException ->
                    AddonFailure(AddonErrorKind.NotJson, "This link does not look like an add-on manifest ($name: ${e.message?.take(120) ?: "no details"})", cause = e)
                else -> AddonFailure(AddonErrorKind.Unknown, "Could not read this link ($name${e.message?.let { ": " + it.take(120) } ?: ""})", cause = e)
            }
        }
    }
}

fun Throwable.toUserMessage(): String = (this as? AddonFailure)?.userMessage ?: (message ?: "Something went wrong")
