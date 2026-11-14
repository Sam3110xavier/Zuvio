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
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.atomic.AtomicInteger

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/** Binary-compatible top-level helper. Upstream extensions link to CloudflareKillerKt.getHeaders. */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>,
): Headers {
    val cookieMap = if (cookie.isNotEmpty()) {
        mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                it.key + "=" + it.value + ";"
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

        // AnimePahe's plugin may check its catalogue while Home is being populated.
        // Do not open an interactive challenge until the user explicitly resolves
        // a stream from AnimePahe. The counter survives coroutine/thread switches
        // used by OkHttp's interceptor and supports nested link callbacks.
        private val animePaheStreamResolutionDepth = AtomicInteger(0)

        private val animePaheRelatedHosts = listOf(
            "animepahe",
            "pahe.win",
            "kwik.cx",
        )

        private fun isAnimePaheRelatedHost(host: String): Boolean =
            animePaheRelatedHosts.any { domain ->
                host.equals(domain, ignoreCase = true) ||
                    host.endsWith(".$domain", ignoreCase = true) ||
                    (domain == "animepahe" && host.contains("animepahe", ignoreCase = true))
            }

        suspend fun <T> withAnimePaheStreamResolution(block: suspend () -> T): T {
            animePaheStreamResolutionDepth.incrementAndGet()
            try {
                return block()
            } finally {
                animePaheStreamResolutionDepth.decrementAndGet()
            }
        }

        fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(';').mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1).trim()
                (key to value).takeIf { key.isNotBlank() && value.isNotBlank() }
            }.toMap()
    }

    init {
        safe { CookieManager.getInstance().removeAllCookies(null) }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) }.orEmpty()
        return getHeaders(userAgentHeaders, savedCookies[URI(url).host].orEmpty())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        savedCookies[request.url.host]?.let { return@runBlocking proceed(request, it) }

        val response = chain.proceed(request)
        if (response.header("Server") !in cloudflareServers || response.code !in errorCodes) {
            return@runBlocking response
        }
        if (
            isAnimePaheRelatedHost(request.url.host) &&
            animePaheStreamResolutionDepth.get() == 0
        ) {
            // A homepage/catalogue/details request must not interrupt app startup
            // with AnimePahe's verification WebView. Leave the 403/503 with the
            // caller; a user-initiated AnimePahe loadLinks call can retry interactively.
            Log.i(TAG, "Deferring AnimePahe Cloudflare challenge outside stream resolution: ${request.url.host}")
            return@runBlocking response
        }
        response.close()
        bypassCloudflare(request)?.let {
            Log.d(TAG, "Succeeded bypassing Cloudflare: ${request.url}")
            return@runBlocking it
        }

        debugWarning({ true }) { "Failed Cloudflare challenge at: ${request.url}" }
        chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? = safe {
        CookieManager.getInstance()?.getCookie(url)
    }

    private fun trySolveWithSavedCookies(request: Request): Boolean =
        getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentHeaders = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        }.orEmpty()
        val headers = getHeaders(request.headers.toMap() + userAgentHeaders, cookies + request.cookies)
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
        return savedCookies[request.url.host]?.let { proceed(request, it) }
    }
}
