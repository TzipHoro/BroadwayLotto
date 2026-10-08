package com.broadwaylotto.ui

import android.app.Activity
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException

/**
 * Google Password Manager access through Android's Credential Manager.
 *
 * Both calls show a system sheet and need a foreground [Activity]. That is by design: Android never
 * hands a saved password to a background job, which is why re-signing-in after a session expires
 * needs one tap from the user.
 */
object SavedLogins {
    /** Offers to save [email]/[password]. Returns false if the user declined or it isn't available. */
    suspend fun save(activity: Activity, email: String, password: String): Boolean = try {
        CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest(id = email, password = password))
        true
    } catch (e: CreateCredentialException) {
        false
    }

    /** Asks the user to pick a saved login. Null if they cancel or none is saved. */
    suspend fun pick(activity: Activity): Pair<String, String>? = try {
        val result = CredentialManager.create(activity)
            .getCredential(activity, GetCredentialRequest(listOf(GetPasswordOption())))
        (result.credential as? PasswordCredential)?.let { it.id to it.password }
    } catch (e: GetCredentialException) {
        null
    }
}
