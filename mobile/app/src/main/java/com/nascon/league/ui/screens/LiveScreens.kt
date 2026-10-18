package com.nascon.league.ui.screens

import android.annotation.SuppressLint
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nascon.league.data.ServiceLocator
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private const val AUTO_PUBLISH = """
(function auto() {
  var codec = document.getElementById('video-codec'), button = document.getElementById('publish-button');
  if (!codec || !button || codec.options.length === 0) { setTimeout(auto, 300); return; }
  for (var i = 0; i < codec.options.length; i++) if (codec.options[i].value.toLowerCase().indexOf('h264') >= 0) { codec.value = codec.options[i].value; break; }
  var set = function (id, value) { var e = document.getElementById(id); if (e) e.value = value; };
  set('video-bitrate', '1500'); set('video-width', '1280'); set('video-height', '720');
  button.click();
})();
"""

/** MediaMTX serves its WebRTC pages on :8889 of the same host the API uses. */
fun mediaUrl(path: String): String {
    val api = android.net.Uri.parse(ServiceLocator.repo.serverUrl)
    return "${api.scheme}://${api.host}:8889/$path"
}

/** WebRTC (WHEP play / WHIP publish) through MediaMTX's own page; camera access is granted to that page only. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MediaWebView(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                webViewClient = object : android.webkit.WebViewClient() {  // keep MediaMTX redirects inside the app
                    override fun onPageFinished(view: WebView, url: String) {
                        // Go Live: pick H264 720p and press MediaMTX's publish button automatically.
                        if (url.contains("/publish")) view.evaluateJavascript(AUTO_PUBLISH, null)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) = request.grant(request.resources)
                }
                loadUrl(url)
            }
        },
        update = { view -> if (view.url != url) view.loadUrl(url) },
    )
}

@Composable
fun LivePlayer(streamKey: String?, label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF1B1B1B)).testTag("live_player"), contentAlignment = Alignment.Center) {
        if (streamKey == null) Text("Waiting for the broadcast…", color = Color.White)
        else MediaWebView(mediaUrl("live/$streamKey/?controls=false&muted=true&autoplay=true&playsinline=true"), Modifier.fillMaxSize())
        Text(label, color = Color.White, modifier = Modifier.align(Alignment.TopStart).background(Color(0xC0000000)).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** In-app solo broadcast (FR-3.2): rotate my key, then publish camera + mic over WebRTC WHIP. */
@Composable
fun GoLiveScreen(sessionHint: String? = null, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Allow camera and microphone, then tap Go live.") }
    var room by remember { mutableStateOf("chat-match-m1") }
    var sessionId by remember { mutableStateOf<String?>(sessionHint) }
    var granted by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        if (!granted) message = "Camera and microphone are needed to broadcast."
    }
    LaunchedEffect(Unit) { permissions.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)) }
    // Publishing is automatic, so the screen can scroll to the co-stream tools and chat below the camera.
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Go live", style = MaterialTheme.typography.headlineMedium)
        Text(message, modifier = Modifier.testTag("golive_status"))
        val current = key
        if (current != null && granted) {
            Box(Modifier.fillMaxWidth().height(360.dp).testTag("golive_preview")) { MediaWebView(mediaUrl("live/$current/publish"), Modifier.fillMaxSize()) }
            Text("Publishing automatically (H264 720p). OBS alternative: rtmp://<laptop>:1935/live/$current", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scope.launch { ServiceLocator.repo.myStreamKey("revoke"); key = null; message = "Broadcast ended. The recording appears in your VOD archive." }
            }, modifier = Modifier.testTag("golive_stop")) { Text("End broadcast") }
        } else {
            Button(onClick = {
                scope.launch {
                    val result = ServiceLocator.repo.myStreamKey("rotate")
                    key = result
                    message = if (result == null) "Only signed-in players can broadcast, or the server is offline." else "You are live. Viewers see you on the match page."
                }
            }, enabled = granted, modifier = Modifier.testTag("golive_start")) { Text("Go live") }
        }
        Button(onClick = { scope.launch {
            runCatching { ServiceLocator.repo.liveRequest("/api/v1/costream", "POST").jsonObject }.onSuccess {
                sessionId = it.getValue("session_id").jsonPrimitive.content; room = "chat-$sessionId"
            }
        } }, modifier = Modifier.testTag("create_costream")) { Text("Start co-stream") }
        sessionId?.let { Text("Session: $it"); CostreamTools(it) }
        LiveChat(room)
        OutlinedButton(onClick = onBack, modifier = Modifier.testTag("golive_back")) { Text("Back") }
    }
}

