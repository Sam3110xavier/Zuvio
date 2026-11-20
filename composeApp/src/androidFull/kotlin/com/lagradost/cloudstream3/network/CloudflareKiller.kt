package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException


/**
 * NiceHttp creates each OkHttp Call synchronously on the plugin coroutine, then executes
 * its interceptors on OkHttp dispatcher threads. Capture the coroutine context at Call
 * creation time with EventListener.Factory so the interceptor can authorize that exact
 * Call instead of consulting process-wide playback state.
 */
private data class CloudStreamRequestContext(
    val pluginId: String,
    val allowInteractiveVerification: Boolean,
)

private val cloudStreamRequestContext = ThreadLocal<CloudStreamRequestContext?>()

private val interactiveChallengeInProgress = AtomicBoolean(false)
private val recentInteractiveChallenges = ConcurrentHashMap<String, Long>()
private const val INTERACTIVE_CHALLENGE_COOLDOWN_MS = 60_000L

/**
 * Context hand-off from NiceHttp's call-creation thread to the OkHttp interceptor thread.
 * The EventListener is created by OkHttpClient.newCall(), before enqueue()/execute() moves
 * interceptor execution to a dispatcher thread. Each Call gets its own immutable context.
 */
internal object CloudStreamCallContextRegistry {
    private val contexts = ConcurrentHashMap<Call, CloudStreamRequestContext>()

    fun eventListenerFactory(): EventListener.Factory = EventListener.Factory { call ->
        cloudStreamRequestContext.get()?.let { contexts[call] = it }
        object : EventListener() {
            override fun callEnd(call: Call) {
                contexts.remove(call)
            }

            override fun callFailed(call: Call, ioe: IOException) {
                contexts.remove(call)
            }
        }
    }

    fun isInteractiveVerificationAllowed(call: Call): Boolean =
        contexts[call]?.allowInteractiveVerification == true

    fun pluginId(call: Call): String? = contexts[call]?.pluginId
}

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/** Binary-compatible top-level helper. Upstream extensions link to CloudflareKillerKt.getHeaders. */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>,
): Headers {
    val cookieMap = if (cookie.isNotEmpty()) {
        mapOf(
            "Cookie" to cookie.entries.joinToString("; ") {
                it.key + "=" + it.value
            },
        )
    } else {
        emptyMap()
    }
    return (DEFAULT_HEADERS + headers + cookieMap).toHeaders()
}

/**
 * Binary-compatible CloudStream interceptor used by providers protected by Cloudflare.
 *
 * This follows CloudStream's host behaviour: ordinary responses stay on the caller's
 * chain, while a 403/503 Cloudflare response is retried after obtaining cf_clearance
 * cookies through the shared WebView resolver.
 */
