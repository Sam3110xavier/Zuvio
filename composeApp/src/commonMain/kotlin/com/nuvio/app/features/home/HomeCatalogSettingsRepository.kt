package com.nuvio.app.features.home

import androidx.compose.ui.text.intl.Locale
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.ServerRepository
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

data class HomeCatalogSettingsItem(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
    val isCollection: Boolean = false,
    val collectionId: String? = null,
    val isPinnedToTop: Boolean = false,
    val isMergedRow: Boolean = false,
    val mergedRowId: String? = null,
) {
    val displayTitle: String
        get() = customTitle.ifBlank { defaultTitle }
}

internal data class HomeCatalogSettingsUiState(
    val heroEnabled: Boolean = true,
    val heroStyle: HomeHeroStyle = HomeHeroStyle.FULL_BLEED,
    val heroTrailerPlaybackEnabled: Boolean = false,
    val heroTrailerStartDelaySeconds: Int = HomeCatalogSettingsRepository.DEFAULT_HERO_TRAILER_START_DELAY_SECONDS,
    val heroTrailerStartUnmuted: Boolean = false,
    val showCatalogType: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val hideMergedTitlesWithoutPosters: Boolean = false,
    val mergedRows: List<MergedHomeRowDefinition> = emptyList(),
    val items: List<HomeCatalogSettingsItem> = emptyList(),
) {
    val signature: String
        get() = buildString {
            append(heroEnabled)
            append('|')
            append(heroStyle.storageValue)
            append('|')
            append(heroTrailerPlaybackEnabled)
            append('|')
            append(heroTrailerStartDelaySeconds)
            append('|')
            append(heroTrailerStartUnmuted)
            append('|')
            append(showCatalogType)
            append('|')
            append(hideUnreleasedContent)
            append('|')
            append(hideMergedTitlesWithoutPosters)
            append('|')
            append(mergedRows.sortedBy { it.order }.joinToString { row ->
                "${row.id}:${row.title}:${row.sourceKeys.joinToString(",")}:${row.displayMode}:${row.blendMode}:${row.contentType}:${row.sortMode}:${row.hideIncludedRows}:${row.hideTitlesWithoutPosters}:${row.deduplicate}"
            })
            append('|')
            append(
                items.joinToString(separator = "|") { item ->
                    "${item.key}:${item.order}:${item.enabled}:${item.heroSourceEnabled}:${item.customTitle}"
                }
            )
        }
}

/**
 * Not a user setting: the hero derives this from the layout (portrait -> poster,
 * landscape/tablet -> backdrop). See heroUsesWideArtwork in HomeHeroSection.
 */
internal enum class HomeHeroArtworkSource(
    val storageValue: String,
) {
    BACKDROP("backdrop"),
    POSTER("poster"),
    ;

    companion object {
        fun fromStorageValue(value: String?): HomeHeroArtworkSource =
            entries.firstOrNull { it.storageValue == value } ?: BACKDROP
    }
}

/**
 * Full-bleed keeps the original edge-to-edge hero. Card renders the artwork inside a rounded
 * TMDB-poster-ratio card inset from the screen edges, below the status bar.
 */
internal enum class HomeHeroStyle(
    val storageValue: String,
) {
    FULL_BLEED("full_bleed"),
    CARD("card"),
    ;

    companion object {
        fun fromStorageValue(value: String?): HomeHeroStyle =
            entries.firstOrNull { it.storageValue == value } ?: FULL_BLEED
    }
}

internal data class HomeCatalogPreference(
    val customTitle: String,
    val enabled: Boolean,
    val heroSourceEnabled: Boolean,
    val order: Int,
)

internal data class HomeCatalogSettingsSnapshot(
    val heroEnabled: Boolean,
    val heroStyle: HomeHeroStyle,
    val showCatalogType: Boolean,
    val hideUnreleasedContent: Boolean,
    val preferences: Map<String, HomeCatalogPreference>,
    val hideMergedTitlesWithoutPosters: Boolean = false,
    val mergedRows: List<MergedHomeRowDefinition> = emptyList(),
)

