package com.nuvio.app.features.streams

import com.nuvio.app.features.cloudstream.CloudStreamPluginItem
import com.nuvio.app.features.cloudstream.CloudStreamRepository
import com.nuvio.app.features.cloudstream.CloudStreamSearchItem
import com.nuvio.app.features.cloudstream.CloudStreamSearchRouteIndex
import com.nuvio.app.features.cloudstream.CloudStreamTvType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import com.nuvio.app.features.cloudstream.sha256Hex
import com.nuvio.app.features.cloudstream.toStreamItem
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.tmdb.TmdbMetadataService

internal data class CloudStreamSearchRequest(
    val title: String,
    val type: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val aliases: List<String> = emptyList(),
    val episodeTitle: String? = null,
    val externalId: String? = null,
    val tmdbId: String? = null,
    val genres: List<String> = emptyList(),
)

internal data class CloudStreamProviderTarget(
    val id: String,
    val name: String,
)

internal data class CloudStreamProviderGroup(
    val addonId: String,
    val addonName: String,
    val providers: List<CloudStreamProviderTarget>,
)

internal data class CloudStreamProviderStreams(
    val streams: List<StreamItem>,
    val error: String? = null,
)

internal fun cloudStreamAddonId(providerId: String): String =
    "cloudstream:${sha256Hex(providerId.encodeToByteArray()).take(16)}"

internal fun buildCloudStreamSearchRequest(
    type: String,
    videoId: String,
    parentMetaId: String?,
    parentMetaType: String?,
    season: Int?,
    episode: Int?,
    searchTitle: String?,
): CloudStreamSearchRequest? {
    val metaLookupType = parentMetaType?.trim()?.takeIf(String::isNotBlank) ?: type
    val metaLookupId = parentMetaId?.trim()?.takeIf(String::isNotBlank) ?: videoId
    val details = MetaDetailsRepository.peek(metaLookupType, metaLookupId)
        ?: MetaDetailsRepository.peek(type, videoId)
    val title = searchTitle?.trim()?.takeIf(String::isNotBlank)
        ?: details?.name?.trim()?.takeIf(String::isNotBlank)
        ?: return null
    val year = details?.releaseInfo
        ?.let { Regex("\\b(19\\d{2}|20\\d{2})\\b").find(it)?.value?.toIntOrNull() }
        ?: cloudStreamYear(title)
    val episodeDetails = if (episode != null) {
        details?.videos?.firstOrNull { it.season == season && it.episode == episode }
    } else {
        null
    }
    val episodeTitle = episodeDetails?.title?.takeIf(String::isNotBlank)
    val imdbIdPattern = Regex("tt\\d{5,12}", RegexOption.IGNORE_CASE)
    val externalId = episodeDetails?.imdbId?.trim()?.takeIf(imdbIdPattern::matches)
        ?: details?.imdbId?.trim()?.takeIf(imdbIdPattern::matches)
        ?: parentMetaId?.trim()?.takeIf(imdbIdPattern::matches)
        ?: videoId.trim().takeIf(imdbIdPattern::matches)
    val tmdbId = listOf(parentMetaId, videoId, details?.id)
        .firstNotNullOfOrNull(::cloudStreamTmdbId)
    val originalTitle = tmdbId?.let { TmdbMetadataService.cachedOriginalTitle(it, type) }
    val aliases = listOfNotNull(
        searchTitle?.trim(),
        details?.name?.trim(),
        originalTitle?.trim(),
    ).filter(String::isNotBlank).distinct()
    val (requestSeason, requestEpisode) = cloudStreamEpisodeCoordinates(videoId, season, episode)
    return CloudStreamSearchRequest(
        title = title,
        type = type,
        year = year,
        season = requestSeason,
        episode = requestEpisode,
        episodeTitle = episodeTitle,
        externalId = externalId,
        tmdbId = tmdbId,
        aliases = aliases,
        genres = details?.genres.orEmpty(),
    )
}

