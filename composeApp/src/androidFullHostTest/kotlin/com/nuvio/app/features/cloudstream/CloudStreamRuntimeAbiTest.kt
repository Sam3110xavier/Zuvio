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
import com.lagradost.cloudstream3.network.CloudStreamCallContextRegistry
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

    @Test
    fun cloudflarePermissionIsCapturedPerOkHttpCallAcrossDispatcherThreads() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val observed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            val client = OkHttpClient.Builder()
                .eventListenerFactory(CloudStreamCallContextRegistry.eventListenerFactory())
                .addInterceptor { chain ->
                    val label = chain.request().header("X-Test-Call")!!
                    observed[label] = CloudflareKiller.isInteractiveVerificationAllowed(chain.call())
                    chain.proceed(chain.request())
                }
                .build()

            suspend fun request(label: String, allowInteractiveVerification: Boolean) {
                CloudflareKiller.withRequestContext(
                    pluginId = label,
                    allowInteractiveVerification = allowInteractiveVerification,
                ) {
                    val call = client.newCall(
                        Request.Builder()
                            .url(server.url("/$label"))
                            .header("X-Test-Call", label)
                            .build(),
                    )
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, error: java.io.IOException) {
                                continuation.resumeWith(Result.failure(error))
                            }

                            override fun onResponse(call: Call, response: Response) {
                                response.close()
                                continuation.resumeWith(Result.success(Unit))
                            }
                        })
                    }
                }
            }

            coroutineScope {
                listOf(
                    async { request("home", allowInteractiveVerification = false) },
                    async { request("playback", allowInteractiveVerification = true) },
                ).awaitAll()
            }

            assertEquals(false, observed["home"])
            assertEquals(true, observed["playback"])
        } finally {
            server.shutdown()
        }
    }
}
