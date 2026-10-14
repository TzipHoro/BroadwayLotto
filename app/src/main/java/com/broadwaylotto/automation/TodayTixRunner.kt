package com.broadwaylotto.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
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

/**
 * Walks the TodayTix app through its lottery screens for each show in the job:
 *
 *   Home (Lottery & Rush) -> show page "Enter now" -> "How many tickets?" -> "Select showtimes"
 *   -> "Contact Information" -> Enter -> "Share to increase odds" (closed, never shared).
 *
 * Mapped by reading the app's screens in October 2026 (see docs/todaytix-flow.md). Screens are found
 * by their text, content description or view id, never by position. Anything unexpected (a login,
 * payment or verification screen, or a screen that doesn't appear in time) stops the whole run.
 */
internal class TodayTixRunner(svc: AccessibilityService, private val job: AppJob) : AppRunner(svc, TODAYTIX_PACKAGE) {
    private val repo = Repo.get(svc)

    /** One line per show, kept here so a stop part-way still reports what was already done. */
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
        val summary = (lines + listOfNotNull(stopped)).joinToString("\n")
        AppAutomationService.say(summary)
        Notifier.simple(svc, 1002, "TodayTix", summary)
    }

    private suspend fun runShows() {
        say("Opening TodayTix…")
        waitFor(20_000) { root() } ?: throw Stop("TodayTix didn't open")

        say("Checking what you've already entered…")
        val entered = readEntered().toMutableSet()

        for (watch in job.shows) {
            checkBudget()
            say("Looking for ${watch.title}…")
            lines += enterShow(watch, entered)
        }
        goHome()
    }

    // ------------------------------------------------------------------ one show

    private suspend fun enterShow(watch: Watch, entered: MutableSet<String>): String {
        goHome()
        if (!openShowCard(watch.title)) return "${watch.title}: not in TodayTix's Lottery & Rush list"

        // The "Enter now" button sits below the fold on the show page, and only exists while open.
        waitFor(10_000) { root()?.takeIf { findText("Key Details") != null || findText("Enter now") != null } }
        var enterNow: AccessibilityNodeInfo? = null
        for (attempt in 0 until 5) {
            enterNow = findText("Enter now")
            if (enterNow != null) break
            scroll(forward = true)
            delay(500)
        }
        val button = enterNow ?: run {
            back()
            return "${watch.title}: no lottery open right now"
        }
        click(button)

        // Ticket count (skipped by the app when only one option exists). If the show offers fewer than the
        // user asked for, the largest available is used and the summary says so.
        var ticketsPicked = job.tickets
        val step = waitFor(10_000) { findId("header_view")?.text?.toString() ?: findText("Select showtimes")?.text?.toString() }
            ?: throw Stop("the entry pop-up for ${watch.title} didn't appear")
        if (step == "How many tickets?") {
            val options = descendants().filter { it.text?.toString()?.trim()?.matches(Regex("\\d")) == true }.toList()
            if (options.isEmpty()) throw Stop("no ticket options for ${watch.title}")
            val pick = options.filter { it.text.toString().trim().toInt() <= job.tickets }
                .maxByOrNull { it.text.toString().trim().toInt() } ?: options.minBy { it.text.toString().trim().toInt() }
            ticketsPicked = pick.text.toString().trim().toInt()
            click(pick)
        }

        waitFor(10_000) { findText("Select showtimes") } ?: throw Stop("showtimes for ${watch.title} didn't appear")
        delay(600)

        // TodayTix arrives with EVERY showtime already ticked, so each box has to be set to exactly what we
        // want: ticked if it fits the user's days/times and isn't already entered, unticked otherwise.
        val rows = readShowtimeRows()
        if (rows.isEmpty()) throw Stop("couldn't read the showtimes for ${watch.title}")
        fun wantedState(row: Row): Pair<LocalDateTime?, Boolean> {
            val whenOf = parseShowtime(row.date, row.time)
            val want = whenOf != null && watch.accepts(whenOf, job.defaults) && entryKey(watch.title, row.date, row.time) !in entered
            return whenOf to want
        }
        for (row in rows) {
            if (row.box.isChecked != wantedState(row).second) {
                click(row.box)
                delay(350)
            }
        }
        // Check what actually ended up ticked, and refuse to submit anything we didn't intend.
        delay(400)
        val chosen = mutableListOf<LocalDateTime>()
        for (row in readShowtimeRows()) {
            val (whenOf, want) = wantedState(row)
            if (row.box.isChecked != want) throw Stop("couldn't set the showtimes for ${watch.title} correctly, so I didn't enter")
            if (want && whenOf != null) chosen += whenOf
        }
        chosen.forEach { whenOf ->
            val row = rows.first { parseShowtime(it.date, it.time) == whenOf }
            entered += entryKey(watch.title, row.date, row.time)
        }
        if (chosen.isEmpty()) {
            // Nothing to enter: step back out without submitting anything.
            findId("back_button")?.let { click(it) }
            delay(500)
            findId("close_button")?.let { click(it) }
            delay(500)
            return "${watch.title}: open, but nothing fits your days and times (or already entered)"
        }

        findId("next_button")?.let { click(it) } ?: throw Stop("no Next button for ${watch.title}")
        waitFor(10_000) { findText("Contact Information") } ?: throw Stop("contact screen for ${watch.title} didn't appear")
        delay(500)
        guardUnexpected()

        // This is the real submit.
        val enter = findId("action_button")?.takeIf { it.text?.toString()?.trim() == "Enter" }
            ?: throw Stop("no Enter button for ${watch.title}")
        click(enter)

        // After a real entry TodayTix offers "Share to increase odds". Seeing it confirms the entry.
        var confirmed = waitFor(8_000) { findText("Share to increase odds") }
        if (confirmed == null && findText("Contact Information") != null) {
            // This button ignores Android's accessibility "click" action (the earlier buttons accept it),
            // so press it with a real touch at its position instead.
            findId("action_button")?.let { tap(it) }
            confirmed = waitFor(15_000) { findText("Share to increase odds") }
        }
        if (confirmed == null) {
            guardUnexpected()
            throw Stop("pressed Enter for ${watch.title} but didn't see TodayTix confirm it, check your TodayTix orders")
        }
        findId("close_button")?.let { click(it) } // never the Instagram/Facebook share buttons
        delay(800)

        chosen.forEach { whenOf ->
            repo.addRecord(
                EntryRecord(
                    lotteryId = "TODAYTIX:${watch.title}:$whenOf",
                    showTitle = watch.title,
                    site = Site.TODAYTIX,
                    performances = listOf(whenOf.toString()),
                    atMillis = System.currentTimeMillis(),
                    status = EntryStatus.ENTERED,
                    message = "Entered through the TodayTix app",
                    deepLink = Site.TODAYTIX.homeUrl,
                ),
            )
        }
        fun tickets(n: Int) = "$n ticket${if (n == 1) "" else "s"}"
        val note = if (ticketsPicked != job.tickets) " (${tickets(ticketsPicked)}; your setting is ${job.tickets} but that's all it offered)" else " (${tickets(ticketsPicked)})"
        return "${watch.title}: entered ${chosen.size} performance${if (chosen.size == 1) "" else "s"}$note"
    }

    private class Row(val date: String, val time: String, val box: AccessibilityNodeInfo)

    private fun readShowtimeRows(): List<Row> =
        descendants().filter { it.idIs("date_label") }.mapNotNull { dateNode ->
            var parent = dateNode.parent
            repeat(4) {
                val p = parent ?: return@repeat
                val time = p.descendantsOf().firstOrNull { it.idIs("time_label") }
                val box = p.descendantsOf().firstOrNull { it.idIs("checkbox_view") }
                if (time != null && box != null) {
                    return@mapNotNull Row(dateNode.text.toString().trim(), time.text.toString().trim(), box)
                }
                parent = p.parent
            }
            null
        }.toList()

    // ------------------------------------------------------------------ what's already entered

    /** Keys like "the rocky horror show|sun, oct 11, 7:00 pm" from Orders > Lottery. */
    private suspend fun readEntered(): Set<String> {
        goHome()
        click(findDesc("Orders") ?: throw Stop("couldn't find TodayTix's Orders tab"))
        delay(1_000)
        click(waitFor(8_000) { findDesc("Lottery") } ?: throw Stop("couldn't find the Lottery tab in Orders"))
        delay(1_500)

        val out = mutableSetOf<String>()
        for (page in 0 until 15) {
            val titles = descendants().filter { it.idIs("show_title") }.map { it.text.toString().trim() }.toList()
            val dates = descendants().filter { it.idIs("show_date_part_1") }.map { it.text.toString().trim() }.toList()
            val before = out.size
            titles.zip(dates).forEach { (t, d) -> out += "${t.lowercase()}|${d.lowercase()}" }
            if (out.size == before) break // nothing new on this screen: that's the whole list
            if (!scroll(forward = true)) break
            delay(600)
        }
        return out
    }

    private fun entryKey(title: String, date: String, time: String) = "${title.lowercase()}|${date.lowercase()}, ${time.lowercase()}"

    // ------------------------------------------------------------------ navigation

    private suspend fun goHome() {
        repeat(6) {
            if (findDesc("Home") != null) {
                click(findDesc("Home")!!)
                delay(600)
                if (findId("filter_label") == null) {
                    findText("Lottery & Rush")?.let { click(it) }
                    waitFor(5_000) { findId("filter_label") }
                }
                return
            }
            back()
            delay(700)
        }
        throw Stop("couldn't get back to TodayTix's home screen")
    }

    private suspend fun openShowCard(title: String): Boolean {
        repeat(12) { if (!scroll(forward = false)) return@repeat; delay(250) } // to the top
        repeat(40) {
            checkBudget()
            val card = descendants().firstOrNull { it.contentDescription?.toString()?.trim().equals(title, ignoreCase = true) }
            if (card != null) {
                click(card)
                return true
            }
            if (!scroll(forward = true)) return false
            delay(450)
        }
        return false
    }

    private fun say(text: String) = AppAutomationService.say(text)

    private fun parseShowtime(date: String, time: String): LocalDateTime? = runCatching {
        // "Sun, Oct 11" has no year: drop the weekday, use this year, or next if that lands well in the past.
        val today = LocalDate.now()
        var d = LocalDate.parse("${date.substringAfter(", ")} ${today.year}", DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US))
        if (d.isBefore(today.minusDays(30))) d = d.plusYears(1)
        LocalDateTime.of(d, LocalTime.parse(time.uppercase(), DateTimeFormatter.ofPattern("h:mm a", Locale.US)))
    }.getOrNull()
}