internal fun cloudStreamEpisodeCoordinates(
    videoId: String,
    season: Int?,
    episode: Int?,
): Pair<Int?, Int?> {
    val absoluteEpisode = absoluteCloudStreamAnimeEpisodeNumber(videoId)
    return if (absoluteEpisode != null) null to absoluteEpisode else season to episode
}

internal fun absoluteCloudStreamAnimeEpisodeNumber(videoId: String): Int? {
    val parts = videoId.trim().split(':')
    if (parts.size != 3) return null
    if (parts[0].lowercase() !in setOf("kitsu", "mal", "anilist", "anidb")) return null
    if (parts[1].isBlank() || parts[1].toLongOrNull() == null) return null
    return parts[2].toIntOrNull()?.takeIf { it > 0 }
}

private fun cloudStreamTmdbId(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val prefix = raw.substringBefore(':', missingDelimiterValue = "")
    if (prefix.lowercase() !in setOf("tmdb", "movie", "series", "tv")) return null
    val candidate = raw.substringAfter(':').substringBefore(':').substringBefore('/').trim()
    return candidate.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.toLongOrNull() != null }
}

internal fun cloudStreamProviderGroupsForRequest(
    type: String,
    request: CloudStreamSearchRequest?,
): List<CloudStreamProviderGroup> {
    val state = CloudStreamRepository.uiState.value
    val repositoryNames = state.repositories.associate { it.manifest.sourceUrl to it.manifest.name }
    return state.plugins
        .asSequence()
        .filter { it.enabled && it.isRunnable }
        .filter { providerSupportsType(it, type.ifBlank { request?.type ?: type }, request?.genres.orEmpty()) }
        .distinctBy { it.metadata.id.value }
        .map { item ->
            val provider = CloudStreamProviderTarget(item.metadata.id.value, item.metadata.name)
            val repositoryUrl = item.metadata.repositoryManifestUrl
            val grouped = state.groupStreamsByRepository
            CloudStreamProviderGroup(
                // Shared addonId lets StreamsRepository aggregate providers from one repository,
                // while each provider still gets an independent timeout and completion event.
                addonId = if (grouped) {
                    "cloudstream-repository:${sha256Hex(repositoryUrl.encodeToByteArray()).take(16)}"
                } else {
                    cloudStreamAddonId(provider.id)
                },
                addonName = if (grouped) {
                    repositoryNames[repositoryUrl]?.takeIf(String::isNotBlank) ?: provider.name
                } else {
                    provider.name
                },
                providers = listOf(provider),
            )
        }
        .sortedWith(
            compareBy<CloudStreamProviderGroup> { it.addonName.lowercase() }
                .thenBy { it.providers.first().name.lowercase() },
        )
        .toList()
}

internal fun cloudStreamTypesSupportRequest(
    supportedTypes: Set<CloudStreamTvType>,
    type: String,
    genres: List<String>,
): Boolean {
    if (supportedTypes.isEmpty() || CloudStreamTvType.Other in supportedTypes) return true
    val normalizedGenres = genres.map { it.trim().lowercase() }.toSet()
    val anime = "anime" in normalizedGenres
    val animation = normalizedGenres.any { it in setOf("animation", "animasyon") }
    val documentary = normalizedGenres.any { it in setOf("documentary", "belgesel") }

    return when (normalizeCloudStreamType(type)) {
        "movie" -> when {
            anime -> supportedTypes.any { it == CloudStreamTvType.AnimeMovie || it == CloudStreamTvType.Movie }
            animation -> supportedTypes.any {
                it == CloudStreamTvType.AnimeMovie || it == CloudStreamTvType.Cartoon || it == CloudStreamTvType.Movie
            }
            documentary -> supportedTypes.any { it == CloudStreamTvType.Documentary || it == CloudStreamTvType.Movie }
            else -> supportedTypes.any { it.nuvioType == "movie" }
        }
        "series" -> when {
            anime -> supportedTypes.any {
                it == CloudStreamTvType.Anime || it == CloudStreamTvType.Ova || it == CloudStreamTvType.TvSeries
            }
            animation -> supportedTypes.any {
                it == CloudStreamTvType.Anime || it == CloudStreamTvType.Ova ||
                    it == CloudStreamTvType.Cartoon || it == CloudStreamTvType.TvSeries
            }
            else -> supportedTypes.any { it.nuvioType == "series" }
        }
        "live" -> supportedTypes.any { it.nuvioType == "live" }
        else -> supportedTypes.any { it.nuvioType == normalizeCloudStreamType(type) }
    }
}

