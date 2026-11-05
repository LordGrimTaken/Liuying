package com.liuying.app.data.party

import com.liuying.app.data.account.ApiResult
import com.liuying.app.domain.Clock
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.party.PartyLocalPlayback
import com.liuying.app.domain.party.PartyLogic
import com.liuying.app.domain.party.PartyPlaybackState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PartyPhase { IDLE, STARTING, JOINING, ACTIVE }

/** Everything the watch-party panel draws. [code] is set only while [phase] is [PartyPhase.ACTIVE]. */
data class PartyUiState(
    val phase: PartyPhase = PartyPhase.IDLE,
    val code: String? = null,
    val host: Boolean = false,
    val members: Int = 0,
    val rev: Long = 0L,
    val hostName: String? = null,
    /** A transient problem that does not end the room (offline poll, failed host write). */
    val warning: String? = null,
    /** An action failed; the user is still in no room. */
    val error: String? = null,
    /** Something ended the room for us ("This watch party ended."). */
    val notice: String? = null,
) {
    val active: Boolean get() = phase == PartyPhase.ACTIVE && code != null
    val busy: Boolean get() = phase == PartyPhase.STARTING || phase == PartyPhase.JOINING
}

/**
 * Runs one watch-party session for the player: polls the room every [PartyLogic.POLL_MS], makes the host's writes, and
 * hands a guest's remote state to the player when [PartyLogic.evaluate] says it should move.
 *
 * It owns no player itself. [bind] supplies how to read the local player (on the main thread) and how to apply a
 * remote state, so this class stays independent of ExoPlayer and of the UI. The scope is app-wide on purpose: the
 * final `leave`/`end` must still reach the server when the player activity is being destroyed.
 */
