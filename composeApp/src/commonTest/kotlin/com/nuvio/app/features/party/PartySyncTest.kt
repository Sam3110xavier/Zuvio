package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PartySyncTest {
    private fun playback(
        seq: Long = 1,
        paused: Boolean = false,
        positionMs: Long = 10_000,
        rate: Float = 1f,
        at: Long = 1_000_000,
        memberId: String = "a",
        waiting: Boolean = false,
    ) = PartyPlayback(
        seq = seq,
        memberId = memberId,
        paused = paused,
        positionMs = positionMs,
        rate = rate,
        at = at,
        waiting = waiting,
    )

    @Test
    fun playingTimelineAdvancesWithRate() {
        val state = playback(rate = 1.5f)
        assertEquals(10_000 + 3_000, PartySync.expectedPositionMs(state, nowMs = 1_002_000))
    }

    @Test
    fun pausedTimelineStaysPut() {
        val state = playback(paused = true)
        assertEquals(10_000, PartySync.expectedPositionMs(state, nowMs = 1_500_000))
    }

    @Test
    fun expectedPositionIsClampedToDuration() {
        val state = playback(positionMs = 99_000)
        assertEquals(100_000, PartySync.expectedPositionMs(state, nowMs = 1_010_000, durationMs = 100_000))
    }

    @Test
    fun clockSkewBeforeAnchorDoesNotRewind() {
        val state = playback()
        assertEquals(10_000, PartySync.expectedPositionMs(state, nowMs = 999_000))
    }

    @Test
    fun localPositionEstimateAdvancesAtEffectivePlaybackRate() {
        assertEquals(
            10_525L,
            PartySync.estimatedLocalPositionMs(
                positionMs = 10_000L,
                elapsedMs = 500L,
                playbackRate = 1.05f,
                isPlaying = true,
                isLoading = false,
            ),
        )
    }

    @Test
    fun localPositionEstimateDoesNotAdvanceWhilePausedOrLoading() {
        assertEquals(
            10_000L,
            PartySync.estimatedLocalPositionMs(10_000L, 500L, 1.5f, isPlaying = false, isLoading = false),
        )
        assertEquals(
            10_000L,
            PartySync.estimatedLocalPositionMs(10_000L, 500L, 1.5f, isPlaying = true, isLoading = true),
        )
    }

    @Test
    fun localPositionEstimateBoundsExtrapolationAndDuration() {
        assertEquals(
            11_000L,
            PartySync.estimatedLocalPositionMs(10_000L, 5_000L, 1f, isPlaying = true, isLoading = false),
        )
        assertEquals(
            10_500L,
            PartySync.estimatedLocalPositionMs(
                positionMs = 10_000L,
                elapsedMs = 1_000L,
                playbackRate = 1f,
                isPlaying = true,
                isLoading = false,
                durationMs = 10_500L,
            ),
        )
    }

    @Test
    fun stalePositionSamplesAreNotUsedForRoutineCorrection() {
        assertTrue(PartySync.isPositionSampleFresh(0L))
        assertTrue(PartySync.isPositionSampleFresh(PartySync.MAX_LOCAL_POSITION_EXTRAPOLATION_MS))
        assertFalse(PartySync.isPositionSampleFresh(PartySync.MAX_LOCAL_POSITION_EXTRAPOLATION_MS + 1L))
        assertFalse(PartySync.isPositionSampleFresh(-1L))
    }

    @Test
    fun higherSequenceWins() {
        assertTrue(PartySync.isNewer(playback(seq = 3), playback(seq = 2)))
        assertFalse(PartySync.isNewer(playback(seq = 2), playback(seq = 3)))
        assertTrue(PartySync.isNewer(playback(seq = 1), null))
    }

    @Test
    fun sameSequenceTieBreaksOnMemberSoEveryoneAgrees() {
        val first = playback(seq = 4, memberId = "aaa")
        val second = playback(seq = 4, memberId = "bbb")
        assertTrue(PartySync.isNewer(second, first))
        assertFalse(PartySync.isNewer(first, second))
        assertFalse(PartySync.isNewer(first, first))
    }

    @Test
    fun rateNudgeDeadbandAvoidsTinyAudioSpeedUpdates() {
        assertTrue(PartySync.shouldUpdateNudge(currentRate = null, targetRate = 1.01f))
        assertFalse(PartySync.shouldUpdateNudge(currentRate = 1.01f, targetRate = 1.0105f))
        assertTrue(PartySync.shouldUpdateNudge(currentRate = 1.01f, targetRate = 1.016f))
        assertFalse(PartySync.shouldUpdateNudge(currentRate = 1.01f, targetRate = 1.01f))
    }

    @Test
    fun nudgeRestorationPreservesBaseRateWhenPartyStateDisappears() {
        assertEquals(1.5f, PartySync.nudgeRestorationRate(authoritativeRate = null, savedBaseRate = 1.5f))
        assertEquals(1.25f, PartySync.nudgeRestorationRate(authoritativeRate = 1.25f, savedBaseRate = 1.5f))
        assertEquals(1f, PartySync.nudgeRestorationRate(authoritativeRate = null, savedBaseRate = null))
    }

    @Test
    fun subsecondDriftDoesNotStartRateNudging() {
        assertEquals(
            PartyCorrection.InSync,
            PartySync.correction(10_250, 10_000, playback(), nudging = false),
        )
        assertIs<PartyCorrection.Nudge>(
            PartySync.correction(10_400, 10_000, playback(), nudging = false),
        )
    }

    @Test
    fun rateNudgesMoveInBoundedSteps() {
        assertEquals(1.51f, PartySync.smoothNudge(null, 1.5f, 1.55f), 0.0001f)
        assertEquals(1.50f, PartySync.smoothNudge(1.51f, 1.5f, 1.47f), 0.0001f)
        assertEquals(1.55f, PartySync.smoothNudge(1.55f, 1.5f, 1.55f), 0.0001f)
    }

    @Test
    fun stateUpdatesSeekOnlyForAnAuthoritativePositionJump() {
        val previous = playback(seq = 1, positionMs = 10_000, at = 1_000_000)
        val rateUpdate = playback(seq = 2, positionMs = 11_000, rate = 1.5f, at = 1_001_000)

        assertFalse(PartySync.isSeekTransition(previous, rateUpdate))
        assertFalse(
            PartySync.shouldSeekForPlaybackUpdate(
                previous = previous,
                next = rateUpdate,
                localPositionMs = 15_000,
                expectedPositionMs = 11_000,
                initialSync = false,
                ownUpdate = false,
            ),
        )

        val seek = playback(seq = 3, positionMs = 50_000, at = 1_002_000)
        assertTrue(PartySync.isSeekTransition(rateUpdate, seek))
        assertTrue(PartySync.shouldDeferSeek(rateUpdate, seek, seekInFlight = true))
        assertFalse(PartySync.shouldDeferSeek(rateUpdate, seek, seekInFlight = false))
        assertTrue(
            PartySync.shouldSeekForPlaybackUpdate(
                previous = rateUpdate,
                next = seek,
                localPositionMs = 11_500,
                expectedPositionMs = 50_000,
                initialSync = false,
                ownUpdate = false,
            ),
        )
    }

    @Test
    fun rapidRepeatedSeeksRemainAuthoritativeWhileAnEarlierSeekSettles() {
        val lastApplied = playback(seq = 1, positionMs = 10_000, at = 1_000_000)
        val firstSkip = playback(seq = 2, positionMs = 20_000, at = 1_001_000)
        val secondSkip = playback(seq = 3, positionMs = 30_000, at = 1_002_000)
        val thirdSkip = playback(seq = 4, positionMs = 40_000, at = 1_003_000)

        // While the first seek is settling, each newer state still represents a real seek.
        // The runtime can leave these states pending and apply the latest target after settling.
        for (target in listOf(firstSkip, secondSkip, thirdSkip)) {
            assertTrue(PartySync.isSeekTransition(lastApplied, target))
            assertTrue(PartySync.shouldDeferSeek(lastApplied, target, seekInFlight = true))
        }
        assertFalse(PartySync.shouldDeferSeek(lastApplied, thirdSkip, seekInFlight = false))
    }

    @Test
    fun pausedPlaybackDoesNotSeekForSubsecondPositionNoise() {
        val paused = playback(paused = true, positionMs = 20_000)
        assertEquals(
            PartyCorrection.InSync,
            PartySync.correction(
                localPositionMs = 20_548,
                expectedPositionMs = 20_000,
                playback = paused,
                nudging = false,
            ),
        )
        assertFalse(
            PartySync.shouldSeekForPlaybackUpdate(
                previous = playback(seq = 1, positionMs = 10_000, at = 1_000_000),
                next = playback(seq = 2, paused = true, positionMs = 11_000, at = 1_001_000),
                localPositionMs = 11_548,
                expectedPositionMs = 11_000,
                initialSync = false,
                ownUpdate = false,
            ),
        )
    }

    @Test
    fun initialAlignmentSeeksButOwnStateDoesNotEchoASeek() {
        val local = playback(seq = 2, positionMs = 30_000, at = 1_001_000)
        assertTrue(
            PartySync.shouldSeekForPlaybackUpdate(
                previous = null,
                next = local,
                localPositionMs = 0,
                expectedPositionMs = 30_000,
                initialSync = true,
                ownUpdate = false,
            ),
        )
        assertFalse(
            PartySync.shouldSeekForPlaybackUpdate(
                previous = playback(seq = 1, positionMs = 10_000, at = 1_000_000),
                next = local,
                localPositionMs = 0,
                expectedPositionMs = 30_000,
                initialSync = false,
                ownUpdate = true,
            ),
        )
    }

    @Test
    fun staleTimelineAnchorCannotBeTreatedAsANewSeek() {
        val previous = playback(seq = 4, positionMs = 10_000, at = 1_000_000)
        val stale = playback(seq = 5, positionMs = 0, at = 999_000)
        assertFalse(PartySync.isSeekTransition(previous, stale))
    }

    @Test
    fun smallDriftIsLeftAlone() {
        val result = PartySync.correction(10_200, 10_000, playback(), nudging = false)
        assertEquals(PartyCorrection.InSync, result)
    }

    @Test
    fun aheadOfTheRoomSlowsDown() {
        val result = PartySync.correction(10_600, 10_000, playback(), nudging = false)
        val nudge = assertIs<PartyCorrection.Nudge>(result)
        assertTrue(nudge.rate < 1f)
    }

    @Test
    fun behindTheRoomSpeedsUp() {
        val result = PartySync.correction(9_400, 10_000, playback(), nudging = false)
        val nudge = assertIs<PartyCorrection.Nudge>(result)
        assertTrue(nudge.rate > 1f)
    }

    @Test
    fun nudgingKeepsGoingUntilCloseEnough() {
        assertIs<PartyCorrection.Nudge>(PartySync.correction(10_150, 10_000, playback(), nudging = true))
        assertEquals(PartyCorrection.InSync, PartySync.correction(10_050, 10_000, playback(), nudging = true))
    }

    @Test
    fun largerDriftGetsStrongerCorrectionThanSmallDrift() {
        val small = assertIs<PartyCorrection.Nudge>(
            PartySync.correction(10_400, 10_000, playback(), nudging = false),
        )
        val large = assertIs<PartyCorrection.Nudge>(
            PartySync.correction(11_800, 10_000, playback(), nudging = false),
        )
        // The guest is ahead of the room, so stronger correction means a lower rate.
        assertTrue(small.rate < 1f)
        assertTrue(large.rate < small.rate)
    }

    @Test
    fun correctionRateStaysWithinSupportedPlaybackRange() {
        val fastBase = assertIs<PartyCorrection.Nudge>(
            PartySync.correction(10_500, 10_000, playback(rate = 4f), nudging = false),
        )
        val slowBase = assertIs<PartyCorrection.Nudge>(
            PartySync.correction(9_500, 10_000, playback(rate = 0.25f), nudging = false),
        )
        assertTrue(fastBase.rate in PartySync.MIN_PLAYBACK_RATE..PartySync.MAX_PLAYBACK_RATE)
        assertTrue(slowBase.rate in PartySync.MIN_PLAYBACK_RATE..PartySync.MAX_PLAYBACK_RATE)
    }

    @Test
    fun speedLimitDoesNotPretendAnImpossibleNudgeIsActive() {
        assertEquals(
            PartyCorrection.InSync,
            PartySync.correction(9_000, 10_000, playback(rate = 4f), nudging = false),
        )
        assertEquals(
            PartyCorrection.InSync,
            PartySync.correction(11_000, 10_000, playback(rate = 0.25f), nudging = false),
        )
    }

    @Test
    fun largeDriftSeeks() {
        val result = PartySync.correction(4_000, 10_000, playback(), nudging = false)
        assertEquals(PartyCorrection.Seek(10_000), result)
    }

    @Test
    fun pausedRoomOnlySeeksWhenNoticeablyOff() {
        val paused = playback(paused = true)
        assertEquals(PartyCorrection.InSync, PartySync.correction(10_300, 10_000, paused, nudging = false))
        assertEquals(PartyCorrection.Seek(10_000), PartySync.correction(12_000, 10_000, paused, nudging = false))
    }

    @Test
    fun hostCoordinatesWhenOnline() {
        val party = party(memberId = "b", hostMemberId = "a", online = listOf("a", "b"))
        assertEquals("a", PartySync.coordinatorId(party))
    }

    @Test
    fun oldestOnlineMemberCoordinatesWhenHostIsAway() {
        val party = party(memberId = "c", hostMemberId = "a", online = listOf("b", "c"))
        assertEquals("b", PartySync.coordinatorId(party))
    }

    @Test
    fun hostOnlyKeepsCoordinationWithHostWhenHostIsAway() {
        val party = party(memberId = "c", hostMemberId = "a", online = listOf("b", "c")).copy(hostOnly = true)
        assertEquals("a", PartySync.coordinatorId(party))
    }

    @Test
    fun onlyHostControlsInHostOnlyParty() {
        val party = party(memberId = "b", hostMemberId = "a", online = listOf("a", "b"))
        assertTrue(party.canControl)
        assertFalse(party.copy(hostOnly = true).canControl)
        assertTrue(party.copy(hostOnly = true, memberId = "a").canControl)
    }

    @Test
    fun actionDetectsPauseAndResume() {
        val playing = playback(seq = 1, at = 1_000_000)
        val paused = playback(seq = 2, paused = true, positionMs = 12_000, at = 1_002_000)
        assertEquals(PartyAction.Paused, PartySync.actionOf(playing, paused))
        assertEquals(PartyAction.Resumed, PartySync.actionOf(paused, paused.copy(seq = 3, paused = false)))
    }

    @Test
    fun actionDetectsSeekButNotSmallDrift() {
        val playing = playback(seq = 1, positionMs = 10_000, at = 1_000_000)
        val drifted = playback(seq = 2, positionMs = 12_400, at = 1_002_000)
        val skipped = playback(seq = 2, positionMs = 22_000, at = 1_002_000)
        assertEquals(null, PartySync.actionOf(playing, drifted))
        assertEquals(PartyAction.Seeked, PartySync.actionOf(playing, skipped))
    }

    @Test
    fun actionDetectsSpeedChange() {
        val playing = playback(seq = 1, at = 1_000_000)
        assertEquals(PartyAction.Speed, PartySync.actionOf(playing, playback(seq = 2, rate = 1.5f, at = 1_000_500)))
    }

    @Test
    fun actionIgnoresWaitingAndFirstState() {
        val playing = playback(seq = 1, at = 1_000_000)
        val waiting = playback(seq = 2, paused = true, waiting = true, at = 1_000_500)
        assertEquals(null, PartySync.actionOf(null, playing))
        assertEquals(null, PartySync.actionOf(playing, waiting))
        assertEquals(null, PartySync.actionOf(waiting, playback(seq = 3, at = 1_001_000)))
    }

    @Test
    fun waitingIgnoresOtherTitlesAndSkippedMembers() {
        val media = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie")
        val party = party(memberId = "a", hostMemberId = "a", online = emptyList()).copy(
            media = media,
            presence = mapOf(
                "a" to PartyPresence("a", PartyMemberStatus.Ready, media.key),
                "b" to PartyPresence("b", PartyMemberStatus.Buffering, media.key),
                "c" to PartyPresence("c", PartyMemberStatus.Loading, "movie|tt2||"),
                "d" to PartyPresence("d", PartyMemberStatus.Loading, media.key),
            ),
        )
        assertEquals(listOf("b", "d"), PartySync.waitingMemberIds(party, ignored = emptySet()))
        assertEquals(listOf("b"), PartySync.waitingMemberIds(party, ignored = setOf("d")))
    }

    @Test
    fun clockUsesTheFastestSample() {
        val time = TestTimeSource()
        val clock = PartyClock(timeSource = time, wallClockMs = { 0L })
        val slow = clock.mark()
        time += 400.milliseconds
        clock.addSample(serverMs = 5_000_000, sentAt = slow)
        assertEquals(5_000_200, clock.nowMs())

        val fast = clock.mark()
        time += 40.milliseconds
        clock.addSample(serverMs = 5_000_500, sentAt = fast)
        assertEquals(5_000_520, clock.nowMs())

        val slower = clock.mark()
        time += 900.milliseconds
        clock.addSample(serverMs = 9_999_999, sentAt = slower)
        assertEquals(5_001_420, clock.nowMs())
    }

    @Test
    fun clockFallsBackToWallTimeUntilSynced() {
        val clock = PartyClock(timeSource = TestTimeSource(), wallClockMs = { 42L })
        assertFalse(clock.isSynced)
        assertEquals(42L, clock.nowMs())
    }

    @Test
    fun codesAreNormalizedAndFormatted() {
        assertEquals("ABC234", normalizePartyCode(" abc-234 "))
        assertTrue(isValidPartyCode("abc 234"))
        assertFalse(isValidPartyCode("ABC10"))
        assertEquals("ABC 234", formatPartyCode("abc234"))
    }

    @Test
    fun mediaKeyIgnoresSourceDetails() {
        val first = PartyMedia(type = "series", metaId = "tt9", metaType = "series", season = 1, episode = 2, source = PartySource(infoHash = "x"))
        val second = first.copy(source = PartySource(infoHash = "y"), title = "Other")
        assertEquals(first.key, second.key)
        assertFalse(first.key == first.copy(episode = 3).key)
    }

    @Test
    fun rewindingFallsBehindTheParty() {
        assertEquals(OwnSeek.Behind(50_000), PartySync.ownSeek(50_000, partyPositionMs = 60_000, behind = false))
        assertEquals(OwnSeek.Behind(55_000), PartySync.ownSeek(55_000, partyPositionMs = 60_000, behind = true))
    }

    @Test
    fun reachingThePartyCatchesUp() {
        assertEquals(OwnSeek.CatchUp, PartySync.ownSeek(59_000, partyPositionMs = 60_000, behind = true))
        assertEquals(OwnSeek.CatchUp, PartySync.ownSeek(90_000, partyPositionMs = 60_000, behind = true))
    }

    @Test
    fun skippingAheadWhileInSyncIsBlocked() {
        assertEquals(OwnSeek.Blocked, PartySync.ownSeek(70_000, partyPositionMs = 60_000, behind = false))
    }

    @Test
    fun rejoinsOnlyWhilePlayingAndClose() {
        assertTrue(PartySync.shouldRejoin(1_500, playing = true))
        assertTrue(PartySync.shouldRejoin(-4_000, playing = true))
        assertFalse(PartySync.shouldRejoin(1_500, playing = false))
        assertFalse(PartySync.shouldRejoin(10_000, playing = true))
    }

    @Test
    fun reportedGapRoundsToSteps() {
        assertEquals(5_000L, PartySync.reportedGapMs(1_200))
        assertEquals(10_000L, PartySync.reportedGapMs(8_000))
        assertEquals(80_000L, PartySync.reportedGapMs(81_900))
    }

    @Test
    fun membersBehindNeverHoldUpTheParty() {
        val media = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie")
        val party = party(memberId = "a", hostMemberId = "a", online = emptyList()).copy(
            media = media,
            presence = mapOf(
                "b" to PartyPresence("b", PartyMemberStatus.Behind, media.key, behindMs = 30_000),
                "c" to PartyPresence("c", PartyMemberStatus.Buffering, media.key),
            ),
        )
        assertEquals(listOf("c"), PartySync.waitingMemberIds(party, ignored = emptySet()))
    }

    @Test
    fun behindPresenceSurvivesTheWire() {
        val presence = PartyPresence("b", PartyMemberStatus.Behind, "movie|tt1||", behindMs = 15_000)
        val encoded = PartyApi.json.encodeToString(PartyPresence.serializer(), presence)
        assertEquals(presence, PartyApi.json.decodeFromString(PartyPresence.serializer(), encoded))
    }

    @Test
    fun previewsReadTheServerReply() {
        val preview = PartyApi.json.decodeFromString(
            PartyPreview.serializer(),
            """{"host":{"id":"h","avatar_url":null,"avatar_color":"#48B461","display_name":"Aju"},""" +
                """"media":{"type":"series","title":"Ted Lasso","season":1,"episode":6,"description":"Richmond plays Everton.",""" +
                """"meta_id":"tt10986410","video_id":"tt10986410:1:6","meta_type":"series"},""" +
                """"members":1,"max_members":3}""",
        )
        assertEquals("Ted Lasso", preview.media?.title)
        assertEquals(6, preview.media?.episode)
        assertEquals("Richmond plays Everton.", preview.media?.description)
        assertEquals(null, preview.media?.source)
        assertEquals(PartyMember(id = "h", displayName = "Aju", avatarColor = "#48B461"), preview.host)
        assertFalse(preview.isFull)
        assertTrue(preview.copy(members = 3).isFull)

        val missing = PartyApi.json.decodeFromString(PartyPreview.serializer(), """{"error":"party_not_found"}""")
        assertEquals(PartyError.NotFound, partyErrorOf(missing.error))
        assertEquals(null, missing.media)
    }

    private fun party(memberId: String, hostMemberId: String, online: List<String>) = ActiveParty(
        id = "p",
        code = "ABC234",
        memberId = memberId,
        hostMemberId = hostMemberId,
        members = listOf("a", "b", "c").map { PartyMember(id = it, displayName = it.uppercase()) },
        presence = online.associateWith { PartyPresence(it, PartyMemberStatus.Ready) },
        media = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie"),
        maxMembers = 3,
        connected = true,
    )
}
