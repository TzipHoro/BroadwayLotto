package com.broadwaylotto.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.broadwaylotto.engine.Notifier
import com.broadwaylotto.model.EntryRecord
import com.broadwaylotto.model.EntryStatus
import com.broadwaylotto.model.Repo
import com.broadwaylotto.model.Site
import com.broadwaylotto.model.Watch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Walks the official Hamilton app through its Broadway lottery:
 *
 *   Home "Lottery" tile -> Lottery list (BROADWAY tab, one card per performance, each with its
 *   PERFORMANCE TIME and LOTTERY CLOSING TIME) -> tap a card -> entry page: "Number of Tickets" (- / +),
 *   two required checkboxes, "Submit Your Entry".
 *
 * Mapped by reading the app's screens in October 2026 (see docs/hamilton-flow.md). This app exposes
 * only content descriptions (no view ids), so everything is found by those. After a successful entry
 * the performance's list card becomes a "YOU'VE ENTERED!" banner (which is also how already-entered
 * performances are skipped: they no longer have a PERFORMANCE TIME card).
 *
 * The two checkboxes are the app's own terms and "I've reviewed my profile / ID pickup" statements.
 * The runner ticks them on the user's behalf, so it only runs after the user has confirmed once in
 * Settings that they've read them (see [AppLauncher] and the Hamilton card).
 */
internal class HamiltonRunner(svc: AccessibilityService, private val job: AppJob) : AppRunner(svc, HAMILTON_PACKAGE) {
    private val repo = Repo.get(svc)
    private val lines = mutableListOf<String>()

    suspend fun run() {
        val stopped = try {
            runShows()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Stop) {
            "Stopped: ${e.message}"
        } catch (e: Exception) {
            "Stopped on an error: ${e.message}"
        }
        val summary = (lines + listOfNotNull(stopped)).joinToString("\n").ifBlank { "Nothing to do." }
        AppAutomationService.say(summary)
        Notifier.simple(svc, 1003, "Hamilton", summary)
    }

    private suspend fun runShows() {
        AppAutomationService.say("Opening Hamilton…")
        waitFor(20_000) { root() } ?: throw Stop("the Hamilton app didn't open")
        openLotteryList()
        for (watch in job.shows) lines += enterPerformances(watch)
    }

    // ------------------------------------------------------------------ list

    private class Card(val node: AccessibilityNodeInfo, val perf: LocalDateTime)

    private fun findDescStarting(prefix: String) =
        descendants().firstOrNull { it.contentDescription?.toString()?.startsWith(prefix, ignoreCase = true) == true }

    private fun onList() = findDescStarting("BROADWAY") != null

    private suspend fun openLotteryList() {
        for (attempt in 0 until 6) {
            if (onList()) {
                click(findDescStarting("BROADWAY")!!)
                delay(800)
                return
            }
            // The home screen has a clickable "Lottery" tile; the list screen has a non-clickable title.
            val tile = descendants().firstOrNull { it.contentDescription?.toString()?.trim() == "Lottery" && it.isClickable }
            if (tile != null) {
                click(tile)
                waitFor(8_000) { findDescStarting("BROADWAY") }
            } else {
                back()
                delay(700)
            }
        }
        throw Stop("couldn't open Hamilton's Lottery screen")
    }

    private class Found(val cards: List<Card>, val otherTabCards: Int)

    /** Cards on screen, split into New York ones (Broadway) and any belonging to another tab (Tampa, Orlando). */
    private fun scanCards(): Found {
        val all = descendants().mapNotNull { n ->
            val d = n.contentDescription?.toString() ?: return@mapNotNull null
            if ("PERFORMANCE TIME" !in d) return@mapNotNull null
            parsePerformance(d)?.let { Card(n, it) to d }
        }.toList()
        val (ny, other) = all.partition { (_, d) -> d.lineSequence().first().contains("New York", ignoreCase = true) }
        return Found(ny.map { it.first }, other.size)
    }

    private fun visibleCards(): List<Card> = scanCards().cards

