package com.nuvio.app.features.gemini

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val GEMINI_MODEL = "gemini-3.8-flash"
private const val GEMINI_TIMEOUT_MS = 25_000L
private const val GEMINI_CACHE_TTL_MS = 6 * 60 * 60 * 1_000L
private const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/" + GEMINI_MODEL + ":generateContent"

object GeminiRelatedRepository {
    private val log = Logger.withTag("GeminiRelated")
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, CachedRecommendations>()

    suspend fun getRelated(meta: MetaDetails, apiKey: String): List<MetaPreview> = withContext(Dispatchers.Default) {
        val normalizedKey = apiKey.trim()
        if (normalizedKey.isBlank()) return@withContext emptyList()
        val cacheKey = meta.type + ":" + meta.id + ":" + normalizedKey.hashCode()
        cache[cacheKey]?.takeIf { TraktPlatformClock.nowEpochMs() - it.updatedAtMs < GEMINI_CACHE_TTL_MS }
            ?.let { return@withContext it.items }
        val recommendations = withTimeoutOrNull(GEMINI_TIMEOUT_MS) { generateRecommendations(meta, normalizedKey) }.orEmpty()
        if (recommendations.isEmpty()) {
            // Do not cache transient API failures or empty generations for six hours.
            return@withContext emptyList()
        }
        val semaphore = Semaphore(4)
        val resolved = coroutineScope {
            recommendations.map { recommendation ->
                async {
                    semaphore.withPermit {
                        TmdbMetadataService.searchRecommendation(recommendation.title, recommendation.year, recommendation.mediaType)
                    }
                }
            }.awaitAll()
        }.filterNotNull().distinctBy { stableRecommendationKey(it) }
        if (resolved.isNotEmpty()) {
            cache[cacheKey] = CachedRecommendations(resolved, TraktPlatformClock.nowEpochMs())
        }
        resolved
    }

    fun clearCache() { cache.clear() }

    internal fun buildRecommendationPrompt(meta: MetaDetails): String = buildString {
        appendLine("You are a careful film and television recommendation editor.")
        appendLine("Recommend up to 30 real, released titles genuinely similar to the source.")
        appendLine("Compare story themes, genre blend, tone, pacing, setting, character dynamics and audience appeal.")
        appendLine("Do not recommend another season or episode of the same series.")
        appendLine("Do not repeat the source title or invent titles, years or media types.")
        appendLine("Prefer close thematic matches over titles that merely share an actor.")
        appendLine("Return only JSON with a recommendations array; each item must contain title, year and mediaType (movie or tv).")
        appendLine()
        appendLine("Source title: " + meta.name)
        appendLine("Media type: " + if (meta.type.equals("movie", true)) "movie" else "series")
        appendLine("Release year: " + meta.releaseInfo.orEmpty())
        appendLine("Genres: " + meta.genres.joinToString(", "))
        appendLine("Synopsis: " + meta.description.orEmpty().take(1_200))
        appendLine("Director: " + meta.director.joinToString(", "))
        appendLine("Cast: " + meta.cast.take(8).joinToString(", ") { it.name })
        appendLine("Country: " + meta.country.orEmpty())
        appendLine("Language: " + meta.language.orEmpty())
    }

    private suspend fun generateRecommendations(meta: MetaDetails, apiKey: String): List<GeminiRecommendation> {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", buildRecommendationPrompt(meta)) } }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.35)
                put("responseMimeType", "application/json")
                put("maxOutputTokens", 2600)
            }
        }
        return try {
            val response = httpRequestRaw(
                method = "POST",
                url = GEMINI_ENDPOINT,
                headers = mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"),
                body = body.toString(),
            )
            if (response.status !in 200..299) {
                log.w { "Gemini recommendation request failed (" + response.status + ")" }
                return emptyList()
            }
            val payload = json.decodeFromString<GeminiGenerateResponse>(response.body)
            val text = payload.candidates.firstNotNullOfOrNull { candidate ->
                candidate.content?.parts.orEmpty().firstNotNullOfOrNull { it.text?.takeIf(String::isNotBlank) }
            } ?: return emptyList()
            val fence = "`".repeat(3)
            val jsonText = text.trim()
                .removePrefix(fence + "json")
                .removePrefix(fence)
                .removeSuffix(fence)
                .trim()
            val parsed = json.decodeFromString<GeminiRecommendationPayload>(jsonText)
            val originalTitle = normalizeTitle(meta.name)
            parsed.recommendations.asSequence().mapNotNull { item ->
                val title = item.title.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val mediaType = normalizeMediaType(item.mediaType, meta.type) ?: return@mapNotNull null
                val year = item.year?.takeIf { it in 1888..2100 }
                if (normalizeTitle(title) == originalTitle) return@mapNotNull null
                GeminiRecommendation(title, year, mediaType)
            }.distinctBy { it.mediaType + ":" + normalizeTitle(it.title) + ":" + (it.year ?: 0) }.toList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.w { "Gemini request or JSON response failed: " + error.message }
            emptyList()
        }
    }

    private fun normalizeMediaType(value: String, fallback: String): String? = when (value.trim().lowercase()) {
        "movie", "film" -> "movie"
        "tv", "series", "show", "television" -> "tv"
        else -> when (fallback.trim().lowercase()) {
            "movie", "film" -> "movie"
            "tv", "series", "show", "television" -> "tv"
            else -> null
        }
    }

    private fun normalizeTitle(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
    private fun stableRecommendationKey(item: MetaPreview): String = item.type.lowercase() + ":" + normalizeTitle(item.name) + ":" + item.releaseInfo.orEmpty().take(4)
    private data class GeminiRecommendation(val title: String, val year: Int?, val mediaType: String)
    private data class CachedRecommendations(val items: List<MetaPreview>, val updatedAtMs: Long)
}

@Serializable private data class GeminiGenerateResponse(val candidates: List<GeminiCandidate> = emptyList())
@Serializable private data class GeminiCandidate(val content: GeminiContent? = null)
@Serializable private data class GeminiContent(val parts: List<GeminiPart> = emptyList())
@Serializable private data class GeminiPart(val text: String? = null)
@Serializable private data class GeminiRecommendationPayload(val recommendations: List<GeminiRecommendationDto> = emptyList())
@Serializable private data class GeminiRecommendationDto(val title: String = "", val year: Int? = null, @SerialName("mediaType") val mediaType: String = "")
