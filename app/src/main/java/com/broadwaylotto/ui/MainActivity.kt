package com.broadwaylotto.ui

import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.broadwaylotto.engine.Scheduler
import com.broadwaylotto.model.Repo
import com.broadwaylotto.model.Site
import com.broadwaylotto.model.SiteEntry
import com.broadwaylotto.model.Watch
import com.broadwaylotto.sites.Sites
import com.broadwaylotto.automation.AppLauncher
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    /** A link a notification asked us to open (see [EXTRA_OPEN_URL]); consumed by [App]. */
    private val incoming = mutableStateOf<PageRequest?>(null)

    /** "SITE|show title" a notification asked us to enter (see [EXTRA_APP_ENTER]). */
    private val incomingAppEntry = mutableStateOf<String?>(null)

    private fun Intent.toPageRequest() = getStringExtra(EXTRA_OPEN_URL)?.let {
        PageRequest(it, getStringArrayExtra(EXTRA_PRESELECT)?.toList().orEmpty(), getIntExtra(EXTRA_TICKETS, 0))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent.toPageRequest()
        incomingAppEntry.value = intent.getStringExtra(EXTRA_APP_ENTER)
    }

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
        const val EXTRA_APP_ENTER = "app_enter"
        const val EXTRA_PRESELECT = "preselect"
        const val EXTRA_TICKETS = "tickets"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming.value = intent?.toPageRequest()
        incomingAppEntry.value = intent?.getStringExtra(EXTRA_APP_ENTER)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        Scheduler.ensurePeriodic(this)
        val repo = Repo.get(this)
        setContent {
            LottoTheme {
                App(
                    repo, incoming.value, onIncomingHandled = { incoming.value = null },
                    incomingAppEntry = incomingAppEntry.value, onAppEntryHandled = { incomingAppEntry.value = null },
                )
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    SHOWS("Shows", Icons.Default.Star),
    ENTERED("Entered", Icons.Default.CheckCircle),
    ACTIVITY("Activity", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(
    repo: Repo,
    incoming: PageRequest?,
    onIncomingHandled: () -> Unit,
    incomingAppEntry: String?,
    onAppEntryHandled: () -> Unit,
) {
    val context = LocalContext.current

    // Hamilton's entry page has two "I accept / I have reviewed" checkboxes, and we tick them for the
    // user, so the first Hamilton run waits for an explicit OK (remembered afterwards).
    var consentFor by remember { mutableStateOf<Pair<Site, String>?>(null) }
    val hamiltonConsent by repo.hamiltonConsent.collectAsState()

    // Enter one show of an app-only site (TodayTix, Hamilton): from its card's button or its alert.
    fun enterApp(site: Site, title: String) {
        if (site == Site.HAMILTON && !hamiltonConsent) {
            consentFor = site to title
            return
        }
        AppLauncher.start(context, repo, site, title)
            ?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    consentFor?.let { (site, title) ->
        HamiltonConsentDialog(
            onAgree = { repo.setHamiltonConsent(true); consentFor = null; enterApp(site, title) },
            onDismiss = { consentFor = null },
        )
    }
    // "Other" shows: open the show's own app ("app:<package>") or website. If the app isn't installed,
    // fall back to its Play Store page.
    fun openOther(watch: Watch) {
        val link = watch.link ?: return
        val intent = if (link.startsWith("app:")) {
            val pkg = link.removePrefix("app:")
            context.packageManager.getLaunchIntentForPackage(pkg)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
        }
        runCatching { context.startActivity(intent) }.onFailure {
            android.widget.Toast.makeText(context, "Couldn't open that link", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(incomingAppEntry) {
        if (incomingAppEntry != null) {
            // "SITE|show title", as written by Notifier.appLotteryOpen.
            val site = Site.entries.firstOrNull { it.name == incomingAppEntry.substringBefore('|') }
            if (site != null) enterApp(site, incomingAppEntry.substringAfter('|'))
            onAppEntryHandled()
        }
    }
    var tab by remember { mutableStateOf(Tab.SHOWS) }
    var editingId by remember { mutableStateOf<String?>(null) }
    // Set while the in-app browser is showing a lottery page.
    var browserRequest by remember { mutableStateOf<PageRequest?>(null) }
    fun openLink(request: PageRequest) {
        if (opensInApp(request.url)) browserRequest = request
        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url)))
    }
    fun openLink(url: String) = openLink(PageRequest(url))
    LaunchedEffect(incoming) {
        if (incoming != null) {
            openLink(incoming)
            onIncomingHandled()
        }
    }
    // A show picked in the Add dialog stays a draft here until the user taps OK.
    var newWatch by remember { mutableStateOf<Watch?>(null) }
    var adding by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val watches by repo.watches.collectAsState()
    val log by repo.log.collectAsState()
    val profile by repo.profile.collectAsState()
    val defaults by repo.defaults.collectAsState()
    val catalog by repo.shows.collectAsState()
    val statuses by repo.watchStatus.collectAsState()

    // Show lists are fetched once per app start and cached; only the Refresh button re-fetches.
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshErrors by remember { mutableStateOf(emptyMap<Site, String>()) }
    fun refreshShows() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            refreshErrors = Sites.refreshShows(repo)
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { refreshShows() }

    // What the sites say we've entered. Loaded when the Entered tab is opened, and on Refresh.
    var entries by remember { mutableStateOf<List<SiteEntry>?>(null) }
    var entryErrors by remember { mutableStateOf(emptyMap<Site, String>()) }
    var entriesLoading by remember { mutableStateOf(false) }
    fun refreshEntries() {
        if (entriesLoading) return
        scope.launch {
            entriesLoading = true
            val all = mutableListOf<SiteEntry>()
            val errors = mutableMapOf<Site, String>()
            for ((site, adapter) in Sites.all) {
                try {
                    all += adapter.myEntries()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[site] = e.message ?: e.javaClass.simpleName
                }
            }
            entries = all
            entryErrors = errors
            entriesLoading = false
        }
    }

    // Any change to watches, profile or defaults re-runs the engine, which also re-arms the next alarm.
    val luckySeat by Sites.luckySeatAuth.state.collectAsState()
    LaunchedEffect(watches, profile, defaults, luckySeat) { Scheduler.checkNow(context) }
    LaunchedEffect(tab, luckySeat.signedIn) { if (tab == Tab.ENTERED) refreshEntries() }

    val page = browserRequest
    if (page != null) {
        BackHandler { browserRequest = null }
        SiteBrowser(page, onClose = { browserRequest = null })
        return
    }

    val editing = newWatch ?: watches.firstOrNull { it.id == editingId }
    if (editing != null) {
        fun close() { newWatch = null; editingId = null }
        BackHandler(onBack = ::close)
        if (editing.site == Site.OTHER) {
            OtherEditor(
                initial = editing,
                isNew = newWatch != null,
                onConfirm = { repo.upsert(it); close() },
                onDelete = { repo.remove(editing.id); close() },
                onBack = ::close,
            )
        } else {
            WatchEditor(
                initial = editing,
                isNew = newWatch != null,
                defaults = defaults,
                onConfirm = { repo.upsert(it); close() },
                onDelete = { repo.remove(editing.id); close() },
                onBack = ::close,
            )
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("BROADWAY LOTTO", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Gold, letterSpacing = 2.sp)
                },
                actions = {
                    IconButton(onClick = { Scheduler.checkNow(context) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Check now", tint = Gold)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { Icon(it.icon, contentDescription = it.label) },
                        label = { Text(it.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink,
                            selectedTextColor = Gold,
                            indicatorColor = Gold,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.SHOWS) FloatingActionButton(onClick = { adding = true }, containerColor = Gold, contentColor = Ink) {
                Icon(Icons.Default.Add, contentDescription = "Add show")
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            Tab.SHOWS -> ShowsList(watches, profile, defaults, statuses, m, onEnterApp = { enterApp(it.site, it.title) }, onOpenOther = { openOther(it) }, onToggle = { repo.upsert(it.copy(enabled = !it.enabled)) }, onOpen = { editingId = it.id }, onGoProfile = { tab = Tab.SETTINGS })
            Tab.ENTERED -> EntriesList(entries, entryErrors, entriesLoading, m, onRefresh = ::refreshEntries, onOpen = { openLink(it) })
            Tab.ACTIVITY -> ActivityList(log, m, onOpen = { openLink(PageRequest(it.deepLink, it.preselect, it.tickets)) }, onDismiss = repo::removeRecord, onClear = repo::clearLog)
            Tab.SETTINGS -> ProfileForm(profile, defaults, m, repo::setProfile, repo::setDefaults)
        }
    }

    if (adding) {
        AddShowDialog(
            catalog = catalog,
            refreshing = refreshing,
            refreshErrors = refreshErrors,
            existing = watches.mapTo(mutableSetOf()) { it.site to it.title },
            onRefresh = ::refreshShows,
            onDismiss = { adding = false },
            onPick = { site, title, link ->
                adding = false
                if (watches.none { it.site == site && it.title == title }) {
                    val watch = Watch(UUID.randomUUID().toString(), title, site, link = link)
                    // "Other" shows have no days/times to confirm, so they're added straight away.
                    if (site == Site.OTHER) repo.upsert(watch) else newWatch = watch
                }
            },
        )
    }
}
