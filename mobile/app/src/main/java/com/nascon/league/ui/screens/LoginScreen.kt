package com.nascon.league.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.nascon.league.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nascon.league.data.friendlyError
import com.nascon.league.ui.AppViewModel
import com.nascon.league.ui.components.Banner
import com.nascon.league.ui.components.BannerKind
import com.nascon.league.ui.components.Pill
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.theme.Pitch
import kotlinx.coroutines.launch

private val demoAccounts = listOf(
    "Admin" to "admin@nascon.test",
    "Organizer" to "organizer@nascon.test",
    "Club manager" to "manager@nascon.test",
    "Player" to "player@nascon.test",
    "Viewer" to "viewer@nascon.test",
)

@Composable
fun LoginScreen(app: AppViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, standalone = true)
        return
    }

    fun signIn() {
        if (email.isBlank() || password.isBlank()) {
            error = "Enter your email and password."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                app.repo.login(email, password)
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        PitchHero()

        Column(
            Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(horizontal = 24.dp).navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Sign in", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("email_input"),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { signIn() }),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }, modifier = Modifier.testTag("toggle_password")) {
                        Text(if (showPassword) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("password_input"),
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Banner(error!!, BannerKind.Error, inset = false) { error = null }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = if (busy) "Signing in" else "Sign in",
                onClick = ::signIn,
                busy = busy,
                modifier = Modifier.fillMaxWidth().testTag("login_button"),
            )

            Spacer(Modifier.height(28.dp))
            Text("Try a demo account", style = MaterialTheme.typography.titleSmall)
            Text(
                "Fills in the form. Every demo password is Nascon@26.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                demoAccounts.forEach { (label, address) ->
                    Pill(
                        text = label,
                        selected = email == address,
                        onClick = { email = address; password = "Nascon@26"; error = null },
                        modifier = Modifier.testTag("demo_${label.lowercase().replace(' ', '_')}"),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { showSettings = true }, modifier = Modifier.testTag("open_settings")) {
                Text("Server address: ${app.repo.serverUrl}")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Turf block with chalk pitch markings: the app's first impression. */
@Composable
private fun PitchHero() {
    Box(
        Modifier.fillMaxWidth().background(Pitch.Turf).statusBarsPadding().height(220.dp),
    ) {
        // Animated bouncing ball over chalk pitch lines, behind the title.
        com.nascon.league.ui.components.AnimatedPitch(Modifier.fillMaxSize())
        // Soft bottom scrim keeps the title readable over the animation.
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color.Transparent, Pitch.Turf.copy(alpha = 0.85f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 36.dp)) {
            Image(painterResource(R.drawable.arenaone_logo), contentDescription = "ArenaOne logo", modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(8.dp))
            Text("arenaone.", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                "Fixtures, tables and your club. Game on.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

