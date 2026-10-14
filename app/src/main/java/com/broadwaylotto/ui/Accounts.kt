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
import com.broadwaylotto.automation.AppAutomationService
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
fun AppAutomationCard() {
    val context = LocalContext.current
    val connected by AppAutomationService.connected.collectAsState()
    val running by AppAutomationService.running.collectAsState()
    val progress by AppAutomationService.progress.collectAsState()

    Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TodayTix and Hamilton", style = MaterialTheme.typography.titleMedium)
            Text(
                "These lotteries only exist in their phone apps, so Broadway Lotto taps through the TodayTix and Hamilton apps for you. " +
                    "It can only see those two apps, and it only enters when you tap an alert or a show's Enter button.",
                color = Muted, fontSize = 13.sp,
            )
            if (!connected) {
                Text("Not switched on yet.", color = Warn, fontWeight = FontWeight.SemiBold)
                Text(
                    "In Android Settings → Accessibility, turn on “Broadway Lotto”. Android will warn you that it can read the app it's allowed to watch; those are the TodayTix and Hamilton apps only.",
                    color = Muted, fontSize = 13.sp,
                )
                Button(
                    onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                ) { Text("Open Accessibility settings") }
            } else {
                Text("Switched on.", color = Good, fontWeight = FontWeight.SemiBold)
                Text(
                    "To get an alert when a lottery opens, keep each app's own lottery reminders on (in TodayTix, the show's lottery alert; in Hamilton, “Send me Broadway lottery reminder notifications”). " +
                        "Broadway Lotto turns those into a tap-to-enter notification for your selected shows. " +
                        "Keep the phone unlocked when you tap it.",
                    color = Muted, fontSize = 13.sp,
                )
                if (running) Text("Running…", color = Gold, fontWeight = FontWeight.SemiBold)
            }
            if (progress.isNotBlank()) Text(progress, color = if (running) Gold else Cream, fontSize = 13.sp)
        }
    }
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

/** Shown once before Hamilton entries: the runner ticks the app's two consent boxes for the user. */
@Composable
fun HamiltonConsentDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Before entering Hamilton for you", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hamilton's entry page makes you tick two boxes before you can submit:", fontSize = 14.sp)
                Text("• You accept the Terms of Service, Privacy Notice and the lottery's official rules.", fontSize = 13.sp, color = Muted)
                Text("• You've reviewed your profile and understand winners must collect tickets in person with a valid ID that matches the name on the entry, and that the entry can't be changed.", fontSize = 13.sp, color = Muted)
                Text("Broadway Lotto will tick both boxes on your behalf each time it enters, which means you're agreeing to them. Read them in the Hamilton app if you haven't.", fontSize = 14.sp)
            }
        },
        confirmButton = {
            Button(onClick = onAgree, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) {
                Text("I've read them, and agree")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}
