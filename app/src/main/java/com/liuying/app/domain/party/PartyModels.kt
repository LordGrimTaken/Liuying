package com.liuying.app.domain.party

/**
 * The playback a watch party shares (the API's `state` object). The room's title is part of the state because a
 * guest must never be seeked into a different film than the one on screen.
 */
data class PartyPlaybackState(
    val mediaId: String,
    val episodeId: String?,
    val positionMs: Long,
    val playing: Boolean,
    /** The server's timestamp; carried through but not used for decisions (device clocks cannot be compared). */
    val updatedAt: Long? = null,
    /** Who started the room, for the guest's "Following ..." line. */
    val hostName: String? = null,
)

/** What this device's player is doing right now, read when a poll response arrives. */
data class PartyLocalPlayback(
    val positionMs: Long,
    val playing: Boolean,
    /** The title on screen; when null the caller did not say, and a remote state is applied regardless. */
    val mediaId: String? = null,
    val episodeId: String? = null,
)
