package com.broadwaylotto.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.broadwaylotto.automation.isApp
import com.broadwaylotto.model.EntryRecord
import com.broadwaylotto.model.EntryStatus
import com.broadwaylotto.model.Profile
import com.broadwaylotto.model.Site
import com.broadwaylotto.model.SiteEntry
import com.broadwaylotto.model.Slot
import com.broadwaylotto.model.TimeSlot
import com.broadwaylotto.model.Watch
import com.broadwaylotto.model.Weekday
import com.broadwaylotto.sites.OtherPresets
import com.broadwaylotto.sites.Sites
import java.text.DateFormat
import java.util.Date

private val CardShape = RoundedCornerShape(18.dp)

@Composable
private fun panelColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
private fun EmptyState(headline: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("*", fontSize = 40.sp, color = Gold)
        Spacer(Modifier.height(8.dp))
        Text(headline, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DayDots(slots: Set<Slot>) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Weekday.entries.forEach { d ->
            val on = slots.any { it.day == d }
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (on) Gold else PanelHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(d.short.take(1), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (on) Ink else Muted)
            }
        }
    }
}

// ---------------------------------------------------------------- Shows

@Composable
fun ShowsList(
    watches: List<Watch>,
    profile: Profile,
    defaults: Set<Slot>,
    statuses: Map<String, String>,
    modifier: Modifier,
    onEnterApp: (Watch) -> Unit,
    onOpenOther: (Watch) -> Unit,
    onToggle: (Watch) -> Unit,
    onOpen: (Watch) -> Unit,
    onGoProfile: () -> Unit,
) {
    // "Other" shows (a show's own app or site) get their own section: nothing is checked or entered for them.
    val (others, tracked) = watches.partition { it.site == Site.OTHER || it.site == Site.HAMILTON }
    val active = tracked.count { it.enabled && it.effectiveSlots(defaults).isNotEmpty() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader("Your shows", if (watches.isEmpty()) "Nothing on the bill yet" else "$active of ${tracked.size} actively entering") }
        if (!profile.isComplete) item {
            Card(
                Modifier.fillMaxWidth().clickable(onClick = onGoProfile),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = Crimson.copy(alpha = 0.25f)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Add your details to start entering", style = MaterialTheme.typography.titleMedium)
                    Text("Nothing gets entered until your profile is filled in. Tap to set it up.", color = Muted, fontSize = 13.sp)
                }
            }
        }
        if (watches.isEmpty()) item {
            EmptyState("Tap + to add a show", "Pick a site and a show, then tell us which days and times you can go.")
        }
        items(tracked, key = { it.id }) { w -> WatchCard(w, defaults, statuses[w.id], onEnterApp, onToggle, onOpen) }
        if (others.isNotEmpty()) {
            item { Column(Modifier.padding(top = 4.dp)) { SectionHeader("Other", "Shows with their own app or website") } }
            items(others, key = { it.id }) { w ->
                // Hamilton is automated like TodayTix, so it keeps the full card; user-added shortcuts get the simple one.
                if (w.site == Site.OTHER) OtherCard(w, onOpenOther, onOpen)
                else WatchCard(w, defaults, statuses[w.id], onEnterApp, onToggle, onOpen)
            }
        }
    }
}

@Composable
private fun OtherCard(w: Watch, onOpenOther: (Watch) -> Unit, onEdit: (Watch) -> Unit) {
    val where = when {
        w.link == null -> "No link saved"
        w.link.startsWith("app:") -> "Opens its app"
        else -> "Opens " + (android.net.Uri.parse(w.link).host ?: w.link)
    }
    Card(Modifier.fillMaxWidth().clickable { onEdit(w) }, shape = CardShape, colors = panelColors()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(Site.OTHER.accent()))
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(w.title, style = MaterialTheme.typography.titleLarge)
                    Text("OTHER", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = Site.OTHER.accent())
                    Text(where, color = Muted, fontSize = 13.sp)
                }
                if (w.link != null) {
                    Button(
                        onClick = { onOpenOther(w) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                    ) { Text("Open", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
            }
        }
    }
}

