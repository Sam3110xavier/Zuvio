package com.nuvio.app.features.cloudstream

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudStreamRuntimeAbiTest {
    @Test
    fun upstreamHostCompatibilityClassesResolve() {
        assertEquals("com.lagradost.cloudstream3.AcraApplication", AcraApplication::class.java.name)
        assertEquals("com.lagradost.cloudstream3.CloudStreamApp", CloudStreamApp::class.java.name)
        assertEquals("com.lagradost.cloudstream3.ui.home.HomeViewModel", HomeViewModel::class.java.name)
        assertEquals("com.lagradost.cloudstream3.utils.DataStoreHelper", DataStoreHelper::class.java.name)
        assertNotNull(Class.forName("com.lagradost.cloudstream3.plugins.PluginManager\$PluginData"))
    }

    @Test
    fun cloudStreamMainActivityFacadeDoesNotPretendToBeAndroidActivity() {
        assertFalse(Activity::class.java.isAssignableFrom(MainActivity::class.java))
    }

    @Test
    fun pluginExposesAllCloudStreamLoadOverloads() {
        assertNotNull(Plugin::class.java.getMethod("load", Activity::class.java))
        assertNotNull(Plugin::class.java.getMethod("load", Context::class.java))
    }

    @Test
    fun commonActivityExposesExpectedMethods() {
        val type = CommonActivity::class.java
        assertNotNull(type.getMethod("getActivity"))
        assertNotNull(type.getMethod("showToast", String::class.java, Integer::class.java))
        assertNotNull(type.getMethod("showToast", Activity::class.java, String::class.java, Integer::class.java))
    }

    @Test
    fun cloudStreamAppExposesStaticHostAbi() {
        val type = CloudStreamApp::class.java
        assertNotNull(type.getMethod("getActivity", android.content.Context::class.java))
        assertNotNull(type.getMethod("getKey", String::class.java))
        assertNotNull(type.getMethod("getKey", String::class.java, Object::class.java))
        assertNotNull(type.getMethod("setKey", String::class.java, Object::class.java))
        assertNotNull(type.getMethod("setKey", String::class.java, String::class.java, Object::class.java))
        assertNotNull(type.getMethod("openBrowser", String::class.java, Boolean::class.javaPrimitiveType!!))
    }

    @Test
    fun acraApplicationExposesStaticHostAbi() {
        val type = AcraApplication::class.java
        assertNotNull(type.getMethod("getActivity"))
        assertNotNull(type.getMethod("setActivity", Activity::class.java))
        assertNotNull(type.getMethod("getKey", String::class.java, Object::class.java))
        assertNotNull(type.getMethod("getKey", String::class.java, String::class.java, Object::class.java))
    }

    @Test
    fun resumeWatchingCompatibilityReturnsAnEmptyHostOwnedState() = runBlocking {
        assertTrue(HomeViewModel.getResumeWatching().isNullOrEmpty())
    }

    @Test
    fun cloudStreamNetworkTopLevelAbiUsesOfficialFacadeClass() {
        val facade = Class.forName("com.lagradost.cloudstream3.network.CloudflareKillerKt")
        assertNotNull(facade.getMethod("getHeaders", Map::class.java, Map::class.java))
        assertNotNull(CloudflareKiller::class.java.getMethod("getCookieHeaders", String::class.java))
    }

    @Test
    fun dataStoreExposesExpectedExtensionMethods() {
        val type = DataStore::class.java
        assertNotNull(type.getMethod("getDefaultSharedPrefs", android.content.Context::class.java))
        assertNotNull(type.getMethod("containsKey", android.content.Context::class.java, String::class.java))
        assertNotNull(type.getMethod("removeKey", android.content.Context::class.java, String::class.java))
        assertNotNull(type.getMethod("setKey", android.content.Context::class.java, String::class.java, Object::class.java))
    }

    @Test
    fun pluginManagerOnlineAndLocalCollectionsAreSafeWhenEmpty() {
        assertTrue(PluginManager.pluginsOnline.isEmpty())
        assertTrue(PluginManager.pluginsLocal.isEmpty())
    }
}
