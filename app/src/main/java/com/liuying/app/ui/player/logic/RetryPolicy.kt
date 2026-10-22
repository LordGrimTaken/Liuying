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
}
