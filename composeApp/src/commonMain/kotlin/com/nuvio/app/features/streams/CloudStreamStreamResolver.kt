package com.nuvio.app.features.streams

import com.nuvio.app.features.cloudstream.CloudStreamPluginItem
import com.nuvio.app.features.cloudstream.CloudStreamRepository
import com.nuvio.app.features.cloudstream.CloudStreamSearchItem
import com.nuvio.app.features.cloudstream.CloudStreamTvType
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
): CloudStreamSearchRequest {
    @Suppress("UNUSED_VARIABLE")
    val ignoredParentType = parentMetaType
    val details = MetaDetailsRepository.peek(type, parentMetaId ?: videoId)
        ?: MetaDetailsRepository.peek(type, videoId)
    val title = searchTitle?.trim()?.takeIf(String::isNotBlank)
        ?: details?.name?.trim()?.takeIf(String::isNotBlank)
        ?: videoId
    val year = details?.releaseInfo
        ?.let { Regex("\\b(19\\d{2}|20\\d{2})\\b").find(it)?.value?.toIntOrNull() }
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
    request: CloudStreamSearchRequest,
): List<CloudStreamProviderGroup> {
    val state = CloudStreamRepository.uiState.value
    val providers = state.plugins
        .asSequence()
        .filter { it.enabled && it.isRunnable }
        .filter { providerSupportsType(it, type.ifBlank { request.type }) }
        .map { item ->
            CloudStreamProviderTarget(
                id = item.metadata.id.value,
                name = item.metadata.name,
            ) to item
        }
        .toList()
    if (providers.isEmpty()) return emptyList()

    if (!state.groupStreamsByRepository) {
        return providers.map { (target, _) ->
            CloudStreamProviderGroup(
                addonId = cloudStreamAddonId(target.id),
                addonName = target.name,
                providers = listOf(target),
            )
        }
    }

    val repositoryNames = state.repositories.associate { it.manifest.sourceUrl to it.manifest.name }
    return providers
        .groupBy { (_, item) -> item.metadata.repositoryManifestUrl }
        .map { (repositoryUrl, entries) ->
            val name = repositoryNames[repositoryUrl]
                ?.takeIf(String::isNotBlank)
                ?: entries.first().second.metadata.name
            CloudStreamProviderGroup(
                addonId = "cloudstream-repository:${sha256Hex(repositoryUrl.encodeToByteArray()).take(16)}",
                addonName = name,
                providers = entries.map { it.first },
            )
        }
        .sortedBy { it.addonName.lowercase() }
}

private fun providerSupportsType(item: CloudStreamPluginItem, type: String): Boolean {
    val requested = normalizeCloudStreamType(type)
    val supported = item.metadata.tvTypes
    return supported.isEmpty() || supported.any { it == CloudStreamTvType.Other || it.nuvioType == requested }
}

private fun normalizeCloudStreamType(type: String): String = when (type.trim().lowercase()) {
    "tv", "show", "series" -> "series"
    "movie", "film" -> "movie"
    "live", "livetv", "channel" -> "live"
    else -> type.trim().lowercase()
}

