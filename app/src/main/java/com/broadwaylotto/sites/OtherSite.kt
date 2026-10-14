package com.broadwaylotto.sites

import com.broadwaylotto.model.EntryResult
import com.broadwaylotto.model.Lottery
import com.broadwaylotto.model.Profile
import com.broadwaylotto.model.Site
import java.time.Instant
import java.time.LocalDateTime

/**
 * Catch-all for lotteries that run in a show's own app or website (Hamilton's official app, for one).
 * There is nothing to list, check or enter from here, so these shows are simply kept in the user's
 * list with a one-tap shortcut to where they enter. The user adds them by hand or from [OtherPresets].
 */
class OtherSite : SiteAdapter {
    override val site = Site.OTHER
    override val canListLotteries = false
    override val hasCatalog = false

    override suspend fun shows(): List<String> = emptyList()

    override suspend fun fetchLotteries(now: Instant, onlyShows: Set<String>): List<Lottery> = emptyList()

    override suspend fun enter(lottery: Lottery, performances: List<LocalDateTime>, profile: Profile): EntryResult =
        EntryResult.NeedsManual("Enter in the show's own app or website")
}

/**
 * Known "own app" lotteries offered as one-tap additions in the Other tab. A preset keeps its own
 * [site] so it can be automated (Hamilton is entered by [HamiltonSite]'s runner) while still being
 * listed under "Other", since it's a single show that doesn't warrant a tab of its own.
 */
object OtherPresets {
    data class Preset(val title: String, val site: Site, val where: String)

    val all = listOf(
        Preset("Hamilton", Site.HAMILTON, "Entered for you through the Hamilton app"),
    )
}
