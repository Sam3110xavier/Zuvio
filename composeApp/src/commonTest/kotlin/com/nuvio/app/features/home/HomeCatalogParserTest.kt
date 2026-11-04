package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogParserTest {

    @Test
    fun `parse catalog response de-duplicates repeated metas but preserves raw count`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            """
            {
              "metas": [
                { "id": "mal:62516", "type": "series", "name": "A" },
                { "id": "mal:62516", "type": "series", "name": "A duplicate" },
                { "id": "mal:1", "type": "movie", "name": "B" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, result.rawItemCount)
        assertEquals(
            listOf("series:mal:62516", "movie:mal:1"),
            result.items.map { it.stableKey() },
        )
        assertEquals("A", result.items.first().name)
    }

    @Test
    fun `parse catalog response respects max item cap without losing raw count`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    { "id": "tt1", "type": "movie", "name": "One" },
                    { "id": "tt1", "type": "movie", "name": "One duplicate" },
                    { "id": "tt2", "type": "movie", "name": "Two" },
                    { "id": "tt3", "type": "movie", "name": "Three" }
                  ]
                }
            """.trimIndent(),
            maxItems = 2,
        )

        assertEquals(4, result.rawItemCount)
        assertEquals(
            listOf("movie:tt1", "movie:tt2"),
            result.items.map { it.stableKey() },
        )
    }

    @Test
    fun `parse catalog response keeps raw released date for unreleased filtering`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    {
                      "id": "tt1",
                      "type": "movie",
                      "name": "Future Movie",
                      "releaseInfo": "2027",
                      "released": "2027-05-12T00:00:00.000Z"
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("2027", result.items.single().releaseInfo)
        assertEquals("2027-05-12T00:00:00.000Z", result.items.single().rawReleaseDate)
    }

    @Test
    fun `parse catalog response keeps addon landscape poster separate from background`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    {
                      "id": "tt1",
                      "type": "movie",
                      "name": "One",
                      "background": "https://img/background.jpg",
                      "landscapePoster": "https://img/landscape-1.jpg"
                    },
                    {
                      "id": "tt2",
                      "type": "movie",
                      "name": "Two",
                      "landscapePoster": "https://img/landscape-2.jpg"
                    }
                  ]
                }
            """.trimIndent(),
        )

        val (withBackground, withoutBackground) = result.items
        assertEquals("https://img/landscape-1.jpg", withBackground.landscapePoster)
        assertEquals(null, withBackground.banner)
        assertEquals("https://img/background.jpg", withBackground.background)
        assertEquals("https://img/landscape-2.jpg", withoutBackground.landscapePoster)
        assertEquals(null, withoutBackground.banner)
        assertEquals(null, withoutBackground.background)
    }

    @Test
    fun `parse catalog response keeps poster banner background and logo separate`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    {
                      "id": "tt1",
                      "type": "movie",
                      "name": "One",
                      "poster": "https://img/poster.jpg",
                      "landscapePoster": "https://img/landscape.jpg",
                      "banner": "https://img/banner.jpg",
                      "background": "https://img/background.jpg",
                      "logo": "https://img/logo.png"
                    }
                  ]
                }
            """.trimIndent(),
        )

        val item = result.items.single()
        assertEquals("https://img/poster.jpg", item.poster)
        assertEquals("https://img/landscape.jpg", item.landscapePoster)
        assertEquals("https://img/banner.jpg", item.banner)
        assertEquals("https://img/background.jpg", item.background)
        assertEquals("https://img/logo.png", item.logo)
    }

    @Test
    fun `parse catalog response reads optional textless poster hint conservatively`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    { "id": "tt1", "type": "movie", "name": "Textless", "posterTextless": true },
                    { "id": "tt2", "type": "movie", "name": "Unknown poster" }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(true, result.items[0].posterTextless)
        assertEquals(false, result.items[1].posterTextless)
    }

    @Test
    fun `parse catalog response reads runtime and status`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    { "id": "tt1", "type": "series", "name": "One", "runtime": "52 min", "status": " Continuing " },
                    { "id": "tt2", "type": "movie", "name": "Two", "runtime": 109, "status": "" }
                  ]
                }
            """.trimIndent(),
        )

        val (series, movie) = result.items
        assertEquals("52 min", series.runtime)
        assertEquals("Continuing", series.status)
        assertEquals("109", movie.runtime)
        assertEquals(null, movie.status)
    }

    @Test
    fun `parse catalog response ignores non text values`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    { "id": "tt1", "type": "movie", "name": "One", "runtime": { "minutes": 90 }, "status": ["Released"] }
                  ]
                }
            """.trimIndent(),
        )

        val item = result.items.single()
        assertEquals(null, item.runtime)
        assertEquals(null, item.status)
    }
}