internal suspend fun resolveCloudStreamProviderStreams(
    providerGroup: CloudStreamProviderGroup,
    request: CloudStreamSearchRequest,
): CloudStreamProviderStreams {
    val streams = mutableListOf<StreamItem>()
    var firstError: String? = null
    var attemptedProviders = 0
    var matchedTitles = 0
    var attemptedExtractors = 0

    for (provider in providerGroup.providers) {
        attemptedProviders++

        // TmdbProvider extensions accept TMDB IDs directly and often do not implement search().
        // Use this path first; the Android runtime falls back to the standard TMDB URL form.
        val tmdbLookup = request.tmdbId?.let { tmdbId ->
            runCatchingUnlessCancelled {
                CloudStreamRepository.loadByExternalId(
                    provider.id,
                    "nuvio-tmdb:$tmdbId:${if (normalizeCloudStreamType(request.type) == "movie") "movie" else "tv"}",
                )
            }
        }
        if (tmdbLookup?.isFailure == true && firstError == null) {
            firstError = tmdbLookup.exceptionOrNull()?.message ?: "CloudStream TMDB lookup failed"
        }
        val tmdbLoaded = tmdbLookup?.getOrNull()?.getOrNull()
        if (tmdbLoaded != null && tmdbLoaded.matchesCloudStreamTmdbRequest(request)) {
            val linkData = tmdbLoaded.linkDataForCloudStreamRequest(request)
            if (linkData != null) {
                attemptedExtractors++
                val linkResult = runCatchingUnlessCancelled {
                    CloudStreamRepository.loadLinks(provider.id, linkData)
                }
                val links = linkResult.getOrNull()?.getOrNull()
                if (!links.isNullOrEmpty()) {
                    streams += links.map { it.toStreamItem(provider.id, provider.name) }
                    continue
                }
                if (firstError == null) {
                    firstError = linkResult.exceptionOrNull()?.message
                        ?: linkResult.getOrNull()?.exceptionOrNull()?.message
                        ?: "CloudStream TMDB match returned no playable links"
                }
            }
        }

        // Prefer a provider's external-ID lookup when metadata supplies a valid IMDb ID.
        // This avoids depending on localized title search for providers that implement sync lookup.
        val externalLookup = request.externalId?.let { externalId ->
            runCatchingUnlessCancelled {
                CloudStreamRepository.loadByExternalId(provider.id, externalId)
            }
        }
        if (externalLookup?.isFailure == true && firstError == null) {
            firstError = externalLookup.exceptionOrNull()?.message ?: "CloudStream external-ID lookup failed"
        }
        val externalLoaded = externalLookup?.getOrNull()?.getOrNull()
        if (externalLoaded != null && externalLoaded.matchesCloudStreamExternalIdRequest(request)) {
            val linkData = externalLoaded.linkDataForCloudStreamRequest(request)
            if (linkData != null) {
                attemptedExtractors++
                val linkResult = runCatchingUnlessCancelled {
                    CloudStreamRepository.loadLinks(provider.id, linkData)
                }
                val links = linkResult.getOrNull()?.getOrNull()
                if (links.isNullOrEmpty()) {
                    val message = linkResult.exceptionOrNull()?.message
                        ?: linkResult.getOrNull()?.exceptionOrNull()?.message
                        ?: "CloudStream external-ID match returned no playable links"
                    if (firstError == null) firstError = message
                } else {
                    streams += links.map { it.toStreamItem(provider.id, provider.name) }
                    continue
                }
            }
        }

        // Search every known title variant. A provider may index the localized title
        // while the metadata source supplies the international title (or vice versa).
        val searchResults = mutableListOf<Result<List<CloudStreamSearchItem>>>()
        (listOf(request.title) + request.aliases)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { query ->
                val searchAttempt = runCatchingUnlessCancelled {
                    CloudStreamRepository.search(query, provider.id)
                }
                if (searchAttempt.isFailure) {
                    if (firstError == null) {
                        firstError = searchAttempt.exceptionOrNull()?.message ?: "CloudStream search failed"
                    }
                } else {
                    searchResults += searchAttempt.getOrNull().orEmpty()
                }
            }
        val searchItems = searchResults.flatMap { result ->
            result.getOrElse { error ->
                if (firstError == null) firstError = error.message ?: "CloudStream search failed"
                emptyList()
            }
        }
        val matches = searchItems.bestCloudStreamMatches(request)
        if (matches.isEmpty()) continue
        matchedTitles += matches.size

        for (item in matches) {
            val loadAttempt = runCatchingUnlessCancelled {
                CloudStreamRepository.load(provider.id, item.data)
            }
            if (loadAttempt.isFailure) {
                if (firstError == null) firstError = loadAttempt.exceptionOrNull()?.message ?: "CloudStream title lookup failed"
                continue
            }
            val loadedResult = loadAttempt.getOrNull()
            if (loadedResult == null || loadedResult.isFailure) {
                if (firstError == null) firstError = loadedResult?.exceptionOrNull()?.message ?: "CloudStream title lookup failed"
                continue
            }
            val loaded = loadedResult.getOrNull() ?: continue
            if (!loaded.matchesCloudStreamRequest(request, item)) continue
            val linkData = loaded.linkDataForCloudStreamRequest(request) ?: continue
            attemptedExtractors++
            val linksAttempt = runCatchingUnlessCancelled {
                CloudStreamRepository.loadLinks(provider.id, linkData)
            }
            if (linksAttempt.isFailure) {
                if (firstError == null) firstError = linksAttempt.exceptionOrNull()?.message ?: "CloudStream link lookup failed"
                continue
            }
            val linksResult = linksAttempt.getOrNull()
            if (linksResult == null || linksResult.isFailure) {
                if (firstError == null) firstError = linksResult?.exceptionOrNull()?.message ?: "CloudStream link lookup failed"
                continue
            }
            streams += linksResult.getOrNull().orEmpty().map { it.toStreamItem(provider.id, provider.name) }
        }
    }

    val diagnostic = when {
        streams.isNotEmpty() -> firstError
        attemptedProviders == 0 -> "No enabled, compatible CloudStream providers are available for this content type"
        matchedTitles == 0 && firstError == null -> "CloudStream providers returned no title matching '${request.title}'"
        attemptedExtractors == 0 && firstError == null -> "CloudStream title matched, but no season/episode entry could be selected"
        firstError == null -> "CloudStream providers matched the title but returned no playable links"
        else -> firstError
    }
    return CloudStreamProviderStreams(streams = streams.distinctBy { it.url }, error = diagnostic)
}