class PartyController(
    private val api: PartyApi,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: Clock = SystemClock,
) {
    private val _state = MutableStateFlow(PartyUiState())
    val state: StateFlow<PartyUiState> = _state.asStateFlow()

    private var localPlayback: (() -> PartyLocalPlayback)? = null
    private var applyRemote: ((PartyPlaybackState) -> Unit)? = null
    private var pollJob: Job? = null
    private var pushJob: Job? = null
    private var lastRev = 0L
    private var lastPushAt = 0L
    private var lastPushed: PartyLocalPlayback? = null
    private var closing = false

    /** Connect the session to the player it should read and drive. Called once when the player starts. */
    fun bind(localPlayback: () -> PartyLocalPlayback, applyRemote: (PartyPlaybackState) -> Unit) {
        this.localPlayback = localPlayback
        this.applyRemote = applyRemote
    }

    // ------------------------------------------------------------------------------------ user actions

    /** Create a room for the title currently on screen and become its host. */
    fun start(mediaId: String, episodeId: String?, positionMs: Long, playing: Boolean) {
        val s = _state.value
        if (closing || s.active || s.busy) return
        _state.value = PartyUiState(phase = PartyPhase.STARTING)
        scope.launch {
            when (val r = api.create(mediaId, episodeId, positionMs, playing)) {
                is ApiResult.Err -> if (!closing) _state.value = PartyUiState(error = message(r))
                is ApiResult.Ok -> if (!closing) {
                    // The request took a moment; start from where the player is now, not from where it was at the tap.
                    val local = readLocal() ?: PartyLocalPlayback(positionMs, playing, mediaId, episodeId)
                    becomeHost(r.value, local)
                }
            }
        }
    }

    /** Join the room [rawCode] names; a whole shared link is accepted too. */
    fun join(rawCode: String) {
        val s = _state.value
        if (closing || s.active || s.busy) return
        val code = PartyLogic.codeFromInput(rawCode)
        if (code == null) {
            _state.value = PartyUiState(error = "Watch party codes are six letters, like ABCDEF.")
            return
        }
        _state.value = PartyUiState(phase = PartyPhase.JOINING)
        scope.launch {
            when (val r = api.join(code)) {
                is ApiResult.Err -> if (!closing) _state.value = PartyUiState(error = message(r, joining = true))
                is ApiResult.Ok -> if (!closing) becomeGuest(r.value)
            }
        }
    }

    /**
     * Leave the room. A host ends it for everyone (there is no host migration in v1), a guest just leaves. The local
     * state is cleared immediately; the request runs on the app scope so a player that is closing still tells the server.
     */
    fun leave() {
        val s = _state.value
        val code = s.code
        if (closing || !s.active || code == null) return
        stopLoops()
        _state.value = PartyUiState(notice = if (s.host) "You ended the watch party." else "You left the watch party.")
        scope.launch { if (s.host) api.end(code) else api.leave(code) }
    }

    /** The player is going away: stop polling and end/leave the room. Safe to call more than once. */
    fun close() {
        if (closing) return
        closing = true
        val s = _state.value
        val code = s.code
        stopLoops()
        _state.value = PartyUiState()
        if (s.active && code != null) scope.launch { if (s.host) api.end(code) else api.leave(code) }
    }

    // ------------------------------------------------------------------------------------ loops

    private fun becomeHost(room: PartyRoom, local: PartyLocalPlayback) {
        lastRev = room.rev
        lastPushAt = clock.nowMs()
        lastPushed = local
        _state.value = PartyUiState(
            phase = PartyPhase.ACTIVE, code = room.code, host = true, members = room.members,
            rev = room.rev, hostName = room.state.hostName ?: api.username,
        )
        startLoops()
    }

    private fun becomeGuest(room: PartyRoom) {
        lastRev = room.rev
        lastPushAt = 0L
        lastPushed = null
        _state.value = PartyUiState(
            phase = PartyPhase.ACTIVE, code = room.code, host = false, members = room.members,
            rev = room.rev, hostName = room.state.hostName,
        )
        startLoops()
    }

    private fun startLoops() {
        stopLoops()
        pollJob = scope.launch {
            while (isActive) {
                delay(PartyLogic.POLL_MS)
                val code = _state.value.code ?: break
                when (val r = api.get(code)) {
                    is ApiResult.Err -> handlePollError(r)
                    is ApiResult.Ok -> handleRoom(r.value)
                }
            }
        }
        if (_state.value.host) {
            pushJob = scope.launch {
                while (isActive) {
                    delay(PartyLogic.PUSH_TICK_MS)
                    pushIfDue()
                }
            }
        }
    }

    private fun stopLoops() {
        pollJob?.cancel(); pollJob = null
        pushJob?.cancel(); pushJob = null
    }

    // ------------------------------------------------------------------------------------ polling

    private suspend fun handleRoom(room: PartyRoom) {
        val s = _state.value
        if (!s.active || s.code != room.code) return
        val hostName = room.state.hostName ?: s.hostName
        // The host is authoritative: its own poll result only refreshes the member count (and a rev a PUT already
        // reported cannot move it backwards).
        if (s.host) {
            lastRev = maxOf(lastRev, room.rev)
            _state.update { it.copy(members = room.members, rev = maxOf(it.rev, room.rev), hostName = hostName, warning = null) }
            return
        }
        val local = readLocal()
        if (local == null) {
            lastRev = maxOf(lastRev, room.rev)
            _state.update { it.copy(members = room.members, rev = maxOf(it.rev, room.rev), hostName = hostName) }
            return
        }
        if (!PartyLogic.sameTitle(room.state, local)) {
            lastRev = maxOf(lastRev, room.rev)
            _state.update {
                it.copy(
                    members = room.members, rev = maxOf(it.rev, room.rev), hostName = hostName,
                    warning = "This watch party is watching a different title.",
                )
            }
            return
        }
        val decision = PartyLogic.evaluate(lastRev, room.rev, room.state, local.positionMs, local.playing)
        lastRev = decision.lastRev
        _state.update { it.copy(members = room.members, rev = maxOf(it.rev, room.rev), hostName = hostName, warning = null) }
        val remote = decision.apply ?: return
        val apply = applyRemote ?: return
        try {
            withContext(main) { apply(remote) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { st -> st.copy(warning = "Could not apply the host's playback.") }
        }
    }

    private fun handlePollError(e: ApiResult.Err) {
        when (e.code) {
            "not_found", "forbidden", "unauthenticated" -> {
                stopLoops()
                _state.value = PartyUiState(error = message(e))
            }
            else -> _state.update { it.copy(warning = "Connection lost. Retrying...") }
        }
    }

    // ------------------------------------------------------------------------------------ host writes

    private suspend fun pushIfDue() {
        val s = _state.value
        val code = s.code ?: return
        if (!s.active || !s.host) return
        val now = clock.nowMs()
        if (!PartyLogic.shouldPush(now, lastPushAt)) return
        val local = readLocal() ?: return
        if (!PartyLogic.pushWorthwhile(local, lastPushed)) return
        // Claim the slot before the request: a slow write must not let the next tick start a second one at once.
        lastPushAt = now
        when (val r = api.update(code, local.positionMs, local.playing)) {
            is ApiResult.Err -> when (r.code) {
                "not_found", "forbidden", "unauthenticated" -> {
                    stopLoops()
                    _state.value = PartyUiState(error = message(r))
                }
                else -> _state.update { it.copy(warning = "Could not update the room. Retrying...") }
            }
            is ApiResult.Ok -> {
                lastPushed = local
                lastRev = maxOf(lastRev, r.value.rev)
                _state.update { it.copy(rev = maxOf(it.rev, r.value.rev), warning = null) }
            }
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private suspend fun readLocal(): PartyLocalPlayback? {
        val read = localPlayback ?: return null
        return try {
            withContext(main) { read() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun message(e: ApiResult.Err, joining: Boolean = false): String = when {
        e.unauthenticated -> "Sign in on the You tab to use watch parties."
        e.code == "not_found" && joining -> "No watch party with that code. Check it and try again."
        e.code == "not_found" -> "This watch party has ended."
        e.code == "forbidden" -> "Only the host can control this room."
        e.code == "rate_limited" -> "Too many requests. Wait a moment and try again."
        else -> e.message
    }
}