@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val errorCodes = setOf(403, 503)
        private val cloudflareServers = setOf("cloudflare-nginx", "cloudflare")

        /**
         * Attach operation purpose to the coroutine that creates each NiceHttp Call.
         * OkHttp's EventListener.Factory snapshots this context into the Call itself,
         * so interceptor execution on dispatcher threads does not depend on ThreadLocal
         * propagation or a process-wide playback counter.
         */
        suspend fun <T> withRequestContext(
            pluginId: String?,
            allowInteractiveVerification: Boolean,
            block: suspend () -> T,
        ): T {
            val context = CloudStreamRequestContext(
                pluginId = pluginId?.takeIf(String::isNotBlank) ?: "unknown-plugin",
                allowInteractiveVerification = allowInteractiveVerification,
            )
            return withContext(cloudStreamRequestContext.asContextElement(context)) {
                block()
            }
        }

        internal fun isInteractiveVerificationAllowed(call: Call): Boolean =
            CloudStreamCallContextRegistry.isInteractiveVerificationAllowed(call)

        internal fun requestPluginId(call: Call): String? =
            CloudStreamCallContextRegistry.pluginId(call)

    fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(';').mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1).trim()
                (key to value).takeIf { key.isNotBlank() && value.isNotBlank() }
            }.toMap()
    }

    // Keep this cache for callers that inspect the last observed cookies, but never
    // trust it as the source of request cookies: CookieManager applies the real
    // domain/path/secure/expiry rules for each URL.
    val savedCookies: MutableMap<String, Map<String, String>> = ConcurrentHashMap()

    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) }.orEmpty()
        return getHeaders(userAgentHeaders, getApplicableWebViewCookies(url))
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        // Import cookies from the shared WebView store before the first network
        // attempt. Waiting until Cloudflare has already returned 403/503 can cause
        // an unnecessary challenge even when a valid clearance cookie exists.
        val request = withWebViewCookies(chain.request())
        val response = chain.proceed(request)
        if (response.header("Server") !in cloudflareServers || response.code !in errorCodes) {
            return@runBlocking response
        }
        val requestContext = CloudStreamCallContextRegistry.pluginId(chain.call())
        if (!CloudStreamCallContextRegistry.isInteractiveVerificationAllowed(chain.call())) {
            Log.i(
                TAG,
                "Deferring interactive Cloudflare verification for non-playback request " +
                    "plugin=${requestContext ?: "unscoped"} host=${request.url.host}",
            )
            return@runBlocking response
        }

        // Only one WebView challenge may be visible at a time. Suppress repeated
        // challenges for the same host for a short cooldown after a challenge starts.
        if (!interactiveChallengeInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "Cloudflare challenge already active; deferring host=${request.url.host}")
            return@runBlocking response
        }

        val now = System.currentTimeMillis()
        var claimedHostChallenge = false
        recentInteractiveChallenges.compute(request.url.host) { _, previous ->
            if (previous == null || now - previous >= INTERACTIVE_CHALLENGE_COOLDOWN_MS) {
                claimedHostChallenge = true
                now
            } else {
                previous
            }
        }
        if (!claimedHostChallenge) {
            interactiveChallengeInProgress.set(false)
            Log.i(TAG, "Cloudflare challenge cooldown active host=${request.url.host}")
            return@runBlocking response
        }

        try {
            Log.i(
                TAG,
                "Interactive Cloudflare verification allowed during playback resolution " +
                    "plugin=${requestContext ?: "unscoped"} host=${request.url.host}",
            )
            response.close()
            bypassCloudflare(request)?.let {
                Log.d(TAG, "Succeeded bypassing Cloudflare: ${request.url}")
                return@runBlocking it
            }

            debugWarning({ true }) { "Failed Cloudflare challenge at: ${request.url}" }
            chain.proceed(withWebViewCookies(request))
        } finally {
            interactiveChallengeInProgress.set(false)
        }
    }

    private fun getWebViewCookie(url: String): String? = safe {
        CookieManager.getInstance().getCookie(url)
    }

    /**
     * CookieManager returns only cookies applicable to [url], respecting the
     * WebView cookie store's domain, path, secure and expiry rules. Do not use
     * the per-interceptor cache as a substitute: it can outlive or out-scope a
     * WebView cookie.
     */
    private fun getApplicableWebViewCookies(url: String): Map<String, String> {
        val cookies = getWebViewCookie(url)?.let(::parseCookieMap).orEmpty()
        val host = runCatching { URI(url).host }.getOrNull()
        if (host != null && !cookies["cf_clearance"].isNullOrBlank()) {
            savedCookies[host] = cookies
            Log.d(TAG, "Found an applicable saved Cloudflare clearance cookie for host=$host")
        }
        return cookies
    }

    /**
     * Merge applicable WebView cookies before the first request. Keep existing
     * provider/session cookie values on name collisions; only Cloudflare cookies
     * (for example cf_clearance and __cf_bm) are refreshed from WebView.
     */
    private fun withWebViewCookies(request: Request): Request {
        val webViewCookies = getApplicableWebViewCookies(request.url.toString())
        if (webViewCookies.isEmpty()) return request

        val requestCookies = request.headers.values("Cookie")
            .flatMap { parseCookieMap(it).entries }
            .associate { it.key to it.value }
        val mergedCookies = mergeRequestAndWebViewCookies(requestCookies, webViewCookies)
        val cookieHeader = mergedCookies.entries.joinToString("; ") { (name, value) ->
            "$name=$value"
        }
        return request.newBuilder()
            .header("Cookie", cookieHeader)
            .build()
    }

    private fun mergeRequestAndWebViewCookies(
        requestCookies: Map<String, String>,
        webViewCookies: Map<String, String>,
    ): Map<String, String> {
        val cookiesToApply = webViewCookies.filter { (name, _) ->
            name !in requestCookies ||
                name.startsWith("cf_", ignoreCase = true) ||
                name.startsWith("__cf", ignoreCase = true)
        }
        return requestCookies + cookiesToApply
    }

    private fun trySolveWithSavedCookies(request: Request): Boolean =
        !getApplicableWebViewCookies(request.url.toString())["cf_clearance"].isNullOrBlank()

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentHeaders = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        }.orEmpty()
        // CookieManager has already filtered its cookies for this URL. Preserve
        // provider/session cookies on collisions, except for refreshed Cloudflare
        // challenge cookies.
        val webViewCookies = cookies + getApplicableWebViewCookies(request.url.toString())
        val mergedCookies = mergeRequestAndWebViewCookies(request.cookies, webViewCookies)
        val headers = getHeaders(request.headers.toMap() + userAgentHeaders, mergedCookies)
        return app.baseClient.newCall(request.newBuilder().headers(headers).build()).await()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        if (!trySolveWithSavedCookies(request)) {
            WebViewResolver(
                interceptUrl = Regex(".^"),
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex(".")),
            ).resolveUsingWebView(request.url.toString()) {
                trySolveWithSavedCookies(request)
            }
        }
        val currentCookies = getApplicableWebViewCookies(request.url.toString())
        if (currentCookies["cf_clearance"].isNullOrBlank()) return null
        // Persist a successful verification so the same WebView clearance can be
        // reused after the WebView closes and across subsequent app sessions.
        safe { CookieManager.getInstance().flush() }
        return proceed(request, currentCookies)
    }
}
