package com.nuvio.app.features.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingReadsTest {

    @Test
    fun `unified status selection maps to each provider native destination`() {
        val tabs = listOf(
            tab("local", null, TrackingLibraryTabKind.WATCHLIST),
            tab("simkl:watching", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:plantowatch", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:hold", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:dropped", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:completed", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("anilist:current", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:planning", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:paused", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:dropped", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:repeating", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:completed", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("mdblist:watchlist", TrackingProviderId.MDBLIST, TrackingLibraryTabKind.WATCHLIST),
            tab("trakt:watchlist", TrackingProviderId.TRAKT, TrackingLibraryTabKind.WATCHLIST),
            tab("trakt:personal:1", TrackingProviderId.TRAKT, TrackingLibraryTabKind.PERSONAL),
        )
        val initial = tabs.associate { it.key to false }.toMutableMap().apply {
            this["local"] = true
            this["simkl:completed"] = true
            this["trakt:personal:1"] = true
        }

        val updated = selectUnifiedTrackingStatus(
            tabs = tabs,
            membership = initial,
            status = UnifiedTrackingStatus.DROPPED,
        )

        assertTrue(updated["simkl:dropped"] == true)
        assertFalse(updated["simkl:watching"] == true)
        assertFalse(updated["simkl:completed"] == true)
        assertFalse(updated["simkl:completed"] == true)
        assertTrue(updated["anilist:dropped"] == true)
        assertFalse(updated["anilist:completed"] == true)
        assertTrue(updated["mdblist:watchlist"] == true)
        assertTrue(updated["trakt:watchlist"] == true)
        assertTrue(updated["trakt:personal:1"] == true)
        assertTrue(updated["local"] == true)
        assertEquals(
            UnifiedTrackingStatus.DROPPED,
            selectedUnifiedTrackingStatus(tabs, updated),
        )
    }

    @Test
    fun `Simkl completed status is a selectable native destination`() {
        val tabs = listOf(
            tab("simkl:watching", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:completed", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:dropped", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
        )
        assertEquals(
            listOf(UnifiedTrackingStatus.WATCHING, UnifiedTrackingStatus.DROPPED, UnifiedTrackingStatus.COMPLETED),
            unifiedTrackingStatuses(tabs),
        )
        val updated = selectUnifiedTrackingStatus(tabs, mapOf("simkl:watching" to true), UnifiedTrackingStatus.COMPLETED)
        assertTrue(updated["simkl:completed"] == true)
        assertFalse(updated["simkl:watching"] == true)
    }

    @Test
    fun `single list providers expose a plan to watch fallback`() {
        val tabs = listOf(
            tab("mdblist:watchlist", TrackingProviderId.MDBLIST, TrackingLibraryTabKind.WATCHLIST),
            tab("trakt:watchlist", TrackingProviderId.TRAKT, TrackingLibraryTabKind.WATCHLIST),
            tab("trakt:personal:1", TrackingProviderId.TRAKT, TrackingLibraryTabKind.PERSONAL),
        )
        assertEquals(
            listOf(UnifiedTrackingStatus.PLAN_TO_WATCH),
            unifiedTrackingStatuses(tabs),
        )
        val updated = selectUnifiedTrackingStatus(
            tabs = tabs,
            membership = emptyMap(),
            status = UnifiedTrackingStatus.PLAN_TO_WATCH,
        )
        assertTrue(updated["mdblist:watchlist"] == true)
        assertTrue(updated["trakt:watchlist"] == true)
        assertEquals(UnifiedTrackingStatus.PLAN_TO_WATCH, selectedUnifiedTrackingStatus(tabs, updated))
    }

    @Test
    fun `WeTrakr exposes only its supported status vocabulary including planning and watched`() {
        val tabs = listOf(
            tab("wetrakr:planning", TrackingProviderId.WETRAKR, TrackingLibraryTabKind.WATCHLIST, "wetrakr"),
            tab("wetrakr:watching", TrackingProviderId.WETRAKR, TrackingLibraryTabKind.STATUS, "wetrakr"),
            tab("wetrakr:watched", TrackingProviderId.WETRAKR, TrackingLibraryTabKind.STATUS, "wetrakr"),
            tab("wetrakr:waiting", TrackingProviderId.WETRAKR, TrackingLibraryTabKind.STATUS, "wetrakr", isMembershipDestination = false),
            tab("wetrakr:paused", TrackingProviderId.WETRAKR, TrackingLibraryTabKind.STATUS, "wetrakr"),
            tab("wetrakr:dropped", TrackingProviderId.WETRAKR, TrackingLibraryTabKind.STATUS, "wetrakr"),
        )
        assertEquals(
            listOf(
                UnifiedTrackingStatus.PLAN_TO_WATCH,
                UnifiedTrackingStatus.WATCHING,
                UnifiedTrackingStatus.ON_HOLD,
                UnifiedTrackingStatus.DROPPED,
                UnifiedTrackingStatus.COMPLETED,
            ),
            unifiedTrackingStatuses(tabs),
        )
        val planning = selectUnifiedTrackingStatus(tabs, emptyMap(), UnifiedTrackingStatus.PLAN_TO_WATCH)
        assertTrue(planning["wetrakr:planning"] == true)
        assertFalse(planning["wetrakr:watching"] == true)
        assertEquals(UnifiedTrackingStatus.PLAN_TO_WATCH, selectedUnifiedTrackingStatus(tabs, planning))
        val watched = selectUnifiedTrackingStatus(tabs, planning, UnifiedTrackingStatus.COMPLETED)
        assertTrue(watched["wetrakr:watched"] == true)
        assertFalse(watched["wetrakr:planning"] == true)
    }

    @Test
    fun `media type aliases are normalized for provider support`() {
        val movieTab = tab("movie", TrackingProviderId.WETRAKR, TrackingLibraryTabKind.STATUS)
            .copy(supportedContentTypes = setOf("movie"))
        val animeTab = tab("anime", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS)
            .copy(supportedContentTypes = setOf("anime"))
        assertTrue(movieTab.supportsContentType("film"))
        assertTrue(movieTab.supportsContentType("Movies"))
        assertTrue(animeTab.supportsContentType("anime-series"))
        assertFalse(movieTab.supportsContentType("series"))
    }

    @Test
    fun `selecting a status clears stale statuses from providers without that status`() {
        val tabs = listOf(
            tab("simkl:watching", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("simkl:dropped", TrackingProviderId.SIMKL, TrackingLibraryTabKind.STATUS, "simkl"),
            tab("anilist:planning", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
            tab("anilist:watching", TrackingProviderId.ANILIST, TrackingLibraryTabKind.STATUS, "anilist"),
        )
        val initial = mapOf("simkl:watching" to true, "anilist:planning" to true)
        val updated = selectUnifiedTrackingStatus(tabs, initial, UnifiedTrackingStatus.DROPPED)
        assertTrue(updated["simkl:dropped"] == true)
        assertFalse(updated["simkl:watching"] == true)
        assertFalse(updated["anilist:planning"] == true)
        assertFalse(updated["anilist:watching"] == true)
        assertEquals(UnifiedTrackingStatus.DROPPED, selectedUnifiedTrackingStatus(tabs, updated))
    }

    private fun tab(
        key: String,
        provider: TrackingProviderId?,
        kind: TrackingLibraryTabKind,
        group: String? = null,
        isMembershipDestination: Boolean = true,
    ) = TrackingLibraryTab(
        key = key,
        title = key,
        providerId = provider,
        kind = kind,
        selectionGroup = group,
        isMembershipDestination = isMembershipDestination,
    )
}
