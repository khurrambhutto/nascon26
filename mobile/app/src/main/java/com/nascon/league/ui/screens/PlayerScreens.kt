package com.nascon.league.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nascon.league.data.ServiceLocator
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun PlayerDirectoryScreen(onPlayer: (String) -> Unit, onBack: () -> Unit) {
    var players by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        loading = true
        runCatching { ServiceLocator.repo.liveRequest("/api/v1/athletes").jsonArray.map { it.jsonObject } }
            .onSuccess { players = it; error = "" }.onFailure { error = "Players could not be loaded. Check the server connection." }
        loading = false
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Players", style = MaterialTheme.typography.headlineMedium)
        if (loading) CircularProgressIndicator(Modifier.testTag("players_loading"))
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = { revision++ }, modifier = Modifier.testTag("players_retry")) { Text("Retry") }
        }
        players.forEach { player ->
            val id = player.getValue("player_id").jsonPrimitive.content
            OutlinedButton(onClick = { onPlayer(id) }, modifier = Modifier.fillMaxWidth().testTag("player_$id")) {
                Column(Modifier.fillMaxWidth()) {
                    Text(player.getValue("name").jsonPrimitive.content, style = MaterialTheme.typography.titleMedium)
                    Text(player["club"]?.jsonPrimitive?.contentOrNull ?: "Independent player")
                    Text("${player["follower_count"]?.jsonPrimitive?.content ?: "0"} followers")
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.testTag("players_back")) { Text("Back") }
    }
}

@Composable
fun PlayerProfileScreen(playerId: String, onBack: () -> Unit) {
    val repo = ServiceLocator.repo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var profile by remember(playerId) { mutableStateOf<JsonObject?>(null) }
    var following by remember(playerId) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    fun openEntity(id: String) {
        context.startActivity(Intent(context, com.nascon.league.MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("nascon://entity/$id")))
    }
    LaunchedEffect(playerId, revision) {
        loading = true
        runCatching { repo.liveRequest("/api/v1/athletes/$playerId").jsonObject }
            .onSuccess { profile = it; error = "" }.onFailure { error = "Player profile unavailable. Check the connection and retry." }
        if (repo.session.value != null) runCatching { repo.liveRequest("/api/v1/athletes/$playerId/following").jsonObject }
            .onSuccess { following = it.getValue("following").jsonPrimitive.boolean }
        loading = false
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) CircularProgressIndicator(Modifier.testTag("player_profile_loading"))
        profile?.let { p ->
            Text(p.getValue("name").jsonPrimitive.content, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("player_profile_name"))
            Text(p["position"]?.jsonPrimitive?.contentOrNull ?: "Player")
            Text("${p["follower_count"]?.jsonPrimitive?.content ?: "0"} followers", modifier = Modifier.testTag("player_followers"))
            Button(enabled = !busy && repo.session.value != null, onClick = { scope.launch {
                busy = true
                runCatching { repo.liveRequest("/api/v1/athletes/$playerId/follow", if (following) "DELETE" else "PUT").jsonObject }
                    .onSuccess { following = it.getValue("following").jsonPrimitive.boolean; revision++ }
                    .onFailure { error = "Could not update follow. Sign in and try again." }
                busy = false
            } }, modifier = Modifier.testTag("player_follow_button")) { Text(if (following) "Unfollow" else "Follow") }
            if (repo.session.value == null) Text("Open the full app and sign in to follow this player.")
            Text("Career", style = MaterialTheme.typography.titleLarge)
            p["career"]?.jsonObject?.forEach { (name, value) -> Text("${name.replaceFirstChar { it.uppercase() }}: ${value.jsonPrimitive.content}") }
            Text("Clubs", style = MaterialTheme.typography.titleLarge)
            p["clubs"]?.jsonArray?.forEach { club -> Text(club.jsonObject.getValue("name").jsonPrimitive.content) }
            Text("Upcoming matches", style = MaterialTheme.typography.titleLarge)
            val fixtures = p["upcoming_fixtures"]?.jsonArray.orEmpty()
            if (fixtures.isEmpty()) Text("No upcoming matches")
            fixtures.forEach { entry ->
                val fixture = entry.jsonObject
                val id = fixture.getValue("match_id").jsonPrimitive.content
                OutlinedButton(onClick = { openEntity(id) }, modifier = Modifier.testTag("player_fixture_$id")) {
                    Text("$id · ${fixture["scheduled_at"]?.jsonPrimitive?.contentOrNull ?: "Time to be confirmed"}")
                }
            }
            Text("Recorded streams", style = MaterialTheme.typography.titleLarge)
            val recordings = p["vod_archive"]?.jsonArray.orEmpty()
            if (recordings.isEmpty()) Text("No recordings yet")
            recordings.forEach { entry ->
                val recording = entry.jsonObject
                val id = recording.getValue("vod_id").jsonPrimitive.content
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.serverUrl.trimEnd('/') + "/api/v1/vod/$id")))
                }, modifier = Modifier.testTag("player_recording_$id")) { Text("Watch · ${recording["recorded_at"]?.jsonPrimitive?.content.orEmpty()}") }
            }
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("player_profile_error"))
            OutlinedButton(onClick = { revision++ }, modifier = Modifier.testTag("player_profile_retry")) { Text("Retry") }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.testTag("player_profile_back")) { Text("Back") }
    }
}
