package com.broadwaylotto.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime

enum class Site(val label: String, val homeUrl: String) {
    BROADWAY_DIRECT("Broadway Direct", "https://lottery.broadwaydirect.com"),
    TODAYTIX("TodayTix", "https://www.todaytix.com"),
    LUCKY_SEAT("Lucky Seat", "https://www.luckyseat.com"),

    /** The official Hamilton app's Broadway lottery. Entered by tapping through that app, like TodayTix. */
    HAMILTON("Hamilton", "https://www.hamiltonmusical.com/lottery/"),

    /** Lotteries that run in some other show's app or site. Tracked with a shortcut, never automated. */
    OTHER("Other", ""),
}

enum class Weekday(val short: String) {
    MONDAY("Mon"), TUESDAY("Tue"), WEDNESDAY("Wed"), THURSDAY("Thu"),
    FRIDAY("Fri"), SATURDAY("Sat"), SUNDAY("Sun"),
}

/** Matinee = curtain before 5pm. Everything else is evening. */
enum class TimeSlot(val label: String) { MATINEE("Matinee"), EVENING("Evening") }

@Serializable
data class Slot(val day: Weekday, val time: TimeSlot)

/**
 * A show the user wants. [slots] is a per-show override of the day/time slots they can attend;
 * null means "use my default availability".
 */
@Serializable
data class Watch(
    val id: String,
    val title: String,
    val site: Site,
    val enabled: Boolean = true,
    val slots: Set<Slot>? = null,
    /**
     * For [Site.OTHER] shows: where to enter. "app:<package>" launches that Android app; anything else
     * is a web address. Unused for the other sites.
     */
    val link: String? = null,
) {
    val usesDefaults get() = slots == null

    fun effectiveSlots(defaults: Set<Slot>): Set<Slot> = slots ?: defaults

    fun accepts(performance: LocalDateTime, defaults: Set<Slot>): Boolean {
        val day = Weekday.valueOf(performance.dayOfWeek.name)
        val time = if (performance.hour < 17) TimeSlot.MATINEE else TimeSlot.EVENING
        return Slot(day, time) in effectiveSlots(defaults)
    }
}

/** What the user's entry details look like. Real adapters need more (login), mocks only need this. */
@Serializable
data class Profile(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val tickets: Int = 2,
    /** Date of birth as ISO yyyy-MM-dd, or blank. Broadway Direct asks for it. */
    val dob: String = "",
    /** Broadway Direct's entry form asks for these too. [country] is one of "USA", "CANADA", "OTHER". */
    val zip: String = "",
    val country: String = "USA",
) {
    /** Enough to be alerted. Entering on a site may need more (see [hasDob]). */
    val isComplete get() = firstName.isNotBlank() && lastName.isNotBlank() && email.isNotBlank()
    val hasDob get() = dob.isNotBlank()
}

/** One open-or-upcoming lottery as reported by a site. A lottery can cover many performances. */
data class Lottery(
    val id: String,
    val site: Site,
    val showTitle: String,
    val opensAt: Instant,
    val closesAt: Instant,
    val performances: List<LocalDateTime>,
    val deepLink: String,
    /** The site's own id for each performance, when it has one (Lucky Seat checkbox ids). */
    val performanceIds: Map<LocalDateTime, String> = emptyMap(),
)

sealed interface EntryResult {
    data object Entered : EntryResult
    /** CAPTCHA, expired login, changed layout... the user has to finish this one by hand. */
    data class NeedsManual(val reason: String) : EntryResult
    data class Failed(val reason: String) : EntryResult
}

/** An entry the user has made on a site, as that site reports it (not our own activity log). */
data class SiteEntry(
    val site: Site,
    val showTitle: String,
    val performance: LocalDateTime,
    val tickets: Int,
    val drawTime: LocalDateTime?,
    /** The site's own status line, e.g. "Drawing on Monday, October 12, 2026". */
    val result: String,
    val isWinner: Boolean,
    /** Where to claim tickets when [isWinner]; null otherwise. */
    val claimLink: String?,
)

enum class EntryStatus { ENTERED, NEEDS_MANUAL, FAILED }

@Serializable
data class EntryRecord(
    val lotteryId: String,
    val showTitle: String,
    val site: Site,
    val performances: List<String>,
    val atMillis: Long,
    val status: EntryStatus,
    val message: String,
    val deepLink: String,
    /** Site-specific ids of the performances to pre-tick on the entry page (Lucky Seat). */
    val preselect: List<String> = emptyList(),
    val tickets: Int = 0,
)
