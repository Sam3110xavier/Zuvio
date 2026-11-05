package com.nuvio.app.features.party

import com.nuvio.app.features.streams.StreamBadge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyStreamTest {
    private val stream = PartyStream(
        mediaKey = "movie|tt1||",
        url = "https://cdn.example.com/movie.mkv",
        headers = mapOf("Referer" to "https://example.com"),
        responseHeaders = mapOf("Content-Type" to "video/x-matroska"),
        name = "TB HD",
        description = "Movie 2017\n1.5 GB",
        addonName = "Torrentio",
        addonId = "addon:torrentio",
        addonLogo = "https://example.com/logo.png",
        filename = "movie.mkv",
        videoSize = 1_500_000_000L,
        bingeGroup = "torrentio|720p",
        badges = listOf(StreamBadge(name = "HDR", imageURL = "https://example.com/hdr.png")),
    )

    @Test
    fun remoteLinksCanBeShared() {
        assertTrue(isShareableStreamUrl("https://cdn.example.com/movie.mkv"))
        assertTrue(isShareableStreamUrl("http://192.168.1.20:8096/video"))
    }

    @Test
    fun localAndNonWebLinksCannotBeShared() {
        assertFalse(isShareableStreamUrl("http://127.0.0.1:11470/stream"))
        assertFalse(isShareableStreamUrl("http://localhost:8080/video"))
        assertFalse(isShareableStreamUrl("http://[::1]:8080/video"))
        assertFalse(isShareableStreamUrl("http://0.0.0.0/video"))
        assertFalse(isShareableStreamUrl("file:///data/movie.mkv"))
        assertFalse(isShareableStreamUrl("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun sharedStreamKeepsCardDetailsAndHeaders() {
        val item = stream.toStreamItem()
        assertEquals("https://cdn.example.com/movie.mkv", item.playableDirectUrl)
        assertEquals("TB HD", item.streamLabel)
        assertEquals("Movie 2017\n1.5 GB", item.streamSubtitle)
        assertEquals("Torrentio", item.addonName)
        assertEquals("https://example.com/logo.png", item.addonLogo)
        assertEquals(1_500_000_000L, item.behaviorHints.videoSize)
        assertEquals("movie.mkv", item.behaviorHints.filename)
        assertEquals(mapOf("Referer" to "https://example.com"), item.behaviorHints.proxyHeaders?.request)
        assertEquals("HDR", item.badges.single().name)
        assertFalse(item.isTorrentStream)
    }

    @Test
    fun sharedStreamSurvivesTheWire() {
        val encoded = PartyApi.json.encodeToString(PartyStream.serializer(), stream)
        assertEquals(stream, PartyApi.json.decodeFromString(PartyStream.serializer(), encoded))
    }

    @Test
    fun disabledSharingNeverForcesTheGuestToFollowAStaleAnnouncement() {
        assertFalse(
            shouldFollowSharedPartyStream(
                isHost = false,
                sharingEnabled = false,
                partyMediaKey = stream.mediaKey,
                localMediaKey = stream.mediaKey,
                sharedStream = stream,
                activeSourceUrl = "https://cdn.example.com/other.mkv",
            ),
        )
    }

    @Test
    fun guestFollowsSharedUrlOnlyForTheMatchingPartyMedia() {
        assertTrue(
            shouldFollowSharedPartyStream(
                isHost = false,
                sharingEnabled = true,
                partyMediaKey = stream.mediaKey,
                localMediaKey = stream.mediaKey,
                sharedStream = stream,
                activeSourceUrl = "https://cdn.example.com/other.mkv",
            ),
        )
        assertFalse(
            shouldFollowSharedPartyStream(
                isHost = true,
                sharingEnabled = true,
                partyMediaKey = stream.mediaKey,
                localMediaKey = stream.mediaKey,
                sharedStream = stream,
                activeSourceUrl = "https://cdn.example.com/other.mkv",
            ),
        )
        assertFalse(
            shouldFollowSharedPartyStream(
                isHost = false,
                sharingEnabled = true,
                partyMediaKey = "movie|different||",
                localMediaKey = stream.mediaKey,
                sharedStream = stream,
                activeSourceUrl = "https://cdn.example.com/other.mkv",
            ),
        )
        assertFalse(
            shouldFollowSharedPartyStream(
                isHost = false,
                sharingEnabled = true,
                partyMediaKey = stream.mediaKey,
                localMediaKey = stream.mediaKey,
                sharedStream = stream.copy(mediaKey = "movie|different||"),
                activeSourceUrl = "https://cdn.example.com/other.mkv",
            ),
        )
        assertFalse(
            shouldFollowSharedPartyStream(
                isHost = false,
                sharingEnabled = true,
                partyMediaKey = stream.mediaKey,
                localMediaKey = stream.mediaKey,
                sharedStream = stream,
                activeSourceUrl = stream.url,
            ),
        )
        assertFalse(
            shouldFollowSharedPartyStream(
                isHost = false,
                sharingEnabled = true,
                partyMediaKey = stream.mediaKey,
                localMediaKey = stream.mediaKey,
                sharedStream = stream.copy(url = "http://localhost:8096/video"),
                activeSourceUrl = "https://cdn.example.com/other.mkv",
            ),
        )
    }

    @Test
    fun hostDepartureFreezesPlaybackWithoutChangingItsAuthoritySequence() {
        val playing = PartyPlayback(
            seq = 7,
            memberId = "host",
            paused = false,
            positionMs = 10_000,
            rate = 1.5f,
            at = 1_000,
            mediaKey = "movie|tt1||",
        )

        val paused = pausePartyPlaybackForHostDeparture(playing, at = 3_000)

        assertEquals(7L, paused?.seq)
        assertEquals("host", paused?.memberId)
        assertEquals(13_000L, paused?.positionMs)
        assertEquals(3_000L, paused?.at)
        assertTrue(paused?.paused == true)
        assertFalse(paused?.waiting == true)
    }

    @Test
    fun hostOnlyPublishesDifferentPartyMediaAfterPlayerIsReady() {
        assertTrue(shouldPublishHostPartyMedia(true, "movie|old||", "movie|new||", playerReady = true))
        assertFalse(shouldPublishHostPartyMedia(false, "movie|old||", "movie|new||", playerReady = true))
        assertFalse(shouldPublishHostPartyMedia(true, "movie|same||", "movie|same||", playerReady = true))
        assertFalse(shouldPublishHostPartyMedia(true, "movie|old||", "movie|new||", playerReady = false))
    }

    @Test
    fun guestAutomaticallySwitchesWhenHostSharingIsDisabled() {
        val target = PartyMedia(type = "movie", metaId = "tt2", metaType = "movie", title = "New movie")
        assertEquals(
            PartyMediaChangeDecision.AutoSwitch,
            decidePartyMediaChange(
                isHost = false,
                localMediaKey = "movie|tt1||",
                media = target,
                sharingEnabled = false,
                sharedStream = null,
            ),
        )
    }

    @Test
    fun guestAutomaticallySwitchesWithoutWaitingForAHostSharedStream() {
        val target = PartyMedia(type = "movie", metaId = "tt2", metaType = "movie", title = "New movie")
        assertEquals(
            PartyMediaChangeDecision.AutoSwitch,
            decidePartyMediaChange(false, "movie|tt1||", target, true, null),
        )
        assertEquals(
            PartyMediaChangeDecision.AutoSwitch,
            decidePartyMediaChange(
                false,
                "movie|tt1||",
                target,
                true,
                stream.copy(mediaKey = "movie|tt1||"),
            ),
        )
        assertEquals(
            PartyMediaChangeDecision.AutoSwitch,
            decidePartyMediaChange(
                false,
                "movie|tt1||",
                target,
                true,
                stream.copy(mediaKey = target.key, url = "http://localhost:8096/video"),
            ),
        )
    }

    @Test
    fun guestAlwaysFollowsHostMediaEvenAfterPreviouslyDismissingIt() {
        val target = PartyMedia(type = "movie", metaId = "tt2", metaType = "movie", title = "New movie")
        assertEquals(
            PartyMediaChangeDecision.AutoSwitch,
            decidePartyMediaChange(
                isHost = false,
                localMediaKey = "movie|tt1||",
                media = target,
                sharingEnabled = true,
                sharedStream = stream.copy(mediaKey = target.key),
            ),
        )
    }

    @Test
    fun hostAndUnchangedMediaNeverTriggerGuestSwitchHandling() {
        val target = PartyMedia(type = "movie", metaId = "tt2", metaType = "movie", title = "New movie")
        assertEquals(
            PartyMediaChangeDecision.Ignore,
            decidePartyMediaChange(true, "movie|tt1||", target, true, stream.copy(mediaKey = target.key)),
        )
        assertEquals(
            PartyMediaChangeDecision.Ignore,
            decidePartyMediaChange(false, target.key, target, false, null),
        )
    }

    @Test
    fun aDifferentEpisodeIsTreatedAsDifferentPartyContent() {
        val target = PartyMedia(
            type = "series",
            metaId = "show1",
            metaType = "series",
            season = 1,
            episode = 2,
            title = "Example Show",
        )
        val oldEpisodeKey = partyMediaKey("series", "show1", 1, 1)
        assertEquals(
            PartyMediaChangeDecision.AutoSwitch,
            decidePartyMediaChange(
                isHost = false,
                localMediaKey = oldEpisodeKey,
                media = target,
                sharingEnabled = true,
                sharedStream = stream.copy(mediaKey = target.key),
            ),
        )
    }

}
