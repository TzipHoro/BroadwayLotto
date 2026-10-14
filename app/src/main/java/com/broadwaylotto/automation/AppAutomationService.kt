package com.broadwaylotto.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.broadwaylotto.engine.Notifier
import com.broadwaylotto.model.Repo
import com.broadwaylotto.model.Site
import com.broadwaylotto.model.Slot
import com.broadwaylotto.model.Watch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

const val TODAYTIX_PACKAGE = "com.todaytix.TodayTix"
const val HAMILTON_PACKAGE = "com.hamilton.app"

/** The Android app whose screens we drive to enter [site]'s lotteries, or null if it has none. */
fun appPackageFor(site: Site): String? = when (site) {
    Site.TODAYTIX -> TODAYTIX_PACKAGE
    Site.HAMILTON -> HAMILTON_PACKAGE
    else -> null
}

/** True for sites whose lotteries only exist in a phone app, entered by tapping through it. */
val Site.isApp: Boolean get() = appPackageFor(this) != null

/** What to enter: one app's shows the user picked, their availability, and how many tickets. */
data class AppJob(val site: Site, val shows: List<Watch>, val defaults: Set<Slot>, val tickets: Int)

/**
 * Accessibility service that taps through the lottery apps (TodayTix, Hamilton). Android delivers
 * only those packages' screens and notifications to it (see res/xml/app_automation_service.xml), and
 * it does nothing until the user starts a run from this app (see [AppLauncher]).
 */
class AppAutomationService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        instance = this
        _connected.value = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _connected.value = false
        _running.value = false
        scope.cancel()
        return super.onUnbind(intent)
    }

    private val lastAlert = mutableMapOf<String, Long>()

    /**
     * The only event we react to: a notification posted by one of the lottery apps themselves. If it
     * mentions one of the user's shows for that app and sounds like a lottery opening, we raise our
     * own alert; tapping it starts the entry. Screens are never read from here, only during a run.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        val fromPackage = event.packageName?.toString() ?: return
        if (fromPackage != TODAYTIX_PACKAGE && fromPackage != HAMILTON_PACKAGE) return

        val extras = (event.parcelableData as? android.app.Notification)?.extras
        val text = buildString {
            event.text.forEach { append(it).append(' ') }
            listOf(android.app.Notification.EXTRA_TITLE, android.app.Notification.EXTRA_TEXT, android.app.Notification.EXTRA_BIG_TEXT)
                .forEach { key -> extras?.getCharSequence(key)?.let { append(it).append(' ') } }
        }.lowercase()
        if (OPEN_WORDS.none { it in text }) return

        val now = System.currentTimeMillis()
        Repo.get(this).watches.value
            .filter { it.enabled && appPackageFor(it.site) == fromPackage }
            // Hamilton's own reminders say "Hamilton" or just "lottery"; its app only has the one show.
            .filter { it.title.trim().lowercase() in text || (it.site == Site.HAMILTON && "lottery" in text) }
            .forEach { watch ->
                // An app can post the same alert more than once; one of ours per show per 10 minutes.
                val key = "${watch.site}|${watch.title}"
                if (now - (lastAlert[key] ?: 0L) < 10 * 60_000) return@forEach
                lastAlert[key] = now
                Notifier.appLotteryOpen(this, watch.site, watch.title)
            }
    }

    override fun onInterrupt() = Unit

    fun start(job: AppJob) {
        if (_running.value) return
        _running.value = true
        scope.launch {
            try {
                when (job.site) {
                    Site.HAMILTON -> HamiltonRunner(this@AppAutomationService, job).run()
                    else -> TodayTixRunner(this@AppAutomationService, job).run()
                }
            } finally {
                _running.value = false
            }
        }
    }

    companion object {
        @Volatile var instance: AppAutomationService? = null
            private set

        private val _connected = MutableStateFlow(false)
        /** True while the user has the accessibility service switched on in Android settings. */
        val connected: StateFlow<Boolean> = _connected

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _progress = MutableStateFlow("")
        val progress: StateFlow<String> = _progress
        internal fun say(text: String) { _progress.value = text }

        /** Words that make an app's notification look like "a lottery you wanted is open". */
        private val OPEN_WORDS = listOf("lottery", "enter", "open", "drawing", "win")
    }
}

/** Starts a run. Must be called while this app is in the foreground (it launches the lottery app). */
object AppLauncher {
    /**
     * Returns a user-readable problem, or null if the run started. With [onlyTitle], enters just that
     * show (from its card or its alert); otherwise every selected show for [site].
     */
    fun start(context: Context, repo: Repo, site: Site, onlyTitle: String? = null): String? {
        val pkg = appPackageFor(site) ?: return "${site.label} can't be entered from here."
        val service = AppAutomationService.instance
            ?: return "Turn on \"Broadway Lotto\" in Android's Accessibility settings first."
        if (AppAutomationService.running.value) return "A run is already in progress."

        val defaults = repo.defaults.value
        val shows = repo.watches.value.filter {
            it.site == site && it.enabled && it.effectiveSlots(defaults).isNotEmpty() &&
                (onlyTitle == null || it.title.trim().equals(onlyTitle.trim(), ignoreCase = true))
        }
        if (shows.isEmpty()) return "Add a ${site.label} show and tick some days and times first."

        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "The ${site.label} app isn't installed."
        context.startActivity(launch)
        service.start(AppJob(site, shows, defaults, repo.profile.value.tickets))
        return null
    }
}
