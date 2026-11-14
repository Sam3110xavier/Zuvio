package com.nuvio.app.features.cloudstream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import co.touchlab.kermit.Logger
import com.nuvio.app.core.logging.InAppLogger
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.nicehttp.ignoreAllSSLErrors
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.ref.WeakReference
import java.security.Security
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import com.nuvio.app.core.network.IPv4FirstDns
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal actual object CloudStreamPlatformRuntime {
    actual val supportsAndroidDex: Boolean = true

    private val log = Logger.withTag("CloudStreamDex")
    private val json = Json { ignoreUnknownKeys = true }
    private val loadMutex = Mutex()
    private val loadedLock = Any()
    private val loaded = linkedMapOf<String, LoadedPlugin>()
    // Invalidates in-flight loads after a profile reset or per-plugin unload.
    private var runtimeGeneration = 0L
    private val pluginGenerations = mutableMapOf<String, Long>()
    private var appContext: Context? = null
    private var activityReference: WeakReference<Activity>? = null
    @Volatile private var cloudStreamNetworkingInitialized = false

    // Share session cookies between CloudStream provider and delegated extractor requests.
    // This mirrors the official Nuvio TV extension cookie jar without sharing cookies
    // with Zuvio's first-party API clients.
    private val extensionCookieJar: CookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostCookies = store[url.host] ?: return emptyList()
            synchronized(hostCookies) {
                return hostCookies.filter { it.expiresAt > System.currentTimeMillis() }
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val hostCookies = store.getOrPut(url.host) { mutableListOf() }
            synchronized(hostCookies) {
                cookies.forEach { newCookie ->
                    hostCookies.removeAll { it.name == newCookie.name }
                    hostCookies.add(newCookie)
                }
            }
        }
    }

    actual fun initialize(context: Any?) {
        val androidContext = context as? Context ?: return
        appContext = androidContext.applicationContext
        activityReference = (androidContext as? Activity)?.let(::WeakReference)
        AcraApplication.context = appContext
        activityReference?.get()?.let { activity ->
            CommonActivity.setActivityInstance(activity)
            AcraApplication.setActivity(activity)
        }
        initializeCloudStreamNetworking(requireNotNull(appContext))
    }

    /**
     * CloudStream normally initializes its NiceHttp Requests.baseClient from its
     * own Application startup. Zuvio embeds the runtime AAR without CloudStream's
     * Application, so plugins otherwise enter loadLinks with an uninitialized
     * network client. Mirror CloudStream's secure default client setup before
     * any extension code runs.
     */
    private fun initializeCloudStreamNetworking(context: Context) {
        if (cloudStreamNetworkingInitialized) return
        synchronized(this) {
            if (cloudStreamNetworkingInitialized) return
            runCatching { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
                .onFailure { error -> log.w(error) { "Could not install Conscrypt provider" } }
            val cacheDirectory = File(context.cacheDir, "cloudstream_http_cache").apply { mkdirs() }
            app.baseClient = OkHttpClient.Builder()
                .dns(IPv4FirstDns())
                .cookieJar(extensionCookieJar)
                .followRedirects(true)
                .followSslRedirects(true)
                // Match Nuvio TV for legacy CS3 providers with broken/expired certificates.
                // This applies only to CloudStream's dedicated client, not Zuvio's app clients.
                .ignoreAllSSLErrors()
                .cache(Cache(cacheDirectory, 50L * 1024L * 1024L))
                .build()
            cloudStreamNetworkingInitialized = true
            log.i { "Initialized CloudStream NiceHttp base client" }
            InAppLogger.cloudStreamInfo("CloudStreamDex", "Initialized CloudStream NiceHttp base client")
        }
    }

    actual suspend fun provider(plugin: CloudStreamPluginItem): CloudStreamProvider? {
        if (plugin.compatibility.runtimeKind != CloudStreamRuntimeKind.AndroidDex) return null
        val pluginId = plugin.metadata.id.value

        // A provider that is already loaded must never wait behind another
        // package's DEX scan. Keep this lookup short and outside loadMutex.
        synchronized(loadedLock) { loaded[pluginId]?.provider }?.let { return it }

        return withContext(Dispatchers.IO) {
            loadMutex.withLock {
                synchronized(loadedLock) { loaded[pluginId]?.provider } ?: run {
                    val generation = synchronized(loadedLock) {
                        runtimeGeneration to (pluginGenerations[pluginId] ?: 0L)
                    }
                    val loadedPlugin = loadPlugin(plugin)
                    val accepted = synchronized(loadedLock) {
                        if (
                            runtimeGeneration == generation.first &&
                            (pluginGenerations[pluginId] ?: 0L) == generation.second
                        ) {
                            loaded[pluginId] = loadedPlugin
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        loadedPlugin.unload()
                        error("CloudStream runtime was reset while plugin $pluginId was loading")
                    }
                    loadedPlugin.provider
                }
            }
        }
    }

    actual suspend fun hasSettings(plugin: CloudStreamPluginItem): Boolean {
        if (plugin.compatibility.runtimeKind != CloudStreamRuntimeKind.AndroidDex || !plugin.isInstalled) {
            return false
        }
        return try {
            provider(plugin)
            val instance = synchronized(loadedLock) { loaded[plugin.metadata.id.value]?.instance }
            (instance as? Plugin)?.openSettings != null
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            log.w(error) { "Could not inspect CloudStream settings capability id=${plugin.metadata.id.value}" }
            false
        }
    }

    actual suspend fun openSettings(plugin: CloudStreamPluginItem): Boolean {
        if (!hasSettings(plugin)) return false
        val cloudStreamPlugin = synchronized(loadedLock) { loaded[plugin.metadata.id.value]?.instance as? Plugin }
            ?: return false
        val callback = cloudStreamPlugin.openSettings ?: return false
        val context = activityReference?.get() ?: appContext ?: return false
        return try {
            callback.invoke(context)
            true
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            log.e(error) { "Failed to open CloudStream settings id=${plugin.metadata.id.value}" }
            false
        }
    }

    actual fun unload(pluginId: String) {
        val removed = synchronized(loadedLock) {
            pluginGenerations[pluginId] = (pluginGenerations[pluginId] ?: 0L) + 1L
            loaded.remove(pluginId)
        }
        removed?.unload()
        val remainingContext = synchronized(loadedLock) { loaded.values.lastOrNull()?.context }
        if (remainingContext != null) {
            CloudStreamApp.context = remainingContext
            setContext(WeakReference(remainingContext))
        } else {
            appContext?.let(::prepareHostContext)
        }
    }

    actual fun clear() {
        val plugins = synchronized(loadedLock) {
            runtimeGeneration += 1
            pluginGenerations.clear()
            loaded.values.toList().also {
                loaded.clear()
            }
        }
        plugins.forEach(LoadedPlugin::unload)
        PluginManager.clear()
        appContext?.let(::prepareHostContext)
    }

    private fun loadPlugin(item: CloudStreamPluginItem): LoadedPlugin {
        InAppLogger.cloudStreamInfo("CloudStreamDex", "Plugin load started id=${item.metadata.id.value} name=${item.metadata.internalName}")
        val context = requireNotNull(appContext) { "CloudStream Android runtime is not initialized" }
        prepareHostContext(context)
        val filePath = requireNotNull(CloudStreamPlatformStorage.packagePath(item.metadata.id.storageKey)) {
            "Installed CloudStream package is missing"
        }
        val file = File(filePath)
        require(file.isFile) { "Installed CloudStream package is missing" }
        item.metadata.fileSize?.let { expectedSize ->
            if (file.length() != expectedSize) {
                log.w {
                    "Installed CloudStream package metadata size mismatch id=${item.metadata.id.value} " +
                        "expected=$expectedSize actual=${file.length()}"
                }
            }
        }
        val packageBytes = file.readBytes()
        // Recheck the archive on every load and validate against the checksum that
        // belongs to this installed version. Repository metadata may already describe
        // a newer version whose checksum must not be applied to the older installed file.
        CloudStreamPackageInspector.inspect(packageBytes)
        require(matchesInstalledCloudStreamPackage(item.installedFileHash, packageBytes)) {
            "Installed CloudStream package SHA-256 no longer matches its installed checksum"
        }
        require(file.setReadOnly() || !file.canWrite()) { "CloudStream package could not be made read-only" }

        val manifest = ZipFile(file).use { archive ->
            val entry = archive.getEntry("manifest.json")
                ?: error("CloudStream package has no manifest.json")
            archive.getInputStream(entry).bufferedReader().use { reader ->
                json.decodeFromString<CloudStreamCs3Manifest>(reader.readText())
            }
        }
        val manifestPluginClassName = manifest.pluginClassName?.takeIf(String::isNotBlank)
        InAppLogger.cloudStreamInfo(
            "CloudStreamDex",
            "Manifest validated id=" + item.metadata.id.value +
                " entry=" + (manifestPluginClassName ?: "<scan DEX>") +
                " version=" + (manifest.version ?: item.metadata.version),
        )
        // Match the production sideload loaders used by NuvioTV and ARVIO. DexClassLoader
        // writes optimized DEX artifacts into the app-private code cache and is the
        // supported loader for downloaded .cs3 archives.
        val optimizedDirectory = File(context.codeCacheDir, "cloudstream_dex_cache").apply { mkdirs() }
        val loader = DexClassLoader(
            file.absolutePath,
            optimizedDirectory.absolutePath,
            null,
            context.classLoader,
        )
        // AtomicList iterators are deliberately not synchronized. Take stable
        // snapshots under each registry's lock before comparing plugin registrations.
        InAppLogger.cloudStreamDebug("CloudStreamDex", "DexClassLoader created id=${item.metadata.id.value} optimizedDir=${optimizedDirectory.name}")
        val providersBefore = APIHolder.allProviders.withLock { APIHolder.allProviders.toSet() }
        val extractorsBefore = extractorApis.withLock { extractorApis.toSet() }
        var instance: BasePlugin? = null
        PluginManager.currentlyLoading = item.metadata.internalName
        try {
            InAppLogger.cloudStreamDebug(
                "CloudStreamDex",
                "Resolving plugin entry class=" + (manifestPluginClassName ?: "by @CloudstreamPlugin DEX scan"),
            )
            val resolvedEntry = resolvePluginEntry(loader, file, manifestPluginClassName)
            val pluginInstance = resolvedEntry.plugin
            val pluginClassName = resolvedEntry.className
            InAppLogger.cloudStreamInfo("CloudStreamDex", "Resolved plugin entry class=" + pluginClassName)
            instance = pluginInstance
            pluginInstance.filename = file.absolutePath
            // Match CloudStream's lifecycle: make the plugin visible to PluginManager
            // before invoking load(), because extensions may inspect the registry while
            // registering providers or initializing their own settings.
            val data = PluginData(
                internalName = item.metadata.internalName,
                url = item.metadata.packageUrl,
                isOnline = true,
                filePath = file.absolutePath,
                version = manifest.version ?: item.metadata.version,
            )
            PluginManager.register(data, pluginInstance)
            val identityContext = CloudStreamIdentityContext(context)
            CloudStreamApp.context = identityContext
            setContext(WeakReference(identityContext))
            applyHostCompatibilityDefaults(pluginClassName)
            if (manifest.requiresResources) pluginInstance.attachResources(identityContext, file)

            InAppLogger.cloudStreamDebug("CloudStreamDex", "Invoking plugin load() id=" + item.metadata.id.value)
            try {
                // Match the official loader: prefer the live Activity so plugins
                // overriding load(Activity?) receive the host lifecycle context.
                pluginInstance.load(activityReference?.get() ?: identityContext)
            } catch (error: Throwable) {
                if (
                    error is kotlinx.coroutines.CancellationException ||
                    error is VirtualMachineError ||
                    error is ThreadDeath
                ) {
                    throw error
                }
                log.w(error) {
                    "CloudStream plugin load partially failed id=" + item.metadata.id.value +
                        "; continuing with registered providers and DEX fallback"
                }
                InAppLogger.cloudStreamWarn(
                    "CloudStreamDex",
                    "Plugin load partially failed id=" + item.metadata.id.value +
                        ": " + InAppLogger.throwableSummary(error),
                )
            }
            val providers = APIHolder.allProviders
                .filter { it !in providersBefore || it.sourcePlugin == file.absolutePath }
                .distinct()
                .toMutableList()
            if (providers.none { it.sourcePlugin == file.absolutePath }) {
                val fallbackProviders = scanMainApis(file, loader)
                fallbackProviders.forEach { api ->
                    if (providers.none { it.javaClass == api.javaClass }) providers += api
                }
                if (fallbackProviders.isNotEmpty()) {
                    InAppLogger.cloudStreamInfo(
                        "CloudStreamDex",
                        "DEX fallback registered " + fallbackProviders.size +
                            " MainAPI provider(s) for id=" + item.metadata.id.value,
                    )
                }
            }
            val registeredExtractors = extractorApis.withLock {
                extractorApis
                    .filter { it !in extractorsBefore || it.sourcePlugin == file.absolutePath }
                    .distinct()
            }.toMutableList()

            // Some real-world .cs3 packages contain ExtractorApi implementations but
            // fail to expose them through BasePlugin.registerExtractorAPI(). NuvioTV
            // and ARVIO both fall back to scanning the DEX in this case. Register those
            // classes into CloudStream's global registry so MainAPI.loadLinks() can
            // resolve links delegated to another plugin's extractor.
            if (registeredExtractors.none { it.sourcePlugin == file.absolutePath }) {
                val dexFile = dalvik.system.DexFile(file)
                try {
                    val entries = dexFile.entries()
                    while (entries.hasMoreElements()) {
                        val className = entries.nextElement()
                        if (className.contains(36.toChar())) continue
                        try {
                            val extractorClass = loader.loadClass(className)
                            if (
                                ExtractorApi::class.java.isAssignableFrom(extractorClass) &&
                                !java.lang.reflect.Modifier.isAbstract(extractorClass.modifiers) &&
                                !extractorClass.isInterface
                            ) {
                                val extractor = extractorClass.getDeclaredConstructor()
                                    .newInstance() as ExtractorApi
                                extractor.sourcePlugin = file.absolutePath
                                val added = extractorApis.withLock {
                                    if (extractorApis.any {
                                            it.javaClass == extractor.javaClass &&
                                                it.sourcePlugin == file.absolutePath
                                        }
                                    ) {
                                        false
                                    } else {
                                        extractorApis.add(extractor)
                                        true
                                    }
                                }
                                if (added) registeredExtractors += extractor
                            }
                        } catch (error: Throwable) {
                            if (
                                error is kotlinx.coroutines.CancellationException ||
                                error is VirtualMachineError ||
                                error is ThreadDeath
                            ) {
                                throw error
                            }
                            log.w(error) {
                                if (error is LinkageError) {
                                    "CloudStream extractor class has incompatible dependencies class=$className"
                                } else {
                                    "Skipping CloudStream extractor class=$className"
                                }
                            }
                        }
                    }
                } finally {
                    dexFile.close()
                }
            }
            // A disabled package may still provide ExtractorApi implementations for
            // cross-plugin delegation, but it must not publish MainAPI providers into
            // the active CloudStream provider registry.
            if (!item.isRunnable && providers.isNotEmpty()) {
                providers.forEach(APIHolder::removePluginMapping)
                APIHolder.allProviders.removeAll { it.sourcePlugin == file.absolutePath }
                InAppLogger.cloudStreamInfo(
                    "CloudStreamDex",
                    "Disabled plugin retained as extractor-only id=${item.metadata.id.value} extractors=${registeredExtractors.size}",
                )
                providers.clear()
            }

            // CloudStream permits extractor-only and utility plugins. Do not reject a
            // valid .cs3 package just because it does not register a MainAPI provider.
            providers.forEach(MainAPI::init)
            log.i {
                "Loaded ${item.metadata.internalName}: ${providers.size} provider(s), " +
                    "${registeredExtractors.size} extractor(s)"
            }
            InAppLogger.cloudStreamInfo("CloudStreamDex", "Plugin loaded id=${item.metadata.id.value} providers=${providers.size} extractors=${registeredExtractors.size}")
            return LoadedPlugin(
                id = item.metadata.id.value,
                path = file.absolutePath,
                context = identityContext,
                instance = pluginInstance,
                providers = providers,
                extractors = registeredExtractors,
                provider = AndroidDexCloudStreamProvider(item.metadata.id.value, providers),
            )
        } catch (error: Throwable) {
            log.e(error) { "Failed to load ${item.metadata.internalName}" }
            InAppLogger.cloudStreamError("CloudStreamDex", "Plugin load failed id=${item.metadata.id.value}: ${InAppLogger.throwableSummary(error)}")
            runCatching { instance?.beforeUnload() }
            val addedProviders = APIHolder.allProviders.filter {
                it !in providersBefore && it.sourcePlugin == file.absolutePath
            }
            addedProviders.forEach(APIHolder::removePluginMapping)
            APIHolder.allProviders.removeAll { it !in providersBefore && it.sourcePlugin == file.absolutePath }
            extractorApis.removeAll { it !in extractorsBefore && it.sourcePlugin == file.absolutePath }
            VideoClickActionHolder.allVideoClickActions.removeAll { it.sourcePlugin == file.absolutePath }
            PluginManager.unregister(file.absolutePath)
            prepareHostContext(context)
            throw error
        } finally {
            PluginManager.currentlyLoading = null
        }
    }


    private data class ResolvedPluginEntry(
        val plugin: Plugin,
        val className: String,
    )

    /**
     * Resolve the manifest entry first, then fall back to annotation-based DEX discovery
     * as the official Nuvio TV loader does.
     */
    private fun resolvePluginEntry(
        classLoader: DexClassLoader,
        dexFile: File,
        manifestClassName: String?,
    ): ResolvedPluginEntry {
        if (!manifestClassName.isNullOrBlank()) {
            try {
                val instance = classLoader.loadClass(manifestClassName)
                    .getDeclaredConstructor()
                    .newInstance()
                val plugin = asHostPlugin(instance)
                if (plugin != null) return ResolvedPluginEntry(plugin, manifestClassName)
                log.w { "Manifest entry is not a CloudStream plugin: " + manifestClassName }
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                CloudStreamPlatformRuntime.log.w(error) { "Could not load manifest entry; scanning DEX for plugin annotation" }
            }
        }

        val dex = dalvik.system.DexFile(dexFile)
        try {
            val entries = dex.entries()
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (className.indexOf(36.toChar()) >= 0) continue
                try {
                    val candidate = classLoader.loadClass(className)
                    if (!candidate.isAnnotationPresent(CloudstreamPlugin::class.java)) continue
                    val instance = candidate.getDeclaredConstructor().newInstance()
                    val plugin = asHostPlugin(instance)
                    if (plugin != null) {
                        CloudStreamPlatformRuntime.log.i { "Found CloudStream plugin entry by DEX annotation scan: " + className }
                        return ResolvedPluginEntry(plugin, className)
                    }
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    CloudStreamPlatformRuntime.log.w(error) { "Skipping incompatible CloudStream plugin entry candidate=" + className }
                }
            }
        } finally {
            dex.close()
        }
        error("No compatible @CloudstreamPlugin entry point found in " + dexFile.name)
    }

    private fun asHostPlugin(instance: Any): Plugin? = when {
        instance is Plugin -> instance
        isPluginLike(instance) -> ReflectiveCloudStreamPluginWrapper(instance)
        else -> null
    }

    private fun isPluginLike(instance: Any): Boolean {
        if (instance is BasePlugin) return true
        val methods = instance.javaClass.methods
        return methods.any { method ->
            method.name == "load" &&
                (method.parameterCount == 0 ||
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java)) ||
                    method.parameterTypes.contentEquals(arrayOf(Activity::class.java)))
        } || methods.any { it.name == "getRegisteredMainAPIs" }
    }

    /**
     * Recover concrete MainAPI classes from the package when plugin.load() did not
     * register providers. This is the official loader's partial-registration fallback.
     */
    private fun scanMainApis(file: File, classLoader: DexClassLoader): List<MainAPI> {
        val discovered = mutableListOf<MainAPI>()
        val dex = dalvik.system.DexFile(file)
        try {
            val entries = dex.entries()
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (
                    className.indexOf(36.toChar()) >= 0 ||
                    className.contains("Plugin") ||
                    className.contains("Fragment") ||
                    !className.startsWith("com.")
                ) continue
                try {
                    val candidate = classLoader.loadClass(className)
                    if (
                        MainAPI::class.java.isAssignableFrom(candidate) &&
                        !java.lang.reflect.Modifier.isAbstract(candidate.modifiers) &&
                        !candidate.isInterface
                    ) {
                        val api = candidate.getDeclaredConstructor().newInstance() as MainAPI
                        api.sourcePlugin = file.absolutePath
                        APIHolder.addPluginMapping(api)
                        APIHolder.allProviders.withLock {
                            if (APIHolder.allProviders.none { it === api }) APIHolder.allProviders.add(api)
                        }
                        discovered += api
                        log.i { "DEX fallback discovered MainAPI " + api.name + " (" + className + ")" }
                    }
                } catch (error: Throwable) {
                    if (
                        error is kotlinx.coroutines.CancellationException ||
                        error is VirtualMachineError ||
                        error is ThreadDeath
                    ) {
                        throw error
                    }
                    if (error is LinkageError) {
                        log.w(error) { "Skipping MainAPI with incompatible dependency class=" + className }
                    } else {
                        log.d { "Skipping non-instantiable MainAPI candidate=" + className }
                    }
                }
            }
        } finally {
            dex.close()
        }
        return discovered.distinctBy { it.javaClass.name }
    }

    /**
     * Adapter for extensions compiled against a nearby CloudStream Plugin ABI.
     * It forwards load overloads and transfers local/global provider registrations
     * into the host's BasePlugin registry.
     */
    private class ReflectiveCloudStreamPluginWrapper(
        private val delegate: Any,
    ) : Plugin() {
        override fun load(context: Context) {
            val providersBefore = APIHolder.allProviders.withLock { APIHolder.allProviders.toSet() }
            val extractorsBefore = extractorApis.withLock { extractorApis.toSet() }

            runCatching {
                delegate.javaClass.getMethod("setFilename", String::class.java).invoke(delegate, filename)
            }
            runCatching {
                val callback = delegate.javaClass.getMethod("getOpenSettings").invoke(delegate)
                    as? ((Context) -> Unit)
                if (callback != null) openSettings = callback
            }

            invokeForeignLoad(context)

            val localProviders = readForeignList("getRegisteredMainAPIs").filterIsInstance<MainAPI>()
            val providers = if (localProviders.isNotEmpty()) localProviders else {
                APIHolder.allProviders.withLock { APIHolder.allProviders.filter { it !in providersBefore } }
            }
            providers.distinctBy { it.javaClass.name }.forEach { api ->
                api.sourcePlugin = filename
                if (APIHolder.allProviders.none { it === api }) registerMainAPI(api)
            }

            val localExtractors = readForeignList("getRegisteredExtractorAPIs").filterIsInstance<ExtractorApi>()
            val extractors = if (localExtractors.isNotEmpty()) localExtractors else {
                extractorApis.withLock { extractorApis.filter { it !in extractorsBefore } }
            }
            extractors.distinctBy { it.javaClass.name }.forEach { extractor ->
                extractor.sourcePlugin = filename
                if (extractorApis.none { it === extractor }) registerExtractorAPI(extractor)
            }
        }

        private fun readForeignList(methodName: String): List<Any> =
            runCatching {
                (delegate.javaClass.getMethod(methodName).invoke(delegate) as? List<*>)?.filterNotNull().orEmpty()
            }.getOrDefault(emptyList())

        private fun invokeForeignLoad(context: Context) {
            val methods = delegate.javaClass.methods
            val contextMethod = methods.firstOrNull {
                it.name == "load" && it.parameterTypes.contentEquals(arrayOf(Context::class.java))
            }
            if (contextMethod != null) {
                try {
                    contextMethod.invoke(delegate, context)
                    return
                } catch (error: java.lang.reflect.InvocationTargetException) {
                    if (error.targetException !is ClassCastException) throw (error.targetException ?: error)
                }
            }

            val activityMethod = methods.firstOrNull {
                it.name == "load" && it.parameterTypes.contentEquals(arrayOf(Activity::class.java))
            }
            if (activityMethod != null) {
                activityMethod.invoke(delegate, AcraApplication.getActivity())
                return
            }

            methods.firstOrNull { it.name == "load" && it.parameterCount == 0 }
                ?.invoke(delegate)
        }
    }

    private fun prepareHostContext(context: Context) {
        activityReference?.get()?.let { activity ->
            CommonActivity.setActivityInstance(activity)
            AcraApplication.setActivity(activity)
        }
        AcraApplication.context = context.applicationContext
        initializeCloudStreamNetworking(context.applicationContext)
        CloudStreamApp.context = context
        setContext(WeakReference(context))
    }

    /**
     * CineStream's optional Simkl catalogue eagerly constructs CloudStream account-sync APIs.
     * Those APIs are application services rather than extension APIs and are intentionally not
     * embedded in Nuvio. The catalogue does not participate in CineStream's source extraction;
     * disabling it lets the primary CineStream/TMDB catalogues and every built-in link provider
     * load exactly as configured by the extension.
     */
    private fun applyHostCompatibilityDefaults(pluginClassName: String) {
        if (pluginClassName != CINESTREAM_PLUGIN_CLASS) return
        val preferences = appContext?.getSharedPreferences(
            com.lagradost.cloudstream3.utils.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ) ?: return
        if (preferences.getString(CINESTREAM_SIMKL_PROVIDER_KEY, null) != "false") {
            CloudStreamApp.setKey(CINESTREAM_SIMKL_PROVIDER_KEY, false)
        }
    }

    @Suppress("DEPRECATION")
    private fun BasePlugin.attachResources(context: Context, file: File) {
        val plugin = this as? Plugin ?: return
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(assets, file.absolutePath)
        plugin.resources = Resources(
            assets,
            context.resources.displayMetrics,
            context.resources.configuration,
        )
    }

    private const val CINESTREAM_PLUGIN_CLASS = "com.megix.CineStream"
    private const val CINESTREAM_SIMKL_PROVIDER_KEY = "ProviderSimkl"

    private data class LoadedPlugin(
        val id: String,
        val path: String,
        @Suppress("unused") val context: Context,
        val instance: BasePlugin,
        val providers: List<MainAPI>,
        val extractors: List<com.lagradost.cloudstream3.utils.ExtractorApi>,
        val provider: CloudStreamProvider,
    ) {
        fun unload() {
            runCatching { instance.beforeUnload() }
            providers.forEach { api ->
                APIHolder.allProviders.remove(api)
                APIHolder.removePluginMapping(api)
            }
            extractorApis.removeAll(extractors.toSet())
            VideoClickActionHolder.allVideoClickActions.removeAll { it.sourcePlugin == path }
            PluginManager.unregister(path)
        }
    }
}