internal suspend fun resolveCloudStreamDirectRoute(
    providerId: String,
    providerName: String,
    data: String,
): Result<List<StreamItem>> {
    val loaded = CloudStreamRepository.load(providerId, data)
    if (loaded.isFailure) return Result.failure(loaded.exceptionOrNull() ?: IllegalStateException("CloudStream title lookup failed"))
    val item = loaded.getOrNull() ?: return Result.success(emptyList())
    return CloudStreamRepository.loadLinks(providerId, item.data).map { links ->
        links.map { it.toStreamItem(providerId, providerName) }
    }
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
}

internal fun com.nuvio.app.features.cloudstream.CloudStreamLoadItem.matchesCloudStreamRequest(
    request: CloudStreamSearchRequest,
    searchItem: CloudStreamSearchItem,
): Boolean {
    val requestedTitles = (listOf(request.title) + request.aliases)
        .map(::normalizeCloudStreamTitle)
        .filter(String::isNotBlank)
        .toSet()
    val titleSimilarity = maxOf(
        cloudStreamTitleSimilarity(name, requestedTitles),
        cloudStreamTitleSimilarity(searchItem.name, requestedTitles),
    )
    if (!cloudStreamTypeMatches(type.nuvioType, request.type) && titleSimilarity < 0.98) return false
    val loadedYear = year ?: cloudStreamYear(name)
    val searchedYear = searchItem.year ?: cloudStreamYear(searchItem.name)
    if (request.year != null) {
        if (loadedYear != null && kotlin.math.abs(loadedYear - request.year) > 1) return false
        if (searchedYear != null && kotlin.math.abs(searchedYear - request.year) > 1) return false
    }
    return titleSimilarity >= 0.86
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
    if (request.year != null && loadedYear != null && kotlin.math.abs(loadedYear - request.year) > 1) return false
    val safeExternalIdFallback = !request.externalId.isNullOrBlank() &&
        (request.year == null || loadedYear == null || loadedYear == request.year)
    return titleMatches || safeExternalIdFallback
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

private fun cloudStreamYear(value: String): Int? =
    Regex("\\b(19\\d{2}|20\\d{2})\\b").find(value)?.value?.toIntOrNull()
