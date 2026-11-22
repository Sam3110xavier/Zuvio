package com.nuvio.app.features.collection

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionCatalogResolverTest {
    @Test
    fun exactAddonIdWinsWhenMultipleAddonsExposeSameCatalog() {
        val unrelatedAddon = addon(
            manifestId = "other.addon",
            manifestUrl = "https://other.example/manifest.json",
        )
        val declaredAddon = addon(
            manifestId = "target.addon",
            manifestUrl = "https://target.example/manifest.json",
        )

        val resolved = listOf(unrelatedAddon, declaredAddon).findCollectionCatalog(source())

        assertEquals("target.addon", resolved?.addon?.manifest?.id)
        assertEquals("https://target.example/manifest.json", resolved?.addon?.manifestUrl)
    }

    @Test
    fun declaredAddonWithoutCatalogDoesNotFallBackToUnrelatedAddon() {
        val declaredAddon = addon(
            manifestId = "target.addon",
            manifestUrl = "https://target.example/manifest.json",
            catalogs = emptyList(),
        )
        val unrelatedAddon = addon(
            manifestId = "other.addon",
            manifestUrl = "https://other.example/manifest.json",
        )

        assertNull(listOf(declaredAddon, unrelatedAddon).findCollectionCatalog(source()))
    }

    @Test
    fun legacyAddonIdFallsBackWhenThereIsExactlyOneCatalogMatch() {
        val onlyMatchingAddon = addon(
            manifestId = "renamed.addon",
            manifestUrl = "https://renamed.example/manifest.json",
        )

        val resolved = listOf(onlyMatchingAddon).findCollectionCatalog(source())

        assertEquals("renamed.addon", resolved?.addon?.manifest?.id)
    }

    @Test
    fun legacyAddonIdDoesNotChooseArbitrarilyBetweenDuplicateCatalogs() {
        val firstAddon = addon(
            manifestId = "first.addon",
            manifestUrl = "https://first.example/manifest.json",
        )
        val secondAddon = addon(
            manifestId = "second.addon",
            manifestUrl = "https://second.example/manifest.json",
        )

        assertNull(listOf(firstAddon, secondAddon).findCollectionCatalog(source()))
    }

    private fun source() = CollectionCatalogSource(
        addonId = "target.addon",
        type = "movie",
        catalogId = "popular",
    )

    private fun addon(
        manifestId: String,
        manifestUrl: String,
        catalogs: List<AddonCatalog> = listOf(
            AddonCatalog(
                type = "movie",
                id = "popular",
                name = "Popular",
            ),
        ),
    ) = ManagedAddon(
        manifestUrl = manifestUrl,
        manifest = AddonManifest(
            id = manifestId,
            name = manifestId,
            description = "",
            version = "1.0.0",
            resources = emptyList(),
            types = listOf("movie"),
            catalogs = catalogs,
            transportUrl = manifestUrl,
        ),
    )
}