private fun providerSupportsType(
    item: CloudStreamPluginItem,
    type: String,
    genres: List<String>,
): Boolean = cloudStreamTypesSupportRequest(item.metadata.tvTypes.toSet(), type, genres)

private fun normalizeCloudStreamType(type: String): String = when (type.trim().lowercase()) {
    "tv", "show", "series" -> "series"
    "movie", "film" -> "movie"
    "live", "livetv", "channel" -> "live"
    else -> type.trim().lowercase()
}

private val cloudStreamProviderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private const val CLOUDSTREAM_PROVIDER_STREAM_TIMEOUT_MS = 120_000L

internal suspend fun resolveCloudStreamProviderStreams(
    providerGroup: CloudStreamProviderGroup,
    request: CloudStreamSearchRequest?,
): CloudStreamProviderStreams {
    val searchRequest = request?.takeIf { it.title.isNotBlank() }
        ?: return CloudStreamProviderStreams(
            streams = emptyList(),
            error = "CloudStream search title is unavailable",
        )
    if (providerGroup.providers.isEmpty()) {
        return CloudStreamProviderStreams(
            streams = emptyList(),
            error = "No enabled, compatible CloudStream providers are available",
        )
    }

    // Third-party providers can block or ignore cancellation. Isolate each provider
    // so a hung extension cannot hold the stream collector waiting for completions.
    val providerTasks = providerGroup.providers.map { provider ->
        provider to cloudStreamProviderScope.async {
            resolveSingleCloudStreamProvider(provider, searchRequest)
        }
    }
    val results = mutableListOf<CloudStreamProviderStreams>()
    try {
        providerTasks.forEach { (provider, task) ->
            val result = try {
                withTimeoutOrNull(CLOUDSTREAM_PROVIDER_STREAM_TIMEOUT_MS) { task.await() }
                    ?: CloudStreamProviderStreams(
                        emptyList(),
                        "CloudStream provider timed out after ${CLOUDSTREAM_PROVIDER_STREAM_TIMEOUT_MS / 1000}s",
                    )
            } catch (timeout: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) throw timeout
                CloudStreamProviderStreams(emptyList(), "CloudStream provider ${provider.name} timed out")
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                CloudStreamProviderStreams(
                    emptyList(),
                    cancelled.message ?: "CloudStream provider ${provider.name} was cancelled unexpectedly",
                )
            } finally {
                if (!task.isCompleted) task.cancel()
            }
            results += result
        }
    } finally {
        providerTasks.forEach { (_, task) -> if (!task.isCompleted) task.cancel() }
    }
    return CloudStreamProviderStreams(
        streams = results.flatMap { it.streams }.distinctBy(::cloudStreamStreamIdentity),
        error = results.firstNotNullOfOrNull { it.error?.takeIf(String::isNotBlank) },
    )
}

