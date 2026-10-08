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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import com.broadwaylotto.model.EntryRecord
import com.broadwaylotto.model.EntryStatus
import com.broadwaylotto.model.Profile
import com.broadwaylotto.model.Site
import com.broadwaylotto.model.SiteEntry
import com.broadwaylotto.model.Slot
import com.broadwaylotto.model.TimeSlot
import com.broadwaylotto.model.Watch
import com.broadwaylotto.model.Weekday
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
    onToggle: (Watch) -> Unit,
    onOpen: (Watch) -> Unit,
    onGoProfile: () -> Unit,
) {
    val active = watches.count { it.enabled && it.effectiveSlots(defaults).isNotEmpty() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader("Your shows", if (watches.isEmpty()) "Nothing on the bill yet" else "$active of ${watches.size} actively entering") }
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
        items(watches, key = { it.id }) { w -> WatchCard(w, defaults, statuses[w.id], onToggle, onOpen) }
    }
}

@Composable
private fun WatchCard(w: Watch, defaults: Set<Slot>, status: String?, onToggle: (Watch) -> Unit, onOpen: (Watch) -> Unit) {
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

@Composable
fun ActivityList(log: List<EntryRecord>, modifier: Modifier, onOpen: (EntryRecord) -> Unit, onClear: () -> Unit) {
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
        items(log) { r ->
            val (color, glyph) = when (r.status) {
                EntryStatus.ENTERED -> Good to "✓"
                EntryStatus.NEEDS_MANUAL -> Warn to "!"
                EntryStatus.FAILED -> Bad to "✕"
            }
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
    var first by remember(profile) { mutableStateOf(profile.firstName) }
    var last by remember(profile) { mutableStateOf(profile.lastName) }
    var email by remember(profile) { mutableStateOf(profile.email) }
    var tickets by remember(profile) { mutableStateOf(profile.tickets) }
    val dirty = Profile(first.trim(), last.trim(), email.trim(), tickets) != profile

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Default availability", "Every show uses this unless you override it")
        AvailabilityEditor(defaults, onChange = onDefaults)
        Spacer(Modifier.height(8.dp))
        SectionHeader("Accounts", "Sign-ins for the lottery sites")
        LuckySeatAccountCard()
        Spacer(Modifier.height(8.dp))
        SectionHeader("Your details", "Used on lottery entry forms")
        Card(Modifier.fillMaxWidth(), shape = CardShape, colors = panelColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(first, { first = it }, label = { Text("First name") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(last, { last = it }, label = { Text("Last name") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        Card(Modifier.fillMaxWidth(), shape = CardShape, colors = panelColors()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tickets per entry", style = MaterialTheme.typography.titleMedium)
                    Text("Most lotteries allow 1–4", color = Muted, fontSize = 13.sp)
                }
                OutlinedButton(onClick = { if (tickets > 1) tickets-- }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−") }
                Text("$tickets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                OutlinedButton(onClick = { if (tickets < 4) tickets++ }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("+") }
            }
        }
        Button(
            onClick = { onSave(Profile(first.trim(), last.trim(), email.trim(), tickets)) },
            enabled = dirty,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
        ) { Text(if (dirty) "Save" else "Saved", fontWeight = FontWeight.Bold) }
    }
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
    onPick: (Site, String) -> Unit,
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
                    Site.entries.forEach {
                        FilterChip(
                            selected = site == it,
                            onClick = { site = it },
                            label = { Text(it.label, maxLines = 1, softWrap = false) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = it.accent(), selectedLabelColor = Ink),
                        )
                    }
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
                                .clickable(enabled = !added) { onPick(site, title) }.padding(14.dp),
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
