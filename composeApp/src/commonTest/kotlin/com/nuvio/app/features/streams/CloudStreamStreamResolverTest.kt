package com.nuvio.app.features.streams

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

    private fun searchItem(
        name: String,
        year: Int? = null,
    ) = CloudStreamSearchItem(
        providerId = "test-provider",
        data = name,
        name = name,
        type = CloudStreamTvType.Movie,
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
