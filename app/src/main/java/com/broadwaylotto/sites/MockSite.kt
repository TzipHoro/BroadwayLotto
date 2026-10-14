package com.broadwaylotto.sites

import com.broadwaylotto.model.EntryResult
import com.broadwaylotto.model.Lottery
import com.broadwaylotto.model.Profile
import com.broadwaylotto.model.Site
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Fake site that mimics each real site's *timing rules* so scheduling can be tested end to end:
 *  - Broadway Direct: window runs 10:00-18:00 the day before each performance day.
 *  - TodayTix:        same-day window 09:00-15:00.
 *  - Lucky Seat:      14-day windows covering performances in the following 14 days.
 * Entering always succeeds except TodayTix "Wicked", which pretends to hit a CAPTCHA.
 */
class MockSite(override val site: Site) : SiteAdapter {
    private val zone = ZoneId.of("America/New_York")

    private val catalog = when (site) {
        Site.BROADWAY_DIRECT -> listOf("Hamilton", "Six", "Moulin Rouge!")
        Site.TODAYTIX -> listOf("Wicked", "Chicago", "Hadestown")
        Site.LUCKY_SEAT -> listOf("Hamilton", "Hadestown", "The Lion King")
        Site.HAMILTON, Site.OTHER -> emptyList()
    }

    private val weekly: Map<DayOfWeek, List<LocalTime>> = mapOf(
        DayOfWeek.TUESDAY to listOf(LocalTime.of(19, 0)),
        DayOfWeek.WEDNESDAY to listOf(LocalTime.of(14, 0), LocalTime.of(19, 0)),
        DayOfWeek.THURSDAY to listOf(LocalTime.of(19, 0)),
        DayOfWeek.FRIDAY to listOf(LocalTime.of(20, 0)),
        DayOfWeek.SATURDAY to listOf(LocalTime.of(14, 0), LocalTime.of(20, 0)),
        DayOfWeek.SUNDAY to listOf(LocalTime.of(15, 0)),
    )

    override suspend fun shows(): List<String> = catalog

    override suspend fun fetchLotteries(now: Instant, onlyShows: Set<String>): List<Lottery> {
        val today = now.atZone(zone).toLocalDate()
        return catalog.flatMap { title ->
            when (site) {
                Site.BROADWAY_DIRECT -> (0L..1L).map { offset ->
                    val d = today.plusDays(offset)
                    lottery(title, d.at(10, 0), d.at(18, 0), performancesOn(d.plusDays(1)))
                }
                Site.TODAYTIX -> (0L..1L).map { offset ->
                    val d = today.plusDays(offset)
                    lottery(title, d.at(9, 0), d.at(15, 0), performancesOn(d))
                }
                Site.LUCKY_SEAT -> {
                    val cycleStart = LocalDate.ofEpochDay(Math.floorDiv(today.toEpochDay(), 14L) * 14L)
                    (0L..1L).map { cycle ->
                        val opens = cycleStart.plusDays(14 * cycle)
                        val closes = opens.plusDays(14)
                        lottery(title, opens.at(0, 0), closes.at(0, 0), (0L until 14L).flatMap { performancesOn(closes.plusDays(it)) })
                    }
                }
                Site.HAMILTON, Site.OTHER -> emptyList()
            }
        }
    }

    override suspend fun enter(lottery: Lottery, performances: List<LocalDateTime>, profile: Profile): EntryResult {
        delay(300)
        return if (site == Site.TODAYTIX && lottery.showTitle == "Wicked") {
            EntryResult.NeedsManual("CAPTCHA required")
        } else {
            EntryResult.Entered
        }
    }

    private fun performancesOn(date: LocalDate) =
        weekly[date.dayOfWeek].orEmpty().map { LocalDateTime.of(date, it) }

    private fun LocalDate.at(h: Int, m: Int): Instant = atTime(h, m).atZone(zone).toInstant()

    private fun lottery(title: String, opens: Instant, closes: Instant, perfs: List<LocalDateTime>) = Lottery(
        id = "${site.name}:$title:${opens.epochSecond}",
        site = site,
        showTitle = title,
        opensAt = opens,
        closesAt = closes,
        performances = perfs,
        deepLink = site.homeUrl,
    )
}