private class CloudStreamIdentityContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = CLOUDSTREAM_PACKAGE_NAME
    override fun getApplicationContext(): Context = this

    companion object {
        private const val CLOUDSTREAM_PACKAGE_NAME = "com.lagradost.cloudstream3"
    }
}

private class AndroidDexCloudStreamProvider(
    override val id: String,
    private val providers: List<MainAPI>,
) : CloudStreamProvider {
    private val log = Logger.withTag("CloudStreamProvider")

    override suspend fun getMainPage(
        page: Int,
    ): List<Pair<String, List<CloudStreamSearchItem>>> = withContext(Dispatchers.IO) {
        val sections = Collections.synchronizedList(
            mutableListOf<Pair<String, List<CloudStreamSearchItem>>>(),
        )
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val providerSemaphore = Semaphore(3)
        providers.filter { api ->
            // Some providers declare categories without setting hasMainPage.
            api.hasMainPage || api.mainPage.any { data ->
                data.name.isNotBlank() || data.data.isNotBlank()
            }
        }.map { api ->
            async {
                providerSemaphore.withPermit {
                    val declaredRequests = api.mainPage.filter { data ->
                        data.name.isNotBlank() || data.data.isNotBlank()
                    }
                    val requests = declaredRequests.ifEmpty {
                        listOf(com.lagradost.cloudstream3.MainPageData(api.name, api.mainUrl, false))
                    }
                    suspend fun loadRequest(data: com.lagradost.cloudstream3.MainPageData) {
                        runCatching {
                            withTimeout(stageTimeout(api.getMainPageTimeoutMs, DEFAULT_DISCOVERY_TIMEOUT_MS)) {
                                api.getMainPage(
                                    page.coerceAtLeast(1),
                                    MainPageRequest(data.name, data.data, data.horizontalImages),
                                )
                            }
                        }.onSuccess { response ->
                            response?.items.orEmpty().forEach { section ->
                                val title = if (providers.size > 1) "${api.name} · ${section.name}" else section.name
                                val items = section.list.map { it.toNuvioSearchItem(api) }
                                if (items.isNotEmpty()) sections += title to items
                            }
                        }.onFailure { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            errors += error
                            log.w(error) { "CloudStream main page failed api=${api.name}" }
                        }
                    }

                    if (api.sequentialMainPage) {
                        requests.forEachIndexed { index, data ->
                            if (index > 0 && api.sequentialMainPageDelay > 0) {
                                delay(api.sequentialMainPageDelay)
                            }
                            loadRequest(data)
                        }
                    } else {
                        coroutineScope {
                            requests.map { data -> async { loadRequest(data) } }.awaitAll()
                        }
                    }
                }
            }
        }.awaitAll()
        if (sections.isEmpty()) throw errors.lastOrNull() ?: return@withContext emptyList()
        synchronized(sections) { sections.toList() }
    }

    override suspend fun search(query: String): List<CloudStreamSearchItem> = withContext(Dispatchers.IO) {
        val providerSemaphore = Semaphore(3)
        val results = providers.map { api ->
            async {
                providerSemaphore.withPermit {
                    runCatching {
                        withTimeout(stageTimeout(api.searchTimeoutMs, DEFAULT_DISCOVERY_TIMEOUT_MS)) {
                            api.search(query, 1)?.items.orEmpty()
                        }
                    }.onSuccess { items ->
                        log.d {
                            "CloudStream search api=${api.name} query=$query returned=${items.size} " +
                                "sample=${items.take(3).joinToString { it.name }}"
                        }
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        log.w(error) { "CloudStream search failed api=${api.name}" }
                    }.map { items -> items.map { it.toNuvioSearchItem(api) } }
                }
            }
        }.awaitAll()
        val successfulResults = results.mapNotNull { it.getOrNull() }.flatten()
        if (successfulResults.isEmpty()) {
            val lastError = results.asReversed().mapNotNull { it.exceptionOrNull() }.firstOrNull()
            throw lastError ?: return@withContext emptyList()
        }
        successfulResults.distinctBy { it.data }
    }

    override suspend fun loadByExternalId(externalId: String): CloudStreamLoadItem? = withContext(Dispatchers.IO) {
        val syncName = when {
            externalId.matches(IMDB_ID_REGEX) -> SyncIdName.Imdb
            else -> return@withContext null
        }
        providers.firstNotNullOfOrNull { api ->
            if (syncName !in api.supportedSyncNames) return@firstNotNullOfOrNull null
            runCatching {
                val url = withTimeout(stageTimeout(api.loadTimeoutMs, DEFAULT_LOAD_TIMEOUT_MS)) {
                    api.getLoadUrl(syncName, externalId)
                }?.takeIf(String::isNotBlank) ?: return@runCatching null
                withTimeout(stageTimeout(api.loadTimeoutMs, DEFAULT_LOAD_TIMEOUT_MS)) {
                    api.load(url)
                }?.toNuvioLoadItem(api)
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                log.w(error) { "CloudStream external-id load failed api=${api.name} id=$externalId" }
            }.getOrNull()
        }
    }

    override suspend fun load(data: String): CloudStreamLoadItem = withContext(Dispatchers.IO) {
        val route = decodeAndroidDexRoute(data)
        val api = findProvider(route.providerClassName)
        val response = withTimeout(stageTimeout(api.loadTimeoutMs, DEFAULT_LOAD_TIMEOUT_MS)) {
            api.load(route.data)
        } ?: error("CloudStream provider returned no details")
        response.toNuvioLoadItem(api)
    }

    override suspend fun loadLinks(data: String): List<CloudStreamPlaybackSource> = withContext(Dispatchers.IO) {
        val route = decodeAndroidDexRoute(data)
        val api = findProvider(route.providerClassName)
        InAppLogger.cloudStreamInfo("CloudStreamProvider", "loadLinks started provider=${api.name} class=${api.javaClass.name}")
        val subtitles = Collections.synchronizedList(mutableListOf<CloudStreamSubtitle>())
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        var loadFailure: Throwable? = null
        val completed = withTimeoutOrNull(stageTimeout(api.loadLinksTimeoutMs, DEFAULT_LINK_TIMEOUT_MS)) {
            try {
                api.loadLinks(
                    route.data,
                    false,
                    { subtitle ->
                        subtitles += CloudStreamSubtitle(
                            url = subtitle.url,
                            language = subtitle.lang,
                            name = subtitle.lang,
                            headers = subtitle.headers.orEmpty(),
                        )
                    },
                    links::add,
                )
            } catch (error: Throwable) {
                // Preserve coroutine cancellation and VM-fatal conditions, but capture
                // every extension failure (including LinkageError) for diagnostics.
                if (
                    error is kotlinx.coroutines.CancellationException ||
                    error is VirtualMachineError ||
                    error is ThreadDeath
                ) {
                    throw error
                }
                loadFailure = error
                false
            }
        }
        val subtitleSnapshot = synchronized(subtitles) { subtitles.toList() }
        val linkSnapshot = synchronized(links) { links.toList() }
        loadFailure?.let { error ->
            InAppLogger.cloudStreamError("CloudStreamProvider", "loadLinks failed provider=${api.name} emitted=${linkSnapshot.size}: ${InAppLogger.throwableSummary(error)}")
            log.w(error) {
                "CloudStream loadLinks failed api=${api.name} after emitting ${linkSnapshot.size} link(s)"
            }
        }
        if (completed != true && linkSnapshot.isNotEmpty()) {
            log.w {
                "CloudStream loadLinks finished with completed=${completed} api=${api.name}; returning ${linkSnapshot.size} partial link(s)"
            }
        }
        if (linkSnapshot.isEmpty()) {
            loadFailure?.let { throw it }
            if (completed == null) error("CloudStream provider timed out before returning links")
            if (completed == false) error("CloudStream provider could not resolve this source")
        }
        InAppLogger.cloudStreamInfo("CloudStreamProvider", "loadLinks completed provider=${api.name} completed=$completed links=${linkSnapshot.size} subtitles=${subtitleSnapshot.size}")
        linkSnapshot.distinctBy { link ->
            listOf(link.url, link.referer, link.quality.toString(), link.type.name, link.headers.toString())
        }.map { link ->
            CloudStreamPlaybackSource(
                name = link.name.ifBlank { link.source },
                url = link.url,
                quality = link.quality.takeIf { it > 0 },
                referer = link.referer.takeIf(String::isNotBlank),
                headers = link.headers,
                subtitles = subtitleSnapshot,
                isHls = link.type == ExtractorLinkType.M3U8,
                isDash = link.type == ExtractorLinkType.DASH,
            )
        }.also { if (it.isEmpty()) error("CloudStream provider returned no playable links") }
    }

    private fun findProvider(className: String): MainAPI =
        providers.firstOrNull { it.javaClass.name == className }
            ?: error("CloudStream provider is no longer registered")

    private fun SearchResponse.toNuvioSearchItem(api: MainAPI): CloudStreamSearchItem =
        CloudStreamSearchItem(
            providerId = this@AndroidDexCloudStreamProvider.id,
            data = encodeAndroidDexRoute(api.javaClass.name, url),
            name = name,
            type = type.toNuvioTvType(),
            posterUrl = posterUrl,
            year = when (this) {
                is AnimeSearchResponse -> year
                is MovieSearchResponse -> year
                is TvSeriesSearchResponse -> year
                is LiveSearchResponse -> null
                else -> null
            },
        )

    private fun LoadResponse.toNuvioLoadItem(api: MainAPI): CloudStreamLoadItem {
        val playbackData = when (this) {
            is MovieLoadResponse -> dataUrl
            is LiveStreamLoadResponse -> dataUrl
            is TorrentLoadResponse -> magnet ?: torrent ?: url
            else -> url
        }
        val episodes = when (this) {
            is TvSeriesLoadResponse -> episodes.map { episode -> episode.toNuvioEpisode(api) }
            is AnimeLoadResponse -> episodes.flatMap { (dubStatus, values) ->
                values.map { episode ->
                    episode.toNuvioEpisode(api, dubStatus.name.takeUnless { episodes.size == 1 })
                }
            }
            else -> emptyList()
        }
        return CloudStreamLoadItem(
            providerId = id,
            data = encodeAndroidDexRoute(api.javaClass.name, playbackData),
            name = name,
            type = type.toNuvioTvType(),
            posterUrl = posterUrl,
            backgroundUrl = backgroundPosterUrl,
            description = plot,
            year = year,
            ratingPercent = score?.toInt(100),
            tags = tags.orEmpty(),
            episodes = episodes,
        )
    }

    private fun com.lagradost.cloudstream3.Episode.toNuvioEpisode(
        api: MainAPI,
        variant: String? = null,
    ): CloudStreamEpisode = CloudStreamEpisode(
        data = encodeAndroidDexRoute(api.javaClass.name, data),
        name = listOfNotNull(name?.takeIf(String::isNotBlank), variant).joinToString(" · ")
            .ifBlank { "Episode ${episode ?: ""}".trim() },
        season = season,
        episode = episode,
        posterUrl = posterUrl,
        description = description,
    )

    private companion object {
        private val IMDB_ID_REGEX = Regex("^tt\\d{5,12}$", RegexOption.IGNORE_CASE)
        private fun stageTimeout(providerHint: Long?, hostMaximum: Long): Long =
            providerHint?.coerceIn(5_000L, hostMaximum) ?: hostMaximum

        private const val DEFAULT_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val DEFAULT_LOAD_TIMEOUT_MS = 30_000L
        private const val DEFAULT_LINK_TIMEOUT_MS = 120_000L
    }
}

