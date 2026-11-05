package com.nuvio.app.features.settings

import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import com.nuvio.app.features.trakt.TraktConnectionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingSettingsPresentationTest {
    @Test
    fun `provider connection modes map to matching card modes`() {
        assertEquals(
            TrackingConnectionCardMode.DISCONNECTED,
            TraktConnectionMode.DISCONNECTED.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.AWAITING_APPROVAL,
            TraktConnectionMode.AWAITING_APPROVAL.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.CONNECTED,
            TraktConnectionMode.CONNECTED.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.DISCONNECTED,
            SimklConnectionMode.DISCONNECTED.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.AWAITING_APPROVAL,
            SimklConnectionMode.AWAITING_APPROVAL.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.CONNECTED,
            SimklConnectionMode.CONNECTED.toTrackingConnectionCardMode(),
        )
    }

    @Test
    fun `remote brands are available only while their provider is connected`() {
        assertTrue(isTrackingBrandAvailable(TrackingBrand.NUVIO, false, false))
        assertTrue(isTrackingBrandAvailable(TrackingBrand.TMDB, false, false))
        assertFalse(isTrackingBrandAvailable(TrackingBrand.TRAKT, false, true))
        assertTrue(isTrackingBrandAvailable(TrackingBrand.TRAKT, true, false))
        assertFalse(isTrackingBrandAvailable(TrackingBrand.SIMKL, true, false))
        assertTrue(isTrackingBrandAvailable(TrackingBrand.SIMKL, false, true))
    }

    @Test
    fun `Trakt recommendations fall back visually without rewriting preference`() {
        val stored = MoreLikeThisSourcePreference.TRAKT

        assertEquals(
            MoreLikeThisSourcePreference.TMDB,
            effectiveTrackingRecommendationsSource(stored, traktConnected = false, simklConnected = false),
        )
        assertEquals(
            MoreLikeThisSourcePreference.TRAKT,
            effectiveTrackingRecommendationsSource(stored, traktConnected = true, simklConnected = false),
        )
        assertEquals(MoreLikeThisSourcePreference.TRAKT, stored)
    }

    @Test
    fun `TMDB remains selectable and Simkl falls back when disconnected`() {
        assertEquals(
            MoreLikeThisSourcePreference.TMDB,
            effectiveTrackingRecommendationsSource(
                MoreLikeThisSourcePreference.TMDB,
                traktConnected = false,
                simklConnected = false,
            ),
        )
        assertEquals(
            MoreLikeThisSourcePreference.TMDB,
            effectiveTrackingRecommendationsSource(
                MoreLikeThisSourcePreference.SIMKL,
                traktConnected = false,
                simklConnected = false,
            ),
        )
        assertEquals(
            MoreLikeThisSourcePreference.SIMKL,
            effectiveTrackingRecommendationsSource(
                MoreLikeThisSourcePreference.SIMKL,
                traktConnected = false,
                simklConnected = true,
            ),
        )
    }
}
