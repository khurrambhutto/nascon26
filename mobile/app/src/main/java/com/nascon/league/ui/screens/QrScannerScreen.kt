package com.nascon.league.ui.screens

import android.Manifest
import android.hardware.Camera
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.nascon.league.data.ServiceLocator
import kotlinx.coroutines.*

@Suppress("DEPRECATION")
@Composable
fun QrScannerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var message by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { permission.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) { onDispose { camera?.setPreviewCallback(null); camera?.release(); camera = null } }
    fun open(value: String) {
        if (working) return
        working = true
        scope.launch {
            runCatching {
                val uri = android.net.Uri.parse(value)
                val link = if (uri.scheme == "nascon") value else withContext(Dispatchers.IO) {
                    require(uri.path?.startsWith("/q/") == true) { "Scan an ArenaOne QR code" }
                    val connection = java.net.URL(ServiceLocator.repo.serverUrl.trimEnd('/') + uri.path).openConnection() as java.net.HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 3000; connection.readTimeout = 3000
                    val route = connection.getHeaderField("Location").orEmpty();connection.disconnect()
                    val id = route.split('/').getOrNull(3) ?: error("QR not recognized")
                    "nascon://${if (route.contains("costream")) "stream" else "entity"}/$id"
                }
                context.startActivity(android.content.Intent(context, com.nascon.league.MainActivity::class.java).setAction(android.content.Intent.ACTION_VIEW).setData(android.net.Uri.parse(link)))
            }.onFailure { message = it.message ?: "Unable to open QR"; working = false }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Text("Scan ArenaOne QR", style = MaterialTheme.typography.headlineMedium)
        if (granted) AndroidView(modifier = Modifier.fillMaxWidth().weight(1f).testTag("qr_camera"), factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        runCatching {
                            val device = Camera.open();camera = device
                            device.setDisplayOrientation(90);device.setPreviewDisplay(holder)
                            device.setPreviewCallback { bytes, c ->
                                if (!working) {
                                    val size = c.parameters.previewSize
                                    runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(bytes,size.width,size.height,0,0,size.width,size.height,false))), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
                                        .onSuccess { code -> post { open(code.text) } }
                                }
                            }
                            device.startPreview()
                        }.onFailure { message = "Camera unavailable. Check permission." }
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) { camera?.setPreviewCallback(null);camera?.release();camera = null }
                })
            }
        }) else Text("Camera permission is required to scan.")
        if (message.isNotBlank()) Text(message, modifier = Modifier.testTag("qr_error"))
        OutlinedButton(onClick = onBack, modifier = Modifier.testTag("qr_back")) { Text("Back") }
    }
}
