package com.nuvio.app.features.streams

import com.nuvio.app.features.cloudstream.CloudStreamEpisode
import com.nuvio.app.features.cloudstream.CloudStreamLoadItem
import com.nuvio.app.features.cloudstream.CloudStreamSearchItem
import com.nuvio.app.features.cloudstream.CloudStreamTvType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudStreamStreamResolverTest {
    @Test
    fun `matches normalized titles despite punctuation article and release year`() {
        val results = listOf(
            searchItem(name = "The.Batman.2022"),
            searchItem(name = "Batman Returns"),
        )

        val matches = results.bestCloudStreamMatches(
            CloudStreamSearchRequest(title = "Batman", type = "movie", year = 2022),
        )

        assertEquals(listOf("The.Batman.2022"), matches.map { it.name })
    }

    @Test
    fun `rejects a title with a conflicting release year`() {
        val matches = listOf(
            searchItem(name = "Dune", year = 1984),
            searchItem(name = "Dune", year = 2021),
        ).bestCloudStreamMatches(
            CloudStreamSearchRequest(title = "Dune", type = "movie", year = 2021),
        )

        assertEquals(listOf(2021), matches.map { it.year })
    }

    @Test
    fun `rejects localized series title returned by external ID lookup`() {
        val request = CloudStreamSearchRequest(
            title = "Money Heist",
            type = "series",
            year = 2017,
            externalId = "tt6468322",
        )
        val loaded = loadItem(name = "La casa de papel", year = 2017, type = CloudStreamTvType.TvSeries)

        assertFalse(loaded.matchesCloudStreamExternalIdRequest(request))
    }

    @Test
    fun `accepts localized movie title returned by external ID lookup when year matches`() {
        val request = CloudStreamSearchRequest(
            title = "The Shawshank Redemption",
            type = "movie",
            year = 1994,
            externalId = "tt0111161",
        )
        val loaded = loadItem(name = "Les Evades", year = 1994)

        assertTrue(loaded.matchesCloudStreamExternalIdRequest(request))
    }

    @Test
    fun `rejects external ID result when its release year conflicts`() {
        val request = CloudStreamSearchRequest(
            title = "Dune",
            type = "movie",
            year = 2021,
            externalId = "tt1160419",
        )
        val loaded = loadItem(name = "Dune", year = 1984)

        assertFalse(loaded.matchesCloudStreamExternalIdRequest(request))
    }

    @Test
    fun `accepts exact title when provider omits release year`() {
        val loaded = loadItem(name = "Dune", year = null)
        val search = searchItem(name = "Dune", year = null)

        assertTrue(
            loaded.matchesCloudStreamRequest(
                request = CloudStreamSearchRequest(title = "Dune", type = "movie", year = 2021),
                searchItem = search,
            ),
        )
    }

    @Test
    fun `matches title with trailing language and release metadata`() {
        val matches = listOf(
            searchItem(name = "Batman.2022.1080p.WEB-DL.Hindi"),
        ).bestCloudStreamMatches(
            CloudStreamSearchRequest(title = "The Batman", type = "movie", year = 2022),
        )

        assertEquals(1, matches.size)
    }

    @Test
    fun `accepts Other provider classification when title is a safe match`() {
        val matches = listOf(
            searchItem(name = "Dune", type = CloudStreamTvType.Other),
        ).bestCloudStreamMatches(
            CloudStreamSearchRequest(title = "Dune", type = "movie", year = 2021),
        )

        assertEquals(1, matches.size)
    }

    @Test
    fun `selects numbered episode when provider localizes its title`() {
        val loaded = CloudStreamLoadItem(
            providerId = "test-provider",
            data = "series-data",
            name = "Money Heist",
            type = CloudStreamTvType.TvSeries,
            episodes = listOf(
                CloudStreamEpisode(
                    data = "episode-s1e1",
                    name = "Llamada entrante",
                    season = 1,
                    episode = 1,
                ),
            ),
        )

        val selected = loaded.linkDataForCloudStreamRequest(
            CloudStreamSearchRequest(
                title = "Money Heist",
                type = "series",
                season = 1,
                episode = 1,
                episodeTitle = "We Are Back",
            ),
        )

        assertEquals("episode-s1e1", selected)
    }


    @Test
    fun `matches a small title typo without accepting unrelated titles`() {
        val matches = listOf(
            searchItem(name = "Invincibl", year = 2021),
            searchItem(name = "Atom Eve", year = 2021),
        ).bestCloudStreamMatches(
            CloudStreamSearchRequest(title = "Invincible", type = "movie", year = 2021),
        )

        assertEquals(listOf("Invincibl"), matches.map { it.name })
    }

    @Test
    fun `allows one year of provider release metadata variance`() {
        val matches = listOf(
            searchItem(name = "Dune", year = 2020),
        ).bestCloudStreamMatches(
            CloudStreamSearchRequest(title = "Dune", type = "movie", year = 2021),
        )

        assertEquals(1, matches.size)
    }

    @Test
    fun `maps anime tracker IDs to absolute episode numbers`() {
        assertEquals(
            null to 68,
            cloudStreamEpisodeCoordinates("mal:63375:68", season = 2, episode = 10),
        )
        assertEquals(
            null to 12,
            cloudStreamEpisodeCoordinates("anilist:12345:12", season = 1, episode = 12),
        )
        assertEquals(
            2 to 10,
            cloudStreamEpisodeCoordinates("tmdb:12345:2:10", season = 2, episode = 10),
        )
    }

    @Test
    fun `rejects malformed anime tracker IDs`() {
        assertEquals(null, absoluteCloudStreamAnimeEpisodeNumber("mal::68"))
        assertEquals(null, absoluteCloudStreamAnimeEpisodeNumber("mal:63375:0"))
        assertEquals(null, absoluteCloudStreamAnimeEpisodeNumber("tmdb:12345:2:10"))
    }

    @Test
    fun `prefers seasonless entry for absolute episode lookup`() {
        val loaded = CloudStreamLoadItem(
            providerId = "test-provider",
            data = "series-data",
            name = "Anime",
            type = CloudStreamTvType.Anime,
            episodes = listOf(
                CloudStreamEpisode(data = "s2e10", name = "Episode 10", season = 2, episode = 10),
                CloudStreamEpisode(data = "absolute-68", name = "Episode 68", season = null, episode = 68),
                CloudStreamEpisode(data = "s1e68", name = "Episode 68", season = 1, episode = 68),
            ),
        )

        val selected = loaded.linkDataForCloudStreamRequest(
            CloudStreamSearchRequest(title = "Anime", type = "series", season = null, episode = 68),
        )

        assertEquals("absolute-68", selected)
    }

    @Test
    fun `genre-aware provider compatibility includes anime series providers`() {
        assertTrue(
            cloudStreamTypesSupportRequest(
                supportedTypes = setOf(CloudStreamTvType.Anime, CloudStreamTvType.Ova),
                type = "series",
                genres = listOf("Animation", "Anime"),
            ),
        )
    }

    @Test
    fun `genre-aware provider compatibility includes documentary providers`() {
        assertTrue(
            cloudStreamTypesSupportRequest(
                supportedTypes = setOf(CloudStreamTvType.Documentary),
                type = "movie",
                genres = listOf("Documentary"),
            ),
        )
    }

    @Test
    fun `provider compatibility retains broad media type matches when genres are missing`() {
        assertTrue(
            cloudStreamTypesSupportRequest(
                supportedTypes = setOf(CloudStreamTvType.Anime),
                type = "series",
                genres = emptyList(),
            ),
        )
        assertTrue(
            cloudStreamTypesSupportRequest(
                supportedTypes = setOf(CloudStreamTvType.Documentary),
                type = "movie",
                genres = emptyList(),
            ),
        )
    }

    @Test
    fun `genre-aware provider compatibility rejects unrelated provider types`() {
        assertFalse(
            cloudStreamTypesSupportRequest(
                supportedTypes = setOf(CloudStreamTvType.Anime),
                type = "movie",
                genres = listOf("Drama"),
            ),
        )
    }

    @Test
    fun `localized ranked fallback requires exact movie year`() {
        val request = CloudStreamSearchRequest(title = "Money Heist", type = "movie", year = 2017)
        val localizedMovie = loadItem(name = "La casa de papel", year = 2017)
        val rankedSearch = searchItem(name = "La casa de papel", year = 2017)

        assertTrue(localizedMovie.matchesCloudStreamRequest(request, rankedSearch, true))
        assertFalse(localizedMovie.matchesCloudStreamRequest(request, rankedSearch, false))
    }

    @Test
    fun `localized ranked fallback is never accepted for series`() {
        val request = CloudStreamSearchRequest(title = "Money Heist", type = "series", year = 2017)
        val localizedSeries = loadItem(
            name = "La casa de papel",
            year = 2017,
            type = CloudStreamTvType.TvSeries,
        )
        val rankedSearch = searchItem(
            name = "La casa de papel",
            year = 2017,
            type = CloudStreamTvType.TvSeries,
        )

        assertFalse(localizedSeries.matchesCloudStreamRequest(request, rankedSearch, true))
    }

    @Test
    fun `same torrent info hash remains the same identity across magnet tracker URLs`() {
        val first = StreamItem(
            url = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789&tr=https%3A%2F%2Fa.example",
            infoHash = "0123456789012345678901234567890123456789",
            addonName = "Addon A",
            addonId = "addon:a",
        )
        val second = first.copy(
            url = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789&tr=https%3A%2F%2Fb.example",
            addonName = "Addon B",
            addonId = "addon:b",
        )

        assertTrue(crossProviderStreamIdentity(first) == crossProviderStreamIdentity(second))
    }

    @Test
    fun `same direct URL with different request headers has different identity`() {
        val first = StreamItem(
            url = "https://cdn.example/video.m3u8?token=one",
            addonName = "CloudStream",
            addonId = "cloudstream:test",
            behaviorHints = StreamBehaviorHints(
                proxyHeaders = StreamProxyHeaders(request = mapOf("Referer" to "https://one.example")),
            ),
        )
        val second = first.copy(
            behaviorHints = StreamBehaviorHints(
                proxyHeaders = StreamProxyHeaders(request = mapOf("Referer" to "https://two.example")),
            ),
        )

        assertFalse(crossProviderStreamIdentity(first) == crossProviderStreamIdentity(second))
        assertFalse(cloudStreamStreamIdentity(first) == cloudStreamStreamIdentity(second))
    }

    @Test
    fun `same URL and headers with different subtitle tracks have different CloudStream identity`() {
        val first = StreamItem(
            url = "https://cdn.example/video.m3u8",
            addonName = "CloudStream",
            addonId = "cloudstream:test",
            externalSubtitles = listOf(StreamSubtitle("https://cdn.example/en.vtt", "en")),
        )
        val second = first.copy(
            externalSubtitles = listOf(StreamSubtitle("https://cdn.example/es.vtt", "es")),
        )

        assertFalse(cloudStreamStreamIdentity(first) == cloudStreamStreamIdentity(second))
    }

    @Test
    fun `keeps year qualified search query distinct from plain title`() {
        assertEquals(
            listOf("Dune", "Dune 2021"),
            cloudStreamSearchQueries(
                CloudStreamSearchRequest(title = "Dune", type = "movie", year = 2021),
            ),
        )
    }

    @Test
    fun `deduplicates equivalent search aliases but preserves release year query`() {
        assertEquals(
            listOf("Dune", "Dune 2021"),
            cloudStreamSearchQueries(
                CloudStreamSearchRequest(
                    title = "Dune",
                    aliases = listOf("dune", "Dune.2021"),
                    type = "movie",
                    year = 2021,
                ),
            ),
        )
    }

    private fun searchItem(
        name: String,
        year: Int? = null,
        type: CloudStreamTvType = CloudStreamTvType.Movie,
    ) = CloudStreamSearchItem(
        providerId = "test-provider",
        data = name,
        name = name,
        type = type,
        year = year,
    )

    private fun loadItem(
        name: String,
        year: Int?,
        type: CloudStreamTvType = CloudStreamTvType.Movie,
    ) = CloudStreamLoadItem(
        providerId = "test-provider",
        data = name,
        name = name,
        type = type,
        year = year,
    )
}