private suspend fun resolveSingleCloudStreamProvider(
    provider: CloudStreamProviderTarget,
    request: CloudStreamSearchRequest,
): CloudStreamProviderStreams {
    var firstError: String? = null
    var matchedTitles = 0
    var attemptedExtractors = 0

    fun rememberError(error: Throwable?, fallback: String) {
        if (firstError == null) firstError = error?.message?.takeIf(String::isNotBlank) ?: fallback
    }

    suspend fun resolveMatch(
        item: CloudStreamSearchItem,
        allowProviderRankedFallback: Boolean,
    ): List<StreamItem>? {
        val loadedResult = runCatchingUnlessCancelled { CloudStreamRepository.load(provider.id, item.data) }
        val loaded = loadedResult.getOrNull()?.getOrNull()
        if (loaded == null) {
            if (firstError == null) firstError = loadedResult.exceptionOrNull()?.message
                ?: loadedResult.getOrNull()?.exceptionOrNull()?.message
                ?: "CloudStream title lookup failed"
            return null
        }
        if (!loaded.matchesCloudStreamRequest(request, item, allowProviderRankedFallback)) return null
        val linkData = loaded.linkDataForCloudStreamRequest(request) ?: run {
            rememberError(null, "CloudStream episode not found")
            return null
        }
        attemptedExtractors++
        val linksResult = runCatchingUnlessCancelled { CloudStreamRepository.loadLinks(provider.id, linkData) }
        val links = linksResult.getOrNull()?.getOrNull()
        if (links.isNullOrEmpty()) {
            if (firstError == null) firstError = linksResult.exceptionOrNull()?.message
                ?: linksResult.getOrNull()?.exceptionOrNull()?.message
                ?: "CloudStream provider returned no playable links"
            return null
        }
        return links.map { it.toStreamItem(provider.id, provider.name) }
            .distinctBy(::cloudStreamStreamIdentity)
    }

    // First try cached provider URLs collected from CloudStream catalogue search.
    val cachedRoutes = CloudStreamSearchRouteIndex.find(
        providerId = provider.id,
        titles = listOf(request.title) + request.aliases,
        type = request.type,
        year = request.year,
    )
    for (route in cachedRoutes) {
        resolveMatch(route, allowProviderRankedFallback = false)?.let {
            return CloudStreamProviderStreams(it)
        }
    }

    // TMDB providers may support ID lookup without implementing text search.
    val tmdbLookup = request.tmdbId?.let { tmdbId ->
        runCatchingUnlessCancelled {
            CloudStreamRepository.loadByExternalId(
                provider.id,
                "nuvio-tmdb:$tmdbId:${if (normalizeCloudStreamType(request.type) == "movie") "movie" else "tv"}",
            )
        }
    }
    if (tmdbLookup?.isFailure == true) rememberError(tmdbLookup.exceptionOrNull(), "CloudStream TMDB lookup failed")
    val tmdbResult = tmdbLookup?.getOrNull()
    if (tmdbResult?.isFailure == true) rememberError(tmdbResult.exceptionOrNull(), "CloudStream TMDB lookup failed")
    val tmdbLoaded = tmdbResult?.getOrNull()
    if (tmdbLoaded != null && tmdbLoaded.matchesCloudStreamTmdbRequest(request)) {
        val linkData = tmdbLoaded.linkDataForCloudStreamRequest(request)
        if (linkData != null) {
            attemptedExtractors++
            val linksAttempt = runCatchingUnlessCancelled { CloudStreamRepository.loadLinks(provider.id, linkData) }
            if (linksAttempt.isFailure) rememberError(linksAttempt.exceptionOrNull(), "CloudStream TMDB link extraction failed")
            val linksResult = linksAttempt.getOrNull()
            if (linksResult?.isFailure == true) rememberError(linksResult.exceptionOrNull(), "CloudStream TMDB link extraction failed")
            val links = linksResult?.getOrNull().orEmpty()
            if (links.isNotEmpty()) return CloudStreamProviderStreams(links.map { it.toStreamItem(provider.id, provider.name) })
        }
    }

    val externalLookup = request.externalId?.let { id ->
        runCatchingUnlessCancelled { CloudStreamRepository.loadByExternalId(provider.id, id) }
    }
    if (externalLookup?.isFailure == true) rememberError(externalLookup.exceptionOrNull(), "CloudStream external-ID lookup failed")
    val externalResult = externalLookup?.getOrNull()
    if (externalResult?.isFailure == true) rememberError(externalResult.exceptionOrNull(), "CloudStream external-ID lookup failed")
    val externalLoaded = externalResult?.getOrNull()
    if (externalLoaded != null && externalLoaded.matchesCloudStreamExternalIdRequest(request)) {
        val linkData = externalLoaded.linkDataForCloudStreamRequest(request)
        if (linkData != null) {
            attemptedExtractors++
            val linksAttempt = runCatchingUnlessCancelled { CloudStreamRepository.loadLinks(provider.id, linkData) }
            if (linksAttempt.isFailure) rememberError(linksAttempt.exceptionOrNull(), "CloudStream external-ID link extraction failed")
            val linksResult = linksAttempt.getOrNull()
            if (linksResult?.isFailure == true) rememberError(linksResult.exceptionOrNull(), "CloudStream external-ID link extraction failed")
            val links = linksResult?.getOrNull().orEmpty()
            if (links.isNotEmpty()) return CloudStreamProviderStreams(links.map { it.toStreamItem(provider.id, provider.name) })
        }
    }

    val seen = mutableSetOf<String>()
    for (query in cloudStreamSearchQueries(request)) {
        val searchAttempt = runCatchingUnlessCancelled { CloudStreamRepository.search(query, provider.id) }
        if (searchAttempt.isFailure) rememberError(searchAttempt.exceptionOrNull(), "CloudStream search failed")
        val searchItems = searchAttempt.getOrNull().orEmpty().flatMap { result ->
            result.getOrElse { error ->
                if (firstError == null) firstError = error.message ?: "CloudStream search failed"
                emptyList()
            }
        }.distinctBy { it.type to it.data }.filter { seen.add(it.type.toString() + "|" + it.data) }
        val strictMatches = searchItems.bestCloudStreamMatches(request)
        val fallback = strictMatches.isEmpty()
        val matches = strictMatches.ifEmpty { searchItems.providerRankedCloudStreamFallbacks(request) }
        if (matches.isEmpty()) continue
        matchedTitles += matches.size
        for (item in matches) {
            resolveMatch(item, allowProviderRankedFallback = fallback)?.let { streams ->
                if (streams.isNotEmpty()) return CloudStreamProviderStreams(streams, firstError)
            }
        }
    }

    val diagnostic = when {
        matchedTitles == 0 && firstError == null -> "CloudStream returned no title matching '${request.title}'"
        attemptedExtractors == 0 && firstError == null -> "CloudStream title matched, but no season/episode entry could be selected"
        firstError == null -> "CloudStream matched the title but returned no playable links"
        else -> firstError
    }
    return CloudStreamProviderStreams(emptyList(), diagnostic)
}

