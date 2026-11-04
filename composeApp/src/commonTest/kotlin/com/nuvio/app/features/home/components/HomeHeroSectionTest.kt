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
    fun `portrait priority is banner then backdrop then poster`() {
        val item = MetaPreview(id = "portrait", type = "movie", name = "Portrait", poster = "poster.jpg", banner = "banner.jpg", background = "backdrop.jpg")
        val selection = selectHomeHeroArtwork(item, HomeHeroOrientation.Portrait)
        assertEquals("banner.jpg", selection?.imageUrl)
        assertEquals(true, selection?.showTitleOverlay)
    }

    @Test
    fun `portrait uses backdrop before poster and poster has no overlay by default`() {
        val item = MetaPreview(id = "portrait", type = "movie", name = "Portrait", poster = "poster.jpg", background = "backdrop.jpg")
        val backdrop = selectHomeHeroArtwork(item, HomeHeroOrientation.Portrait)
        assertEquals("backdrop.jpg", backdrop?.imageUrl)
        assertEquals(true, backdrop?.showTitleOverlay)
        val poster = selectHomeHeroArtwork(item.copy(background = null), HomeHeroOrientation.Portrait)
        assertEquals("poster.jpg", poster?.imageUrl)
        assertEquals(false, poster?.showTitleOverlay)
    }

    @Test
    fun `textless poster opts into logo or text overlay`() {
        val item = MetaPreview(id = "textless", type = "movie", name = "Textless", poster = "poster.jpg", posterTextless = true)
        assertEquals(true, selectHomeHeroArtwork(item, HomeHeroOrientation.Portrait)?.showTitleOverlay)
    }

    @Test
    fun `landscape priority is landscape poster then banner then backdrop then poster`() {
        val all = MetaPreview(id = "landscape", type = "movie", name = "Landscape", poster = "poster.jpg", landscapePoster = "landscape.jpg", banner = "banner.jpg", background = "backdrop.jpg")
        assertEquals("landscape.jpg", selectHomeHeroArtwork(all, HomeHeroOrientation.Landscape)?.imageUrl)
        assertEquals("banner.jpg", selectHomeHeroArtwork(all.copy(landscapePoster = null), HomeHeroOrientation.Landscape)?.imageUrl)
        assertEquals("backdrop.jpg", selectHomeHeroArtwork(all.copy(landscapePoster = null, banner = null), HomeHeroOrientation.Landscape)?.imageUrl)
        val poster = selectHomeHeroArtwork(all.copy(landscapePoster = null, banner = null, background = null), HomeHeroOrientation.Landscape)
        assertEquals("poster.jpg", poster?.imageUrl)
        assertEquals(false, poster?.showTitleOverlay)
    }

    @Test
    fun `hero eligibility follows the active orientation`() {
        val banner = MetaPreview(id = "banner", type = "movie", name = "Banner", banner = "banner.jpg")
        val backdrop = MetaPreview(id = "backdrop", type = "movie", name = "Backdrop", background = "backdrop.jpg")
        val wideOnly = MetaPreview(id = "wide", type = "movie", name = "Wide", landscapePoster = "wide.jpg")
        assertEquals(true, isHomeHeroEligible(banner, HomeHeroOrientation.Portrait))
        assertEquals(true, isHomeHeroEligible(backdrop, HomeHeroOrientation.Portrait))
        assertEquals(false, isHomeHeroEligible(wideOnly, HomeHeroOrientation.Portrait))
        assertEquals(false, isHomeHeroEligible(MetaPreview(id = "empty", type = "movie", name = "Empty"), HomeHeroOrientation.Portrait))
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
