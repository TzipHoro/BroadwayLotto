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
import com.broadwaylotto.model.Repo
import com.broadwaylotto.sites.Sites
import org.json.JSONObject

private const val LUCKY_SEAT_ORIGIN = "https://www.luckyseat.com/"

/** True for links that should open in [SiteBrowser]: Lucky Seat (its session carries over) and Broadway Direct entry pages (pre-filled). */
fun opensInApp(url: String) = isLuckySeat(url) || isBroadwayDirectEntry(url)

private fun isLuckySeat(url: String) = Uri.parse(url).host?.endsWith("luckyseat.com") == true

private fun isBroadwayDirectEntry(url: String): Boolean {
    val u = Uri.parse(url)
    return u.host == "lottery.broadwaydirect.com" && u.path.orEmpty().startsWith("/enter-lottery")
}

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
    var ready by remember(url) { mutableStateOf(!isLuckySeat(url)) }
    // Broadway Direct has no sign-in; its entry form is filled from the saved profile instead.
    val fillScript = remember(url) {
        if (isBroadwayDirectEntry(url)) broadwayDirectFillScript(Repo.get(context).profile.value) else null
    }
    LaunchedEffect(url) {
        if (isLuckySeat(url)) {
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
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { configure(webView, request, session, fillScript) { loading = false }; webView })
            }
        }
    }
}

/**
 * Fills Broadway Direct's entry form from the saved profile: name, ticket quantity (the form offers 0 to
 * 2), email, date of birth, country and zip. Deliberately NOT touched: the "I agree to the Terms &
 * Conditions" box, the Cloudflare Turnstile bot check and the Enter button, which stay with the user.
 * Values are written the way a real edit would be (native setter plus input/change events) so the page's
 * own scripts notice them, and it scrolls the agreement box into view. The form is waited for, up to ~10s.
 */
private fun broadwayDirectFillScript(p: com.broadwaylotto.model.Profile): String {
    val dob = p.dob.split('-')
    val values = JSONObject()
        .put("first", p.firstName).put("last", p.lastName).put("email", p.email)
        .put("qty", minOf(p.tickets, 2).toString())
        .put("month", dob.getOrNull(1).orEmpty()).put("day", dob.getOrNull(2).orEmpty()).put("year", dob.getOrNull(0).orEmpty())
        .put("zip", p.zip).put("country", p.country)
    return """
        (function(v) {
          var tries = 0;
          function setVal(id, val) {
            var el = document.getElementById(id);
            if (!el || !val) return;
            var proto = el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
            Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, val);
            el.dispatchEvent(new Event('input', {bubbles: true}));
            el.dispatchEvent(new Event('change', {bubbles: true}));
          }
          function pickCountry(name) {
            var el = document.getElementById('dlslot_country');
            if (!el) return;
            for (var i = 0; i < el.options.length; i++) {
              if (el.options[i].text.trim().toUpperCase() === name.toUpperCase()) {
                el.selectedIndex = i;
                el.dispatchEvent(new Event('change', {bubbles: true}));
                return;
              }
            }
          }
          (function step() {
            var form = document.querySelector('form[name=dlslot-entry-form]');
            if (!form) { if (++tries < 40) setTimeout(step, 250); return; }
            setVal('dlslot_name_first', v.first);
            setVal('dlslot_name_last', v.last);
            setVal('dlslot_ticket_qty', v.qty);
            setVal('dlslot_email', v.email);
            setVal('dlslot_dob_month', v.month);
            setVal('dlslot_dob_day', v.day);
            setVal('dlslot_dob_year', v.year);
            setVal('dlslot_zip', v.zip);
            if (v.country) pickCountry(v.country);
            var agree = document.getElementById('dlslot_agree');
            if (agree) agree.scrollIntoView({block: 'center'});
          })();
        })($values);
    """.trimIndent()
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
private fun configure(webView: WebView, request: PageRequest, session: Map<String, String>?, fillScript: String?, onLoaded: () -> Unit) {
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
                fillScript?.let { view.evaluateJavascript(it, null) }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            // Stay in the app for the sites we host and Google's pages (reCAPTCHA); all of Broadway Direct's
            // lottery site, not just the entry form, so its confirmation page after you enter stays here too.
            val host = req.url.host.orEmpty()
            if (opensInApp(req.url.toString()) || host == "lottery.broadwaydirect.com" || host.endsWith("google.com")) return false
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
