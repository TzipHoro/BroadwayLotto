package com.broadwaylotto.model

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Tiny JSON-in-SharedPreferences store. Plenty for a few dozen watches and a log. */
class Repo private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lotto", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _watches = MutableStateFlow(migrateWatches(load("watches", emptyList<Watch>())))

    /**
     * Hamilton used to be an "Other" shortcut (an Open button for its app). It's now a real site that
     * the app enters for, so an old shortcut becomes a Hamilton watch; its days and times then follow
     * the user's defaults. If a Hamilton watch already exists, the old shortcut is simply dropped.
     */
    private fun migrateWatches(saved: List<Watch>): List<Watch> {
        val isOldHamilton = { w: Watch -> w.site == Site.OTHER && w.link == "app:com.hamilton.app" }
        if (saved.none(isOldHamilton)) return saved
        val hasNew = saved.any { it.site == Site.HAMILTON }
        val migrated = saved.mapNotNull { w ->
            when {
                !isOldHamilton(w) -> w
                hasNew -> null
                else -> w.copy(site = Site.HAMILTON, link = null, slots = null)
            }
        }
        prefs.edit().putString("watches", json.encodeToString(migrated)).apply()
        return migrated
    }
    private val _log = MutableStateFlow(load("log", emptyList<EntryRecord>()))

    // The profile holds personal details (name, email, date of birth), so it lives in encrypted
    // storage, with its key in the Android Keystore, rather than in the plain prefs above.
    private val securePrefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "lotto_profile",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val _profile = MutableStateFlow(loadProfile())

    private fun loadProfile(): Profile {
        securePrefs.getString(PROFILE_KEY, null)
            ?.let { runCatching { json.decodeFromString<Profile>(it) }.getOrNull() }
            ?.let { return it }
        // One-time move of a profile saved by an older version in plain text.
        val legacy = prefs.getString("profile", null)?.let { runCatching { json.decodeFromString<Profile>(it) }.getOrNull() }
        if (legacy != null) {
            securePrefs.edit().putString(PROFILE_KEY, json.encodeToString(legacy)).apply()
            prefs.edit().remove("profile").apply()
            return legacy
        }
        return Profile()
    }

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

    // The user's one-time OK for the Hamilton runner to tick the app's two "I accept / I have reviewed"
    // boxes on their behalf. Saved so it only asks once.
    private val _hamiltonConsent = MutableStateFlow(prefs.getBoolean("hamilton_consent", false))
    val hamiltonConsent: StateFlow<Boolean> = _hamiltonConsent

    fun setHamiltonConsent(value: Boolean) {
        _hamiltonConsent.value = value
        prefs.edit().putBoolean("hamilton_consent", value).apply()
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

    /** Removes one item from the Activity list; per-lottery progress is kept, so it won't re-alert. */
    @Synchronized
    fun removeRecord(record: EntryRecord) {
        _log.value = _log.value.filterNot { it.lotteryId == record.lotteryId && it.atMillis == record.atMillis }
        save("log", _log.value)
    }

    /** Empties the Activity list only; per-lottery progress is kept. */
    @Synchronized
    fun clearLog() {
        _log.value = emptyList()
        save("log", _log.value)
    }

    fun setProfile(profile: Profile) {
        _profile.value = profile
        securePrefs.edit().putString(PROFILE_KEY, json.encodeToString(profile)).apply()
    }

    private inline fun <reified T> load(key: String, default: T): T =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: default

    private inline fun <reified T> save(key: String, value: T) {
        prefs.edit().putString(key, json.encodeToString(value)).apply()
    }

    companion object {
        private const val HANDLED = -1
        private const val PROFILE_KEY = "profile"
        @Volatile private var instance: Repo? = null
        fun get(context: Context): Repo =
            instance ?: synchronized(this) { instance ?: Repo(context).also { instance = it } }
    }
}
