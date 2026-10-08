package com.broadwaylotto.sites

import com.broadwaylotto.model.EntryResult
import com.broadwaylotto.model.Lottery
import com.broadwaylotto.model.Profile
import com.broadwaylotto.model.Site
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Real Broadway Direct adapter.
 *
 * Listing is public HTML: the home page lists shows, and each show page has one table row per
 * performance (`tr.lotteries-row`, row id = performance time as a Unix timestamp) with its own
 * "Opens <date> at <time>" window. Broadway Direct lotteries are per performance, so each
 * [Lottery] here has exactly one performance.
 *
 * Entering is NOT automated yet: [enter] hands the user a notification that deep-links to the
 * show page the moment the lottery opens.
 */
class BroadwayDirectSite(private val web: WebFetcher) : SiteAdapter {
    override val site = Site.BROADWAY_DIRECT

    private val base = "https://lottery.broadwaydirect.com"
    private val zone = ZoneId.of("America/New_York")
    private val dateTime = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("MM/dd/yy h:mm a").toFormatter(Locale.US)

    private data class ShowRef(val title: String, val url: String)

    override suspend fun shows(): List<String> = showRefs().map { it.title }

    override suspend fun fetchLotteries(now: Instant, onlyShows: Set<String>): List<Lottery> =
        showRefs().filter { it.title in onlyShows }.flatMap { show -> parseShowPage(show, get(show.url), now) }

    override suspend fun enter(lottery: Lottery, performances: List<LocalDateTime>, profile: Profile): EntryResult =
        EntryResult.NeedsManual("It's open - tap to enter on Broadway Direct")

    private suspend fun showRefs(): List<ShowRef> {
        val doc = Jsoup.parse(get(base), base)
        return doc.select("a[href*=/show/]").mapNotNull { a ->
            val title = a.selectFirst("h4")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ShowRef(title, a.absUrl("href"))
        }.distinctBy { it.url }
    }

    private fun parseShowPage(show: ShowRef, html: String, now: Instant): List<Lottery> {
        val doc: Document = Jsoup.parse(html, show.url)
        val slug = show.url.trimEnd('/').substringAfterLast('/')
        return doc.select("tr.lotteries-row").mapNotNull { row ->
            val perfEpoch = row.id().toLongOrNull() ?: return@mapNotNull null
            val performance = LocalDateTime.ofInstant(Instant.ofEpochSecond(perfEpoch), zone)
            val status = row.selectFirst("td.lotteries-status")?.text().orEmpty()
            if (status.contains("Closed", ignoreCase = true)) return@mapNotNull null

            val opensAt = Regex("""Opens\s+(\d\d/\d\d/\d\d)\s+at\s+(\d{1,2}:\d\d\s*[ap]m)""", RegexOption.IGNORE_CASE)
                .find(status)?.let { parse(it.groupValues[1], it.groupValues[2]) }
            val closesAt = Regex("""Closes\s+(\d\d/\d\d/\d\d)\s+at\s+(\d{1,2}:\d\d\s*[ap]m)""", RegexOption.IGNORE_CASE)
                .find(status)?.let { parse(it.groupValues[1], it.groupValues[2]) }

            // Not closed and no "Opens" text means it's open now. Without a stated close time,
            // assume it stays open until curtain.
            Lottery(
                id = "${site.name}:$slug:$perfEpoch",
                site = site,
                showTitle = show.title,
                opensAt = opensAt ?: now.minusSeconds(1),
                closesAt = closesAt ?: Instant.ofEpochSecond(perfEpoch),
                performances = listOf(performance),
                deepLink = show.url,
            )
        }
    }

    private fun parse(date: String, time: String): Instant? = runCatching {
        LocalDateTime.parse("$date ${time.replace(Regex("\\s+"), " ").trim()}", dateTime).atZone(zone).toInstant()
    }.getOrNull()

    private suspend fun get(url: String): String = web.get(base, url)
}
