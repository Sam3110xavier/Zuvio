package com.nuvio.app.features.membership

import com.nuvio.app.features.profiles.NuvioProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileBackgroundTest {
    @Test
    fun catalogBackgroundResolvesWithoutSupporterEntitlement() {
        val profile = NuvioProfile(profileBackgroundId = "aurora")
        assertEquals(ProfileBackgroundSelection.Catalog("aurora"), resolveProfileBackground(profile))
    }

    @Test
    fun customBackgroundTakesPriorityOverCatalogBackground() {
        val profile = NuvioProfile(
            profileBackgroundId = "aurora",
            profileBackgroundUrl = "https://example.com/background.png",
        )
        assertEquals(
            ProfileBackgroundSelection.Custom("https://example.com/background.png"),
            resolveProfileBackground(profile),
        )
    }

    @Test
    fun blankCustomBackgroundFallsBackToCatalogBackground() {
        val profile = NuvioProfile(
            profileBackgroundId = "aurora",
            profileBackgroundUrl = "  ",
        )
        assertEquals(ProfileBackgroundSelection.Catalog("aurora"), resolveProfileBackground(profile))
    }

    @Test
    fun customBackgroundUrlIsTrimmed() {
        val profile = NuvioProfile(
            profileBackgroundUrl = "  https://example.com/background.png  ",
        )
        assertEquals(
            ProfileBackgroundSelection.Custom("https://example.com/background.png"),
            resolveProfileBackground(profile),
        )
    }

    @Test
    fun noBackgroundSelectionReturnsNull() {
        assertNull(resolveProfileBackground(NuvioProfile()))
    }
}
