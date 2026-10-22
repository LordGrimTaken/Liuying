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
            AddonErrorKind.NotJson -> "The source did not return valid data."
            AddonErrorKind.MissingIdentity -> "The manifest is missing an id or a name."
            AddonErrorKind.Incompatible -> "This source does not offer anything this app can use."
            AddonErrorKind.Empty -> "The source had nothing for this."
            AddonErrorKind.Unknown -> message
        }
}

fun Throwable.toUserMessage(): String = (this as? AddonFailure)?.userMessage ?: (message ?: "Something went wrong")