internal suspend fun resolveCloudStreamDirectRoute(
    providerId: String,
    providerName: String,
    data: String,
): Result<List<StreamItem>> {
    val resolved = withTimeoutOrNull(CLOUDSTREAM_PROVIDER_STREAM_TIMEOUT_MS) {
        val loadAttempt = runCatchingUnlessCancelled { CloudStreamRepository.load(providerId, data) }
        val loadedResult = loadAttempt.getOrElse {
            return@withTimeoutOrNull Result.failure(it)
        }
        if (loadedResult.isFailure) {
            return@withTimeoutOrNull Result.failure(
                loadedResult.exceptionOrNull() ?: IllegalStateException("CloudStream title lookup failed"),
            )
        }
        val item = loadedResult.getOrNull() ?: return@withTimeoutOrNull Result.success(emptyList())

        val linksAttempt = runCatchingUnlessCancelled { CloudStreamRepository.loadLinks(providerId, item.data) }
        val linksResult = linksAttempt.getOrElse {
            return@withTimeoutOrNull Result.failure(it)
        }
        if (linksResult.isFailure) {
            return@withTimeoutOrNull Result.failure(
                linksResult.exceptionOrNull() ?: IllegalStateException("CloudStream link extraction failed"),
            )
        }
        Result.success(
            linksResult.getOrNull().orEmpty()
                .map { it.toStreamItem(providerId, providerName) }
                .distinctBy(::cloudStreamStreamIdentity),
        )
    }
    return resolved ?: Result.failure(IllegalStateException("CloudStream provider timed out"))
}

