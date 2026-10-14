package com.broadwaylotto.sites

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetches pages through a real (hidden) Android WebView instead of a bare HTTP client.
 *
 * Both Broadway Direct and Lucky Seat reject non-browser clients with HTTP 403, so plain
 * HttpURLConnection can't read their listings. A WebView is the same browser engine the user would
 * open these sites in: it loads the site's origin once, then requests are made with fetch() from
 * that page, exactly as the site's own scripts do. It does not disguise itself as anything else.
 */
class WebFetcher(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var loadedOrigin: String? = null
    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<Pair<Int, String>>>()

    private inner class Bridge {
        @JavascriptInterface
        fun done(id: String, status: Int, body: String) {
            pending.remove(id)?.let { if (it.isActive) it.resume(status to body) }
        }
    }

    /** A non-200 reply. [status] lets callers react to 401 (signed out) and friends. */
    class HttpStatusException(val status: Int, url: String) : IOException("HTTP $status from $url")

    /** GET [url] (same origin as [origin]) and return the response body. Throws on non-200. */
    suspend fun get(origin: String, url: String, headers: Map<String, String> = emptyMap()): String =
        send(origin, url, "GET", headers, null)

    /** POST a JSON [body]. Nothing here logs the body, since it can carry a password. */
    suspend fun postJson(origin: String, url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        send(origin, url, "POST", headers + ("Content-Type" to "application/json"), body)

    private suspend fun send(origin: String, url: String, method: String, headers: Map<String, String>, body: String?): String =
        mutex.withLock {
            withContext(Dispatchers.Main) {
                // Try twice: a failure is often the page being replaced mid-request or a slow first load,
                // and a fresh load of the origin fixes that.
                var result = runCatching { attempt(origin, url, method, headers, body, fallback = false) }
                if (result.exceptionOrNull() is IOException) {
                    loadedOrigin = null
                    result = runCatching { attempt(origin, url, method, headers, body, fallback = true) }
                }
                val (status, text) = result.getOrThrow()
                when {
                    status != 200 -> throw HttpStatusException(status, url)
                    else -> text
                }
            }
        }

    /**
     * One load-then-fetch. [fallback] switches the starting page from a light one to the site's own
     * front page (slower, but it's what is known to work everywhere). Throws [IOException] with a
     * specific reason, never a bare timeout.
     */
    private suspend fun attempt(
        origin: String, url: String, method: String, headers: Map<String, String>, body: String?, fallback: Boolean,
    ): Pair<Int, String> {
        val page = if (fallback) origin else origin + (LIGHT_PAGES[origin] ?: "/robots.txt")
        val seconds = if (fallback) 45L else 20L
        val wv = withTimeoutOrNull(seconds * 1000) { load(origin, page) }
            ?: throw IOException("$page didn't finish loading within $seconds seconds")
        val (status, text) = withTimeoutOrNull(25_000) { request(wv, url, method, headers, body) }
            ?: throw IOException("no answer from ${url.take(80)} within 25 seconds")
        if (status == 0) throw IOException("network error ($text) fetching ${url.take(80)}")
        return status to text
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun load(origin: String, page: String): WebView {
        val wv = webView ?: WebView(appContext).also {
            it.settings.javaScriptEnabled = true
            it.settings.domStorageEnabled = true
            it.addJavascriptInterface(Bridge(), "BroadwayLotto")
            webView = it
        }
        // Reuse the page only if it's still on this origin: a redirect can have moved it elsewhere.
        if (loadedOrigin == origin && wv.url?.startsWith(origin) == true) return wv
        loadedOrigin = null
        suspendCancellableCoroutine { cont ->
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && cont.isActive) {
                        cont.resumeWithException(IOException("Couldn't load $origin: ${error.description}"))
                    }
                }
            }
            // Cancellation can arrive on any thread (WorkManager cancels from its own), but WebView
            // methods are main-thread only, so hop over before touching it.
            cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { wv.stopLoading() } }
            // Any page on the origin will do as the place to run fetch() from. A tiny one (see LIGHT_PAGES)
            // finishes quickly, unlike a full front page with bot checks, analytics and ads.
            wv.loadUrl(page)
        }
        loadedOrigin = origin
        return wv
    }

    private suspend fun request(
        wv: WebView, url: String, method: String, headers: Map<String, String>, body: String?,
    ): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            pending[id] = cont
            cont.invokeOnCancellation { pending.remove(id) }
            val options = JSONObject().put("method", method).put("headers", JSONObject(headers))
            if (body != null) options.put("body", body)
            wv.evaluateJavascript(
                "fetch(${JSONObject.quote(url)},$options)" +
                    ".then(function(r){return r.text().then(function(b){BroadwayLotto.done('$id',r.status,b)})})" +
                    ".catch(function(e){BroadwayLotto.done('$id',0,String(e))});",
                null,
            )
        }

    private companion object {
        /**
         * Small same-origin pages to start from, per site. Anything not listed uses /robots.txt.
         * Lucky Seat has no robots.txt (its address would load the whole app), but this API reply is tiny.
         */
        val LIGHT_PAGES = mapOf(
            "https://www.luckyseat.com" to "/api/showmanagement/getshowcategories",
        )
    }
}
