package com.nuvio.app.features.party

import kotlin.math.abs

internal sealed interface PartyCorrection {
    data object InSync : PartyCorrection
    data class Seek(val positionMs: Long) : PartyCorrection
    data class Nudge(val rate: Float) : PartyCorrection
}

internal sealed interface OwnSeek {
    data class Behind(val positionMs: Long) : OwnSeek
    data object CatchUp : OwnSeek
    data object Blocked : OwnSeek
}

internal object PartySync {
    const val MAX_LOCAL_POSITION_EXTRAPOLATION_MS = 1_000L
    const val NUDGE_START_MS = 300L
    const val NUDGE_STOP_MS = 120L
    const val SEEK_THRESHOLD_MS = 2_000L
    const val PAUSED_TOLERANCE_MS = 500L
    const val PAUSED_SEEK_THRESHOLD_MS = 1_000L
    const val MIN_NUDGE_FACTOR = 0.01f
    const val MAX_NUDGE_FACTOR = 0.05f
    const val RATE_UPDATE_THRESHOLD = 0.005f
    const val MAX_NUDGE_RATE_STEP = 0.01f
    const val MIN_PLAYBACK_RATE = 0.25f
    const val MAX_PLAYBACK_RATE = 4f
    const val SEEK_NOTICE_MS = 2_000L
    const val REJOIN_GAP_MS = 2_000L
    const val BEHIND_STEP_MS = 5_000L

    fun expectedPositionMs(playback: PartyPlayback, nowMs: Long, durationMs: Long = 0L): Long {
        val position = if (playback.paused) {
            playback.positionMs
        } else {
            val elapsed = (nowMs - playback.at).coerceAtLeast(0L)
            playback.positionMs + (elapsed * playback.rate).toLong()
        }
        val clamped = position.coerceAtLeast(0L)
        return if (durationMs > 0L) clamped.coerceAtMost(durationMs) else clamped
    }

    /**
     * Estimate the playhead between player snapshots without extrapolating indefinitely.
     * Correction decisions use the effective local rate, including a temporary sync nudge,
     * rather than the base rate exposed to the rest of the player UI.
     */
    fun estimatedLocalPositionMs(
        positionMs: Long,
        elapsedMs: Long,
        playbackRate: Float,
        isPlaying: Boolean,
        isLoading: Boolean,
        durationMs: Long = 0L,
    ): Long {
        val elapsed = if (isPlaying && !isLoading) {
            elapsedMs.coerceIn(0L, MAX_LOCAL_POSITION_EXTRAPOLATION_MS)
        } else {
            0L
        }
        val advanceMs = (elapsed * playbackRate.coerceAtLeast(0f)).toLong()
        val predicted = (positionMs.coerceAtLeast(0L) + advanceMs).coerceAtLeast(0L)
        return if (durationMs > 0L) predicted.coerceAtMost(durationMs) else predicted
    }

    /** Don't run feedback correction from a stale moving-playhead sample. */
    fun isPositionSampleFresh(sampleAgeMs: Long): Boolean =
        sampleAgeMs in 0L..MAX_LOCAL_POSITION_EXTRAPOLATION_MS

    fun isNewer(candidate: PartyPlayback, current: PartyPlayback?): Boolean {
        if (current == null) return true
        if (candidate.seq != current.seq) return candidate.seq > current.seq
        return candidate.memberId.orEmpty() > current.memberId.orEmpty()
    }

    fun correction(
        localPositionMs: Long,
        expectedPositionMs: Long,
        playback: PartyPlayback,
        nudging: Boolean,
    ): PartyCorrection {
        val drift = localPositionMs - expectedPositionMs
        val distance = abs(drift)
        if (playback.paused) {
            return if (distance > PAUSED_SEEK_THRESHOLD_MS) PartyCorrection.Seek(expectedPositionMs) else PartyCorrection.InSync
        }
        val threshold = if (nudging) NUDGE_STOP_MS else NUDGE_START_MS
        return when {
            distance > SEEK_THRESHOLD_MS -> PartyCorrection.Seek(expectedPositionMs)
            distance > threshold -> {
                val rampRange = (SEEK_THRESHOLD_MS - threshold).coerceAtLeast(1L)
                val progress = ((distance - threshold).toFloat() / rampRange).coerceIn(0f, 1f)
                val factor = MIN_NUDGE_FACTOR + (MAX_NUDGE_FACTOR - MIN_NUDGE_FACTOR) * progress
                val adjustedRate = (playback.rate * if (drift > 0L) (1f - factor) else (1f + factor))
                    .coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
                // At a player speed limit, an impossible nudge should not be marked active:
                // that would suppress later corrections while the actual rate cannot change.
                val canCorrect = if (drift > 0L) adjustedRate < playback.rate else adjustedRate > playback.rate
                if (canCorrect) PartyCorrection.Nudge(adjustedRate) else PartyCorrection.InSync
            }
            else -> PartyCorrection.InSync
        }
    }

