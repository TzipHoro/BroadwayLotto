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
import java.util.Locale

/**
 * Real Broadway Direct adapter.
 *
 * Listing is public HTML: the home page lists shows, and each show page has one table row per
 * performance (`tr.lotteries-row`, row id = performance time as a Unix timestamp) with its own
 * "Opens <date> at <time>" window. Broadway Direct lotteries are per performance, so each
 * [Lottery] here has exactly one performance.
 *
 * Entering can't be fully automated: the entry form carries a Cloudflare Turnstile bot check and an
 * "I agree" box. [enter] hands the user a notification that opens the lottery's entry page inside the
 * app with the saved profile filled in (see ui/SiteBrowser.kt); they tick the agreement, pass the
 * check and press Enter.
 */
class BroadwayDirectSite(private val web: WebFetcher) : SiteAdapter {
    override val site = Site.BROADWAY_DIRECT

    private val base = "https://lottery.broadwaydirect.com"
    private val zone = ZoneId.of("America/New_York")

    private data class ShowRef(val title: String, val url: String)

    override suspend fun shows(): List<String> = showRefs().map { it.title }

    override suspend fun fetchLotteries(now: Instant, onlyShows: Set<String>): List<Lottery> =
        showRefs().filter { it.title in onlyShows }.flatMap { show -> parseShowPage(show, get(show.url), now) }

    override suspend fun enter(lottery: Lottery, performances: List<LocalDateTime>, profile: Profile): EntryResult =
        EntryResult.NeedsManual("tap to open the entry form, already filled in")

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

            // The wording changes with the date. Before midnight a lottery reads "Opens 10/13/26 at 9:00 am";
            // after midnight the same one reads "Opens Today at 9:00 am"; an open one reads
            // "Active/ Closes Today at 3:00 pm" and has an Enter link.
            val opensAt = parseMoment(status, "Opens", now)
            val closesAt = parseMoment(status, "Closes", now)
            val enterLink = row.selectFirst("a[href*=enter-lottery]")?.absUrl("href")?.takeIf { it.isNotBlank() }

            val upcoming = status.contains("Opens", ignoreCase = true)
            val open = enterLink != null || status.contains("Active", ignoreCase = true)
            val opens: Instant = when {
                // An upcoming lottery whose time we can't read is skipped, never treated as open: that's
                // what used to send "it's open" alerts at midnight.
                upcoming -> opensAt ?: return@mapNotNull null
                open -> now.minusSeconds(1)
                else -> return@mapNotNull null // wording we don't recognise: don't guess
            }

            Lottery(
                id = "${site.name}:$slug:$perfEpoch",
                site = site,
                showTitle = show.title,
                opensAt = opens,
                closesAt = closesAt ?: Instant.ofEpochSecond(perfEpoch),
                performances = listOf(performance),
                deepLink = enterLink ?: show.url,
            )
        }
    }

    /**
     * The time after [keyword] ("Opens" / "Closes") in a status like "Opens 10/13/26 at 9:00 am",
     * "Opens Today at 9:00 am" or "Closes Tomorrow at 3:00 pm", read in the venue's time zone.
     */
    private fun parseMoment(status: String, keyword: String, now: Instant): Instant? {
        val m = Regex("""$keyword\s+(today|tomorrow|\d\d/\d\d/\d\d)\s+at\s+(\d{1,2}:\d\d\s*[ap]m)""", RegexOption.IGNORE_CASE)
            .find(status) ?: return null
        val today = now.atZone(zone).toLocalDate()
        val date = when (val word = m.groupValues[1].lowercase()) {
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            else -> runCatching { java.time.LocalDate.parse(word, java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US)) }.getOrNull()
                ?: return null
        }
        val time = runCatching {
            java.time.LocalTime.parse(m.groupValues[2].replace(Regex("\\s+"), " ").trim().uppercase(), java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        }.getOrNull() ?: return null
        return date.atTime(time).atZone(zone).toInstant()
    }

    private suspend fun get(url: String): String = web.get(base, url)
}
