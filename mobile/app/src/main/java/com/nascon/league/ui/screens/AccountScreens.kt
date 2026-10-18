package com.nascon.league.ui.screens

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.data.ServiceLocator
import com.nascon.league.data.Session
import com.nascon.league.ui.AppViewModel
import com.nascon.league.ui.components.Banner
import com.nascon.league.ui.components.DialogTags
import com.nascon.league.ui.components.BannerKind
import com.nascon.league.ui.components.Connection
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.ScreenHeader
import com.nascon.league.ui.components.SectionLabel
import com.nascon.league.ui.components.TeamBadge
import com.nascon.league.ui.roleName
import com.nascon.league.ui.theme.Pitch

// ---------- Me ----------

@Composable
fun MeScreen(
    session: Session,
    pendingCount: Int,
    onOpenSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRoster: () -> Unit,
    onLogout: () -> Unit,
    onManagement: (() -> Unit)? = null,
    onOrders: () -> Unit = {},
    onGoLive: () -> Unit = {},
    onScanQr: () -> Unit = {},
    onPlayers: () -> Unit = {},
) {
    var confirmLogout by remember { mutableStateOf(false) }
    val user = session.user
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(title = "Me")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamBadge(user.name.ifBlank { user.email }, user.user_id, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(user.name.ifBlank { user.email }, style = MaterialTheme.typography.titleLarge)
                Text(roleName(user.role), style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted, modifier = Modifier.testTag("user_role"))
                session.tenant?.let { Text(it.name, style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted) }
            }
        }

        SectionLabel("Your tools")
        MenuRow("Players", "Profiles, career records and follows", Icons.Filled.Settings, "menu_players", onPlayers)
        MenuRow("Scan QR", "Open a match, club, player or co-stream", Icons.Filled.Settings, "menu_scan_qr", onScanQr)
        if (onManagement != null) MenuRow("Management", "Leagues, matches, shop and club operations", Icons.Filled.Settings, "menu_management", onManagement)
        MenuRow("Orders", "View purchases and order status", Icons.AutoMirrored.Filled.KeyboardArrowRight, "menu_orders", onOrders)
        if (user.player_id != null) MenuRow("Go live", "Broadcast from this phone's camera", Icons.AutoMirrored.Filled.KeyboardArrowRight, "menu_golive", onGoLive)
        when (user.role) {
            "club_manager", "super_admin" -> MenuRow("Manage squad", "Transfer, loan or release players", Icons.AutoMirrored.Filled.KeyboardArrowRight, "menu_roster", onOpenRoster)
            "league_organizer" -> InfoText("Open a match from Matches to start it, record scores, or undo a wrong event.")
            "player" -> InfoText("You can follow fixtures, the table, your club and the store.")
            else -> InfoText("You can view fixtures, tables, clubs and the store. Scoring and squad changes need an organizer or manager account.")
        }
        MenuRow(
            "Sync queue",
            if (pendingCount == 0) "Everything is synced" else "$pendingCount waiting to sync",
            Icons.Filled.Refresh,
            "menu_sync",
            onOpenSync,
        )
        MenuRow("Server address", ServiceLocator.repo.serverUrl, Icons.Filled.Settings, "menu_settings", onOpenSettings)

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { confirmLogout = true },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 52.dp).testTag("logout_button"),
        ) { Text("Sign out") }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmLogout) {
        AlertDialog(
            modifier = DialogTags,
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    if (pendingCount > 0) "$pendingCount actions haven't synced yet. They stay on this phone and sync after you sign in again."
                    else "You'll need your email and password to sign back in."
                )
            },
            confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }, modifier = Modifier.testTag("confirm_logout")) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Stay signed in") } },
        )
    }
}

@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

@Composable
private fun MenuRow(title: String, subtitle: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted)
        }
        Icon(icon, contentDescription = null, tint = Pitch.Muted)
    }
    HorizontalDivider(Modifier.padding(start = 20.dp), color = Pitch.Line)
}

// ---------- Sync queue ----------