@Composable
fun LiveChat(room: String) {
    var messages by remember(room) { mutableStateOf(listOf<String>()) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(room) {
        while (true) {
            ServiceLocator.repo.chatRoom(room)?.let { response ->
                messages = response["messages"]?.jsonArray?.map { item ->
                    val m = item.jsonObject
                    "${m["sender"]?.jsonPrimitive?.content}: ${m["text"]?.jsonPrimitive?.content}"
                }.orEmpty()
            }
            kotlinx.coroutines.delay(500)
        }
    }
    Text("Shared chat", style = MaterialTheme.typography.titleMedium)
    messages.takeLast(12).forEach { Text(it, modifier = Modifier.testTag("broadcast_chat_message")) }
    androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth().testTag("broadcast_chat_input"))
    Button(onClick = { scope.launch {
        runCatching { ServiceLocator.repo.liveRequest("/api/v1/chat", "POST", buildJsonObject { put("chat_room_id", room); put("text", text) }.toString()) }
            .onSuccess { text = ""; error = "" }.onFailure { error = "Message blocked or connection unavailable." }
    } }, enabled = text.isNotBlank(), modifier = Modifier.testTag("broadcast_chat_send")) { Text("Send") }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
}

@Composable
fun CostreamTools(sessionId: String, inviteToken: String? = null, onBroadcast: (() -> Unit)? = null) {
    val repo = ServiceLocator.repo
    val scope = rememberCoroutineScope()
    var invitee by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(inviteToken.orEmpty()) }
    var message by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var joined by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(sessionId, joined) {
        val socket = if (joined) repo.presence(sessionId) else null
        onDispose { /* Activity owns the socket across broadcast-screen navigation. */ }
    }
    LaunchedEffect(sessionId) { while (true) { state = repo.costream(sessionId); kotlinx.coroutines.delay(700) } }
    val me = repo.session.value?.user?.player_id
    val host = state?.get("active_host")?.jsonPrimitive?.content
    LaunchedEffect(host, me) { if (host != null && host == me) joined = true }
    if (onBroadcast != null && me != null) Button(onClick = onBroadcast, modifier = Modifier.testTag("costream_broadcast")) { Text("Broadcast camera") }
    suspend fun action(path: String, body: String) {
        runCatching { repo.liveRequest(path, "POST", body) }.onSuccess { message = "Saved" }.onFailure { message = "Action refused. Check your role or invite token." }
    }
    if (me != null) {
        androidx.compose.material3.OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Invitation token") }, modifier = Modifier.testTag("costream_invite_token"))
        Button(onClick = { scope.launch {
            if (host == me) joined = true else {
                val success = runCatching { repo.liveRequest("/api/v1/costream/$sessionId/join", "POST", buildJsonObject { put("player_id", me); put("invite_token", token) }.toString()) }.isSuccess
                joined = success; message = if (success) "Joined" else "Invalid invitation or session full"
            }
        } }, modifier = Modifier.testTag("costream_join")) { Text(if (host == me) "Host this session" else "Join broadcast") }
    }
    if (host == me) {
        androidx.compose.material3.OutlinedTextField(value = invitee, onValueChange = { invitee = it }, label = { Text("Player ID to invite") }, modifier = Modifier.testTag("costream_invitee"))
        Button(onClick = { scope.launch { action("/api/v1/costream/$sessionId/invites", buildJsonObject { put("player_id", invitee) }.toString()) } }, modifier = Modifier.testTag("costream_invite")) { Text("Send invitation") }
        state?.get("participants")?.jsonArray?.forEach { entry ->
            val player = entry.jsonPrimitive.content
            Text(player)
            androidx.compose.foundation.layout.Row {
                listOf("mute", "unmute", "spotlight", "kick").forEach { command ->
                    androidx.compose.material3.TextButton(onClick = { scope.launch { action("/api/v1/costream/$sessionId/control", buildJsonObject { put("action", command); put("player_id", player) }.toString()) } }, modifier = Modifier.testTag("costream_${command}_$player")) { Text(command) }
                }
            }
        }
    }
    if (message.isNotBlank()) Text(message)
}
