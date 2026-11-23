package com.cosmotune.music.ui

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cosmotune.music.auth.AuthStore
import com.cosmotune.remote.PhoneSignIn
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.sheets.CosmoDialogBody
import com.cosmotune.ui.sheets.CosmoDialogCard
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's half of signing the computer in: the computer's QR code led here through a `cosmotune://signin` link. The listener is
 * asked first; Sign in computer sends this phone's YouTube Music session, sealed, to that computer only.
 */
@Composable
fun ComputerSignInDialog(link: PhoneSignIn.Link, hazeState: HazeState?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fonts = orbitFonts()
    val session = remember { AuthStore(context.applicationContext).activeSession }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val who = session?.let { it.name.ifBlank { it.email }.ifBlank { null } }
    CosmoDialogCard(onDismiss = { if (!sending) onDone() }, maxWidth = 460.dp, hazeState = hazeState) {
        CosmoDialogBody(
            overline = "Account",
            title = "Sign in your computer?",
            message = when {
                session == null -> "Sign in to YouTube Music on this phone first, then scan the code on your computer again."
                who != null -> "Cosmotune on the computer at ${link.computer} will use $who, the account on this phone. " +
                    "Only do this for your own computer."
                else -> "Cosmotune on the computer at ${link.computer} will use the YouTube Music account on this phone. " +
                    "Only do this for your own computer."
            },
            confirmLabel = if (session == null) null else "Sign in computer",
            onConfirm = {
                val cookie = session?.cookie ?: return@CosmoDialogBody
                sending = true
                problem = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { PhoneSignIn.send(link, cookie) } }
                    sending = false
                    result.onSuccess {
                        Toast.makeText(context, "Your computer is signing in", Toast.LENGTH_SHORT).show()
                        onDone()
                    }.onFailure {
                        problem = it.message?.takeIf { message -> message.startsWith("The computer") }
                            ?: "Could not reach the computer. Check that both are on the same Wi-Fi."
                    }
                }
            },
            dismissLabel = if (session == null) "Close" else "Not now",
            onDismiss = onDone,
            confirmEnabled = !sending,
            dismissEnabled = !sending,
            busy = sending,
            extra = problem?.let { shown ->
                { Text(shown, style = fonts.body, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            },
        )
    }
}
