package com.broadwaylotto.sites

import com.broadwaylotto.model.EntryResult
import com.broadwaylotto.model.Lottery
import com.broadwaylotto.model.Profile
import com.broadwaylotto.model.Site
import java.time.Instant
import java.time.LocalDateTime

/**
 * The official Hamilton app's Broadway lottery. Like TodayTix, it exists only inside a phone app:
 * there's no web listing, so this adapter reports no lotteries and offers the one show. Entering is
 * done by [com.broadwaylotto.automation.AppAutomationService] tapping through the Hamilton app.
 */
class HamiltonSite : SiteAdapter {
    override val site = Site.HAMILTON
    override val canListLotteries = false

    override suspend fun shows(): List<String> = listOf("Hamilton")

    override suspend fun fetchLotteries(now: Instant, onlyShows: Set<String>): List<Lottery> = emptyList()

    override suspend fun enter(lottery: Lottery, performances: List<LocalDateTime>, profile: Profile): EntryResult =
        EntryResult.NeedsManual("Use the Enter button on the show's card")
}
