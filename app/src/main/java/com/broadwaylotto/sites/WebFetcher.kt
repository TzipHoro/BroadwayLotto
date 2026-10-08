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
import kotlinx.coroutines.withTimeout
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
            withTimeout(45_000) {
                withContext(Dispatchers.Main) {
                    val wv = load(origin)
                    val (status, text) = request(wv, url, method, headers, body)
                    when {
                        status == 0 -> throw IOException("network error: $text")
                        status != 200 -> throw HttpStatusException(status, url)
                        else -> text
                    }
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun load(origin: String): WebView {
        val wv = webView ?: WebView(appContext).also {
            it.settings.javaScriptEnabled = true
            it.settings.domStorageEnabled = true
            it.addJavascriptInterface(Bridge(), "BroadwayLotto")
            webView = it
        }
        if (loadedOrigin == origin) return wv
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
            cont.invokeOnCancellation { wv.stopLoading() }
            wv.loadUrl(origin)
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
}
