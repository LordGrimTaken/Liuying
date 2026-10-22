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
            AddonErrorKind.NoConnection -> "Can't reach the source. Check your connection."
            AddonErrorKind.Timeout -> "Can't reach the source: it took too long to respond."
            AddonErrorKind.Tls -> "A secure connection could not be established with this source."
            AddonErrorKind.HttpStatus -> "The source answered with an error (HTTP ${httpCode ?: "?"})."
            AddonErrorKind.NotJson -> if (message.startsWith("This link does not look")) message else "The source did not return valid data."
            AddonErrorKind.MissingIdentity -> "The manifest is missing an id or a name."
            AddonErrorKind.Incompatible -> "This source does not offer anything this app can use."
            AddonErrorKind.Empty -> "The source had nothing for this."
            AddonErrorKind.Unknown -> message
        }

    companion object {
        /** Maps a network-level IOException (reset, EOF, timeout, no route, TLS) to a failure; never NotJson. */
        fun fromIo(e: java.io.IOException): AddonFailure {
            val msg = e.message.orEmpty()
            val dropped = msg.contains("reset", true) || msg.contains("closed", true) || msg.contains("unexpected end", true) ||
                msg.contains("broken pipe", true) || msg.contains("eof", true) || e is java.io.EOFException || e.cause is java.net.SocketException
            return when {
                e is javax.net.ssl.SSLException && !dropped -> AddonFailure(AddonErrorKind.Tls, e.message ?: "TLS error", cause = e)
                e is java.io.InterruptedIOException || msg.contains("timeout", true) || msg.contains("timed out", true) ->
                    AddonFailure(AddonErrorKind.Timeout, "Timed out", cause = e)
                else -> AddonFailure(AddonErrorKind.NoConnection, e.message ?: "Network error", cause = e)
            }
        }

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
