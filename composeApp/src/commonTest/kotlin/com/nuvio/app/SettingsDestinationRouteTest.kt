package com.nuvio.app

import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.navigation.CloudStreamSettingsRoute
import com.nuvio.app.navigation.PluginsSettingsRoute
import com.nuvio.app.navigation.SettingsPageRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsDestinationRouteTest {
    @Test
    fun cloudStreamPageUsesDedicatedDestination() {
        assertEquals(
            CloudStreamSettingsRoute(title = "CloudStream"),
            settingsDestinationRoute(SettingsPage.CloudStream.name, "CloudStream"),
        )
    }

    @Test
    fun pluginsPageUsesDedicatedDestination() {
        assertEquals(
            PluginsSettingsRoute(title = "Plugins"),
            settingsDestinationRoute(SettingsPage.Plugins.name, "Plugins"),
        )
    }

    @Test
    fun otherSettingsPagesKeepGenericDestination() {
        assertEquals(
            SettingsPageRoute(pageName = "ContentDiscovery", title = "Content discovery"),
            settingsDestinationRoute(SettingsPage.ContentDiscovery.name, "Content discovery"),
        )
    }
}
