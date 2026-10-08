package com.broadwaylotto.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.broadwaylotto.sites.Sites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun Context.findActivity(): Activity {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    error("No Activity found")
}

@Composable
fun LuckySeatAccountCard() {
    val auth = Sites.luckySeatAuth
    val state by auth.state.collectAsState()
    var dialog by remember { mutableStateOf(false) }
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Lucky Seat", style = MaterialTheme.typography.titleMedium)
            when {
                state.signedIn -> {
                    Text("Signed in as ${state.email}", fontSize = 14.sp)
                    Text("Session valid until ${dateFmt.format(Date(state.expiresAtMillis))}. Performances you've already entered are skipped.", color = Muted, fontSize = 13.sp)
                    TextButton(onClick = auth::signOut) { Text("Sign out", color = Bad) }
                }
                state.expired -> {
                    Text("Signed out - your session expired", color = Warn, fontWeight = FontWeight.SemiBold)
                    Text("Sign back in as ${state.email} to keep track of what you've entered.", color = Muted, fontSize = 13.sp)
                    Button(onClick = { dialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("Sign in again") }
                }
                else -> {
                    Text(
                        "Sign in so the app can skip performances you've already entered. Your password goes to Google Password Manager, never into this app's storage.",
                        color = Muted, fontSize = 13.sp,
                    )
                    Button(onClick = { dialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("Sign in") }
                }
            }
        }
    }
    if (dialog) SignInDialog(prefillEmail = state.email.orEmpty(), onDismiss = { dialog = false })
}

@Composable
private fun SignInDialog(prefillEmail: String, onDismiss: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(prefillEmail) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun signIn(e: String, p: String, offerToSave: Boolean) {
        scope.launch {
            busy = true
            error = null
            try {
                Sites.luckySeatAuth.login(e, p)
                if (offerToSave) SavedLogins.save(activity, e, p)
                onDismiss()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                error = ex.message ?: "Sign-in failed"
            } finally {
                password = "" // don't keep it around longer than the call
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Sign in to Lucky Seat", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    email, { email = it }, label = { Text("Email") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            SavedLogins.pick(activity)?.let { (e, p) -> email = e; signIn(e, p, offerToSave = false) }
                        }
                    },
                ) { Text("Use a saved login", color = Gold) }
                error?.let { Text(it, color = Bad, fontSize = 13.sp) }
                Text("After a successful sign-in, Android offers to save it in Google Password Manager.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = { signIn(email.trim(), password, offerToSave = true) },
                enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
            ) { Text(if (busy) "Signing in…" else "Sign in") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel", color = Muted) } },
    )
}
