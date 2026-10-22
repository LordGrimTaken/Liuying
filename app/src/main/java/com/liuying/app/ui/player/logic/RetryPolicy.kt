package com.liuying.app.ui.player.logic

import androidx.media3.common.PlaybackException

/** Exponential backoff schedule totalling roughly 30 s before the user sees an error. */
class RetryPolicy(private val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000)) {
    val maxAttempts: Int get() = delaysMs.size

    /** Delay before retry number [attempt] (0-based), or null when the budget is exhausted. */
    fun delayFor(attempt: Int): Long? = delaysMs.getOrNull(attempt)

    val totalBudgetMs: Long get() = delaysMs.sum()
}

enum class ErrorKind {
    /** Transient connectivity/timeouts: retry with backoff. */
    NETWORK,
    /** 401/403/404/410 etc: link probably expired. */
    LINK_EXPIRED,
    /** Codec/container not supported by this device. */
    UNSUPPORTED,
    /** Live window fell behind etc: re-prepare immediately. */
    BEHIND_LIVE_WINDOW,
    /** The subtitle/stream could not be parsed. */
    MALFORMED,
    OTHER,
}

object ErrorClassifier {
    fun classify(errorCode: Int, httpStatus: Int?): ErrorKind {
        if (httpStatus != null) {
            when (httpStatus) {
                401, 403, 404, 410 -> return ErrorKind.LINK_EXPIRED
                408, 429, in 500..599 -> return ErrorKind.NETWORK
            }
        }
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> ErrorKind.NETWORK
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                if (httpStatus != null && httpStatus >= 400 && httpStatus < 500) ErrorKind.LINK_EXPIRED else ErrorKind.NETWORK
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> ErrorKind.LINK_EXPIRED
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> ErrorKind.BEHIND_LIVE_WINDOW
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> ErrorKind.UNSUPPORTED
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> ErrorKind.MALFORMED
            else -> ErrorKind.OTHER
        }
    }

    fun title(kind: ErrorKind): String = when (kind) {
        ErrorKind.NETWORK -> "Connection problem"
        ErrorKind.LINK_EXPIRED -> "This stream link isn't working"
        ErrorKind.UNSUPPORTED -> "Can't play this format"
        ErrorKind.MALFORMED -> "This file looks damaged"
        ErrorKind.BEHIND_LIVE_WINDOW, ErrorKind.OTHER -> "Playback failed"
    }

    fun message(kind: ErrorKind): String = when (kind) {
        ErrorKind.NETWORK -> "We kept trying to reconnect but the connection did not recover. Check your network and try again."
        ErrorKind.LINK_EXPIRED -> "The link may have expired or the server refused it. Try another stream, or reload the streams for this title."
        ErrorKind.UNSUPPORTED -> "This device can't decode this video or audio format. Try another stream, or open it in an external player."
        ErrorKind.MALFORMED -> "The stream could not be read. Try another stream."
        ErrorKind.BEHIND_LIVE_WINDOW, ErrorKind.OTHER -> "Something went wrong while playing. Retry, or try another stream."
    }

    /** L050: what happened, why, and the next step, with the action to emphasise. */
    fun copy(kind: ErrorKind, httpStatus: Int? = null): ErrorCopy = when {
        kind == ErrorKind.LINK_EXPIRED && httpStatus == 403 -> ErrorCopy(
            "The source refused this link", "This stream did not start.",
            "The server answered \"403 forbidden\". Links from some sources only work for a short time or only from the app that asked.",
            "Try another stream, or reload the stream list for a fresh link.", ErrorAction.TRY_ANOTHER,
        )
        kind == ErrorKind.LINK_EXPIRED && (httpStatus == 404 || httpStatus == 410) -> ErrorCopy(
            "This stream is gone", "The file could not be found.",
            "The server answered \"${httpStatus}\": the link has expired or the file was removed.",
            "Try another stream, or reload the stream list for a fresh link.", ErrorAction.TRY_ANOTHER,
        )
        kind == ErrorKind.LINK_EXPIRED && httpStatus == 401 -> ErrorCopy(
            "This stream needs a sign-in", "The source asked for credentials it did not get.",
            "The server answered \"401 unauthorised\". Some sources need to be configured on their own page first.",
            "Open the source in Sources to configure it, or try another stream.", ErrorAction.TRY_ANOTHER,
        )
        kind == ErrorKind.LINK_EXPIRED -> ErrorCopy(
            "This stream link isn't working", "The stream did not start.",
            "The link may have expired, or the server refused it.",
            "Try another stream, or reload the stream list for a fresh link.", ErrorAction.TRY_ANOTHER,
        )
        kind == ErrorKind.NETWORK -> ErrorCopy(
            "Connection problem", "Playback stopped after several automatic retries.",
            "Your connection dropped, or the server was too slow to answer.",
            "Check your connection and retry, or try another stream.", ErrorAction.RETRY,
        )
        kind == ErrorKind.UNSUPPORTED -> ErrorCopy(
            "Can't play this format", "This device cannot decode the video or audio.",
            "The stream uses a codec or container this phone does not support, for example HEVC, AV1 or Dolby Vision.",
            "Try a stream marked H.264 or a lower resolution, or open it in an external player.", ErrorAction.TRY_ANOTHER,
        )
        kind == ErrorKind.MALFORMED -> ErrorCopy(
            "This file looks damaged", "The stream could not be read.",
            "The file is incomplete or not a valid video.",
            "Try another stream.", ErrorAction.TRY_ANOTHER,
        )
        else -> ErrorCopy(
            "Playback failed", "Something went wrong while playing.",
            "The player reported an error it could not recover from.",
            "Retry, or try another stream.", ErrorAction.RETRY,
        )
    }
}

enum class ErrorAction { RETRY, TRY_ANOTHER }

data class ErrorCopy(val title: String, val what: String, val why: String, val next: String, val primary: ErrorAction)
