package com.liuying.app.domain.party

import kotlin.math.abs

/**
 * The watch party rules that do not need a network or a player, so they can be tested directly:
 * code handling, when a poll response may move the guest's player, and how often the host may write.
 *
 * The server contract (docs/watch-party.md): a room is identified by a 6-letter uppercase code, `rev` grows on every
 * host write, and a guest applies the host's state only when `rev` is newer *and* the local player has drifted by more
 * than [DRIFT_MS] (or the play/pause state differs).
 */
object PartyLogic {
    const val CODE_LENGTH = 6

    /** How often a participant asks for the room. */
    const val POLL_MS = 2_000L

    /** The host writes at most once per this interval. */
    const val WRITE_INTERVAL_MS = 2_000L

    /** The guest's player is only moved when it is more than this far from the host. */
    const val DRIFT_MS = 2_000L

    /** How often the host checks whether there is something worth writing. */
    const val PUSH_TICK_MS = 500L

    /** A paused host does not rewrite its position, so this much movement is needed to justify a write. */
    const val MIN_PUSH_DELTA_MS = 1_000L

    /** Where the web player opens a room; keep in step with the site's `/watch/?party=CODE` route. */
    const val LINK_BASE = "https://www.liuyingapp.com/watch/?party="

    /**
     * A typed or pasted code in canonical form, or null. Case, surrounding spaces and hyphens are forgiven; anything
     * that is not exactly six letters (after that) is rejected before a request is made.
     */
    fun normalizeCode(raw: String): String? {
        val compact = raw.filterNot { it.isWhitespace() || it == '-' }.uppercase()
        return compact.takeIf { it.length == CODE_LENGTH && it.all { c -> c in 'A'..'Z' } }
    }

    /**
     * A code typed by the user, accepting either the six letters or a whole shared link
     * (`https://www.liuyingapp.com/watch/?party=ABCDEF`) pasted from the web player or a chat.
     */
    fun codeFromInput(raw: String): String? {
        val text = raw.trim()
        val marker = "party="
        val at = text.indexOf(marker, ignoreCase = true)
        if (at < 0) return normalizeCode(text)
        val value = text.substring(at + marker.length).takeWhile { it.isLetterOrDigit() }
        return normalizeCode(value)
    }

    /** The link to share for [code], or null when it is not a valid code. */
    fun shareLink(code: String?): String? = normalizeCode(code.orEmpty())?.let { LINK_BASE + it }

    /** True when the remote state belongs to the title this device is playing. Unknown local titles never block. */
    fun sameTitle(state: PartyPlaybackState, local: PartyLocalPlayback): Boolean {
        val mediaId = local.mediaId ?: return true
        return state.mediaId == mediaId && state.episodeId == local.episodeId
    }

    /**
     * Compare a poll response with the last revision this device saw and what it is playing.
     *
     * [PartyDecision.lastRev] advances whenever the response is newer, even when nothing is applied: `rev` is the
     * freshness gate, and a state already seen must not be considered again on the next poll. [PartyDecision.apply]
     * is non-null only when the local player is more than [DRIFT_MS] away or its play/pause state differs.
     */
    fun evaluate(
        lastRev: Long,
        responseRev: Long,
        state: PartyPlaybackState,
        localPositionMs: Long,
        localPlaying: Boolean,
    ): PartyDecision {
        if (responseRev <= lastRev) return PartyDecision(lastRev, null)
        val drifted = abs(state.positionMs - localPositionMs) > DRIFT_MS
        val playingDiffers = state.playing != localPlaying
        return PartyDecision(responseRev, if (drifted || playingDiffers) state else null)
    }

    /** The host may write now: never twice within [WRITE_INTERVAL_MS] (a clock stepping back must not wedge it). */
    fun shouldPush(nowMs: Long, lastPushAtMs: Long): Boolean =
        lastPushAtMs <= 0L || nowMs < lastPushAtMs || nowMs - lastPushAtMs >= WRITE_INTERVAL_MS

    /** Whether the host has something new to say: a play/pause change, or the position moved enough to matter. */
    fun pushWorthwhile(local: PartyLocalPlayback, lastPushed: PartyLocalPlayback?): Boolean {
        if (lastPushed == null) return true
        if (local.playing != lastPushed.playing) return true
        return abs(local.positionMs - lastPushed.positionMs) >= MIN_PUSH_DELTA_MS
    }
}

/** The outcome of [PartyLogic.evaluate]: the revision now seen, and the state to apply to the player (if any). */
data class PartyDecision(val lastRev: Long, val apply: PartyPlaybackState?)