    /** "...\nPERFORMANCE TIME\nOctober 20, 2026\n7:00pm\nLOTTERY CLOSING TIME..." */
    private fun parsePerformance(desc: String): LocalDateTime? = runCatching {
        val lines = desc.lines().map { it.trim() }
        val i = lines.indexOf("PERFORMANCE TIME")
        val date = LocalDate.parse(lines[i + 1], DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
        val time = LocalTime.parse(lines[i + 2].uppercase(), DateTimeFormatter.ofPattern("h:mma", Locale.US))
        LocalDateTime.of(date, time)
    }.getOrNull()

    // ------------------------------------------------------------------ one performance after another

    private suspend fun enterPerformances(watch: Watch): String {
        val seen = mutableSetOf<LocalDateTime>()
        var entered = 0
        var skipped = 0
        var rounds = 0
        while (rounds++ < 40) {
            checkBudget()
            val found = scanCards()
            if (found.cards.isEmpty() && found.otherTabCards > 0) {
                // We've ended up on another tab's list (Tampa/Orlando): go back to Broadway, don't enter anything.
                findDescStarting("BROADWAY")?.let { click(it) }
                delay(1_000)
                continue
            }
            val next = found.cards.firstOrNull { it.perf !in seen }
            if (next == null) {
                if (!scroll(forward = true)) break // end of the list
                delay(600)
                continue
            }
            seen += next.perf
            val id = "HAMILTON:${watch.title}:${next.perf}"
            if (!watch.accepts(next.perf, job.defaults) || repo.isDone(id, 1)) {
                skipped++
                continue
            }
            AppAutomationService.say("Entering Hamilton ${next.perf.toLocalDate()} ${next.perf.toLocalTime()}…")
            enterOne(watch, next, id)
            entered++
        }
        return when {
            entered > 0 -> "${watch.title}: entered $entered performance${if (entered == 1) "" else "s"}"
            else -> "${watch.title}: nothing to enter (${seen.size} on the list, $skipped don't fit your days and times or are already entered)"
        }
    }

    private suspend fun enterOne(watch: Watch, card: Card, id: String) {
        click(card.node)
        waitFor(10_000) { findDesc("Number of Tickets") } ?: throw Stop("the entry page for ${card.perf} didn't open")
        delay(800)
        guardSignIn()

        setTickets(job.tickets)

        // The checkboxes and Submit are below the fold.
        for (attempt in 0 until 5) {
            if (findDescStarting("I accept the Term") != null && findDesc("Submit Your Entry") != null) break
            scroll(forward = true)
            delay(500)
        }
        for (prefix in listOf("I accept the Term", "I have reviewed my profile")) {
            val box = findDescStarting(prefix) ?: throw Stop("couldn't find Hamilton's \"$prefix…\" box")
            if (!box.isChecked) click(box)
            delay(500)
        }

        // Submit stays disabled until both boxes are ticked.
        val submit = waitFor(6_000) { findDesc("Submit Your Entry")?.takeIf { it.isClickable || it.isEnabled && it.parent?.isClickable == true } }
            ?: throw Stop("Hamilton's Submit button stayed disabled for ${card.perf}")
        guardSignIn()
        click(submit)

        var gone = waitFor(6_000) { if (findDesc("Submit Your Entry") == null) true else null }
        if (gone == null) {
            // In case the accessibility click was ignored: press it with a real touch instead.
            findDesc("Submit Your Entry")?.let { tap(it) }
            gone = waitFor(15_000) { if (findDesc("Submit Your Entry") == null) true else null }
        }
        gone ?: throw Stop("pressed Submit for ${card.perf} but Hamilton stayed on the entry page, check the app")

        // Hamilton confirms by turning that performance's card on the list into a banner reading
        // "YOU'VE ENTERED! You have successfully entered the lottery for October 20, 7:00pm".
        returnToList()
        waitFor(8_000) { enteredBanner(card.perf) }
            ?: throw Stop("submitted ${card.perf} but Hamilton's list didn't show it as entered, check the app")

        repo.addRecord(
            EntryRecord(
                lotteryId = id,
                showTitle = watch.title,
                site = Site.HAMILTON,
                performances = listOf(card.perf.toString()),
                atMillis = System.currentTimeMillis(),
                status = EntryStatus.ENTERED,
                message = "Entered through the Hamilton app",
                deepLink = Site.HAMILTON.homeUrl,
            ),
        )
    }

    /** The "YOU'VE ENTERED!" banner for [perf], e.g. "...the lottery for | October 20, 7:00pm". */
    private fun enteredBanner(perf: LocalDateTime): AccessibilityNodeInfo? {
        val day = "${perf.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)} ${perf.dayOfMonth}"
        val time = perf.toLocalTime().format(DateTimeFormatter.ofPattern("h:mma", Locale.US)).lowercase()
        return descendants().firstOrNull {
            val d = it.contentDescription?.toString().orEmpty()
            d.contains("ENTERED", ignoreCase = false) && d.contains(day) && d.lowercase().contains(time)
        }
    }

    private suspend fun returnToList() {
        for (attempt in 0 until 5) {
            if (onList()) return
            back()
            delay(800)
        }
        throw Stop("couldn't get back to Hamilton's lottery list")
    }

    // ------------------------------------------------------------------ the "Number of Tickets" row

    private class TicketRow(val count: Int, val minus: AccessibilityNodeInfo?, val plus: AccessibilityNodeInfo?)

    private fun ticketRow(): TicketRow? {
        val label = findDesc("Number of Tickets") ?: return null
        val labelRect = Rect().also { label.getBoundsInScreen(it) }
        fun sameRow(n: AccessibilityNodeInfo): Boolean {
            val r = Rect().also { n.getBoundsInScreen(it) }
            return abs(r.centerY() - labelRect.centerY()) < 60
        }
        val countNode = descendants().firstOrNull {
            sameRow(it) && it.contentDescription?.toString()?.trim()?.matches(Regex("\\d")) == true
        } ?: return null
        val countX = Rect().also { countNode.getBoundsInScreen(it) }.centerX()
        fun xOf(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }.centerX()
        val buttons = descendants().filter { sameRow(it) && it.isClickable && it != label && it != countNode }.toList()
        return TicketRow(
            count = countNode.contentDescription.toString().trim().toInt(),
            minus = buttons.filter { xOf(it) < countX }.maxByOrNull { xOf(it) },
            plus = buttons.filter { xOf(it) > countX }.minByOrNull { xOf(it) },
        )
    }

    private suspend fun setTickets(want: Int) {
        for (attempt in 0 until 4) {
            val row = ticketRow() ?: return
            when {
                row.count < want && row.plus != null -> click(row.plus)
                row.count > want && row.minus != null -> click(row.minus)
                else -> return // already right, or the app's own limit stops us
            }
            delay(500)
        }
    }

    // ------------------------------------------------------------------ safety

    /** Stops if the app is asking the user to sign in: we never type credentials. */
    private fun guardSignIn() {
        val hit = descendants().mapNotNull { it.contentDescription?.toString() }
            .firstOrNull { d -> listOf("sign in", "log in", "login", "create account").any { d.contains(it, ignoreCase = true) } }
        if (hit != null) throw Stop("Hamilton is asking you to sign in (\"${hit.take(40)}\")")
    }
}
