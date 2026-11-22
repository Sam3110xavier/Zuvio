package com.nuvio.app.features.collection

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons

internal data class ResolvedCollectionCatalog(
    val addon: ManagedAddon,
    val catalog: AddonCatalog,
)

internal fun List<ManagedAddon>.findCollectionCatalog(
    source: CollectionCatalogSource,
): ResolvedCollectionCatalog? {
    val activeAddons = enabledAddons()
    val declaredAddon = activeAddons.firstOrNull { it.manifest?.id == source.addonId }
    if (declaredAddon != null) {
        val declaredCatalog = declaredAddon.manifest?.catalogs?.findSourceCatalog(source)
        return declaredCatalog?.let { catalog ->
            ResolvedCollectionCatalog(addon = declaredAddon, catalog = catalog)
        }
    }

    // Imported/legacy collections may refer to an old addon ID. A fallback is safe only
    // when exactly one enabled addon declares this catalog; choosing the first match can send
    // the request to an unrelated addon and silently produce an empty catalog.
    val fallbackMatches = activeAddons.mapNotNull { addon ->
        val catalog = addon.manifest?.catalogs?.findSourceCatalog(source) ?: return@mapNotNull null
        ResolvedCollectionCatalog(addon = addon, catalog = catalog)
    }
    return fallbackMatches.singleOrNull()
}

internal fun List<AvailableCatalog>.findAvailableCatalog(
    source: CollectionCatalogSource,
): AvailableCatalog? {
    val declaredCatalogs = filter { it.addonId == source.addonId }
    return declaredCatalogs.findSourceCatalog(source)
        ?: firstOrNull { it.catalogId == source.catalogId && it.type == source.type }
}

private fun List<AddonCatalog>.findSourceCatalog(source: CollectionCatalogSource): AddonCatalog? =
    find { it.id == source.catalogId && it.type == source.type }
        ?: find { it.id == source.catalogId.substringBefore(",") && it.type == source.type }

private fun List<AvailableCatalog>.findSourceCatalog(source: CollectionCatalogSource): AvailableCatalog? =
    find { it.catalogId == source.catalogId && it.type == source.type }
        ?: find { it.catalogId == source.catalogId.substringBefore(",") && it.type == source.type }
