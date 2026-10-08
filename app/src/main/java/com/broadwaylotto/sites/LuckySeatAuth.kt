package com.broadwaylotto.sites

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/**
 * Lucky Seat sign-in and session.
 *
 * The password is NEVER stored by this app. It passes through memory to the login call, and the
 * UI offers it to Google Password Manager (Credential Manager) for safekeeping. What we keep is the
 * session token Lucky Seat hands back (valid ~20 days), encrypted with an Android Keystore key.
 * When it expires or is rejected we drop it and ask the user to sign in again.
 */
class LuckySeatAuth(context: Context, private val web: WebFetcher) {
    private val base = "https://www.luckyseat.com"
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "luckyseat_session",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** What the UI needs: who's signed in and until when. Never contains the token itself. */
    data class State(val email: String?, val expiresAtMillis: Long, val expired: Boolean) {
        val signedIn get() = email != null && !expired
    }

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<State> = _state

    /** The bearer token if we have a live session, else null. Expired tokens are treated as signed out. */
    fun token(): String? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        if (System.currentTimeMillis() >= prefs.getLong(KEY_EXPIRY, 0)) {
            markExpired()
            return null
        }
        return token
    }

    /** Throws with a user-readable message on failure. */
    suspend fun login(email: String, password: String) {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString()
        val reply = try {
            web.postJson(base, "$base/api/account/login", body)
        } catch (e: WebFetcher.HttpStatusException) {
            throw IllegalStateException(
                if (e.status == 400 || e.status == 401 || e.status == 404) "Wrong email or password" else "Lucky Seat sign-in failed (HTTP ${e.status})",
            )
        }
        val obj = json.parseToJsonElement(reply).jsonObject
        val token = obj["token"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Lucky Seat didn't return a session")
        val expiry = expiryOf(token)
        fun field(name: String) = obj[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        prefs.edit()
            .putString(KEY_TOKEN, token).putString(KEY_EMAIL, email).putLong(KEY_EXPIRY, expiry).putBoolean(KEY_EXPIRED, false)
            // The Lucky Seat web app expects these next to the token, so the in-app browser can start signed in.
            .putString(KEY_FIRST_NAME, field("firstName")).putString(KEY_PHONE, field("phone"))
            .putString(KEY_PHONE_VERIFIED, field("phoneVerified"))
            .apply()
        _state.value = readState()
    }

    /**
     * The values Lucky Seat's own web app keeps in browser storage after sign-in, for pre-seeding the
     * in-app browser. Null when there's no live session. Treat as secret: never log.
     */
    suspend fun browserSession(): Map<String, String>? {
        val token = token() ?: return null
        // The site bounces signed-in users with no phone number to its account-settings page. Sessions
        // saved by older versions of this app lack those details, so fetch them once and keep them.
        if (prefs.getString(KEY_PHONE, "").isNullOrEmpty()) refreshProfile(token)
        return mapOf(
            "token" to token,
            "email" to prefs.getString(KEY_EMAIL, "").orEmpty(),
            "firstName" to prefs.getString(KEY_FIRST_NAME, "").orEmpty(),
            "phone" to prefs.getString(KEY_PHONE, "").orEmpty(),
            "phoneVerified" to prefs.getString(KEY_PHONE_VERIFIED, "").orEmpty(),
        )
    }

    private suspend fun refreshProfile(token: String) {
        try {
            val details = json.parseToJsonElement(
                web.get(base, "$base/api/account/getLoggedInUserDetails", mapOf("Authorization" to "Bearer $token")),
            ).jsonObject
            fun field(name: String) = details[name]?.jsonPrimitive?.contentOrNull.orEmpty()
            prefs.edit()
                .putString(KEY_FIRST_NAME, field("firstName"))
                .putString(KEY_PHONE, field("mobileNumber"))
                .putString(KEY_PHONE_VERIFIED, field("phoneVerified"))
                .apply()
        } catch (e: WebFetcher.HttpStatusException) {
            if (e.status == 401 || e.status == 403) markExpired()
        } catch (e: java.io.IOException) {
            // Offline: open the browser anyway; worst case the site shows account settings.
        }
    }

    /** User chose to sign out: forget everything. */
    fun signOut() {
        prefs.edit().clear().apply()
        _state.value = readState()
    }

    /** The server rejected the token, or it timed out: keep the email so the UI can say who to sign back in as. */
    fun markExpired() {
        prefs.edit().remove(KEY_TOKEN).putBoolean(KEY_EXPIRED, true).putBoolean(KEY_NOTIFY, true).apply()
        _state.value = readState()
    }

    /** True once after a session expired, so the engine can send a single "sign back in" notification. */
    fun consumeExpiredNotice(): Boolean {
        if (!prefs.getBoolean(KEY_NOTIFY, false)) return false
        prefs.edit().putBoolean(KEY_NOTIFY, false).apply()
        return true
    }

    private fun readState(): State {
        val email = prefs.getString(KEY_EMAIL, null)
        val hasToken = prefs.getString(KEY_TOKEN, null) != null
        val expiry = prefs.getLong(KEY_EXPIRY, 0)
        val expired = email != null && (!hasToken || System.currentTimeMillis() >= expiry)
        return State(email, expiry, expired)
    }

    /** JWT `exp` claim (seconds) as millis; falls back to 7 days if the token isn't a readable JWT. */
    private fun expiryOf(token: String): Long = runCatching {
        val payload = token.split('.')[1]
        val claims: JsonObject = json.parseToJsonElement(
            String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)),
        ).jsonObject
        claims["exp"]!!.jsonPrimitive.longOrNull!! * 1000
    }.getOrElse { System.currentTimeMillis() + 7L * 24 * 3600 * 1000 }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_EMAIL = "email"
        const val KEY_EXPIRY = "expiry"
        const val KEY_EXPIRED = "expired"
        const val KEY_NOTIFY = "notify"
        const val KEY_FIRST_NAME = "firstName"
        const val KEY_PHONE = "phone"
        const val KEY_PHONE_VERIFIED = "phoneVerified"
    }
}
