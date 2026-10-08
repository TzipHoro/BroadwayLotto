package com.broadwaylotto.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Tiny JSON-in-SharedPreferences store. Plenty for a few dozen watches and a log. */
class Repo private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lotto", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _watches = MutableStateFlow(load("watches", emptyList<Watch>()))
    private val _log = MutableStateFlow(load("log", emptyList<EntryRecord>()))
    private val _profile = MutableStateFlow(load("profile", Profile()))

    private val _defaults = MutableStateFlow(load("defaults", emptySet<Slot>()))

    /** Default availability, used by every show that hasn't set its own. */
    val defaults: StateFlow<Set<Slot>> = _defaults

    fun setDefaults(slots: Set<Slot>) {
        _defaults.value = slots
        save("defaults", slots)
    }

    // Cached show lists per site (keyed by Site.name), refreshed on app start and on demand.
    // Key is versioned so lists cached from the old mock sites are discarded.
    private val _shows = MutableStateFlow(load("shows_v2", emptyMap<String, List<String>>()))
    val shows: StateFlow<Map<String, List<String>>> = _shows

    fun setShows(site: Site, titles: List<String>) {
        _shows.value = _shows.value + (site.name to titles)
        save("shows_v2", _shows.value)
    }

    val watches: StateFlow<List<Watch>> = _watches
    val log: StateFlow<List<EntryRecord>> = _log
    val profile: StateFlow<Profile> = _profile

    @Synchronized
    fun upsert(watch: Watch) {
        val list = _watches.value
        _watches.value = if (list.any { it.id == watch.id }) list.map { if (it.id == watch.id) watch else it } else list + watch
        save("watches", _watches.value)
    }

    @Synchronized
    fun remove(id: String) {
        _watches.value = _watches.value.filterNot { it.id == id }
        save("watches", _watches.value)
    }

    // Per-lottery progress, kept apart from the visible log so clearing the log can't make us
    // re-enter or re-notify a lottery that's still open. HANDLED = entered or handed to the user;
    // anything else is the number of failed attempts so far.
    private val lotteryState: MutableMap<String, Int> =
        (load<Map<String, Int>?>("lottery_state", null) ?: rebuildLotteryState()).toMutableMap()

    private fun rebuildLotteryState(): Map<String, Int> {
        val state = mutableMapOf<String, Int>()
        for (r in _log.value.reversed()) bump(state, r)
        return state
    }

    private fun bump(state: MutableMap<String, Int>, r: EntryRecord) {
        if (r.lotteryId == "-") return
        state[r.lotteryId] = when {
            r.status != EntryStatus.FAILED -> HANDLED
            state[r.lotteryId] == HANDLED -> HANDLED
            else -> (state[r.lotteryId] ?: 0) + 1
        }
    }

    /** True if this lottery was entered / handed to the user, or has failed [maxFailures] times. */
    @Synchronized
    fun isDone(lotteryId: String, maxFailures: Int): Boolean {
        val s = lotteryState[lotteryId] ?: return false
        return s == HANDLED || s >= maxFailures
    }

    @Synchronized
    fun addRecord(record: EntryRecord) {
        _log.value = (listOf(record) + _log.value).take(300)
        save("log", _log.value)
        bump(lotteryState, record)
        save("lottery_state", lotteryState.toMap())
    }

    // What the last check found for each watch (watch id -> one line). In memory only: it's
    // recomputed on every check, so there's nothing worth persisting.
    private val _watchStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val watchStatus: StateFlow<Map<String, String>> = _watchStatus

    fun setWatchStatus(updates: Map<String, String>) {
        _watchStatus.value = _watchStatus.value + updates
    }

    /** Marks a lottery as handled without a log entry (it was folded into another alert). */
    @Synchronized
    fun markHandled(lotteryId: String) {
        lotteryState[lotteryId] = HANDLED
        save("lottery_state", lotteryState.toMap())
    }

    /** Forgets which lotteries were handled, so open ones trigger again. For development only. */
    @Synchronized
    fun resetLotteryState() {
        lotteryState.clear()
        save("lottery_state", lotteryState.toMap())
    }

    /** Empties the Activity list only; per-lottery progress is kept. */
    @Synchronized
    fun clearLog() {
        _log.value = emptyList()
        save("log", _log.value)
    }

    fun setProfile(profile: Profile) {
        _profile.value = profile
        save("profile", profile)
    }

    private inline fun <reified T> load(key: String, default: T): T =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: default

    private inline fun <reified T> save(key: String, value: T) {
        prefs.edit().putString(key, json.encodeToString(value)).apply()
    }

    companion object {
        private const val HANDLED = -1
        @Volatile private var instance: Repo? = null
        fun get(context: Context): Repo =
            instance ?: synchronized(this) { instance ?: Repo(context).also { instance = it } }
    }
}
