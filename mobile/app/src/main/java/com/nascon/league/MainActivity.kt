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
import com.nascon.league.ui.screens.LoginScreen
import com.nascon.league.ui.theme.LeagueTheme
import com.nascon.league.ui.theme.Pitch
import com.nascon.league.ui.theme.parseHexColor

class LeagueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.repo = Repository(this)
    }
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark system icons: the connection strip is paper-coloured while online.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val app: AppViewModel = viewModel()
            val session by app.session.collectAsStateWithLifecycle()
            val brand = parseHexColor(session?.tenant?.primary_color) ?: Pitch.Turf
            LeagueTheme(brand = brand) {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val current = session
                    if (current == null) LoginScreen(app) else MainShell(app, current)
                }
            }
        }
    }
}
