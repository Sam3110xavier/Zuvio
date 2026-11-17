package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeCatalogSectionTest {
    @Test
    fun `can open catalog when page has more below preview limit`() {
        val section = section(
            availableItemCount = 11,
            hasMore = true,
        )

        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun `can open catalog when non paginated section exceeds preview limit`() {
        val section = section(
            availableItemCount = 19,
            hasMore = false,
        )

        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun `cannot open catalog when section fits preview and has no more pages`() {
        val section = section(
            availableItemCount = 11,
            hasMore = false,
        )

        assertFalse(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun mergedRowsCanOpenCatalogEvenWhenTheMergedPreviewFitsOnOneShelf() {
        val section = section(availableItemCount = 3, hasMore = false).copy(isMergedRow = true)
        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun sourceTabPreviewUsesTheSelectedSourcesItems() {
        val fallback = (1..18).map { MetaPreview(id = "merged-$it", type = "movie", name = "Merged $it") }
        val sourceItems = (1..20).map { MetaPreview(id = "source-$it", type = "movie", name = "Source $it") }
        val preview = homeCatalogPreviewItems(
            HomeCatalogTab(key = "source", title = "Source", items = sourceItems),
            fallback,
        )
        assertEquals(18, preview.size)
        assertEquals("source-1", preview.first().id)
    }

    private fun section(
        availableItemCount: Int,
        hasMore: Boolean,
    ): HomeCatalogSection =
        HomeCatalogSection(
            key = "addon:movie:popular",
            title = "Popular",
            subtitle = "Addon",
            addonName = "Addon",
            target = CatalogTarget.Addon(
                manifestUrl = "https://example.com/manifest.json",
                contentType = "movie",
                catalogId = "popular",
                supportsPagination = hasMore,
            ),
            items = listOf(
                MetaPreview(
                    id = "tt1",
                    type = "movie",
                    name = "Movie",
                ),
            ),
            availableItemCount = availableItemCount,
            hasMore = hasMore,
        )
}