internal fun List<CloudStreamSearchItem>.bestCloudStreamMatch(
    searchTitle: String,
    type: String,
): CloudStreamSearchItem? =
    bestCloudStreamMatches(
        CloudStreamSearchRequest(title = searchTitle, type = type),
    ).firstOrNull()

internal fun List<CloudStreamSearchItem>.bestCloudStreamMatches(
    request: CloudStreamSearchRequest,
): List<CloudStreamSearchItem> {
    val requestedTitles = (listOf(request.title) + request.aliases)
        .map(::normalizeCloudStreamTitle)
        .filter(String::isNotBlank)
        .toSet()
    if (requestedTitles.isEmpty()) return emptyList()
    return mapNotNull { item ->
        val candidateYear = item.year ?: cloudStreamYear(item.name)
        if (request.year != null && candidateYear != null &&
            kotlin.math.abs(candidateYear - request.year) > 1
        ) return@mapNotNull null

        val similarity = cloudStreamTitleSimilarity(item.name, requestedTitles)
        val minimumSimilarity = if (requestedTitles.any { it.length <= 5 }) 0.92 else 0.86
        if (similarity < minimumSimilarity) return@mapNotNull null

        val actualType = normalizeCloudStreamType(item.type.nuvioType)
        val requestedType = normalizeCloudStreamType(request.type)
        val exactTitle = similarity >= 0.98
        if (actualType != requestedType && actualType != "other" && !exactTitle) {
            return@mapNotNull null
        }
        if (requestedType == "series" && actualType in setOf("anime", "ova", "asiandrama") &&
            similarity < 0.92
        ) return@mapNotNull null

        val yearBonus = if (request.year != null && candidateYear == request.year) 0.15 else 0.0
        val typeBonus = if (actualType == requestedType) 0.05 else 0.0
        item to (similarity + yearBonus + typeBonus)
    }.sortedByDescending { it.second }
        .distinctBy { it.first.data }
        .map { it.first }
        .take(3)
}

private fun List<CloudStreamSearchItem>.providerRankedCloudStreamFallbacks(
    request: CloudStreamSearchRequest,
): List<CloudStreamSearchItem> {
    val requestedType = normalizeCloudStreamType(request.type)
    val typed = filter { cloudStreamTypeMatches(it.type.nuvioType, requestedType) }
    return (typed.ifEmpty { this })
        .filter { item ->
            val candidateYear = item.year ?: cloudStreamYear(item.name)
            request.year == null || candidateYear == null || candidateYear == request.year
        }
        .distinctBy { it.data }
        .take(10)
}

internal fun com.nuvio.app.features.cloudstream.CloudStreamLoadItem.matchesCloudStreamRequest(
    request: CloudStreamSearchRequest,
    searchItem: CloudStreamSearchItem,
    allowProviderRankedFallback: Boolean = false,
): Boolean {
    val requestedTitles = (listOf(request.title) + request.aliases)
        .map(::normalizeCloudStreamTitle).filter(String::isNotBlank).toSet()
    val titleSimilarity = maxOf(
        cloudStreamTitleSimilarity(name, requestedTitles),
        cloudStreamTitleSimilarity(searchItem.name, requestedTitles),
    )
    val loadedYear = year ?: cloudStreamYear(name)
    val searchedYear = searchItem.year ?: cloudStreamYear(searchItem.name)
    if (request.year != null) {
        if (loadedYear != null && kotlin.math.abs(loadedYear - request.year) > 1) return false
        if (searchedYear != null && kotlin.math.abs(searchedYear - request.year) > 1) return false
    }
    if (cloudStreamTypeMatches(type.nuvioType, request.type) && titleSimilarity >= 0.86) return true
    // Localized provider-ranked fallback is only safe for movies with exact year identity.
    return allowProviderRankedFallback &&
        normalizeCloudStreamType(request.type) == "movie" &&
        request.year != null && loadedYear == request.year &&
        cloudStreamTypeMatches(type.nuvioType, request.type)
}

