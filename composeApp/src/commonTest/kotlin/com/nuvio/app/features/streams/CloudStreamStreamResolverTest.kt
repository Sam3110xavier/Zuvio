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
    fun `accepts localized title returned by validated external ID lookup`() {
        val request = CloudStreamSearchRequest(
            title = "Money Heist",
            type = "series",
            year = 2017,
            externalId = "tt6468322",
        )
        val loaded = loadItem(name = "La casa de papel", year = 2017, type = CloudStreamTvType.TvSeries)

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
