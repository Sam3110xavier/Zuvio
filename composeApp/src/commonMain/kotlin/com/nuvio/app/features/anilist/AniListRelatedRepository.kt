package com.nuvio.app.features.anilist

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape

/** AniList recommendations are public and limited to anime. */
object AniListRelatedRepository {
    private val cache = mutableMapOf<Long, Pair<Long, List<MetaPreview>>>()
    private const val CACHE_TTL_MS = 10 * 60_000L

    suspend fun getRelated(meta: MetaDetails): List<MetaPreview> {
        val mediaId = AniListTracker.resolver.resolveMediaId(meta.id, meta.type) ?: return emptyList()
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        cache[mediaId]?.takeIf { now - it.first < CACHE_TTL_MS }?.let { return it.second }

        val items = AniListTracker.api.recommendations(mediaId)
            .filterNot { it.isAdult || it.id == mediaId }
            .distinctBy { it.id }
            .map { media ->
                MetaPreview(
                    id = media.idMal?.let { "mal:$it" } ?: "anilist:${media.id}",
                    type = media.contentType,
                    name = media.displayTitle(),
                    poster = media.posterUrl(),
                    banner = media.bannerImage?.takeIf(String::isNotBlank),
                    posterShape = PosterShape.Poster,
                    description = null,
                    releaseInfo = media.releaseYear()?.toString(),
                    imdbRating = media.averageScore?.let { (it / 10.0).toString() },
                    genres = media.genres,
                )
            }
            .filter { it.poster != null || it.banner != null }
        cache[mediaId] = now to items
        return items
    }

    fun clearCache() = cache.clear()
}
