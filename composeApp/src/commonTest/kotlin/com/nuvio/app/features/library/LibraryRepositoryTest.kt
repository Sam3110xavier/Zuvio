package com.nuvio.app.features.library

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRepositoryTest {

    @Test
    fun `display title uses exact type formatting`() {
        assertEquals("Movie", "movie".toLibraryDisplayTitle())
        assertEquals("Anime Series", "anime-series".toLibraryDisplayTitle())
        assertEquals("Tv", "tv".toLibraryDisplayTitle())
        assertEquals("Other", "".toLibraryDisplayTitle())
    }

    @Test
    fun `meta preview mapping preserves exact type and poster shape`() {
        val item = LibraryItem(
            id = "tt1",
            type = "anime-series",
            name = "Title",
            poster = "poster",
            banner = "banner",
            logo = "logo",
            description = "desc",
            releaseInfo = "2024",
            imdbRating = "8.4",
            genres = listOf("Drama"),
            posterShape = PosterShape.Poster,
            savedAtEpochMs = 1L,
        )

        val preview = item.toMetaPreview()

        assertEquals("anime-series", preview.type)
        assertEquals(PosterShape.Poster, preview.posterShape)
        assertEquals("banner", preview.banner)
    }

    @Test
    fun `metadata mappings keep imdb ids for Trakt-compatible sync`() {
        val previewItem = MetaPreview(
            id = "tt1234567",
            type = "movie",
            name = "Movie",
        ).toLibraryItem(savedAtEpochMs = 1L)
        val detailsItem = MetaDetails(
            id = "tt7654321",
            type = "series",
            name = "Show",
        ).toLibraryItem(savedAtEpochMs = 2L)
        val tmdbOnlyItem = MetaPreview(
            id = "tmdb:42",
            type = "movie",
            name = "TMDB",
        ).toLibraryItem(savedAtEpochMs = 3L)

        assertEquals("tt1234567", previewItem.imdbId)
        assertEquals("tt7654321", detailsItem.imdbId)
        assertEquals(null, tmdbOnlyItem.imdbId)
    }

    @Test
    fun `library tabs include local Nuvio library before Trakt tabs`() {
        val traktTab = TrackingLibraryTab(
            key = "trakt:watchlist",
            title = "Watchlist",
            providerId = TrackingProviderId.TRAKT,
            kind = TrackingLibraryTabKind.WATCHLIST,
        )

        val tabs = libraryTabsWithLocal(listOf(traktTab))

        assertEquals(listOf("local", "trakt:watchlist"), tabs.map { it.key })
        assertEquals("Nuvio Library", tabs.first().title)
    }

    @Test
    fun `active source exposes only its own membership destinations plus local library`() {
        val tabs = libraryTabsForActiveSource(
            listOf(
                TrackingLibraryTab(
                    key = "wetrakr:planning",
                    title = "Planning",
                    providerId = TrackingProviderId.WETRAKR,
                    kind = TrackingLibraryTabKind.WATCHLIST,
                ),
                TrackingLibraryTab(
                    key = "wetrakr:waiting",
                    title = "Waiting",
                    providerId = TrackingProviderId.WETRAKR,
                    kind = TrackingLibraryTabKind.STATUS,
                    isMembershipDestination = false,
                ),
            ),
        )
        assertEquals(listOf("local", "wetrakr:planning"), tabs.map { it.key })
        assertFalse(tabs.any { it.key.startsWith("trakt:") })
    }

    @Test
    fun `hidden provider destinations preserve existing membership on save`() {
        val tabs = listOf(
            TrackingLibraryTab(
                key = "wetrakr:planning",
                title = "Planning",
                providerId = TrackingProviderId.WETRAKR,
                kind = TrackingLibraryTabKind.WATCHLIST,
            ),
            TrackingLibraryTab(
                key = "wetrakr:waiting",
                title = "Waiting",
                providerId = TrackingProviderId.WETRAKR,
                kind = TrackingLibraryTabKind.STATUS,
                isMembershipDestination = false,
            ),
            TrackingLibraryTab(
                key = "wetrakr:watching",
                title = "Watching",
                providerId = TrackingProviderId.WETRAKR,
                kind = TrackingLibraryTabKind.STATUS,
                supportedContentTypes = setOf("series"),
            ),
        )
        val merged = mergeHiddenProviderMembership(
            desiredMembership = mapOf("wetrakr:planning" to false),
            providerTabs = tabs,
            currentMembership = mapOf(
                "wetrakr:planning" to true,
                "wetrakr:waiting" to true,
                "wetrakr:watching" to true,
            ),
        )
        assertEquals(
            mapOf(
                "wetrakr:planning" to false,
                "wetrakr:waiting" to true,
                "wetrakr:watching" to true,
            ),
            merged,
        )
    }

    @Test
    fun `local status selection updates membership and groups saved titles into status sections`() {
        val initial = libraryMembershipWithLocal(
            inLocal = true,
            localStatus = LocalLibraryStatus.PLANNING.storageKey,
        )
        val updated = selectLocalLibraryStatus(initial, LocalLibraryStatus.DROPPED)
        assertTrue(updated["local"] == true)
        assertTrue(updated[LocalLibraryStatus.DROPPED.sectionKey] == true)
        assertFalse(updated[LocalLibraryStatus.PLANNING.sectionKey] == true)
        assertEquals(LocalLibraryStatus.DROPPED, selectedLocalLibraryStatus(updated))

        val sections = localLibrarySections(
            listOf(
                libraryItem(id = "a", savedAtEpochMs = 1L).copy(localStatus = LocalLibraryStatus.PLANNING.storageKey),
                libraryItem(id = "b", savedAtEpochMs = 2L).copy(localStatus = LocalLibraryStatus.COMPLETED.storageKey),
                libraryItem(id = "c", savedAtEpochMs = 3L).copy(localStatus = LocalLibraryStatus.DROPPED.storageKey),
            ),
        )
        assertEquals(
            listOf(
                LocalLibraryStatus.PLANNING.sectionKey,
                LocalLibraryStatus.DROPPED.sectionKey,
                LocalLibraryStatus.COMPLETED.sectionKey,
            ),
            sections.map(LibrarySection::type),
        )
    }

    @Test
    fun `local library vertical projection exposes status sections`() {
        val sections = localLibrarySections(
            listOf(libraryItem(id = "a", savedAtEpochMs = 1L).copy(localStatus = LocalLibraryStatus.COMPLETED.storageKey)),
        )
        val projection = buildLibraryVerticalProjection(
            sections = sections,
            sourceMode = LibrarySourceMode.LOCAL,
            selectedSectionKey = LocalLibraryStatus.COMPLETED.sectionKey,
            selectedType = null,
            sortOption = LibrarySortOption.ADDED_DESC,
        )
        assertEquals(listOf(LocalLibraryStatus.COMPLETED.sectionKey), projection.availableSections.map(LibrarySection::type))
        assertEquals(LocalLibraryStatus.COMPLETED.sectionKey, projection.selectedSectionKey)
        assertEquals(listOf("a"), projection.entries.map { entry -> entry.item.id })
    }

    @Test
    fun `library membership always includes local state before Trakt membership`() {
        val membership = libraryMembershipWithLocal(
            inLocal = true,
            providerMembership = mapOf("trakt:watchlist" to false),
        )

        assertEquals(
            mapOf(
                "local" to true,
                LocalLibraryStatus.PLANNING.sectionKey to true,
                LocalLibraryStatus.WATCHING.sectionKey to false,
                LocalLibraryStatus.ON_HOLD.sectionKey to false,
                LocalLibraryStatus.DROPPED.sectionKey to false,
                LocalLibraryStatus.COMPLETED.sectionKey to false,
                LocalLibraryStatus.REWATCHING.sectionKey to false,
                "trakt:watchlist" to false,
            ),
            membership,
        )
    }

    @Test
    fun `local snapshot remains attached to its profile after replacement`() {
        val state = LibraryLocalState()
        val profileOneLoad = state.beginProfileLoad(profileId = 1).snapshot
        assertFalse(profileOneLoad.hasLoaded)
        assertTrue(profileOneLoad.isLoading)

        val profileOneSnapshot = assertNotNull(
            state.completeProfileLoad(
                token = profileOneLoad.token,
                activeProfileId = 1,
                items = listOf(libraryItem(id = "profile-one", savedAtEpochMs = 1L)),
            ),
        )
        assertTrue(profileOneSnapshot.hasLoaded)
        assertFalse(profileOneSnapshot.isLoading)

        val profileTwoToken = state.beginProfileLoad(profileId = 2).snapshot.token
        state.completeProfileLoad(
            token = profileTwoToken,
            activeProfileId = 2,
            items = listOf(libraryItem(id = "profile-two", savedAtEpochMs = 2L)),
        )
        val profileTwoSnapshot = state.snapshot()

        assertEquals(1, profileOneSnapshot.token.profileId)
        assertEquals(listOf("profile-one"), profileOneSnapshot.items.map { it.id })
        assertEquals(2, profileTwoSnapshot.token.profileId)
        assertEquals(listOf("profile-two"), profileTwoSnapshot.items.map { it.id })
    }

    @Test
    fun `stale profile load cannot replace current profile items`() {
        val state = LibraryLocalState()
        val staleToken = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = staleToken,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "old", savedAtEpochMs = 1L)),
        )
        val staleSnapshot = state.snapshot()

        val currentToken = state.beginProfileLoad(profileId = 2).snapshot.token
        state.completeProfileLoad(
            token = currentToken,
            activeProfileId = 2,
            items = listOf(libraryItem(id = "current", savedAtEpochMs = 2L)),
        )

        val staleCommit = state.completeProfileLoad(
            token = staleToken,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "stale", savedAtEpochMs = 3L)),
        )

        assertNull(staleCommit)
        var stalePersistenceAccepted = false
        assertFalse(
            state.runIfContentCurrent(staleSnapshot) {
                stalePersistenceAccepted = true
            },
        )
        assertFalse(stalePersistenceAccepted)
        assertEquals(2, state.snapshot().token.profileId)
        assertEquals(listOf("current"), state.snapshot().items.map { it.id })
    }

    @Test
    fun `stale publication revision is rejected after same profile mutation`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "first", savedAtEpochMs = 1L)),
        )
        val staleSnapshot = state.snapshot()
        state.upsert(libraryItem(id = "second", savedAtEpochMs = 2L))

        var committed = false
        val accepted = state.runIfCurrent(staleSnapshot) {
            committed = true
        }

        assertFalse(accepted)
        assertFalse(committed)
        assertEquals(setOf("first", "second"), state.snapshot().items.map { it.id }.toSet())
    }

    @Test
    fun `pull lifecycle does not invalidate pending local push`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = emptyList(),
        )
        val localSnapshot = state.upsert(libraryItem(id = "local", savedAtEpochMs = 1L))

        state.markPullStarted(token)

        assertTrue(state.isContentCurrent(localSnapshot))
    }

    @Test
    fun `server pull preserves local items awaiting push`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
        )
        state.upsert(libraryItem(id = "local-new", savedAtEpochMs = 2L))
        val pullSnapshot = assertNotNull(state.markPullStarted(token))

        val result = assertNotNull(
            state.applyServerItems(
                pullSnapshot = pullSnapshot,
                serverItems = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
            ),
        )

        assertTrue(result.preservedLocalItems)
        assertEquals(
            setOf("remote-old", "local-new"),
            result.snapshot.items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `server pull preserves a local mutation made while pulling`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
        )
        val pullSnapshot = assertNotNull(state.markPullStarted(token))
        state.upsert(libraryItem(id = "local-new", savedAtEpochMs = 2L))

        val result = assertNotNull(
            state.applyServerItems(
                pullSnapshot = pullSnapshot,
                serverItems = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
            ),
        )

        assertTrue(result.preservedLocalItems)
        assertEquals(
            setOf("remote-old", "local-new"),
            result.snapshot.items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `server pull applies after local push completes`() {
        val state = LibraryLocalState()
        val token = state.beginProfileLoad(profileId = 1).snapshot.token
        state.completeProfileLoad(
            token = token,
            activeProfileId = 1,
            items = listOf(libraryItem(id = "remote-old", savedAtEpochMs = 1L)),
        )
        val localSnapshot = state.upsert(libraryItem(id = "local-new", savedAtEpochMs = 2L))
        state.markPushCompleted(localSnapshot)
        val pullSnapshot = assertNotNull(state.markPullStarted(token))

        val result = assertNotNull(
            state.applyServerItems(
                pullSnapshot = pullSnapshot,
                serverItems = listOf(libraryItem(id = "remote-new", savedAtEpochMs = 3L)),
            ),
        )

        assertFalse(result.preservedLocalItems)
        assertEquals(listOf("remote-new"), result.snapshot.items.map { it.id })
    }

    private fun libraryItem(id: String, savedAtEpochMs: Long): LibraryItem =
        LibraryItem(
            id = id,
            type = "movie",
            name = id,
            savedAtEpochMs = savedAtEpochMs,
        )
}
