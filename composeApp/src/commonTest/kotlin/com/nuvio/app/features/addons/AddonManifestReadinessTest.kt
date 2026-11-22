package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonManifestReadinessTest {
    @Test
    fun everyEnabledManifestMustFinishBeforeAddonStateIsReady() {
        val addons = listOf(
            managedAddon("loaded", enabled = true, refreshing = false, hasManifest = true),
            managedAddon("pending", enabled = true, refreshing = true, hasManifest = false),
        )

        assertTrue(addons.hasPendingEnabledManifests())
    }

    @Test
    fun disabledAddonRefreshDoesNotBlockReadiness() {
        val addons = listOf(
            managedAddon("enabled", enabled = true, refreshing = false, hasManifest = true),
            managedAddon("disabled", enabled = false, refreshing = true, hasManifest = false),
        )

        assertFalse(addons.hasPendingEnabledManifests())
    }

    @Test
    fun allEnabledManifestFailuresAreTerminalForReadiness() {
        val addons = listOf(
            managedAddon("failed", enabled = true, refreshing = false, hasManifest = false),
            managedAddon("loaded", enabled = true, refreshing = false, hasManifest = true),
        )

        assertFalse(addons.hasPendingEnabledManifests())
    }

    private fun managedAddon(
        url: String,
        enabled: Boolean,
        refreshing: Boolean,
        hasManifest: Boolean,
    ) = ManagedAddon(
        manifestUrl = "https://$url.example/manifest.json",
        manifest = if (hasManifest) {
            AddonManifest(
                id = url,
                name = url,
                description = "",
                version = "1.0.0",
                resources = emptyList(),
                types = emptyList(),
                transportUrl = "https://$url.example/manifest.json",
            )
        } else {
            null
        },
        enabled = enabled,
        isRefreshing = refreshing,
    )
}
