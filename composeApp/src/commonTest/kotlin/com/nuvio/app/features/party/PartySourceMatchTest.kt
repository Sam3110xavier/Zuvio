package com.nuvio.app.features.party

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartySourceMatchTest {
    private fun stream(
        name: String = "1080p WEB-DL",
        addonId: String = "addon.example",
        filename: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        videoHash: String? = null,
    ) = StreamItem(
        name = name,
        addonName = "Example",
        addonId = addonId,
        infoHash = infoHash,
        fileIdx = fileIdx,
        behaviorHints = StreamBehaviorHints(filename = filename, videoHash = videoHash),
    )

    @Test
    fun httpStreamMatchesByVideoHashAcrossAddonsAndLabels() {
        val source = PartySource(
            filename = "Old.Name.mkv",
            videoHash = "ABC123",
            addonId = "addon.one",
            name = "Release A",
        )
        assertTrue(
            source.matches(
                stream(
                    name = "Release B",
                    addonId = "addon.two",
                    filename = "Different.Name.mkv",
                    videoHash = "abc123",
                ),
            ),
        )
    }

    @Test
    fun differingVideoHashesRejectEvenWhenFilenamesMatch() {
        val source = PartySource(filename = "Movie.mkv", videoHash = "aaa111")
        assertFalse(source.matches(stream(filename = "Movie.mkv", videoHash = "bbb222")))
    }

    @Test
    fun missingVideoHashDoesNotFallBackToFilenameOrLabel() {
        val source = PartySource(filename = "Movie.mkv", addonId = "addon.example", name = "1080p WEB-DL")
        assertFalse(source.matches(stream(filename = "Movie.mkv")))
        assertFalse(source.matches(stream()))
    }

    @Test
    fun filenameMatchAloneIsNotTreatedAsSourceIdentity() {
        val source = PartySource(filename = "Movie.mkv")
        assertFalse(source.matches(stream(filename = "movie.MKV")))
    }

    @Test
    fun torrentHashAndFileIndexMatch() {
        val source = PartySource(infoHash = "AAAAAAAA", fileIdx = 2)
        assertTrue(source.matches(stream(infoHash = "aaaaaaaa", fileIdx = 2)))
    }

    @Test
    fun torrentHashMismatchNeverFallsBackToMatchingLabel() {
        val source = PartySource(infoHash = "aaaaaaaa", fileIdx = 1, addonId = "addon.example", name = "1080p WEB-DL")
        assertFalse(source.matches(stream(filename = "Movie.mkv", infoHash = "bbbbbbbb", fileIdx = 1)))
    }

    @Test
    fun torrentIdentityPresentOnOnlyOneSideDoesNotFallBackToFilename() {
        val source = PartySource(infoHash = "aaaaaaaa", fileIdx = 1, filename = "Movie.mkv")
        assertFalse(source.matches(stream(filename = "Movie.mkv")))
    }

    @Test
    fun torrentFileIndexMismatchDoesNotMatch() {
        val source = PartySource(infoHash = "aaaaaaaa", fileIdx = 1)
        assertFalse(source.matches(stream(infoHash = "aaaaaaaa", fileIdx = 2)))
    }

    @Test
    fun torrentWithMissingFileIndexDoesNotClaimExactMatch() {
        val source = PartySource(infoHash = "aaaaaaaa", filename = "Episode.01.mkv")
        assertFalse(source.matches(stream(infoHash = "aaaaaaaa", filename = "Episode.01.mkv")))
    }
}