private data class AndroidDexRoute(val providerClassName: String, val data: String)

private fun encodeAndroidDexRoute(providerClassName: String, data: String): String =
    "nuvio-cs-api:${providerClassName.length}:$providerClassName$data"

private fun decodeAndroidDexRoute(value: String): AndroidDexRoute {
    val payload = value.removePrefix("nuvio-cs-api:")
    require(payload != value) { "Invalid CloudStream provider route" }
    val separator = payload.indexOf(':')
    require(separator > 0) { "Invalid CloudStream provider route" }
    val providerLength = payload.substring(0, separator).toIntOrNull()
        ?: error("Invalid CloudStream provider route")
    val content = payload.substring(separator + 1)
    require(providerLength in 1..content.length) { "Invalid CloudStream provider route" }
    return AndroidDexRoute(
        providerClassName = content.substring(0, providerLength),
        data = content.substring(providerLength),
    )
}

private fun TvType?.toNuvioTvType(): CloudStreamTvType = when (this) {
    TvType.Movie -> CloudStreamTvType.Movie
    TvType.AnimeMovie -> CloudStreamTvType.AnimeMovie
    TvType.TvSeries -> CloudStreamTvType.TvSeries
    TvType.Cartoon -> CloudStreamTvType.Cartoon
    TvType.Anime -> CloudStreamTvType.Anime
    TvType.OVA -> CloudStreamTvType.Ova
    TvType.Torrent -> CloudStreamTvType.Torrent
    TvType.Documentary -> CloudStreamTvType.Documentary
    TvType.AsianDrama -> CloudStreamTvType.AsianDrama
    TvType.Live -> CloudStreamTvType.Live
    TvType.Music, TvType.Audio, TvType.AudioBook, TvType.Podcast -> CloudStreamTvType.Music
    else -> CloudStreamTvType.Other
}