@Composable
fun SyncScreen(app: AppViewModel, onBack: () -> Unit) {
    val pending by app.pending.collectAsStateWithLifecycle()
    val results by app.lastSync.collectAsStateWithLifecycle()
    val connection by app.connection.collectAsStateWithLifecycle()
    val syncError by app.repo.syncError.collectAsStateWithLifecycle()
    val backgroundError by app.repo.backgroundSyncError.collectAsStateWithLifecycle()
    val queuedCount by app.queuedCount.collectAsStateWithLifecycle()
    val preparing by app.repo.preparingOffline.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Sync", subtitle = when (connection) {
            Connection.Online -> "Connected. Waiting actions sync automatically."
            Connection.Syncing -> "Sending your saved actions now."
            Connection.ServerDown -> "Connected, but the server isn't answering."
            Connection.Offline -> "Offline. Actions are saved on this phone."
        }, onBack = onBack)
        if (preparing) Text("Preparing saved screens for offline use. Keep the connection on until this finishes.", Modifier.padding(20.dp).testTag("offline_cache_preparing"))
        syncError?.let { Text(it, Modifier.padding(horizontal = 20.dp).testTag("sync_error")) }
        backgroundError?.let { Text(it, Modifier.padding(horizontal = 20.dp).testTag("background_sync_error")) }
        if (queuedCount > pending.size) InfoText("${queuedCount - pending.size} saved action(s) from another account will sync automatically. That account's details stay private.")

        LazyColumn(Modifier.weight(1f).testTag("sync_list")) {
            item { SectionLabel("Waiting to sync (${pending.size})") }
            if (pending.isEmpty()) {
                item { InfoText("Nothing waiting. Scores and squad changes you make offline appear here.") }
            }
            items(pending, key = { it.action.action_id }) { item ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).testTag("pending_${item.action.action_id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.size(10.dp).clip(CircleShape).background(Pitch.Floodlight))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.titleSmall)
                        Text("Pending, saved ${item.action.queued_at.replace('T', ' ').removeSuffix("Z")} UTC", style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 20.dp), color = Pitch.Line)
            }

            if (results.isNotEmpty()) {
                item { SectionLabel("Last sync results") }
                items(results) { r ->
                    val (icon, tint, status) = when (r.status) {
                        "applied" -> Triple(Icons.Filled.CheckCircle, Pitch.Win, "Applied")
                        "duplicate" -> Triple(Icons.Filled.Info, Pitch.Muted, "Already synced")
                        else -> Triple(Icons.Filled.Warning, Pitch.Whistle, "Rejected")
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("$status: ${r.label}", style = MaterialTheme.typography.titleSmall)
                            Text(r.message, style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted)
                        }
                    }
                }
            }
        }
        PrimaryButton(
            text = if (connection == Connection.Syncing) "Syncing" else "Sync now",
            busy = connection == Connection.Syncing,
            enabled = queuedCount > 0,
            onClick = app::syncNow,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("sync_now"),
        )
    }
}

// ---------- Server settings ----------

@Composable
fun SettingsScreen(onBack: () -> Unit, standalone: Boolean = false) {
    val repo = ServiceLocator.repo
    var url by remember { mutableStateOf(repo.serverUrl) }
    var saved by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var connectionMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = runCatching { java.net.URI(url.trim()).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() } }.getOrDefault(false)

    Column(Modifier.fillMaxSize().then(if (standalone) Modifier.statusBarsPadding() else Modifier).verticalScroll(rememberScrollState())) {
        ScreenHeader(title = "Server address", onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                "Where the app finds the league server. Use your laptop's address on the same Wi-Fi or hotspot.",
                color = Pitch.Muted,
            )
            Spacer(Modifier.height(8.dp))
            Text("For use without USB, enter the laptop's Wi-Fi IPv4 address and port 8080. Both devices must share a Wi-Fi network or phone hotspot. 127.0.0.1 only works while the USB tunnel is connected.")
            if (repo.wirelessUrl.isNotBlank()) Text("Saved wireless backup: ${repo.wirelessUrl}. The app tries it automatically if the USB connection fails.")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; saved = false },
                label = { Text("Server address") },
                singleLine = true,
                isError = !valid,
                supportingText = { if (!valid) Text("Start with http:// and include the port, for example :8080") },
                modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
            )
            Spacer(Modifier.height(8.dp))
            Text("Quick picks", style = MaterialTheme.typography.titleSmall)
            listOf(
                repo.wirelessUrl to "Saved wireless connection",
                "http://127.0.0.1:8080/" to "USB cable (after adb reverse)",
                "http://10.0.2.2:8080/" to "Android emulator",
            ).filter { it.first.isNotBlank() }.forEach { (address, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { url = address; saved = false }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(address, style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted, fontFamily = FontFamily.Default)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (saved) Banner("Saved. The app now uses this address.", BannerKind.Success, inset = false) { saved = false }
            connectionMessage?.let { Text(it, modifier = Modifier.testTag("connection_test_result")) }
            PrimaryButton(
                text = if (checking) "Checking connection" else "Save and test connection",
                enabled = valid && !checking,
                onClick = {
                    repo.setServerUrl(url); saved = true; checking = true
                    scope.launch {
                        try {
                            repo.probe()
                            connectionMessage = if (repo.serverReachable.value) "Connected to ${repo.serverUrl}" else "Server unreachable. Keep the laptop and phone on the same network, start the backend, and allow TCP port 8080 in the laptop firewall. Your cached data and queued actions remain available."
                        } catch (e: CancellationException) { throw e }
                        finally { checking = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("save_server_url"),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