internal fun com.nuvio.app.features.cloudstream.CloudStreamLoadItem.matchesCloudStreamExternalIdRequest(
    request: CloudStreamSearchRequest,
): Boolean {
    if (!cloudStreamTypeMatches(type.nuvioType, request.type)) return false
    val requestedTitles = (listOf(request.title) + request.aliases)
        .map(::normalizeCloudStreamTitle)
        .filter(String::isNotBlank)
        .toSet()
    val titleMatches = cloudStreamTitleMatches(name, requestedTitles)
    val loadedYear = year ?: cloudStreamYear(name)
    if (request.year != null && loadedYear != request.year) return false
    if (titleMatches) return true

    // External-ID lookups may return localized movie titles. A year alone cannot
    // safely identify a localized series because many shows share the same release year.
    if (normalizeCloudStreamType(request.type) == "series") return false
    return request.year != null && loadedYear == request.year
}

internal fun com.nuvio.app.features.cloudstream.CloudStreamLoadItem.matchesCloudStreamTmdbRequest(
    request: CloudStreamSearchRequest,
): Boolean {
    if (!cloudStreamTypeMatches(type.nuvioType, request.type)) return false
    val loadedYear = year ?: cloudStreamYear(name)
    return request.year == null || loadedYear == null ||
        kotlin.math.abs(loadedYear - request.year) <= 1
}

internal fun com.nuvio.app.features.cloudstream.CloudStreamLoadItem.linkDataForCloudStreamRequest(
    request: CloudStreamSearchRequest,
): String? {
    if (request.season == null && request.episode == null && request.episodeTitle.isNullOrBlank()) return data
    if (episodes.isEmpty()) return null
    val seasonCandidates = episodes.filter { episode ->
        request.season == null || episode.season == request.season ||
            (request.season == 1 && episode.season == null)
    }
    val episodeTitle = request.episodeTitle?.let(::normalizeCloudStreamTitle)
    val numbered = if (request.episode == null) {
        null
    } else if (request.season != null) {
        seasonCandidates.firstOrNull { it.episode == request.episode }
    } else {
        // Absolute anime catalogs are usually seasonless; some tag every row S1.
        seasonCandidates.firstOrNull { it.episode == request.episode && it.season == null }
            ?: seasonCandidates.firstOrNull { it.episode == request.episode }
    }
    if (numbered != null) {
        // Season/episode numbering is more reliable than translated episode titles.
        // Providers frequently localize names while retaining correct episode numbers.
        return numbered.data
    }
    if (!episodeTitle.isNullOrBlank()) {
        return seasonCandidates.firstOrNull {
            normalizeCloudStreamTitle(it.name) == episodeTitle
        }?.data
    }
    return null
}

private fun cloudStreamTypeMatches(actualType: String, requestedType: String): Boolean {
    val actual = normalizeCloudStreamType(actualType)
    val requested = normalizeCloudStreamType(requestedType)
    // CloudStream providers sometimes classify otherwise valid results as Other.
    // Keep those candidates, but still require title/year validation below.
    return actual == requested || actual == "other"
}

private val cloudStreamReleaseSuffixTokens = setOf(
    "480p", "720p", "1080p", "2160p", "4k", "8k",
    "hindi", "english", "tamil", "telugu", "malayalam", "kannada", "japanese", "korean",
    "dub", "dubbed", "sub", "subbed", "dual", "multi", "audio",
    "web", "webdl", "webrip", "dl", "bluray", "brrip", "hdrip", "hdtv", "remux", "nf", "amzn", "dsnp",
    "x264", "x265", "h264", "h265", "hevc", "av1", "aac", "dts", "hdr", "10bit",
    "complete", "uncut", "extended", "proper", "repack", "season", "episode",
)

private fun cloudStreamTitleMatches(value: String, requestedTitles: Set<String>): Boolean =
    cloudStreamTitleSimilarity(value, requestedTitles) >= 0.86

