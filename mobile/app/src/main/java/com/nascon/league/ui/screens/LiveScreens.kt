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
                webViewClient = android.webkit.WebViewClient()  // keep MediaMTX redirects inside the app
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
fun GoLiveScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Allow camera and microphone, then tap Go live.") }
    var granted by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        if (!granted) message = "Camera and microphone are needed to broadcast."
    }
    LaunchedEffect(Unit) { permissions.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Go live", style = MaterialTheme.typography.headlineMedium)
        Text(message, modifier = Modifier.testTag("golive_status"))
        val current = key
        if (current != null && granted) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).testTag("golive_preview")) { MediaWebView(mediaUrl("live/$current/publish/"), Modifier.fillMaxSize()) }
            Text("RTMP for OBS: rtmp://<laptop>:1935/live/$current", style = MaterialTheme.typography.bodySmall)
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
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
