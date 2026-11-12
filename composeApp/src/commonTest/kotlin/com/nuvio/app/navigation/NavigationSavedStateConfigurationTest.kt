package com.nuvio.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertNotNull

@OptIn(ExperimentalSerializationApi::class)
class NavigationSavedStateConfigurationTest {
    @Test
    fun whatsNewRouteIsRegisteredForSavedStateSerialization() {
        val serializer = navigationSavedStateConfiguration.serializersModule
            .getPolymorphic(NavKey::class, WhatsNewSettingsRoute("What's New"))

        assertNotNull(serializer)
    }

    @Test
    fun pluginsRouteIsRegisteredForSavedStateSerialization() {
        val serializer = navigationSavedStateConfiguration.serializersModule
            .getPolymorphic(NavKey::class, PluginsSettingsRoute("Plugins"))

        assertNotNull(serializer)
    }

    @Test
    fun cloudStreamRouteIsRegisteredForSavedStateSerialization() {
        val serializer = navigationSavedStateConfiguration.serializersModule
            .getPolymorphic(NavKey::class, CloudStreamSettingsRoute("CloudStream"))

        assertNotNull(serializer)
    }
}