private fun cloudStreamTitleSimilarity(value: String, requestedTitles: Set<String>): Double {
    val candidate = normalizeCloudStreamTitle(value)
    if (candidate.isBlank()) return 0.0
    return requestedTitles.maxOfOrNull { requested ->
        when {
            candidate == requested -> 1.0
            candidate.isBlank() || requested.isBlank() -> 0.0
            else -> {
                val shorter = minOf(candidate.length, requested.length).toDouble()
                val longer = maxOf(candidate.length, requested.length).toDouble()
                val containment = shorter / longer
                if ((candidate.startsWith("$requested ") || requested.startsWith("$candidate ")) &&
                    containment >= 0.8
                ) {
                    0.90
                } else {
                    val distance = cloudStreamLevenshteinDistance(candidate, requested)
                    1.0 - distance.toDouble() / longer
                }
            }
        }
    } ?: 0.0
}

private fun cloudStreamLevenshteinDistance(left: String, right: String): Int {
    val previous = IntArray(right.length + 1) { it }
    val current = IntArray(right.length + 1)
    for (i in 1..left.length) {
        current[0] = i
        for (j in 1..right.length) {
            val cost = if (left[i - 1] == right[j - 1]) 0 else 1
            current[j] = minOf(
                current[j - 1] + 1,
                previous[j] + 1,
                previous[j - 1] + cost,
            )
        }
        for (j in previous.indices) previous[j] = current[j]
    }
    return previous[right.length]
}

private fun normalizeCloudStreamTitle(value: String): String {
    val words = value.lowercase()
        .replace(Regex("\\b(19\\d{2}|20\\d{2})\\b"), " ")
        .replace(Regex("\\b(temporada|season)\\s*\\d+\\b"), " ")
        .replace(Regex("\\b[st]\\d{1,2}\\b"), " ")
        .replace(Regex("\\b(part|parte)\\s*\\d+\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .filterNot { it in cloudStreamReleaseSuffixTokens }
        .toMutableList()
    if (words.firstOrNull() in setOf("the", "a", "an")) words.removeAt(0)
    return words.joinToString(" ")
}

internal fun cloudStreamSearchQueries(request: CloudStreamSearchRequest): List<String> =
    (listOf(request.title) + request.aliases)
        .flatMap { candidate ->
            val title = candidate.trim()
            listOfNotNull(
                title,
                request.year?.takeIf { cloudStreamYear(title) == null }?.let { "$title $it" },
            )
        }
        .map(String::trim)
        .filter(String::isNotBlank)
        // Years remain meaningful query tokens. Do not use the title matcher normalizer here.
        .distinctBy(::normalizeCloudStreamSearchQuery)

private fun normalizeCloudStreamSearchQuery(value: String): String =
    value.lowercase()
        .map { char -> if (char.isLetterOrDigit()) char else ' ' }
        .joinToString("")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .joinToString(" ")

internal fun cloudStreamStreamIdentity(stream: StreamItem): String {
    val requestHeaders = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        .entries
        .sortedWith(compareBy({ it.key.lowercase() }, { it.value }))
        .joinToString("\u001f") { "${it.key.lowercase()}=${it.value}" }
    val subtitleIdentity = stream.externalSubtitles.joinToString("\u001e") { subtitle ->
        listOf(
            subtitle.url,
            subtitle.language,
            subtitle.name.orEmpty(),
            subtitle.headers.orEmpty().entries
                .sortedWith(compareBy({ it.key.lowercase() }, { it.value }))
                .joinToString("\u001f") { "${it.key.lowercase()}=${it.value}" },
        ).joinToString("\u001d")
    }
    return listOf(
        stream.addonId,
        stream.sourceName.orEmpty(),
        stream.streamLabel,
        stream.playableDirectUrl.orEmpty(),
        stream.streamType.orEmpty(),
        requestHeaders,
        subtitleIdentity,
    ).joinToString("\u001c")
}

private fun cloudStreamYear(value: String): Int? =
    Regex("\\b(19\\d{2}|20\\d{2})\\b").find(value)?.value?.toIntOrNull()
