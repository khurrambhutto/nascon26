package com.nascon.league

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.data.Repository
import com.nascon.league.data.ServiceLocator
import com.nascon.league.ui.AppViewModel
import com.nascon.league.ui.MainShell
import com.nascon.league.ui.screens.DeepLink
import com.nascon.league.ui.screens.DeepLinkScreen
import com.nascon.league.ui.screens.LoginScreen
import com.nascon.league.ui.screens.SplashScreen
import com.nascon.league.ui.theme.LeagueTheme

class LeagueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.repo = Repository(this)
    }
}

class MainActivity : ComponentActivity() {
    private val notificationMatch = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val deepLink = kotlinx.coroutines.flow.MutableStateFlow<DeepLink?>(null)

    override fun onStop() {
        super.onStop()
        ServiceLocator.repo.closeBroadcastPresence()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationMatch.value = intent.getStringExtra("reminder_match_id")
        deepLink.value = DeepLink.parse(intent.data)
    }
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark system icons: the connection strip is paper-coloured while online.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val preferences = getPreferences(MODE_PRIVATE)
            if (!preferences.getBoolean("notification_permission_asked", false)) {
                preferences.edit().putBoolean("notification_permission_asked", true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 400)
            }
        }
        notificationMatch.value = intent.getStringExtra("reminder_match_id")
        deepLink.value = DeepLink.parse(intent.data)
        setContent {
            val app: AppViewModel = viewModel()
            val session by app.session.collectAsStateWithLifecycle()
            val requestedMatch by notificationMatch.collectAsStateWithLifecycle()
            val link by deepLink.collectAsStateWithLifecycle()
            var showingSplash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
            LeagueTheme {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val current = session
                    val openLink = link
                    if (openLink != null) DeepLinkScreen(openLink) { deepLink.value = null; showingSplash = false }
                    else if (showingSplash) SplashScreen { showingSplash = false }
                    else if (current == null) LoginScreen(app, onGuest = { deepLink.value = DeepLink("match-m1") }) else androidx.compose.runtime.key(current.user.user_id) {
                        MainShell(app, current, requestedMatch) { notificationMatch.value = null }
                    }
                }
            }
        }
    }
}