@Serializable
private data class StoredHomeCatalogPreference(
    val key: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
private data class StoredHomeCatalogSettingsPayload(
    val heroEnabled: Boolean = true,
    val heroStyle: String = HomeHeroStyle.FULL_BLEED.storageValue,
    val heroTrailerPlaybackEnabled: Boolean = false,
    val heroTrailerStartDelaySeconds: Int = HomeCatalogSettingsRepository.DEFAULT_HERO_TRAILER_START_DELAY_SECONDS,
    val heroTrailerStartUnmuted: Boolean = false,
    val showCatalogType: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val hideMergedTitlesWithoutPosters: Boolean = false,
    val mergedRows: List<MergedHomeRowDefinition> = emptyList(),
    val items: List<StoredHomeCatalogPreference> = emptyList(),
)

object HomeCatalogSettingsRepository {
    const val HERO_SOURCE_SELECTION_LIMIT = 2

    /** Bounds for the "start hero trailer after N seconds" preference. */
    const val MIN_HERO_TRAILER_START_DELAY_SECONDS = 0
    const val MAX_HERO_TRAILER_START_DELAY_SECONDS = 10
    const val DEFAULT_HERO_TRAILER_START_DELAY_SECONDS = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(HomeCatalogSettingsUiState())
    internal val uiState: StateFlow<HomeCatalogSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var definitions: List<HomeCatalogDefinition> = emptyList()
    private var collectionDefinitions: List<CollectionCatalogDefinition> = emptyList()
    private var lastCatalogSync: CatalogSyncInput? = null
    private var lastCollectionSync: Pair<List<Collection>, String>? = null
    private val preferencesRef = atomic<Map<String, StoredHomeCatalogPreference>>(emptyMap())
    private var preferences: Map<String, StoredHomeCatalogPreference>
        get() = preferencesRef.value
        set(value) {
            preferencesRef.value = value
        }
    private var heroEnabled = true
    private var heroStyle = HomeHeroStyle.FULL_BLEED
    private var heroTrailerPlaybackEnabled = false
    private var heroTrailerStartDelaySeconds = DEFAULT_HERO_TRAILER_START_DELAY_SECONDS
    private var heroTrailerStartUnmuted = false
    private var showCatalogType = true
    private var hideUnreleasedContent = false
    private var hideMergedTitlesWithoutPosters = false
    private var mergedRows: List<MergedHomeRowDefinition> = emptyList()

    fun onProfileChanged() {
        hasLoaded = false
        preferences = emptyMap()
        heroEnabled = true
        heroStyle = HomeHeroStyle.FULL_BLEED
        heroTrailerPlaybackEnabled = false
        heroTrailerStartDelaySeconds = DEFAULT_HERO_TRAILER_START_DELAY_SECONDS
        heroTrailerStartUnmuted = false
        showCatalogType = true
        hideUnreleasedContent = false
        hideMergedTitlesWithoutPosters = false
        mergedRows = emptyList()
        definitions = emptyList()
        collectionDefinitions = emptyList()
        lastCatalogSync = null
        lastCollectionSync = null
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun clearLocalState() {
        hasLoaded = false
        definitions = emptyList()
        collectionDefinitions = emptyList()
        lastCatalogSync = null
        lastCollectionSync = null
        preferences = emptyMap()
        heroEnabled = true
        heroStyle = HomeHeroStyle.FULL_BLEED
        heroTrailerPlaybackEnabled = false
        heroTrailerStartDelaySeconds = DEFAULT_HERO_TRAILER_START_DELAY_SECONDS
        heroTrailerStartUnmuted = false
        showCatalogType = true
        hideUnreleasedContent = false
        hideMergedTitlesWithoutPosters = false
        mergedRows = emptyList()
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun syncCatalogs(addons: List<ManagedAddon>) {
        ensureLoaded()
        val collections = CollectionRepository.collections.value
        val syncInput = CatalogSyncInput(addons, collections, Locale.current.toLanguageTag(), ServerRepository.uiState.value.revision)
        if (lastCatalogSync == syncInput) return
        definitions = buildHomeCatalogDefinitions(addons)
        collectionDefinitions = buildCollectionDefinitions(collections)
        lastCatalogSync = syncInput
        lastCollectionSync = lastCollectionSync?.takeIf {
            it.first == collections && it.second == syncInput.locale
        }
        if (definitions.isEmpty() && collectionDefinitions.isEmpty()) {
            publish()
            return
        }
        normalizePreferences()
        publish()
        persist()
    }

    fun syncCollections(collections: List<Collection>) {
        ensureLoaded()
        val syncInput = collections to Locale.current.toLanguageTag()
        if (lastCollectionSync == syncInput) return
        collectionDefinitions = buildCollectionDefinitions(collections)
        lastCatalogSync = lastCatalogSync?.takeIf {
            it.collections == collections && it.locale == syncInput.second
        }
        lastCollectionSync = syncInput
        normalizePreferences()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    internal fun mergedRowSourceKeys(): Set<String> {
        ensureLoaded()
        return eligibleMergedRowSourceKeys()
    }

    private fun eligibleMergedRowSourceKeys(): Set<String> =
        definitions.asSequence()
            .filter { it.serverConnectionId == null && !ServerCatalog.isServerKey(it.key) }
            .mapTo(linkedSetOf()) { it.key }

    fun resolveMergedHomeRow(id: String): ResolvedMergedHomeRow? {
        ensureLoaded()
        val row = mergedRows.firstOrNull { it.id == id } ?: return null
        val currentSettings = snapshot()
        val availableDefinitions = row.sourceKeys.mapNotNull { sourceKey ->
            definitions.firstOrNull { definition ->
                definition.key == sourceKey && definition.serverConnectionId == null &&
                    !ServerCatalog.isServerKey(definition.key)
            }
        }
        val baseTitles = availableDefinitions.map { definition ->
            currentSettings.preferences[definition.key]?.customTitle?.takeIf(String::isNotBlank)
                ?: definition.titleFor(currentSettings.showCatalogType)
        }
        val duplicateTitleCounts = baseTitles.groupingBy { it }.eachCount()
        val sources = availableDefinitions.mapIndexed { index, definition ->
            val baseTitle = baseTitles[index]
            val title = cleanMergedSourceTabTitle(
                if ((duplicateTitleCounts[baseTitle] ?: 0) > 1) "$baseTitle ${definition.addonName}" else baseTitle,
            )
            ResolvedMergedHomeRowSource(definition = definition, title = title)
        }
        return ResolvedMergedHomeRow(
            definition = row,
            sources = sources,
            unavailableSourceCount = (row.sourceKeys.distinct().size - sources.size).coerceAtLeast(0),
        )
    }

    internal fun snapshot(): HomeCatalogSettingsSnapshot {
        ensureLoaded()
        return HomeCatalogSettingsSnapshot(
            heroEnabled = heroEnabled,
            heroStyle = heroStyle,
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            hideMergedTitlesWithoutPosters = hideMergedTitlesWithoutPosters,
            mergedRows = mergedRows.sortedBy { it.order },
            preferences = preferences.mapValues { (_, value) ->
                HomeCatalogPreference(
                    customTitle = value.customTitle,
                    enabled = value.enabled,
                    heroSourceEnabled = value.heroSourceEnabled,
                    order = value.order,
                )
            },
        )
    }

    fun setHeroEnabled(enabled: Boolean) {
        ensureLoaded()
        heroEnabled = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    internal fun setHeroStyle(style: HomeHeroStyle) {
        ensureLoaded()
        if (heroStyle == style) return
        heroStyle = style
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setHeroTrailerPlaybackEnabled(enabled: Boolean) {
        ensureLoaded()
        if (heroTrailerPlaybackEnabled == enabled) return
        heroTrailerPlaybackEnabled = enabled
        publish()
        persist()
    }

    /**
     * How long the hero waits, after an item becomes the active page, before its trailer starts.
     * Clamped to [MIN_HERO_TRAILER_START_DELAY_SECONDS]..[MAX_HERO_TRAILER_START_DELAY_SECONDS].
     */
    fun setHeroTrailerStartUnmuted(enabled: Boolean) {
        ensureLoaded()
        if (heroTrailerStartUnmuted == enabled) return
        heroTrailerStartUnmuted = enabled
        publish()
        persist()
    }

    fun setHeroTrailerStartDelaySeconds(seconds: Int) {
        ensureLoaded()
        val clamped = seconds.coerceIn(
            MIN_HERO_TRAILER_START_DELAY_SECONDS,
            MAX_HERO_TRAILER_START_DELAY_SECONDS,
        )
        if (heroTrailerStartDelaySeconds == clamped) return
        heroTrailerStartDelaySeconds = clamped
        publish()
        persist()
    }

    fun setShowCatalogType(enabled: Boolean) {
        ensureLoaded()
        if (showCatalogType == enabled) return
        showCatalogType = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setHideUnreleasedContent(enabled: Boolean) {
        ensureLoaded()
        if (hideUnreleasedContent == enabled) return
        hideUnreleasedContent = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setHideMergedTitlesWithoutPosters(enabled: Boolean) {
        ensureLoaded()
        if (hideMergedTitlesWithoutPosters == enabled) return
        hideMergedTitlesWithoutPosters = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setHeroSourceEnabled(key: String, enabled: Boolean) {
        if (validMergedRowKey(key)) return
        updatePreference(key, pushRemote = false, heroSourcesChanged = true) { preference ->
            if (!enabled) {
                preference.copy(heroSourceEnabled = false)
            } else if (selectedHeroSourceCount(excludingKey = key) >= HERO_SOURCE_SELECTION_LIMIT) {
                preference
            } else {
                preference.copy(heroSourceEnabled = true)
            }
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            preference.copy(enabled = enabled)
        }
    }

    fun setCustomTitle(key: String, title: String) {
        updatePreference(key) { preference ->
            preference.copy(customTitle = title)
        }
    }

    fun createMergedRow(
        title: String, sourceKeys: List<String>, displayMode: MergedHomeRowDisplayMode,
        deduplicate: Boolean = true,
        contentType: MergedHomeRowContentType = MergedHomeRowContentType.ALL,
        blendMode: MergedHomeRowBlendMode = MergedHomeRowBlendMode.SMART_FRESHNESS_POPULARITY,
        sortMode: MergedHomeRowSortMode = MergedHomeRowSortMode.OFF,
        hideIncludedRows: Boolean = false,
        hideTitlesWithoutPosters: Boolean = false,
    ): String? {
        ensureLoaded()
        val available = eligibleMergedRowSourceKeys()
        val sources = sourceKeys.distinct()
        if (sources.size !in 2..MAX_MERGED_HOME_ROW_SOURCES || sources.any { it !in available }) return null
        val safeTitle = title.trim()
        if (safeTitle.isBlank()) return null
        var id: String
        do { id = "mr_${kotlin.random.Random.nextLong()}" } while (mergedRows.any { it.id == id })
        mergedRows = mergedRows + MergedHomeRowDefinition(
            id = id, title = safeTitle, sourceKeys = sources,
            displayMode = displayMode.storageValue, deduplicate = deduplicate,
            order = (mergedRows.maxOfOrNull { it.order } ?: -1) + 1,
            contentType = contentType.storageValue, blendMode = blendMode.storageValue,
            sortMode = sortMode.storageValue, hideIncludedRows = hideIncludedRows,
            hideTitlesWithoutPosters = hideTitlesWithoutPosters,
        )
        val key = mergedHomeRowKey(id)
        preferences = preferences + (key to StoredHomeCatalogPreference(
            key = key, enabled = true, heroSourceEnabled = false,
            order = (preferences.values.maxOfOrNull { it.order } ?: -1) + 1,
        ))
        publish(); persist(); HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
        return id
    }

    fun updateMergedRow(
        id: String, title: String, sourceKeys: List<String>,
        displayMode: MergedHomeRowDisplayMode, deduplicate: Boolean,
        contentType: MergedHomeRowContentType? = null,
        blendMode: MergedHomeRowBlendMode? = null,
        sortMode: MergedHomeRowSortMode? = null,
        hideIncludedRows: Boolean? = null,
        hideTitlesWithoutPosters: Boolean? = null,
    ): Boolean {
        ensureLoaded()
        val existing = mergedRows.firstOrNull { it.id == id } ?: return false
        val sources = sourceKeys.distinct()
        if (sources.size !in 2..MAX_MERGED_HOME_ROW_SOURCES || sources.any { it !in eligibleMergedRowSourceKeys() }) return false
        val safeTitle = title.trim()
        if (safeTitle.isBlank()) return false
        val updated = existing.copy(
            title = safeTitle, sourceKeys = sources, displayMode = displayMode.storageValue,
            deduplicate = deduplicate, contentType = contentType?.storageValue ?: existing.contentType,
            blendMode = blendMode?.storageValue ?: existing.blendMode,
            sortMode = sortMode?.storageValue ?: existing.sortMode,
            hideIncludedRows = hideIncludedRows ?: existing.hideIncludedRows,
            hideTitlesWithoutPosters = hideTitlesWithoutPosters ?: existing.hideTitlesWithoutPosters,
        )
        if (updated == existing) return true
        mergedRows = mergedRows.map { if (it.id == id) updated else it }
        publish(); persist(); HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
        return true
    }

    fun duplicateMergedRow(id: String): String? {
        ensureLoaded()
        val source = mergedRows.firstOrNull { it.id == id } ?: return null
        var newId: String
        do { newId = "mr_${kotlin.random.Random.nextLong()}" } while (mergedRows.any { it.id == newId })
        val copy = source.copy(id = newId, title = "${source.title} (copy)", order = (mergedRows.maxOfOrNull { it.order } ?: -1) + 1)
        mergedRows = mergedRows + copy
        val newKey = mergedHomeRowKey(newId)
        val sourcePreference = preferences[mergedHomeRowKey(id)]
        preferences = preferences + (newKey to StoredHomeCatalogPreference(
            key = newKey, enabled = sourcePreference?.enabled ?: true, heroSourceEnabled = false,
            order = (preferences.values.maxOfOrNull { it.order } ?: -1) + 1,
        ))
        publish(); persist(); HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
        return newId
    }

    fun deleteMergedRow(id: String) {
        ensureLoaded()
        if (mergedRows.none { it.id == id }) return
        mergedRows = mergedRows.filterNot { it.id == id }.mapIndexed { index, row -> row.copy(order = index) }
        preferences = preferences - mergedHomeRowKey(id)
        publish(); persist(); HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun mergedRowEnabled(id: String): Boolean {
        ensureLoaded()
        return preferences[mergedHomeRowKey(id)]?.enabled != false
    }

    private fun validMergedRowKey(key: String): Boolean =
        mergedRows.any { mergedHomeRowKey(it.id) == key }

    fun resetToDefaults() {
        ensureLoaded()
        heroEnabled = true
        heroStyle = HomeHeroStyle.FULL_BLEED
        heroTrailerPlaybackEnabled = false
        heroTrailerStartDelaySeconds = DEFAULT_HERO_TRAILER_START_DELAY_SECONDS
        heroTrailerStartUnmuted = false
        showCatalogType = true
        hideUnreleasedContent = false
        hideMergedTitlesWithoutPosters = false
        mergedRows = emptyList()
        preferences = emptyMap()
        normalizePreferences()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun moveUp(key: String) {
        move(key = key, direction = -1)
    }

    fun moveDown(key: String) {
        move(key = key, direction = 1)
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val keys = allOrderedKeys()
        if (fromIndex !in keys.indices || toIndex !in keys.indices || fromIndex == toIndex) return
        val orderedKeys = keys.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        persistVisibleOrder(orderedKeys)
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    private fun persistVisibleOrder(orderedKeys: List<String>) {
        val updatedPreferences = preferences.toMutableMap()
        orderedKeys.forEachIndexed { index, key ->
            val current = updatedPreferences[key] ?: return@forEachIndexed
            updatedPreferences[key] = current.copy(order = index)
        }
        preferences = updatedPreferences
        val mergedOrderByKey = orderedKeys.withIndex().associate { it.value to it.index }
        mergedRows = mergedRows.map { row ->
            mergedOrderByKey[mergedHomeRowKey(row.id)]?.let { row.copy(order = it) } ?: row
        }
    }

    private fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = HomeCatalogSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val parsedPayload = runCatching {
            json.decodeFromString<StoredHomeCatalogSettingsPayload>(payload)
        }.getOrNull()

        if (parsedPayload != null) {
            heroEnabled = parsedPayload.heroEnabled
            heroStyle = HomeHeroStyle.fromStorageValue(parsedPayload.heroStyle)
            heroTrailerPlaybackEnabled = parsedPayload.heroTrailerPlaybackEnabled
            heroTrailerStartDelaySeconds = parsedPayload.heroTrailerStartDelaySeconds.coerceIn(
                MIN_HERO_TRAILER_START_DELAY_SECONDS,
                MAX_HERO_TRAILER_START_DELAY_SECONDS,
            )
            heroTrailerStartUnmuted = parsedPayload.heroTrailerStartUnmuted
            showCatalogType = parsedPayload.showCatalogType
            hideUnreleasedContent = parsedPayload.hideUnreleasedContent
            hideMergedTitlesWithoutPosters = parsedPayload.hideMergedTitlesWithoutPosters
            mergedRows = parsedPayload.mergedRows.filter { it.id.isNotBlank() }
                .map { it.copy(sourceKeys = it.sourceKeys.distinct().take(MAX_MERGED_HOME_ROW_SOURCES)) }
            preferences = parsedPayload.items.associateBy { it.key }
            publish()
            return
        }

        val legacyItems = runCatching {
            json.decodeFromString<List<StoredHomeCatalogPreference>>(payload)
        }.getOrDefault(emptyList())

        preferences = legacyItems.associateBy { it.key }
        publish()
    }

    private fun normalizePreferences() {
        val current = preferences
        data class UnifiedEntry(val key: String, val isCollection: Boolean, val isMergedRow: Boolean = false)
        val catalogEntries = definitions.map { UnifiedEntry(it.key, false) }
        val collectionEntries = collectionDefinitions.map { UnifiedEntry(it.key, true) }
        val mergedEntries = mergedRows.map { UnifiedEntry(mergedHomeRowKey(it.id), false, true) }
        val allEntries = catalogEntries + collectionEntries + mergedEntries
        val knownKeys = allEntries.mapTo(linkedSetOf(), UnifiedEntry::key)
        var nextOrder = (current.values.maxOfOrNull(StoredHomeCatalogPreference::order) ?: -1) + 1

        val orderedEntries = allEntries.mapIndexed { defaultIndex, entry ->
            Triple(
                entry,
                current[entry.key]?.order ?: (nextOrder + defaultIndex),
                defaultIndex,
            )
        }.sortedWith(
            compareBy<Triple<UnifiedEntry, Int, Int>>(
                { it.second },
                { it.third },
            ),
        ).map { it.first }

        val normalized = current
            .filterKeys { it !in knownKeys }
            .toMutableMap()
        var enabledHeroSourceCount = 0
        orderedEntries.forEach { entry ->
            val stored = current[entry.key]
            val heroSourceEnabled = if (entry.isCollection || entry.isMergedRow) {
                false
            } else {
                (stored?.heroSourceEnabled ?: true) &&
                    enabledHeroSourceCount < HERO_SOURCE_SELECTION_LIMIT
            }
            if (heroSourceEnabled) {
                enabledHeroSourceCount += 1
            }
            normalized[entry.key] = StoredHomeCatalogPreference(
                key = entry.key,
                customTitle = stored?.customTitle.orEmpty(),
                enabled = stored?.enabled ?: true,
                heroSourceEnabled = heroSourceEnabled,
                order = stored?.order ?: nextOrder++,
            )
        }
        preferences = normalized.toMap()
        mergedRows = mergedRows.map { row ->
            normalized[mergedHomeRowKey(row.id)]?.let { row.copy(order = it.order) } ?: row
        }
        enforcePinnedCollectionsAtTop()
    }

    private fun publish() {
        val hiddenIncludedSourceKeys = mergedRows.asSequence()
            .filter { row -> row.hideIncludedRows && preferences[mergedHomeRowKey(row.id)]?.enabled != false }
            .flatMap { it.sourceKeys.asSequence() }
            .toSet()
        val catalogItems = definitions.map { definition ->
            val preference = preferences[definition.key]
            HomeCatalogSettingsItem(
                key = definition.key, defaultTitle = definition.defaultTitle, addonName = definition.addonName,
                customTitle = preference?.customTitle.orEmpty(),
                enabled = (preference?.enabled ?: true) && definition.key !in hiddenIncludedSourceKeys,
                heroSourceEnabled = preference?.heroSourceEnabled ?: true, order = preference?.order ?: 0,
            )
        }
        val collectionItems = collectionDefinitions.map { colDef ->
            val preference = preferences[colDef.key]
            HomeCatalogSettingsItem(
                key = colDef.key, defaultTitle = colDef.title, addonName = colDef.subtitle,
                customTitle = preference?.customTitle.orEmpty(), enabled = preference?.enabled ?: true,
                heroSourceEnabled = false, order = preference?.order ?: 0, isCollection = true,
                collectionId = colDef.collectionId, isPinnedToTop = colDef.isPinnedToTop,
            )
        }
        val mergedItems = mergedRows.map { row ->
            val key = mergedHomeRowKey(row.id)
            val preference = preferences[key]
            HomeCatalogSettingsItem(
                key = key, defaultTitle = row.title, addonName = "Merged row",
                customTitle = preference?.customTitle.orEmpty(), enabled = preference?.enabled ?: true,
                heroSourceEnabled = false, order = preference?.order ?: 0,
                isMergedRow = true, mergedRowId = row.id,
            )
        }
        val items = (catalogItems + collectionItems + mergedItems).sortedBy { it.order }
        _uiState.value = HomeCatalogSettingsUiState(
            heroEnabled = heroEnabled, heroStyle = heroStyle,
            heroTrailerPlaybackEnabled = heroTrailerPlaybackEnabled,
            heroTrailerStartDelaySeconds = heroTrailerStartDelaySeconds,
            heroTrailerStartUnmuted = heroTrailerStartUnmuted,
            showCatalogType = showCatalogType, hideUnreleasedContent = hideUnreleasedContent,
            hideMergedTitlesWithoutPosters = hideMergedTitlesWithoutPosters,
            mergedRows = mergedRows.sortedBy { it.order }, items = items,
        )
    }

    private fun persist() {
        HomeCatalogSettingsStorage.savePayload(
            json.encodeToString(
                StoredHomeCatalogSettingsPayload(
                    heroEnabled = heroEnabled,
                    heroStyle = heroStyle.storageValue,
                    heroTrailerPlaybackEnabled = heroTrailerPlaybackEnabled,
                    heroTrailerStartDelaySeconds = heroTrailerStartDelaySeconds,
                    heroTrailerStartUnmuted = heroTrailerStartUnmuted,
                    showCatalogType = showCatalogType,
                    hideUnreleasedContent = hideUnreleasedContent,
                    hideMergedTitlesWithoutPosters = hideMergedTitlesWithoutPosters,
                    mergedRows = mergedRows.sortedBy { it.order },
                    items = preferences.values.sortedBy { it.order },
                ),
            ),
        )
    }

    private fun updatePreference(
        key: String,
        pushRemote: Boolean = true,
        heroSourcesChanged: Boolean = false,
        transform: (StoredHomeCatalogPreference) -> StoredHomeCatalogPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: defaultPreferenceForMissingKey(key) ?: return
        val updated = transform(current)
        if (updated == current) return
        preferences = preferences + (key to updated)
        publish()
        persist()
        HomeRepository.applyCurrentSettings(heroSourcesChanged = heroSourcesChanged)
        if (pushRemote) {
            HomeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun selectedHeroSourceCount(excludingKey: String? = null): Int {
        val catalogKeys = definitions.mapTo(mutableSetOf()) { it.key }
        return preferences.count { (itemKey, preference) ->
            itemKey != excludingKey && itemKey in catalogKeys && preference.heroSourceEnabled
        }
    }

    private fun move(
        key: String,
        direction: Int,
    ) {
        val currentIndex = _uiState.value.items.indexOfFirst { it.key == key }
        moveByIndex(currentIndex, currentIndex + direction)
    }

    fun exportToSyncPayload(): SyncHomeCatalogPayload {
        ensureLoaded()
        val catalogDefinitionsByKey = definitions.associateBy { it.key }
        val collectionDefinitionsByKey = collectionDefinitions.associateBy { it.key }
        val items = preferences.values.filterNot { ServerCatalog.isServerKey(it.key) }.sortedBy { it.order }.map { pref ->
            val catalogDefinition = catalogDefinitionsByKey[pref.key]
            val collectionDefinition = collectionDefinitionsByKey[pref.key]
            val isCollection = collectionDefinition != null || pref.key.startsWith("collection_")
            if (isCollection) {
                SyncCatalogItem(
                    addonId = "",
                    type = "",
                    catalogId = "",
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = true,
                    collectionId = collectionDefinition?.collectionId ?: pref.key.removePrefix("collection_"),
                    key = pref.key,
                )
            } else {
                val legacyParts = pref.key.split(':', limit = 3)
                SyncCatalogItem(
                    addonId = catalogDefinition?.addonIdForSync() ?: legacyParts.getOrElse(0) { "" },
                    type = catalogDefinition?.type ?: legacyParts.getOrElse(1) { "" },
                    catalogId = catalogDefinition?.catalogId ?: legacyParts.getOrElse(2) { "" },
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = false,
                    key = pref.key,
                )
            }
        }
        return SyncHomeCatalogPayload(
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            hideMergedTitlesWithoutPosters = hideMergedTitlesWithoutPosters,
            mergedRows = mergedRows.sortedBy { it.order },
            items = items,
        )
    }

    fun applyFromRemote(payload: SyncHomeCatalogPayload) {
        ensureLoaded()
        showCatalogType = payload.showCatalogType
        hideUnreleasedContent = payload.hideUnreleasedContent
        hideMergedTitlesWithoutPosters = payload.hideMergedTitlesWithoutPosters
        mergedRows = payload.mergedRows.filter { it.id.isNotBlank() }
            .map { it.copy(sourceKeys = it.sourceKeys.distinct().take(MAX_MERGED_HOME_ROW_SOURCES)) }
        if (payload.items.isNotEmpty()) {
            val existingHeroState = preferences.mapValues { it.value.heroSourceEnabled }
            val remotePreferences = payload.items.associate { item ->
                val key = item.preferenceKey()
                key to StoredHomeCatalogPreference(
                    key = key,
                    customTitle = item.customTitle,
                    enabled = item.enabled,
                    heroSourceEnabled = existingHeroState[key] ?: true,
                    order = item.order,
                )
            }
            val remoteKeys = remotePreferences.keys
            val knownKeys = knownPreferenceKeys()
            val preservedPreferences = preferences.filterKeys { key ->
                key !in remoteKeys && (knownKeys.isEmpty() || key in knownKeys || key.requiresExplicitSyncKey())
            }
            preferences = preservedPreferences + remotePreferences
            normalizePreferences()
        }
        hasLoaded = true
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun allOrderedKeys(): List<String> {
        val catalogKeys = definitions.map { it.key }
        val collectionKeys = collectionDefinitions.map { it.key }
        val mergedKeys = mergedRows.map { mergedHomeRowKey(it.id) }
        return (catalogKeys + collectionKeys + mergedKeys)
            .sortedBy { key -> preferences[key]?.order ?: Int.MAX_VALUE }
    }

    private fun enforcePinnedCollectionsAtTop() {
        val orderedKeys = allOrderedKeys()
        if (orderedKeys.isEmpty()) return
        val pinnedCollectionKeys = collectionDefinitions.filter { it.isPinnedToTop }.map { it.key }.toSet()
        if (pinnedCollectionKeys.isEmpty()) return
        val pinnedKeys = orderedKeys.filter { it in pinnedCollectionKeys }
        if (pinnedKeys.isEmpty()) return
        val reorderedKeys = pinnedKeys + orderedKeys.filterNot { it in pinnedCollectionKeys }
        if (reorderedKeys != orderedKeys) persistVisibleOrder(reorderedKeys)
    }

    private fun defaultPreferenceForMissingKey(key: String): StoredHomeCatalogPreference? {
        val isCollection = collectionDefinitions.any { it.key == key }
        val isMerged = validMergedRowKey(key)
        val isCatalog = definitions.any { it.key == key }
        if (!isCollection && !isCatalog && !isMerged) return null

        return StoredHomeCatalogPreference(
            key = key,
            enabled = true,
            heroSourceEnabled = isCatalog && !isMerged &&
                selectedHeroSourceCount(excludingKey = key) < HERO_SOURCE_SELECTION_LIMIT,
            order = _uiState.value.items.firstOrNull { it.key == key }?.order
                ?: ((preferences.values.maxOfOrNull { it.order } ?: -1) + 1),
        )
    }

    private fun knownPreferenceKeys(): Set<String> =
        definitions.mapTo(mutableSetOf()) { it.key }.also { keys ->
            keys.addAll(collectionDefinitions.map { it.key })
            keys.addAll(mergedRows.map { mergedHomeRowKey(it.id) })
        }

    private fun HomeCatalogDefinition.addonIdForSync(): String {
        val suffix = ":$type:$catalogId"
        return key.removeSuffix(suffix)
    }

    private fun SyncCatalogItem.preferenceKey(): String =
        key.ifBlank {
            if (isCollection) {
                "collection_$collectionId"
            } else {
                "$addonId:$type:$catalogId"
            }
        }

    private fun String.requiresExplicitSyncKey(): Boolean =
        !startsWith("collection_") && count { it == ':' } > 2
}

internal data class CollectionCatalogDefinition(
    val key: String,
    val collectionId: String,
    val title: String,
    val subtitle: String,
    val isPinnedToTop: Boolean,
)

internal fun visibleCollectionsWithUniqueIds(collections: List<Collection>): List<Collection> =
    collections
        .filter { collection -> collection.folders.isNotEmpty() }
        .distinctBy(Collection::id)

internal fun buildCollectionDefinitions(collections: List<Collection>): List<CollectionCatalogDefinition> =
    visibleCollectionsWithUniqueIds(collections).map { collection ->
        CollectionCatalogDefinition(
            key = "collection_${collection.id}",
            collectionId = collection.id,
            title = collection.title,
            subtitle = runBlocking { getString(Res.string.collections_folder_count, collection.folders.size) },
            isPinnedToTop = collection.pinToTop,
        )
    }

private data class CatalogSyncInput(
    val addons: List<ManagedAddon>,
    val collections: List<Collection>,
    val locale: String,
    val serverRevision: Int,
)
