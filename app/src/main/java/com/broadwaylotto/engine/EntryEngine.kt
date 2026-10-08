package com.broadwaylotto.engine

import android.content.Context
import com.broadwaylotto.model.EntryRecord
import com.broadwaylotto.model.EntryResult
import com.broadwaylotto.model.EntryStatus
import com.broadwaylotto.model.Lottery
import com.broadwaylotto.model.Repo
import com.broadwaylotto.model.Site
import com.broadwaylotto.model.Slot
import com.broadwaylotto.model.Watch
import com.broadwaylotto.sites.Sites
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MAX_FAILED_ATTEMPTS = 3

/** A lottery that's open now, with the performances in it the user can attend. */
private data class OpenLottery(val lottery: Lottery, val wanted: List<LocalDateTime>)

/**
 * Core loop: for every enabled watch, find lotteries for that show that include at least one
 * performance the user can attend, and enter the ones that are open right now (once each).
 */
class EntryEngine(private val context: Context) {
    private val repo = Repo.get(context)

    /** Returns the next moment a matching lottery opens, so the caller can schedule a wake-up. */
    suspend fun run(now: Instant = Instant.now()): Instant? {
        val profile = repo.profile.value
        val defaults = repo.defaults.value
        val watches = repo.watches.value.filter { it.enabled && it.effectiveSlots(defaults).isNotEmpty() }
        var nextOpen: Instant? = null

        for ((site, siteWatches) in watches.groupBy { it.site }) {
            val adapter = Sites.all[site] ?: continue
            val lotteries = try {
                adapter.fetchLotteries(now, siteWatches.mapTo(mutableSetOf()) { it.title })
            } catch (e: Exception) {
                // This runs every 15 minutes; don't flood the log with the same outage.
                val message = "Couldn't load lotteries: ${e.message}"
                val last = repo.log.value.firstOrNull()
                if (last == null || last.lotteryId != "-" || last.site != site || last.message != message) {
                    record(site, "-", site.label, emptyList(), EntryStatus.FAILED, message, site.homeUrl)
                }
                repo.setWatchStatus(siteWatches.associate { it.id to "Couldn't check ${site.label}" })
                continue
            }
            repo.setWatchStatus(siteWatches.associate { it.id to describe(it, site, lotteries, defaults, now) })

            // A show can have several lotteries open at once (Lucky Seat opens a new batch of
            // performances each week) while its entry form lists them all. So gather everything the
            // user could enter right now, per show, and act once per show.
            val openByShow = mutableMapOf<String, MutableList<OpenLottery>>()
            for (lottery in lotteries) {
                val watch = siteWatches.firstOrNull { it.title == lottery.showTitle } ?: continue
                val wanted = lottery.performances.filter { watch.accepts(it, defaults) }
                if (wanted.isEmpty() || !lottery.closesAt.isAfter(now)) continue

                if (lottery.opensAt.isAfter(now)) {
                    if (nextOpen == null || lottery.opensAt.isBefore(nextOpen)) nextOpen = lottery.opensAt
                    continue
                }
                openByShow.getOrPut(lottery.showTitle) { mutableListOf() } += OpenLottery(lottery, wanted)
            }
            if (!profile.isComplete) continue

            for ((title, ready) in openByShow) {
                // Only alert if something here is new; already-alerted batches still feed the pre-ticked list.
                val pending = ready.filterNot { alreadyHandled(it.lottery.id) }
                if (pending.isEmpty()) continue

                val lead = pending.first().lottery
                val allWanted = ready.flatMap { it.wanted }.distinct().sorted()
                val result = try {
                    adapter.enter(lead, allWanted, profile)
                } catch (e: Exception) {
                    EntryResult.Failed(e.message ?: "Unknown error")
                }
                val (status, message) = when (result) {
                    EntryResult.Entered -> EntryStatus.ENTERED to "Entered for ${allWanted.size} performance(s)"
                    is EntryResult.NeedsManual -> EntryStatus.NEEDS_MANUAL to "Finish by hand: ${result.reason}"
                    is EntryResult.Failed -> EntryStatus.FAILED to result.reason
                }
                // Remembered so the in-app browser can tick exactly these performances when the link is opened.
                val preselect = ready.flatMap { r -> r.wanted.mapNotNull { r.lottery.performanceIds[it] } }.distinct()
                val rec = record(site, lead.id, title, allWanted.map { it.toString() }, status, message, lead.deepLink, preselect, profile.tickets)
                if (status != EntryStatus.FAILED) pending.drop(1).forEach { repo.markHandled(it.lottery.id) }
                Notifier.entry(context, rec)
            }
        }
        if (Sites.luckySeatAuth.consumeExpiredNotice()) {
            Notifier.simple(
                context, 1001, "Signed out of Lucky Seat",
                "Open Broadway Lotto > Settings to sign back in. Until then, performances you've already entered aren't tracked.",
            )
        }
        return nextOpen
    }

    /** One line explaining what the last check found for [watch], shown on its card. */
    private fun describe(watch: Watch, site: Site, lotteries: List<Lottery>, defaults: Set<Slot>, now: Instant): String {
        val mine = lotteries.filter { it.showTitle == watch.title }
        if (mine.isEmpty()) return "Not listed on ${site.label} right now"

        // (lottery, performance) pairs the user could still enter and attend.
        val matching = mine.filter { it.closesAt.isAfter(now) }
            .flatMap { l -> l.performances.filter { watch.accepts(it, defaults) }.map { l } }
        if (matching.isEmpty()) return "Nothing left that fits your days and times"

        val openNow = matching.count { !it.opensAt.isAfter(now) }
        if (openNow > 0) return "$openNow matching performance${if (openNow == 1) "" else "s"} open now"

        val next = matching.minOf { it.opensAt }
        val fmt = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.getDefault())
        return "Next opens ${next.atZone(ZoneId.systemDefault()).format(fmt)}"
    }

    /** Entered or handed to the user = done. Failures are retried a few times. */
    private fun alreadyHandled(lotteryId: String): Boolean = repo.isDone(lotteryId, MAX_FAILED_ATTEMPTS)

    private fun record(
        site: Site, lotteryId: String, title: String, perfs: List<String>,
        status: EntryStatus, message: String, link: String,
        preselect: List<String> = emptyList(), tickets: Int = 0,
    ) = EntryRecord(lotteryId, title, site, perfs, System.currentTimeMillis(), status, message, link, preselect, tickets)
        .also(repo::addRecord)
}