/** Editor for an "Other" show: just its name and where to enter. No days and times, nothing is automated. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherEditor(initial: Watch, isNew: Boolean, onConfirm: (Watch) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    var name by remember(initial.id) { mutableStateOf(initial.title) }
    var link by remember(initial.id) { mutableStateOf(initial.link.orEmpty()) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Cream) } },
                actions = { if (!isNew) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove show", tint = Bad) } },
            )
        },
        bottomBar = {
            Button(
                onClick = { onConfirm(initial.copy(title = name.trim(), link = normalizeLink(link))) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
            ) { Text("OK", fontWeight = FontWeight.Bold) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp, 0.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("OTHER", fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = Site.OTHER.accent())
            Text("This lottery runs in the show's own app or website, so Broadway Lotto can't check or enter it. It just keeps it in your list with a shortcut.", color = Muted)
            OutlinedTextField(name, { name = it }, label = { Text("Show name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                link, { link = it }, label = { Text("Website link") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { if (initial.link?.startsWith("app:") == true && link == initial.link) Text("Opens the app on your phone", color = Muted) },
            )
        }
    }
}

@Composable
private fun WatchCard(
    w: Watch,
    defaults: Set<Slot>,
    status: String?,
    onEnterApp: (Watch) -> Unit,
    onToggle: (Watch) -> Unit,
    onOpen: (Watch) -> Unit,
) {
    val slots = w.effectiveSlots(defaults)
    val dim = if (w.enabled) 1f else 0.5f
    Card(Modifier.fillMaxWidth().clickable { onOpen(w) }, shape = CardShape, colors = panelColors()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(w.site.accent().copy(alpha = dim)))
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(w.title, style = MaterialTheme.typography.titleLarge, color = Cream.copy(alpha = dim))
                    Text(w.site.label.uppercase(), fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = w.site.accent().copy(alpha = dim))
                    if (slots.isEmpty()) {
                        Text(if (w.usesDefaults) "No default availability set yet" else "Tap to set when you can go", color = Warn, fontSize = 13.sp)
                    } else {
                        DayDots(slots)
                        Text(if (w.usesDefaults) "Using your defaults" else "Custom days & times", color = Muted, fontSize = 12.sp)
                        // What the last check found; amber when it won't enter anything.
                        if (w.enabled && status != null) {
                            val problem = status.startsWith("Not listed") || status.startsWith("Couldn't") || status.startsWith("Nothing left")
                            Text(status, color = if (problem) Warn else Good, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        // These lotteries can only be entered through their phone app, so each card has its own button.
                        if (w.site.isApp && w.enabled && slots.isNotEmpty()) {
                            Button(
                                onClick = { onEnterApp(w) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                            ) { Text("Enter lottery now", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        }
                    }
                }
                Switch(
                    checked = w.enabled,
                    onCheckedChange = { onToggle(w) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Ink),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Activity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityList(
    log: List<EntryRecord>,
    modifier: Modifier,
    onOpen: (EntryRecord) -> Unit,
    onDismiss: (EntryRecord) -> Unit,
    onClear: () -> Unit,
) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("Activity", "Every entry the app has made for you") }
                if (log.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear", color = Muted) }
            }
        }
        if (log.isEmpty()) item { EmptyState("Nothing yet", "Alerts show up here when a lottery you're watching opens. For what you've already entered, see the Entered tab.") }
        items(log, key = { "${it.lotteryId}:${it.atMillis}" }) { r ->
            val (color, glyph) = when (r.status) {
                EntryStatus.ENTERED -> Good to "✓"
                EntryStatus.NEEDS_MANUAL -> Warn to "!"
                EntryStatus.FAILED -> Bad to "✕"
            }
            // Swipe either way, or tap the ✕, to remove just this item.
            val swipe = rememberSwipeToDismissBoxState(
                confirmValueChange = { target ->
                    if (target == SwipeToDismissBoxValue.Settled) false else { onDismiss(r); true }
                },
            )
            SwipeToDismissBox(
                state = swipe,
                backgroundContent = {
                    Box(Modifier.fillMaxSize().clip(CardShape).background(PanelHigh), contentAlignment = Alignment.CenterEnd) {
                        Text("Dismiss", color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                },
            ) {
            Card(Modifier.fillMaxWidth(), shape = CardShape, colors = panelColors()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Text(glyph, color = color, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.showTitle, style = MaterialTheme.typography.titleMedium)
                        Text(r.message, color = color, fontSize = 13.sp)
                        Text("${r.site.label} · ${fmt.format(Date(r.atMillis))}", color = Muted, fontSize = 12.sp)
                        if (r.status != EntryStatus.ENTERED) {
                            TextButton(onClick = { onOpen(r) }, contentPadding = PaddingValues(0.dp)) {
                                Text("Open ${r.site.label}", color = Gold)
                            }
                        }
                    }
                    IconButton(onClick = { onDismiss(r) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Clear activity?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("This only empties the list. Lotteries already entered or notified won't be repeated.", color = Muted) },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Clear", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = Muted) } },
        )
    }
}

// ---------------------------------------------------------------- Entries

/** What the sites themselves say you've entered. Upcoming performances first, then past ones. */
@Composable
fun EntriesList(
    entries: List<SiteEntry>?,
    errors: Map<Site, String>,
    loading: Boolean,
    modifier: Modifier,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val perfFmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", java.util.Locale.getDefault()) }
    val now = remember(entries) { java.time.LocalDateTime.now() }
    val sorted = remember(entries) {
        val (upcoming, past) = entries.orEmpty().partition { !it.performance.isBefore(now) }
        upcoming.sortedBy { it.performance } + past.sortedByDescending { it.performance }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("Entered", "Lotteries you've already entered") }
                TextButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh", color = Gold) }
            }
        }
        errors.forEach { (site, message) ->
            item { Text("${site.label}: $message", color = Warn, fontSize = 13.sp) }
        }
        if (entries != null && sorted.isEmpty() && errors.isEmpty()) {
            item { EmptyState("No entries yet", "Entries you make on Lucky Seat show up here.") }
        }
        items(sorted) { e ->
            val past = e.performance.isBefore(now)
            Card(Modifier.fillMaxWidth(), shape = CardShape, colors = panelColors()) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(5.dp).fillMaxHeight().background(e.site.accent().copy(alpha = if (past) 0.4f else 1f)))
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(e.showTitle, style = MaterialTheme.typography.titleLarge, color = if (past) Muted else Cream)
                        Text(e.performance.format(perfFmt), fontWeight = FontWeight.SemiBold)
                        Text("${e.tickets} ticket${if (e.tickets == 1) "" else "s"} · ${e.site.label}", color = Muted, fontSize = 13.sp)
                        if (e.result.isNotBlank()) Text(e.result, color = if (e.isWinner) Good else Muted, fontSize = 13.sp)
                        e.claimLink?.let { link ->
                            TextButton(onClick = { onOpen(link) }, contentPadding = PaddingValues(0.dp)) {
                                Text("You won – claim tickets", color = Good, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        item { Text("Broadway Direct entries aren't tracked yet.", color = Muted, fontSize = 12.sp) }
    }
}

// ---------------------------------------------------------------- Profile

@Composable
fun ProfileForm(
    profile: Profile,
    defaults: Set<Slot>,
    modifier: Modifier,
    onSave: (Profile) -> Unit,
    onDefaults: (Set<Slot>) -> Unit,
) {
    // Each field resets only when ITS saved value changes, so saving the ticket count (below) doesn't
    // wipe a name or email the user is half-way through typing.
    var first by remember(profile.firstName) { mutableStateOf(profile.firstName) }
    var last by remember(profile.lastName) { mutableStateOf(profile.lastName) }
    var email by remember(profile.email) { mutableStateOf(profile.email) }
    var zip by remember(profile.zip) { mutableStateOf(profile.zip) }
    var country by remember(profile.country) { mutableStateOf(profile.country) }
    // Date of birth is kept as three fields (like the sites' own forms) and saved as ISO yyyy-MM-dd.
    val dobSaved = remember(profile.dob) { profile.dob.split('-') }
    var dobMonth by remember(profile.dob) { mutableStateOf(dobSaved.getOrNull(1)?.trimStart('0').orEmpty()) }
    var dobDay by remember(profile.dob) { mutableStateOf(dobSaved.getOrNull(2)?.trimStart('0').orEmpty()) }
    var dobYear by remember(profile.dob) { mutableStateOf(dobSaved.getOrNull(0).orEmpty()) }
    val dobIso = dobToIso(dobMonth, dobDay, dobYear) // null = not a real date
    // The ticket count saves the moment it changes, so it isn't part of "unsaved changes".
    val dirty = dobIso != null && Profile(first.trim(), last.trim(), email.trim(), profile.tickets, dobIso, zip.trim(), country) != profile

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Default availability", "Every show uses this unless you override it")
        AvailabilityEditor(defaults, onChange = onDefaults)
        Spacer(Modifier.height(8.dp))
        SectionHeader("Accounts", "Sign-ins for the lottery sites")
        LuckySeatAccountCard()
        AppAutomationCard()
        Spacer(Modifier.height(8.dp))
        SectionHeader("Your details", "Used on lottery entry forms")
        Card(Modifier.fillMaxWidth(), shape = CardShape, colors = panelColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(first, { first = it }, label = { Text("First name") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(last, { last = it }, label = { Text("Last name") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Date of birth", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val digits = KeyboardOptions(keyboardType = KeyboardType.Number)
                    OutlinedTextField(dobMonth, { dobMonth = it.filter(Char::isDigit).take(2) }, label = { Text("MM") }, singleLine = true, keyboardOptions = digits, isError = dobIso == null, modifier = Modifier.weight(1f))
                    OutlinedTextField(dobDay, { dobDay = it.filter(Char::isDigit).take(2) }, label = { Text("DD") }, singleLine = true, keyboardOptions = digits, isError = dobIso == null, modifier = Modifier.weight(1f))
                    OutlinedTextField(dobYear, { dobYear = it.filter(Char::isDigit).take(4) }, label = { Text("YYYY") }, singleLine = true, keyboardOptions = digits, isError = dobIso == null, modifier = Modifier.weight(1.4f))
                }
                if (dobIso == null) Text("That isn't a valid date.", color = Bad, fontSize = 12.sp)
                else Text("Broadway Direct asks for it. Your details are stored encrypted on this phone.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(zip, { zip = it.take(10) }, label = { Text("Zip / postal code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Country of residence", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("USA" to "USA", "CANADA" to "Canada", "OTHER" to "Other").forEach { (value, label) ->
                        FilterChip(selected = country == value, onClick = { country = value }, label = { Text(label) })
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = CardShape, colors = panelColors()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tickets per entry", style = MaterialTheme.typography.titleMedium)
                    Text("Saved as you tap. Most lotteries allow 1–4", color = Muted, fontSize = 13.sp)
                }
                OutlinedButton(onClick = { if (profile.tickets > 1) onSave(profile.copy(tickets = profile.tickets - 1)) }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−") }
                Text("${profile.tickets}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                OutlinedButton(onClick = { if (profile.tickets < 4) onSave(profile.copy(tickets = profile.tickets + 1)) }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("+") }
            }
        }
        Button(
            onClick = { onSave(Profile(first.trim(), last.trim(), email.trim(), profile.tickets, dobIso.orEmpty(), zip.trim(), country)) },
            enabled = dirty,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
        ) { Text(if (dirty) "Save" else "Saved", fontWeight = FontWeight.Bold) }
    }
}

/** "" when all three are blank, an ISO date when they form a real past date, null when they don't. */
private fun dobToIso(month: String, day: String, year: String): String? {
    if (month.isBlank() && day.isBlank() && year.isBlank()) return ""
    return runCatching {
        java.time.LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            .takeIf { it.year >= 1900 && !it.isAfter(java.time.LocalDate.now()) }
            ?.toString()
    }.getOrNull()
}

// ---------------------------------------------------------------- Add show

@Composable
fun AddShowDialog(
    catalog: Map<String, List<String>>,
    refreshing: Boolean,
    refreshErrors: Map<Site, String>,
    existing: Set<Pair<Site, String>>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (Site, String, String?) -> Unit,
) {
    var site by remember { mutableStateOf(Site.BROADWAY_DIRECT) }
    val shows = catalog[site.name].orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add a show", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onRefresh, enabled = !refreshing) { Text(if (refreshing) "Refreshing…" else "Refresh", color = Gold) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Scrolls sideways so every chip keeps its full label.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Site.entries.filter { it != Site.HAMILTON }.forEach {
                        FilterChip(
                            selected = site == it,
                            onClick = { site = it },
                            label = { Text(it.label, maxLines = 1, softWrap = false) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = it.accent(), selectedLabelColor = Ink),
                        )
                    }
                }
                if (site == Site.OTHER) {
                    OtherPicker(existing, onPick)
                    return@Column
                }
                if (shows.isEmpty()) {
                    Text(if (refreshing) "Loading shows…" else "No shows loaded yet. Tap Refresh.", color = Muted)
                }
                val error = refreshErrors[site]
                if (error != null && !refreshing) {
                    Text(
                        "Couldn't load ${site.label}: $error" + if (shows.isNotEmpty()) " (showing the last saved list)" else "",
                        color = Warn, fontSize = 12.sp,
                    )
                }
                // The list scrolls on its own; the site chips and messages above stay put.
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shows) { title ->
                        val added = (site to title) in existing
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PanelHigh)
                                .alpha(if (added) 0.5f else 1f)
                                .clickable(enabled = !added) { onPick(site, title, null) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(site.accent()))
                            Text(title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                            if (added) Text("Added", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
    )
}

/** The "Other" tab of the Add dialog: known own-app lotteries, or any show + link the user types. */
@Composable
private fun OtherPicker(existing: Set<Pair<Site, String>>, onPick: (Site, String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    val nameTaken = (Site.OTHER to name.trim()) in existing

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("For lotteries that run in a show's own app or website. Hamilton is entered for you; anything you add yourself is kept in your list with a shortcut.", color = Muted, fontSize = 12.sp)
        OtherPresets.all.forEach { preset ->
            val added = (preset.site to preset.title) in existing
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PanelHigh)
                    .alpha(if (added) 0.5f else 1f)
                    .clickable(enabled = !added) { onPick(preset.site, preset.title, null) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Site.OTHER.accent()))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(preset.title, style = MaterialTheme.typography.titleMedium)
                    Text(preset.where, color = Muted, fontSize = 12.sp)
                }
                if (added) Text("Added", color = Muted, fontSize = 12.sp)
            }
        }
        Text("Or add your own", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Show name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(link, { link = it }, label = { Text("Website link (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (nameTaken) Text("Already in your list.", color = Warn, fontSize = 12.sp)
        Button(
            onClick = { onPick(Site.OTHER, name.trim(), normalizeLink(link)) },
            enabled = name.isNotBlank() && !nameTaken,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
        ) { Text("Add") }
    }
}

/** Blank -> none; adds https:// when the user didn't type a scheme. */
internal fun normalizeLink(raw: String): String? {
    val t = raw.trim()
    return when {
        t.isEmpty() -> null
        t.startsWith("app:") || t.contains("://") -> t
        else -> "https://$t"
    }
}

// ---------------------------------------------------------------- Editor

/** Presets + day/time grid. Used for the global defaults and for a show's own override. */
@Composable
fun AvailabilityEditor(slots: Set<Slot>, enabled: Boolean = true, onChange: (Set<Slot>) -> Unit) {
    fun preset(pred: (Weekday, TimeSlot) -> Boolean) =
        onChange(Weekday.entries.flatMap { d -> TimeSlot.entries.map { Slot(d, it) } }.filter { pred(it.day, it.time) }.toSet())

    val weekend = setOf(Weekday.FRIDAY, Weekday.SATURDAY, Weekday.SUNDAY)

    Column(Modifier.alpha(if (enabled) 1f else 0.45f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (enabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip("Any") { preset { _, _ -> true } }
            PresetChip("Fri–Sun") { preset { d, _ -> d in weekend } }
            PresetChip("Evenings") { preset { _, t -> t == TimeSlot.EVENING } }
            PresetChip("Clear") { preset { _, _ -> false } }
        }
        Card(Modifier.fillMaxWidth(), shape = CardShape, colors = panelColors()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Spacer(Modifier.width(52.dp))
                    TimeSlot.entries.forEach {
                        Text(it.label, Modifier.weight(1f), textAlign = TextAlign.Center, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Weekday.entries.forEach { day ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(day.short, Modifier.width(44.dp), fontWeight = FontWeight.SemiBold)
                        TimeSlot.entries.forEach { t ->
                            val slot = Slot(day, t)
                            SlotCell(slot in slots, enabled, Modifier.weight(1f)) {
                                onChange(if (slot in slots) slots - slot else slots + slot)
                            }
                        }
                    }
                }
            }
        }
        Text("${slots.size} of 14 slots selected", color = Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchEditor(
    initial: Watch,
    isNew: Boolean,
    defaults: Set<Slot>,
    onConfirm: (Watch) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    // Edits stay in this draft until the user taps OK; Back throws them away.
    var watch by remember(initial.id) { mutableStateOf(initial) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Cream) } },
                actions = {
                    if (!isNew) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove show", tint = Bad) }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = { onConfirm(watch) },
                modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
            ) { Text(if (isNew) "OK – add show" else "OK", fontWeight = FontWeight.Bold) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(watch.title, style = MaterialTheme.typography.headlineMedium)
                Text(watch.site.label.uppercase(), fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = watch.site.accent())
            }
            Text("We'll only enter lotteries that include a performance in a slot you pick.", color = Muted)

            Card(Modifier.fillMaxWidth(), shape = CardShape, colors = panelColors()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use my default availability", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (watch.usesDefaults) "Change it once in Settings and every show follows." else "This show has its own days and times.",
                            color = Muted, fontSize = 13.sp,
                        )
                    }
                    Switch(
                        checked = watch.usesDefaults,
                        // Turning the override on starts from a copy of the defaults.
                        onCheckedChange = { watch = watch.copy(slots = if (it) null else defaults) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Ink),
                    )
                }
            }

            AvailabilityEditor(
                slots = watch.effectiveSlots(defaults),
                enabled = !watch.usesDefaults,
                onChange = { watch = watch.copy(slots = it) },
            )
            if (watch.usesDefaults && defaults.isEmpty()) {
                Text("Your defaults are empty, so this show won't enter anything yet. Set them in Settings.", color = Warn, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SlotCell(selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Gold else PanelHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Ink, modifier = Modifier.size(20.dp))
    }
}