    /**
     * Avoid reconfiguring the audio time-stretch processor for imperceptibly small rate changes.
     * Party snapshots arrive frequently; without a deadband, integer-position jitter changes the
     * calculated Float rate on almost every correction tick.
     */
    fun shouldUpdateNudge(currentRate: Float?, targetRate: Float): Boolean =
        currentRate == null || abs(currentRate - targetRate) >= RATE_UPDATE_THRESHOLD

    /** Prefer the room's authoritative speed, but retain the saved base rate if the room vanished. */
    fun nudgeRestorationRate(authoritativeRate: Float?, savedBaseRate: Float?): Float =
        (authoritativeRate ?: savedBaseRate ?: 1f).coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)

    /**
     * Distinguishes an authoritative timeline jump from pause/rate updates that naturally carry
     * a newer sequence but do not represent a seek. Comparing against the previous state's
     * predicted position avoids treating normal playback progression as a discontinuity.
     */
    fun isSeekTransition(previous: PartyPlayback?, next: PartyPlayback): Boolean {
        if (previous == null || previous.waiting || next.waiting) return false
        if (previous.mediaKey != next.mediaKey || next.at < previous.at) return false
        return abs(next.positionMs - expectedPositionMs(previous, next.at)) > SEEK_NOTICE_MS
    }

    /** Coalesce a remote seek that arrives while the previous seek is still settling. */
    fun shouldDeferSeek(previous: PartyPlayback?, next: PartyPlayback, seekInFlight: Boolean): Boolean =
        seekInFlight && isSeekTransition(previous, next)

    /**
     * A local seek is already reflected in the local timeline; don't echo it back as a remote
     * seek. Initial alignment, explicit remote timeline jumps, and inaccurate paused states still
     * get an exact seek.
     */
    fun shouldSeekForPlaybackUpdate(
        previous: PartyPlayback?,
        next: PartyPlayback,
        localPositionMs: Long,
        expectedPositionMs: Long,
        initialSync: Boolean,
        ownUpdate: Boolean,
    ): Boolean {
        if (abs(localPositionMs - expectedPositionMs) <= PAUSED_TOLERANCE_MS) return false
        if (initialSync) return true
        if (ownUpdate) return false
        if (next.paused) {
            return isSeekTransition(previous, next) ||
                abs(localPositionMs - expectedPositionMs) > PAUSED_SEEK_THRESHOLD_MS
        }
        return isSeekTransition(previous, next)
    }

    /**
     * Bound how much the time-stretch rate changes in one correction tick. This prevents abrupt
     * alternating adjustments when noisy drift samples move across zero.
     */
    fun smoothNudge(currentNudgeRate: Float?, baseRate: Float, targetRate: Float): Float {
        val current = (currentNudgeRate ?: baseRate).coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
        val target = targetRate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
        val next = when {
            target > current -> minOf(target, current + MAX_NUDGE_RATE_STEP)
            target < current -> maxOf(target, current - MAX_NUDGE_RATE_STEP)
            else -> target
        }
        return next.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
    }

    fun coordinatorId(party: ActiveParty): String? {
        val online = party.presence.keys + party.memberId
        val host = party.hostMemberId
        if (host != null && host in online) return host
        if (party.hostOnly) return host
        return party.members.firstOrNull { it.id in online }?.id ?: party.memberId
    }

    fun actionOf(previous: PartyPlayback?, next: PartyPlayback): PartyAction? {
        if (previous == null || previous.waiting || next.waiting) return null
        if (previous.mediaKey != next.mediaKey) return null
        return when {
            previous.paused != next.paused -> if (next.paused) PartyAction.Paused else PartyAction.Resumed
            abs(previous.rate - next.rate) > 0.01f -> PartyAction.Speed
            isSeekTransition(previous, next) -> PartyAction.Seeked
            else -> null
        }
    }

    fun ownSeek(targetMs: Long, partyPositionMs: Long, behind: Boolean): OwnSeek = when {
        targetMs < partyPositionMs - REJOIN_GAP_MS -> OwnSeek.Behind(targetMs)
        behind -> OwnSeek.CatchUp
        else -> OwnSeek.Blocked
    }

    fun shouldRejoin(gapMs: Long, playing: Boolean): Boolean = playing && gapMs <= REJOIN_GAP_MS

    fun reportedGapMs(gapMs: Long): Long =
        ((gapMs.coerceAtLeast(0L) + BEHIND_STEP_MS / 2) / BEHIND_STEP_MS * BEHIND_STEP_MS).coerceAtLeast(BEHIND_STEP_MS)

    fun waitingMemberIds(party: ActiveParty, ignored: Set<String>): List<String> =
        party.presence.values
            .filter { it.mediaKey == party.media.key }
            .filter { it.status == PartyMemberStatus.Loading || it.status == PartyMemberStatus.Buffering }
            .map { it.memberId }
            .filterNot { it in ignored }
}
