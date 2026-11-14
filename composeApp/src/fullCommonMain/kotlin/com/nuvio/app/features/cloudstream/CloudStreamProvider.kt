package com.nuvio.app.features.cloudstream

internal fun normalizeCloudStreamProviderKey(raw: String): String =
    raw.trim()
        .substringAfterLast(":")
        .substringAfterLast("/")
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")

internal object CloudStreamProviderRegistry {
    private val providers: List<CloudStreamProvider> = listOf(
        KickTrCloudStreamProvider,
    )

    fun find(providerId: String): CloudStreamProvider? {
        providers.firstOrNull { it.id == providerId }?.let { return it }
        val normalized = normalizeCloudStreamProviderKey(providerId)
        if (normalized.isBlank()) return null
        return providers
            .filter { normalizeCloudStreamProviderKey(it.id) == normalized }
            .singleOrNull()
    }
}
