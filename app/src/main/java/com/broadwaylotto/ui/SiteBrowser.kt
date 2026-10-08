package com.broadwaylotto.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.broadwaylotto.sites.Sites
import org.json.JSONObject

private const val LUCKY_SEAT_ORIGIN = "https://www.luckyseat.com/"

/** True for links that should open in [SiteBrowser] so the Lucky Seat session carries over. */
fun opensInApp(url: String) = Uri.parse(url).host?.endsWith("luckyseat.com") == true

/**
 * In-app browser. For Lucky Seat it starts already signed in: the stored session is written into the
 * page's *session* storage (the same place the site puts it after a normal sign-in) before the real
 * page loads, so nothing is persisted to disk by the browser and it disappears when this screen closes.
 * Navigation off luckyseat.com opens in the user's normal browser instead.
 */
/**
 * A page to open. [preselect] are the entry-form checkbox ids (Lucky Seat performance ids) to tick and
 * [tickets] the ticket count to set, so the only thing left for the user is the CAPTCHA.
 */
data class PageRequest(val url: String, val preselect: List<String> = emptyList(), val tickets: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteBrowser(request: PageRequest, onClose: () -> Unit) {
    val url = request.url
    val context = LocalContext.current
    // Loading the session can involve one quick network call (see LuckySeatAuth.browserSession),
    // so the page waits until it's ready.
    var session by remember(url) { mutableStateOf<Map<String, String>?>(null) }
    var ready by remember(url) { mutableStateOf(!opensInApp(url)) }
    LaunchedEffect(url) {
        if (opensInApp(url)) {
            session = Sites.luckySeatAuth.browserSession()
            ready = true
        }
    }
    var loading by remember { mutableStateOf(true) }
    val webView = remember { WebView(context) }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(Uri.parse(url).host.orEmpty(), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Cream) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
            if (ready) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { configure(webView, request, session) { loading = false }; webView })
            }
        }
    }
}

/**
 * Ticks the given performance checkboxes and sets the ticket count on Lucky Seat's entry form, then
 * scrolls to the CAPTCHA. It only fills fields in; it never touches the CAPTCHA or Submit.
 * The form is built by Angular after the page loads, so this polls (up to ~20s) until it appears.
 * Checkbox ids are performance ids; the count uses the form's own +/- buttons so its logic runs.
 */
private fun fillEntryFormScript(ids: List<String>, tickets: Int): String {
    val idList = ids.joinToString(",") { JSONObject.quote(it) }
    return """
        (function(ids, want) {
          var tries = 0;
          (function step() {
            var plus = document.querySelector('.form-number-plus');
            var minus = document.querySelector('.form-number-minus');
            var num = document.querySelector('input[type=number]');
            var boxes = ids.map(function(id) { return document.getElementById(id); }).filter(Boolean);
            if (plus && minus && num && boxes.length > 0) {
              boxes.forEach(function(b) { if (!b.checked) b.click(); });
              for (var i = 0; i < 10 && want > 0 && (parseInt(num.value || '0', 10) < want); i++) plus.click();
              for (var j = 0; j < 10 && want > 0 && (parseInt(num.value || '0', 10) > want); j++) minus.click();
              var captcha = document.getElementById('capcha_element');
              if (captcha) captcha.scrollIntoView({block: 'center'});
              return;
            }
            if (++tries < 80) setTimeout(step, 250);
          })();
        })([$idList], $tickets);
    """.trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
private fun configure(webView: WebView, request: PageRequest, session: Map<String, String>?, onLoaded: () -> Unit) {
    val url = request.url
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    // reCAPTCHA is served from google.com and needs its own cookies to work.
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

    var seeded = session == null
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, finishedUrl: String) {
            if (!seeded) {
                seeded = true
                val script = session!!.entries.joinToString(";") {
                    "sessionStorage.setItem(${JSONObject.quote(it.key)},${JSONObject.quote(it.value)})"
                }
                view.evaluateJavascript(script) { view.loadUrl(url) }
            } else {
                onLoaded()
                // Safe to repeat: ticking only unticked boxes and matching the count is idempotent,
                // and it covers the site doing a full reload on its way to the entry page.
                if (request.preselect.isNotEmpty()) {
                    view.evaluateJavascript(fillEntryFormScript(request.preselect, request.tickets), null)
                }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            if (opensInApp(req.url.toString()) || req.url.host?.endsWith("google.com") == true) return false
            view.context.startActivity(Intent(Intent.ACTION_VIEW, req.url))
            return true
        }
    }

    if (session != null) {
        // A blank page on the site's origin, so the storage we seed belongs to luckyseat.com.
        webView.loadDataWithBaseURL(LUCKY_SEAT_ORIGIN, "<html></html>", "text/html", "utf-8", null)
    } else {
        webView.loadUrl(url)
    }
}
