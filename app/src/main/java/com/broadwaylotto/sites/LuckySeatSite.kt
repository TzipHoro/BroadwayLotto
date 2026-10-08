package com.broadwaylotto.sites

import com.broadwaylotto.model.EntryResult
import com.broadwaylotto.model.Lottery
import com.broadwaylotto.model.Profile
import com.broadwaylotto.model.Site
import com.broadwaylotto.model.SiteEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lucky Seat adapter, New York market only for now.
 *
 * Lucky Seat has no public API, but its own web app reads two unauthenticated JSON endpoints that
 * we use for listings:
 *  - /api/showmanagement/getshowlist                              -> every show, with market/timezone
 *  - /api/performancemanagement/getfirstshowperformancesschedule/<showId> -> performances, each with
 *    its own open string and close time
 * These are internal and could change without notice; failures surface as a logged error, not a crash.
 *
 * Performances that open at the same moment are grouped into ONE [Lottery] (Lucky Seat lets you pick
 * several performances in a single entry), so you get one notification per show per opening.
 *
 * Entering can't be fully automated: Lucky Seat's `createentry` call carries a reCAPTCHA response
 * that the site verifies, so the user must tick it. [enter] hands them a notification that deep-links
 * to the show page. Signing in (see [LuckySeatAuth]) lets us skip performances already entered.
 */
class LuckySeatSite(private val web: WebFetcher, private val auth: LuckySeatAuth) : SiteAdapter {
    override val site = Site.LUCKY_SEAT

    private val base = "https://www.luckyseat.com"
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val openFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a", Locale.US)

    @Serializable
    private data class ShowDto(
        val showId: Int,
        val showName: String,
        val showAlias: String,
        val market: String? = null,
        val timezone: String? = null,
        val isActive: Boolean = true,
    )

    @Serializable
    private data class PerfDto(
        val performanceId: Int,
        val perfSchedule: String,
        val openString: String,
        val closeTime: String,
        /** Only meaningful on the signed-in schedule. (Sic: that's how Lucky Seat spells it.) */
        val userEntred: Boolean = false,
    )

    @Serializable
    private data class ScheduleDto(val perfDateTimeList: List<PerfDto> = emptyList())

    @Serializable
    private data class EntryDto(
        val showName: String = "",
        val perfId: Int = 0,
        val perfSchedule: String = "",
        val selectedTickets: Int = 0,
        val drawTime: String? = null,
        val isWinner: Boolean = false,
        val result: String? = null,
        val claimable: Boolean = false,
    )

    override suspend fun myEntries(): List<SiteEntry> {
        val token = auth.token() ?: throw IllegalStateException("Sign in to Lucky Seat in Settings to see your entries")
        val seen = linkedMapOf<String, EntryDto>()
        // Pages of entries; stop at the first page that adds nothing new (the site doesn't say how many pages).
        for (page in 1..10) {
            val batch = try {
                json.decodeFromString<List<EntryDto>>(
                    web.get(base, "$base/api/entrymanagement/getallentries/$page", mapOf("Authorization" to "Bearer $token")),
                )
            } catch (e: WebFetcher.HttpStatusException) {
                if (e.status == 401 || e.status == 403) {
                    auth.markExpired()
                    throw IllegalStateException("Your Lucky Seat session expired. Sign in again in Settings.")
                }
                throw e
            }
            val added = batch.count { seen.putIfAbsent("${it.perfId}:${it.showName}:${it.perfSchedule}", it) == null }
            if (added == 0) break
        }
        return seen.values.mapNotNull { e ->
            runCatching {
                SiteEntry(
                    site = site,
                    showTitle = e.showName,
                    performance = LocalDateTime.parse(e.perfSchedule),
                    tickets = e.selectedTickets,
                    drawTime = e.drawTime?.let { LocalDateTime.parse(it) },
                    result = e.result.orEmpty(),
                    isWinner = e.isWinner,
                    claimLink = if (e.isWinner && e.claimable) "$base/dash/mydashboard" else null,
                )
            }.getOrNull()
        }
    }

    override suspend fun shows(): List<String> = nycShows().map { it.showName }.distinct().sorted()

    override suspend fun fetchLotteries(now: Instant, onlyShows: Set<String>): List<Lottery> =
        nycShows().filter { it.showName in onlyShows }.flatMap { show ->
            toLotteries(show, schedule(show.showId).perfDateTimeList, now)
        }

    /**
     * Signed in: the member schedule, which flags performances you've already entered (`userEntred`)
     * so we never nag you about them. Signed out, or if the token is rejected: the public schedule.
     */
    private suspend fun schedule(showId: Int): ScheduleDto {
        val token = auth.token()
        if (token != null) {
            try {
                return json.decodeFromString(
                    web.get(base, "$base/api/performancemanagement/getshowperformancesschedule/$showId", mapOf("Authorization" to "Bearer $token")),
                )
            } catch (e: WebFetcher.HttpStatusException) {
                if (e.status != 401 && e.status != 403) throw e
                auth.markExpired()
            }
        }
        return json.decodeFromString(get("$base/api/performancemanagement/getfirstshowperformancesschedule/$showId"))
    }

    override suspend fun enter(lottery: Lottery, performances: List<LocalDateTime>, profile: Profile): EntryResult =
        EntryResult.NeedsManual("It's open - tap to enter on Lucky Seat")

    private suspend fun nycShows(): List<ShowDto> =
        json.decodeFromString<List<ShowDto>>(get("$base/api/showmanagement/getshowlist"))
            .filter { it.isActive && it.market.orEmpty().startsWith("New York", ignoreCase = true) }

    private fun toLotteries(show: ShowDto, perfs: List<PerfDto>, now: Instant): List<Lottery> {
        val zone = ZoneId.of(show.timezone ?: "America/New_York")
        data class Parsed(val id: Int, val perf: LocalDateTime, val opens: Instant, val closes: Instant)

        val parsed = perfs.filterNot { it.userEntred }.mapNotNull { p ->
            runCatching {
                Parsed(
                    id = p.performanceId,
                    perf = LocalDateTime.parse(p.perfSchedule),
                    // "Monday, September 28, 2026 12:00 PM EDT" -> drop the zone suffix, use the show's zone.
                    opens = LocalDateTime.parse(p.openString.substringBeforeLast(' ').trim(), openFormat).atZone(zone).toInstant(),
                    closes = LocalDateTime.parse(p.closeTime).atZone(zone).toInstant(),
                )
            }.getOrNull()
        }.filter { it.closes.isAfter(now) } // already-closed performances can't be entered

        return parsed.groupBy { it.opens }.map { (opens, group) ->
            Lottery(
                id = "${site.name}:${show.showAlias}:${opens.epochSecond}",
                site = site,
                showTitle = show.showName,
                opensAt = opens,
                closesAt = group.maxOf { it.closes },
                performances = group.map { it.perf },
                deepLink = "$base/shows/${show.showAlias}",
                // The entry page's checkboxes use the performance id as their element id.
                performanceIds = group.associate { it.perf to it.id.toString() },
            )
        }
    }

    private suspend fun get(url: String): String = web.get(base, url)
}
