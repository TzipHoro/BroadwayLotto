package com.broadwaylotto.sites

import com.broadwaylotto.model.EntryResult
import com.broadwaylotto.model.Lottery
import com.broadwaylotto.model.Profile
import com.broadwaylotto.model.Site
import java.time.Instant
import java.time.LocalDateTime

/**
 * One implementation per lottery site. The engine only talks to this interface, so swapping
 * [MockSite] for a real implementation (WebView automation, etc.) touches nothing else.
 */
interface SiteAdapter {
    val site: Site

    /** Shows this site runs lotteries for, for the "add a show" picker. */
    suspend fun shows(): List<String>

    /**
     * Open and upcoming lotteries as of [now], for the shows in [onlyShows] (titles as returned by
     * [shows]). Implementations that cost a request per show should skip the rest.
     */
    suspend fun fetchLotteries(now: Instant, onlyShows: Set<String>): List<Lottery>

    /**
     * The user's own entries on this site. Empty if the site has no way to tell us; throws with a
     * user-readable message if they need to sign in first.
     */
    suspend fun myEntries(): List<com.broadwaylotto.model.SiteEntry> = emptyList()

    /** Enter [lottery] for the given [performances] (already filtered to the user's availability). */
    suspend fun enter(lottery: Lottery, performances: List<LocalDateTime>, profile: Profile): EntryResult
}

object Sites {
    lateinit var all: Map<Site, SiteAdapter>
        private set
    lateinit var luckySeatAuth: LuckySeatAuth
        private set

    /** Called once from [com.broadwaylotto.App]. Swap in real adapters here as they're built. */
    fun init(context: android.content.Context) {
        val web = WebFetcher(context)
        luckySeatAuth = LuckySeatAuth(context, web)
        all = Site.entries.associateWith {
            when (it) {
                Site.BROADWAY_DIRECT -> BroadwayDirectSite(web)
                Site.LUCKY_SEAT -> LuckySeatSite(web, luckySeatAuth)
                else -> MockSite(it)
            }
        }
    }

    /**
     * Re-fetches the show list for every site into [repo]'s cache. A site whose fetch fails keeps
     * its previously cached list. Returns the error message for each site that failed (empty = all ok).
     */
    suspend fun refreshShows(repo: com.broadwaylotto.model.Repo): Map<Site, String> {
        val errors = mutableMapOf<Site, String>()
        for ((site, adapter) in all) {
            try {
                val titles = adapter.shows()
                if (titles.isEmpty()) errors[site] = "no shows found" else repo.setShows(site, titles)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors[site] = e.message ?: e.javaClass.simpleName
            }
        }
        return errors
    }
}
