package com.broadwaylotto.sites

import com.broadwaylotto.model.EntryResult
import com.broadwaylotto.model.Lottery
import com.broadwaylotto.model.Profile
import com.broadwaylotto.model.Site
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDateTime

/**
 * TodayTix. Its lotteries exist only inside the TodayTix phone app, so this adapter can do just one
 * thing: list the shows with a lottery (not rush-only ones) from TodayTix's public "Rush & Lottery"
 * web page for the show picker.
 * It reports no lotteries (the web page has no open times); entering is done by
 * [com.broadwaylotto.automation.AppAutomationService], driving the TodayTix app on the user's phone.
 */
class TodayTixSite(private val web: WebFetcher) : SiteAdapter {
    override val site = Site.TODAYTIX
    override val canListLotteries = false

    private val base = "https://www.todaytix.com"

    /**
     * Shows that have a lottery. The page lists rush and lottery shows together, but its embedded data
     * (`__NEXT_DATA__`) gives each show a `lowPriceForLotteryTickets`, which is null for rush-only shows.
     */
    override suspend fun shows(): List<String> {
        val doc = Jsoup.parse(web.get(base, "$base/nyc/category/rush-tickets"), base)
        val data = doc.getElementById("__NEXT_DATA__")?.data()
            ?: throw IllegalStateException("TodayTix's page layout changed (no show data)")
        val shows = Json.parseToJsonElement(data).jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
            ?.get("productList")?.jsonObject?.get("results")?.jsonObject?.get("shows")?.jsonArray
            ?: throw IllegalStateException("TodayTix's page layout changed (no show list)")
        return shows.mapNotNull { it.jsonObject }
            .filter { it["lowPriceForLotteryTickets"].let { price -> price != null && price !is JsonNull } }
            .mapNotNull { it["displayName"]?.jsonPrimitive?.content?.trim()?.takeIf { name -> name.isNotBlank() } }
            .distinct()
            .sorted()
    }

    override suspend fun fetchLotteries(now: Instant, onlyShows: Set<String>): List<Lottery> = emptyList()

    override suspend fun enter(lottery: Lottery, performances: List<LocalDateTime>, profile: Profile): EntryResult =
        EntryResult.NeedsManual("Use the TodayTix button in Settings")
}
