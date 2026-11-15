package com.lagradost.cloudstream3.plugins

import java.io.File

/** Host-side registration payload used by Zuvio's DEX loader. */
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int,
)

/**
 * CloudStream PluginManager compatibility surface.
 * Zuvio owns repository/download state; extension calls cannot mutate host state.
 */
object PluginManager {
    /** Upstream exposes this nested type as PluginManager.PluginData. */
    data class PluginData(
        val name: String = "",
        val url: String = "",
        val internalName: String = "",
        val version: Int = 0,
    )

    private val localPluginData = linkedMapOf<String, PluginData>()
    private val onlinePluginData = linkedMapOf<String, PluginData>()
    private val loadedPlugins = linkedMapOf<String, BasePlugin>()

    @Volatile
    var currentlyLoading: String? = null

    val plugins: Map<String, BasePlugin>
        get() = synchronized(this) { loadedPlugins.toMap() }

    val urlPlugins: Map<String, BasePlugin>
        get() = synchronized(this) {
            onlinePluginData.entries.mapNotNull { (filePath, data) ->
                loadedPlugins[filePath]?.let { data.url to it }
            }.toMap()
        }

    val pluginsLocal: Array<PluginData>
        get() = synchronized(this) { localPluginData.values.toTypedArray() }

    val pluginsOnline: Array<PluginData>
        get() = synchronized(this) { onlinePluginData.values.toTypedArray() }

    val loadedLocalPlugins: Boolean
        get() = synchronized(this) { localPluginData.isNotEmpty() }

    val loadedOnlinePlugins: Boolean
        get() = synchronized(this) { onlinePluginData.isNotEmpty() }

    fun register(data: com.lagradost.cloudstream3.plugins.PluginData, plugin: BasePlugin) = synchronized(this) {
        loadedPlugins[data.filePath] = plugin
        val compatibleData = PluginData(
            name = data.internalName,
            url = data.url.orEmpty(),
            internalName = data.internalName,
            version = data.version,
        )
        if (data.isOnline) onlinePluginData[data.filePath] = compatibleData
        else localPluginData[data.filePath] = compatibleData
    }

    fun unregister(filePath: String) = synchronized(this) {
        loadedPlugins.remove(filePath)
        localPluginData.remove(filePath)
        onlinePluginData.remove(filePath)
    }

    fun unloadPlugin(filePath: String) = unregister(filePath)

    /** Nuvio owns installed packages; plugins may not delete files from the host. */
    suspend fun deletePlugin(file: File): Boolean = false

    fun clear() = synchronized(this) {
        currentlyLoading = null
        loadedPlugins.clear()
        localPluginData.clear()
        onlinePluginData.clear()
    }
}
