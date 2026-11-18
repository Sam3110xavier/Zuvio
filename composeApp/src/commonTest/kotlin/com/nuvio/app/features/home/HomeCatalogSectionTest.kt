package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeCatalogSectionTest {
    @Test
    fun `source tab preview includes its own titles even when absent from merged preview`() {
        val mergedPreview = (1..18).map { MetaPreview(id = "merged-$it", type = "movie", name = "Merged $it") }
        val sourceItems = (1..25).map { MetaPreview(id = "source-$it", type = "movie", name = "Source $it") }
        val selectedTab = HomeCatalogTab(
            key = "source-a",
            title = "Source A",
            items = sourceItems,
        )

        val preview = homeCatalogPreviewItems(selectedTab, mergedPreview)

        assertTrue(preview.all { it.id.startsWith("source-") })
        assertTrue(preview.none { it.id.startsWith("merged-") })
        assertEquals((1..18).map { "source-$it" }, preview.map { it.id })
    }

    @Test
    fun `all tab fallback preview remains capped at the home preview limit`() {
        val fallbackItems = (1..25).map { MetaPreview(id = "item-$it", type = "movie", name = "Item $it") }

        val preview = homeCatalogPreviewItems(selectedTab = null, fallbackItems = fallbackItems)

        assertEquals(18, preview.size)
        assertEquals((1..18).map { "item-$it" }, preview.map { it.id })
    }

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
