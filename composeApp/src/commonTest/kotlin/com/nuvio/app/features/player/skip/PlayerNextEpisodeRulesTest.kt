package com.nuvio.app.features.player.skip

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerNextEpisodeRulesTest {
    @Test
    fun finalKnownEpisodeRequiresTheCurrentEpisodeToExistInMetadata() {
        val videos = listOf(
            MetaVideo(id = "tt-series:1:1", title = "Episode 1", season = 1, episode = 1),
            MetaVideo(id = "tt-series:1:2", title = "Episode 2", season = 1, episode = 2),
            MetaVideo(id = "tt-series:2:1", title = "Episode 3", season = 2, episode = 1),
        )

        assertFalse(PlayerNextEpisodeRules.isFinalKnownEpisode(videos, 1, 1))
        assertFalse(PlayerNextEpisodeRules.isFinalKnownEpisode(videos, 1, 2))
        assertTrue(PlayerNextEpisodeRules.isFinalKnownEpisode(videos, 2, 1))
        assertFalse(PlayerNextEpisodeRules.isFinalKnownEpisode(videos, 9, 9))
        assertFalse(PlayerNextEpisodeRules.isFinalKnownEpisode(emptyList(), 1, 1))
    }
}
