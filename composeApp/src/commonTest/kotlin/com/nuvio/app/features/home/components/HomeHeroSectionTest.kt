package com.nuvio.app.features.home.components

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeHeroSectionTest {

    @Test
    fun `first and last indicators select adjacent pages across the loop`() {
        assertEquals(79, heroPageForItem(currentPage = 80, itemIndex = 7, itemCount = 8))
        assertEquals(88, heroPageForItem(currentPage = 87, itemIndex = 0, itemCount = 8))
    }

    @Test
    fun `indicators keep the current page when selecting the active title`() {
        assertEquals(83, heroPageForItem(currentPage = 83, itemIndex = 3, itemCount = 8))
    }

    @Test
    fun `indicators select the closest occurrence of a title`() {
        assertEquals(85, heroPageForItem(currentPage = 83, itemIndex = 5, itemCount = 8))
        assertEquals(81, heroPageForItem(currentPage = 83, itemIndex = 1, itemCount = 8))
    }

    @Test
    fun `portrait orientation prefers portrait poster`() {
        val item = MetaPreview(
            id = "portrait",
            type = "movie",
            name = "Portrait",
            poster = "poster.jpg",
            landscapePoster = "landscape.jpg",
            banner = "banner.jpg",
        )

        val selection = selectHomeHeroArtwork(item, HomeHeroOrientation.Portrait)

        assertEquals("poster.jpg", selection?.imageUrl)
        assertEquals(HomeHeroArtworkAnchor.Center, selection?.anchor)
    }

    @Test
    fun `portrait orientation uses landscape poster and preserves title side crop`() {
        val item = MetaPreview(
            id = "landscape",
            type = "movie",
            name = "Landscape",
            landscapePoster = "landscape.jpg",
            banner = "banner.jpg",
        )

        val selection = selectHomeHeroArtwork(item, HomeHeroOrientation.Portrait)

        assertEquals("landscape.jpg", selection?.imageUrl)
        assertEquals(HomeHeroArtworkAnchor.Start, selection?.anchor)
    }

    @Test
    fun `landscape orientation prefers landscape poster then banner then portrait poster`() {
        val landscapeItem = MetaPreview(
            id = "landscape",
            type = "movie",
            name = "Landscape",
            poster = "poster.jpg",
            landscapePoster = "landscape.jpg",
            banner = "banner.jpg",
        )
        val bannerItem = landscapeItem.copy(landscapePoster = null)
        val posterItem = bannerItem.copy(banner = null)

        assertEquals(
            "landscape.jpg",
            selectHomeHeroArtwork(landscapeItem, HomeHeroOrientation.Landscape)?.imageUrl,
        )
        assertEquals(
            "banner.jpg",
            selectHomeHeroArtwork(bannerItem, HomeHeroOrientation.Landscape)?.imageUrl,
        )
        val portraitFallback = selectHomeHeroArtwork(posterItem, HomeHeroOrientation.Landscape)
        assertEquals("poster.jpg", portraitFallback?.imageUrl)
        assertEquals(HomeHeroArtworkAnchor.Bottom, portraitFallback?.anchor)
    }

    @Test
    fun `portrait selection falls back to banner when called without poster candidates`() {
        val item = MetaPreview(
            id = "banner",
            type = "movie",
            name = "Banner",
            banner = "banner.jpg",
        )

        assertEquals(
            "banner.jpg",
            selectHomeHeroArtwork(item, HomeHeroOrientation.Portrait)?.imageUrl,
        )
        assertEquals(false, isHomeHeroEligible(item))
    }

    @Test
    fun `hero filters out entries without either poster variant`() {
        assertEquals(
            false,
            isHomeHeroEligible(MetaPreview(id = "banner", type = "movie", name = "Banner", banner = "banner.jpg")),
        )
        assertEquals(
            true,
            isHomeHeroEligible(MetaPreview(id = "landscape", type = "movie", name = "Landscape", landscapePoster = "landscape.jpg")),
        )
    }

    @Test
    fun `orientation follows window dimensions rather than tablet width`() {
        assertEquals(HomeHeroOrientation.Portrait, homeHeroOrientation(800, 1200))
        assertEquals(HomeHeroOrientation.Landscape, homeHeroOrientation(1200, 800))
    }

    @Test
    fun `selected title index is restored by stable key after filtering or reordering`() {
        val one = MetaPreview(id = "one", type = "movie", name = "One", poster = "one.jpg")
        val two = MetaPreview(id = "two", type = "movie", name = "Two", poster = "two.jpg")
        val three = MetaPreview(id = "three", type = "movie", name = "Three", poster = "three.jpg")

        assertEquals(1, homeHeroItemIndexForKey(listOf(three, two), "movie:two"))
        assertEquals(0, homeHeroItemIndexForKey(listOf(one, three), "movie:missing"))
    }

    @Test
    fun `logo validation rejects portrait and landscape poster aspect ratios`() {
        assertEquals(true, isValidHomeHeroLogo(widthPx = 1200, heightPx = 400))
        assertEquals(false, isValidHomeHeroLogo(widthPx = 1600, heightPx = 900))
        assertEquals(false, isValidHomeHeroLogo(widthPx = 400, heightPx = 800))
        assertEquals(false, isValidHomeHeroLogo(widthPx = 0, heightPx = 400))
        assertEquals(false, isValidHomeHeroLogo(widthPx = 400, heightPx = 0))
    }

    @Test
    fun `mobile hero height stays compact without continue watching`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 844f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(452.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height remains width driven even with viewport height`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = 1200f,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(386.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero height leaves room for continue watching card section`() {
        val viewportHeight = 844f
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidthDp = 390f)
        val continueWatchingHeight = continueWatchingSectionHeightEstimate(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val reserveHeight = continueWatchingHeroViewportReserveHeight(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = viewportHeight,
            mobileBelowSectionHeightHintDp = reserveHeight.value,
        )

        assertEquals(24f, viewportHeight - layout.heroHeight.value - continueWatchingHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero can shrink below default minimum to fit short viewport`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 568f,
            mobileBelowSectionHeightHintDp = 300f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(268f, layout.heroHeight.value, 0.001f)
    }
}
