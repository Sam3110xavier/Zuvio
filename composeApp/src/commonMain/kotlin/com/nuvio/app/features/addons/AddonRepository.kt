package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
private data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

private const val ADDON_PUSH_DEBOUNCE_MS = 500L

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var pulledFromServer = false
    private var currentProfileId: Int = 1
    private var profileGeneration: Long = 0L
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val activeRefreshJobsLock = SynchronizedObject()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        activateProfile(effectiveProfileId).forEach(Job::cancel)
        val expectedProfile = synchronized(activeRefreshJobsLock) {
            if (initialized) return
            currentProfileId to profileGeneration
        }
        val profileId = expectedProfile.first
        val generation = expectedProfile.second
        log.d { "initialize() — loading local addons for profile $profileId" }

        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(profileId))
        val enabledByUrl = AddonStorage.loadAddonEnabledStates(profileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }
        val hiddenCatalogsByUrl = AddonStorage.loadAddonCatalogHiddenStates(profileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }
        val existingByUrl = synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(profileId, generation)) return
            _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        }
        val loadedAddons = storedUrls.map { manifestUrl ->
            existingByUrl[manifestUrl].toPendingAddon(
                manifestUrl = manifestUrl,
                enabled = enabledByUrl[manifestUrl],
                hideCatalogs = hiddenCatalogsByUrl[manifestUrl],
            )
        }
        val applied = synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(profileId, generation)) {
                false
            } else {
                _uiState.value = AddonsUiState(addons = loadedAddons)
                initialized = true
                true
            }
        }
        if (!applied) return
        log.d { "initialize() — local addon count: ${storedUrls.size}" }
        InAppLogger.info("Addons/Repository", "initialize profile=$profileId localAddons=${storedUrls.size}")

        val loadedByUrl = loadedAddons.associateBy(ManagedAddon::manifestUrl)
        storedUrls.forEach { manifestUrl ->
            val existing = existingByUrl[manifestUrl]
            val addon = loadedByUrl[manifestUrl]
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddonForProfile(
                    manifestUrl = manifestUrl,
                    forceRefresh = false,
                    expectedProfileId = profileId,
                    expectedGeneration = generation,
                )
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        val alreadyLoaded = synchronized(activeRefreshJobsLock) {
            effectiveProfileId == currentProfileId && initialized
        }
        if (alreadyLoaded) return
        InAppLogger.info(
            "Addons/Repository",
            "profile changed requested=$profileId effective=$effectiveProfileId previous=$currentProfileId",
        )
        activateProfile(effectiveProfileId, forceReset = true).forEach(Job::cancel)
    }

    fun clearLocalState() {
        InAppLogger.info("Addons/Repository", "clear local in-memory state")
        activateProfile(1, forceReset = true).forEach(Job::cancel)
        val pushJobs = synchronized(activeRefreshJobsLock) {
            pushJobsByProfile.values.toList().also { pushJobsByProfile.clear() }
        }
        pushJobs.forEach(Job::cancel)
    }

    suspend fun pullFromServer(profileId: Int) {
        if (ProfileRepository.activeProfileId != profileId) {
            log.w { "pullFromServer() — ignoring stale request profileId=$profileId activeProfileId=${ProfileRepository.activeProfileId}" }
            return
        }

        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        activateProfile(effectiveProfileId).forEach(Job::cancel)
        val expectedGeneration = synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(effectiveProfileId, profileGeneration)) return
            profileGeneration
        }

        fun isCurrentProfile(): Boolean =
            ProfileRepository.activeProfileId == profileId &&
                synchronized(activeRefreshJobsLock) {
                    isProfileGenerationCurrentLocked(effectiveProfileId, expectedGeneration)
                }

        log.i { "pullFromServer() — profileId=$profileId effectiveProfileId=$effectiveProfileId generation=$expectedGeneration" }
        InAppLogger.info(
            "Addons/Repository",
            "pullFromServer requested=$profileId effective=$effectiveProfileId generation=$expectedGeneration",
        )

        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("addons")
                .select {
                    filter { eq("profile_id", effectiveProfileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<AddonRow>()

            if (!isCurrentProfile()) {
                log.w { "pullFromServer() — discarding stale response profileId=$effectiveProfileId generation=$expectedGeneration" }
                return@runCatching
            }

            val rowsByUrl = linkedMapOf<String, AddonRow>()
            rows.forEach { row ->
                val manifestUrl = ensureManifestSuffix(row.url)
                if (!rowsByUrl.containsKey(manifestUrl)) {
                    rowsByUrl[manifestUrl] = row.copy(url = manifestUrl)
                }
            }

            val urls = rowsByUrl.keys.toList()
            log.i { "pullFromServer() — server returned ${rows.size} addons" }
            InAppLogger.info("Addons/Repository", "pullFromServer serverRows=${rows.size} uniqueUrls=${urls.size}")
            urls.forEachIndexed { index, url -> log.d { "  server[$index]: $url" } }

            val alreadyPulled = synchronized(activeRefreshJobsLock) {
                if (isCurrentProfile()) pulledFromServer else null
            } ?: return@runCatching

            if (urls.isEmpty() && !alreadyPulled) {
                val localUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(effectiveProfileId))
                log.i { "pullFromServer() — server empty, local has ${localUrls.size} addons" }
                if (localUrls.isNotEmpty()) {
                    val enabledByUrl = loadLocalEnabledStates(effectiveProfileId)
                    val hiddenCatalogsByUrl = loadLocalCatalogHiddenStates(effectiveProfileId)
                    if (
                        !applyAddonsForProfile(
                            expectedProfileId = effectiveProfileId,
                            expectedGeneration = expectedGeneration,
                            urls = localUrls,
                            rowsByUrl = emptyMap(),
                            hiddenCatalogsByUrl = hiddenCatalogsByUrl,
                            enabledOverrides = enabledByUrl,
                        )
                    ) return@runCatching

                    val addonsToPush = synchronized(activeRefreshJobsLock) {
                        if (!isCurrentProfile()) null
                        else _uiState.value.addons.mapIndexed { index, addon ->
                            AddonPushItem(
                                url = addon.manifestUrl,
                                name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name.orEmpty(),
                                enabled = addon.enabled,
                                sortOrder = index,
                            )
                        }
                    } ?: return@runCatching

                    val params = buildJsonObject {
                        put("p_profile_id", effectiveProfileId)
                        put("p_addons", json.encodeToJsonElement(addonsToPush))
                        putSyncOriginClientId()
                    }
                    SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                    log.i { "pullFromServer() — migration push done (${addonsToPush.size} addons)" }
                    InAppLogger.info("Addons/Repository", "migrated local addons to server count=${addonsToPush.size}")
                    return@runCatching
                }
            }

            if (urls.isEmpty()) {
                val localUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(effectiveProfileId))
                if (localUrls.isNotEmpty()) {
                    log.w { "pullFromServer() — remote empty while local has ${localUrls.size} addons; preserving local addons" }
                    val enabledByUrl = loadLocalEnabledStates(effectiveProfileId)
                    val hiddenCatalogsByUrl = loadLocalCatalogHiddenStates(effectiveProfileId)
                    if (
                        !applyAddonsForProfile(
                            expectedProfileId = effectiveProfileId,
                            expectedGeneration = expectedGeneration,
                            urls = localUrls,
                            rowsByUrl = emptyMap(),
                            hiddenCatalogsByUrl = hiddenCatalogsByUrl,
                            enabledOverrides = enabledByUrl,
                        )
                    ) return@runCatching

                    return@runCatching
                }
            }

            val hiddenCatalogsByUrl = loadLocalCatalogHiddenStates(effectiveProfileId)
            if (
                !applyAddonsForProfile(
                    expectedProfileId = effectiveProfileId,
                    expectedGeneration = expectedGeneration,
                    urls = urls,
                    rowsByUrl = rowsByUrl,
                    hiddenCatalogsByUrl = hiddenCatalogsByUrl,
                )
            ) return@runCatching

            log.i { "pullFromServer() — applied ${urls.size} addons to state" }
            InAppLogger.info("Addons/Repository", "pullFromServer applied=${urls.size}")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (isCurrentProfile()) {
                log.e(error) { "pullFromServer() — FAILED" }
                InAppLogger.error("Addons/Repository", "pullFromServer failed: ${InAppLogger.throwableSummary(error)}")
            } else {
                log.w { "pullFromServer() — ignored failure from stale profile=$effectiveProfileId" }
            }
        }

        if (isCurrentProfile()) {
            awaitManifestsLoaded()
        }
    }

    private fun applyAddonsForProfile(
        expectedProfileId: Int,
        expectedGeneration: Long,
        urls: List<String>,
        rowsByUrl: Map<String, AddonRow>,
        hiddenCatalogsByUrl: Map<String, Boolean>,
        enabledOverrides: Map<String, Boolean> = emptyMap(),
    ): Boolean {
        val plan = synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(expectedProfileId, expectedGeneration)) {
                null
            } else {
                val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
                val nextAddons = urls.map { url ->
                    val row = rowsByUrl[url]
                    existingByUrl[url].toPendingAddon(
                        manifestUrl = url,
                        userSetName = row?.name?.takeIf { it.isNotBlank() },
                        enabled = row?.enabled ?: enabledOverrides[url],
                        hideCatalogs = hiddenCatalogsByUrl[url],
                    )
                }
                _uiState.value = AddonsUiState(addons = nextAddons)
                initialized = true
                pulledFromServer = true
                persist(expectedProfileId)
                existingByUrl to nextAddons
            }
        } ?: return false

        urls.forEach { url ->
            val existing = plan.first[url]
            val addon = plan.second.firstOrNull { it.manifestUrl == url }
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddonForProfile(
                    manifestUrl = url,
                    forceRefresh = false,
                    expectedProfileId = expectedProfileId,
                    expectedGeneration = expectedGeneration,
                )
            }
        }
        return true
    }

    suspend fun awaitManifestsLoaded() {
        uiState.first { state ->
            state.addons.none { addon -> addon.enabled && addon.isRefreshing }
        }
    }

    private val _pendingTrackingConsent = MutableStateFlow<AddonManifest?>(null)
    val pendingTrackingConsent: StateFlow<AddonManifest?> = _pendingTrackingConsent.asStateFlow()

    fun requestTrackingConsent(manifest: AddonManifest) {
        _pendingTrackingConsent.value = manifest
    }

    fun dismissTrackingConsent() {
        _pendingTrackingConsent.value = null
    }

    suspend fun installWithTrackingConsent(manifest: AddonManifest): AddAddonResult {
        _pendingTrackingConsent.value = null
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        if (_uiState.value.addons.any { it.manifestUrl == manifest.transportUrl }) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }
        return installManifest(manifest.transportUrl, manifest)
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            InAppLogger.warn("Addons/Repository", "addAddon blocked because active profile uses primary addons")
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        log.i { "addAddon() — rawUrl=$rawUrl" }
        InAppLogger.info("Addons/Repository", "addAddon rawUrl=${InAppLogger.redactUrl(rawUrl)}")
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            InAppLogger.warn(
                "Addons/Repository",
                "addAddon invalid url=${InAppLogger.redactUrl(rawUrl)} error=${error.message.orEmpty()}",
            )
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_invalid_url))
        }
        InAppLogger.debug("Addons/Repository", "addAddon normalized=${InAppLogger.redactUrl(manifestUrl)}")

        if (_uiState.value.addons.any { it.manifestUrl == manifestUrl }) {
            InAppLogger.warn("Addons/Repository", "addAddon duplicate url=${InAppLogger.redactUrl(manifestUrl)}")
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }

        val manifest = try {
            withContext(Dispatchers.Default) {
                InAppLogger.info("Addons/Manifest", "GET ${InAppLogger.redactUrl(manifestUrl)} reason=add")
                val payload = fetchAddonResponseText(manifestUrl)
                InAppLogger.info("Addons/Manifest", "GET ${InAppLogger.redactUrl(manifestUrl)} ok chars=${payload.length} reason=add")
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
        } catch (error: Throwable) {
            InAppLogger.error("Addons/Manifest", "GET ${InAppLogger.redactUrl(manifestUrl)} failed reason=add: ${InAppLogger.throwableSummary(error)}")
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_load_manifest_failed))
        }

        if (manifest.isTrackingAddon) {
            return AddAddonResult.TrackingConsentRequired(manifest)
        }
        return installManifest(manifestUrl, manifest)
    }

    private fun installManifest(manifestUrl: String, manifest: AddonManifest): AddAddonResult {
        _uiState.update { current ->
            current.copy(
                addons = current.addons + ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    isRefreshing = false,
                    errorMessage = null,
                ),
            )
        }
        persist()
        pushToServer()
        InAppLogger.info("Addons/Repository", "addAddon success name=${manifest.name} url=${InAppLogger.redactUrl(manifestUrl)}")
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            InAppLogger.warn("Addons/Repository", "removeAddon blocked because active profile uses primary addons")
            return
        }
        log.i { "removeAddon() — $manifestUrl" }
        var changed = false
        _uiState.update { current ->
            val updatedAddons = current.addons.filterNot { it.manifestUrl == manifestUrl }
            changed = updatedAddons.size != current.addons.size
            if (changed) current.copy(addons = updatedAddons) else current
        }
        InAppLogger.info("Addons/Repository", "removeAddon removed=$changed url=${InAppLogger.redactUrl(manifestUrl)}")
        if (!changed) return
        persist()
        pushToServer()
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            InAppLogger.warn("Addons/Repository", "moveAddon blocked because active profile uses primary addons")
            return
        }
        var movedUrl: String? = null
        var changed = false
        _uiState.update { current ->
            val addons = current.addons
            if (
                fromIndex !in addons.indices ||
                toIndex !in addons.indices ||
                fromIndex == toIndex
            ) {
                InAppLogger.debug(
                    "Addons/Repository",
                    "moveAddon ignored from=$fromIndex to=$toIndex count=${addons.size}",
                )
                return@update current
            }

            val reordered = addons.toMutableList()
            val movingAddon = reordered.removeAt(fromIndex)
            movedUrl = movingAddon.manifestUrl
            reordered.add(toIndex, movingAddon)
            changed = true
            current.copy(addons = reordered)
        }
        movedUrl?.let { url ->
            InAppLogger.info("Addons/Repository", "moveAddon from=$fromIndex to=$toIndex url=${InAppLogger.redactUrl(url)}")
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            InAppLogger.warn("Addons/Repository", "setAddonEnabled blocked because active profile uses primary addons")
            return
        }
        var changed = false
        var shouldRefresh = false
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl != manifestUrl || addon.enabled == enabled) {
                        addon
                    } else {
                        changed = true
                        shouldRefresh = enabled && addon.manifest == null && !addon.isRefreshing
                        addon.copy(enabled = enabled)
                    }
                },
            )
        }
        InAppLogger.info(
            "Addons/Repository",
            "setAddonEnabled enabled=$enabled changed=$changed shouldRefresh=$shouldRefresh url=${InAppLogger.redactUrl(manifestUrl)}",
        )
        if (!changed) return
        persist()
        pushToServer()
        if (shouldRefresh) {
            refreshAddon(manifestUrl)
        }
    }

    fun setAddonHideCatalogs(manifestUrl: String, hideCatalogs: Boolean) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return

        var changed = false
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (
                        addon.manifestUrl != manifestUrl ||
                        !addon.enabled ||
                        addon.hideCatalogs == hideCatalogs
                    ) {
                        addon
                    } else {
                        changed = true
                        addon.copy(hideCatalogs = hideCatalogs)
                    }
                },
            )
        }
        if (changed) persist()
    }

    fun refreshAll() {
        val refreshPlan = synchronized(activeRefreshJobsLock) {
            val profileId = currentProfileId
            val generation = profileGeneration
            if (!isProfileGenerationCurrentLocked(profileId, generation)) return
            val manifestUrls = _uiState.value.addons
                .filter { it.enabled }
                .distinctBy { it.manifestUrl }
                .map { it.manifestUrl }
            val jobsToCancel = manifestUrls.mapNotNull { activeRefreshJobs.remove(it) }
            Triple(profileId to generation, manifestUrls, jobsToCancel)
        }
        refreshPlan.third.forEach(Job::cancel)
        refreshPlan.second.forEach { manifestUrl ->
            refreshAddonForProfile(
                manifestUrl = manifestUrl,
                forceRefresh = true,
                expectedProfileId = refreshPlan.first.first,
                expectedGeneration = refreshPlan.first.second,
            )
        }
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
    ) {
        val expectedProfile = synchronized(activeRefreshJobsLock) {
            currentProfileId to profileGeneration
        }
        refreshAddonForProfile(
            manifestUrl = manifestUrl,
            forceRefresh = forceRefresh,
            expectedProfileId = expectedProfile.first,
            expectedGeneration = expectedProfile.second,
        )
    }

    private fun refreshAddonForProfile(
        manifestUrl: String,
        forceRefresh: Boolean,
        expectedProfileId: Int,
        expectedGeneration: Long,
    ) {
        var jobToStart: Job? = null
        synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(expectedProfileId, expectedGeneration)) return
            val existingJob = activeRefreshJobs[manifestUrl]
            if (existingJob != null && !existingJob.isCompleted) return
            markRefreshing(manifestUrl)
            lateinit var refreshJob: Job
            refreshJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result: Result<AddonManifest> = try {
                        InAppLogger.info("Addons/Manifest", "GET ${InAppLogger.redactUrl(manifestUrl)} reason=refresh")
                        val payload = fetchAddonResponseText(url = manifestUrl, forceRefresh = forceRefresh)
                        val manifest = AddonManifestParser.parse(manifestUrl = manifestUrl, payload = payload)
                        Result.success(manifest)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        log.w(error) { "refreshAddon() failed manifestUrl=" + manifestUrl }
                        Result.failure(error)
                    }
                    val failureMessage = if (result.isFailure) {
                        runCatching { getString(Res.string.addon_load_manifest_failed) }
                            .getOrDefault("Failed to load add-on manifest")
                    } else null
                    synchronized(activeRefreshJobsLock) {
                        if (
                            !isProfileGenerationCurrentLocked(expectedProfileId, expectedGeneration) ||
                            activeRefreshJobs[manifestUrl] !== refreshJob
                        ) return@synchronized
                        _uiState.update { current ->
                            current.copy(
                                addons = current.addons.map { addon ->
                                    if (addon.manifestUrl != manifestUrl) addon
                                    else result.fold(
                                        onSuccess = { manifest ->
                                            InAppLogger.info(
                                                "Addons/Manifest",
                                                "refresh success name=${manifest.name} url=${InAppLogger.redactUrl(manifestUrl)}",
                                            )
                                            addon.copy(manifest = manifest, isRefreshing = false, errorMessage = null)
                                        },
                                        onFailure = { error ->
                                            InAppLogger.error(
                                                "Addons/Manifest",
                                                "refresh failed url=${InAppLogger.redactUrl(manifestUrl)}: ${InAppLogger.throwableSummary(error)}",
                                            )
                                            addon.copy(
                                                isRefreshing = false,
                                                errorMessage = error.message ?: failureMessage,
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                } finally {
                    synchronized(activeRefreshJobsLock) {
                        if (activeRefreshJobs[manifestUrl] === refreshJob) activeRefreshJobs.remove(manifestUrl)
                    }
                }
            }
            activeRefreshJobs[manifestUrl] = refreshJob
            jobToStart = refreshJob
        }
        jobToStart?.start()
    }

    private fun pushToServer() {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        val profileId = currentProfileId
        val addons = _uiState.value.addons
            .distinctBy { it.manifestUrl }
            .mapIndexed { index, addon ->
                AddonPushItem(
                    url = addon.manifestUrl,
                    name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
                    enabled = addon.enabled,
                    sortOrder = index,
                )
            }
        pushJobsByProfile[profileId]?.cancel()
        var pushJob: Job? = null
        pushJob = scope.launch {
            try {
                delay(ADDON_PUSH_DEBOUNCE_MS)
                log.d { "pushToServer() — profileId=$profileId, pushing ${addons.size} addons" }
                InAppLogger.debug("Addons/Repository", "pushToServer profile=$profileId count=${addons.size}")
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_addons", json.encodeToJsonElement(addons))
                    putSyncOriginClientId()
                }
                SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                log.d { "pushToServer() — success" }
                InAppLogger.info("Addons/Repository", "pushToServer success count=${addons.size}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "pushToServer() — FAILED" }
                InAppLogger.error("Addons/Repository", "pushToServer failed: ${InAppLogger.throwableSummary(error)}")
            } finally {
                if (pushJobsByProfile[profileId] === pushJob) {
                    pushJobsByProfile.remove(profileId)
                }
            }
        }
        pushJobsByProfile[profileId] = pushJob
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persist(profileId: Int = currentProfileId) {
        val addons = _uiState.value.addons
        AddonStorage.saveInstalledAddonUrls(
            profileId,
            dedupeManifestUrls(addons.map { it.manifestUrl }),
        )
        AddonStorage.saveAddonEnabledStates(
            profileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
        AddonStorage.saveAddonCatalogHiddenStates(
            profileId,
            addons.associate { it.manifestUrl to it.hideCatalogs },
        )
    }

    private fun loadLocalEnabledStates(profileId: Int = currentProfileId): Map<String, Boolean> =
        AddonStorage.loadAddonEnabledStates(profileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun loadLocalCatalogHiddenStates(profileId: Int = currentProfileId): Map<String, Boolean> =
        AddonStorage.loadAddonCatalogHiddenStates(profileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun activateProfile(profileId: Int, forceReset: Boolean = false): List<Job> =
        synchronized(activeRefreshJobsLock) {
            if (!forceReset && currentProfileId == profileId) return@synchronized emptyList()
            profileGeneration++
            currentProfileId = profileId
            initialized = false
            pulledFromServer = false
            val jobs = activeRefreshJobs.values.toList()
            activeRefreshJobs.clear()
            _uiState.value = AddonsUiState()
            jobs
        }

    private fun isProfileGenerationCurrentLocked(profileId: Int, generation: Long): Boolean =
        currentProfileId == profileId && profileGeneration == generation

    private fun cancelActiveRefreshes() {
        val jobs = synchronized(activeRefreshJobsLock) {
            activeRefreshJobs.values.toList().also { activeRefreshJobs.clear() }
        }
        jobs.forEach(Job::cancel)
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
    hideCatalogs: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
            hideCatalogs = hideCatalogs ?: false,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
            hideCatalogs = hideCatalogs ?: this.hideCatalogs,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
            hideCatalogs = hideCatalogs ?: this.hideCatalogs,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
            hideCatalogs = hideCatalogs ?: this.hideCatalogs,
        )
    }

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

private fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.addons_error_enter_url) } }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
}
