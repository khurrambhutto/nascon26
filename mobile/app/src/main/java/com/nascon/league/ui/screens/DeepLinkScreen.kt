package com.nascon.league.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nascon.league.data.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Target of a nascon://entity/{id} or nascon://stream/{session} link. */
data class DeepLink(val id: String) {
    val isCostream get() = id.startsWith("cs-session-")
    val isMatch get() = id.startsWith("match-")

    companion object {
        fun parse(uri: android.net.Uri?): DeepLink? {
            if (uri?.scheme != "nascon") return null
            val id = uri.pathSegments.firstOrNull() ?: return null
            return if (uri.host == "entity" || uri.host == "stream") DeepLink(id) else null
        }
    }
}

/** Opens straight from a QR / adb deep link: no splash, no login wall (guest read-only data). */
@Composable
fun DeepLinkScreen(link: DeepLink, onClose: () -> Unit) {
    var score by remember { mutableStateOf("…") }
    var teams by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Loading") }
    var chat by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(link) {
        val repo = ServiceLocator.repo
        while (true) {
            if (link.isMatch) {
                val match = repo.publicMatch(link.id)
                if (match == null) status = "Offline – retrying" else {
                    score = match["score_text"]?.jsonPrimitive?.content ?: "–"
                    teams = "${match["home_name"]?.jsonPrimitive?.content} vs ${match["away_name"]?.jsonPrimitive?.content}"
                    status = match["status"]?.jsonPrimitive?.content ?: ""
                }
            } else {
                status = "Live"
            }
            repo.chatRoom("chat-${link.id}")?.let { room ->
                chat = room["messages"]?.jsonArray?.map {
                    val message = it.jsonObject
                    "${message["sender"]?.jsonPrimitive?.content}: ${message["text"]?.jsonPrimitive?.content}"
                }.orEmpty()
            }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp).testTag("deeplink_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (link.isCostream) "Co-stream" else if (link.isMatch) "Match Live" else "ArenaOne",
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("deeplink_title"))
        Text(link.id, modifier = Modifier.testTag("deeplink_entity_id"))
        Card(Modifier.testTag("deeplink_live_card")) {
            Column(Modifier.padding(16.dp)) {
                if (link.isMatch) {
                    Text(teams)
                    Text(score, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("deeplink_score"))
                }
                Text(status, modifier = Modifier.testTag("deeplink_status"))
                Text("HLS: :8888/${if (link.isCostream) "costream" else "live"}/${link.id}/index.m3u8",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Chat", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.testTag("deeplink_chat")) { chat.takeLast(8).forEach { Text(it) } }
        Button(onClick = onClose, modifier = Modifier.testTag("deeplink_close")) { Text("Open full app") }
    }
}
