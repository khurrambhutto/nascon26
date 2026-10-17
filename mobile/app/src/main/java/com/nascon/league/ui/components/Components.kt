package com.nascon.league.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.ui.savedAgo
import com.nascon.league.ui.theme.Condensed
import com.nascon.league.ui.theme.Pitch
import com.nascon.league.ui.theme.TABULAR
import com.nascon.league.ui.theme.contentOn
import kotlin.math.absoluteValue

/** Dialogs open in their own window, so they need testTagsAsResourceId again for Appium. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
val DialogTags: Modifier = Modifier.semantics { testTagsAsResourceId = true }

// ---------- Connection strip (always visible, spec 8.2 "Sync status") ----------

enum class Connection { Online, Offline, ServerDown, Syncing }

@Composable
fun ConnectionStrip(connection: Connection, pendingCount: Int, onClick: () -> Unit) {
    val (text, color) = when (connection) {
        Connection.Online -> "Online" to Pitch.Win
        Connection.Syncing -> "Syncing" to Color(0xFF1F5FA8)
        Connection.ServerDown -> "Server not reachable" to Color(0xFF8A5A00)
        Connection.Offline -> "Offline, showing saved data" to Pitch.Whistle
    }
    val background by animateColorAsState(color, label = "strip")
    val pendingText = when (pendingCount) {
        0 -> null
        1 -> "1 action waiting"
        else -> "$pendingCount actions waiting"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .statusBarsPadding()
            .clickable(onClickLabel = "Open sync queue", onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("connection_strip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (connection == Connection.Syncing) {
            CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("connection_text"))
        Spacer(Modifier.weight(1f))
        if (pendingText != null) {
            Text(pendingText, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("pending_count"))
        }
    }
}

// ---------- Screen header ----------

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 4.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}

@Composable
fun RefreshButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("refresh_button")) {
        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
    }
}

// ---------- Loading / error / empty ----------

@Composable
fun LoadingState(text: String = "Loading") {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    MessageState(
        icon = Icons.Filled.Warning,
        title = "Couldn't load this",
        body = message,
        actionLabel = "Try again",
        onAction = onRetry,
    )
}

@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.size(4.dp))
        Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(16.dp))
            OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag("state_action")) { Text(actionLabel) }
        }
    }
}

/** Shown above data that came from the offline cache. */
@Composable
fun SavedDataNotice(savedAt: Long?) {
    if (savedAt == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp)).background(Pitch.Wash).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = Pitch.Muted)
        Spacer(Modifier.width(8.dp))
        Text("Saved copy from ${savedAgo(savedAt)}", style = MaterialTheme.typography.labelMedium, color = Pitch.Muted)
    }
}

/** Inline result message after an action (success, queued, or failure). */
@Composable
fun Banner(text: String, kind: BannerKind, inset: Boolean = true, onDismiss: () -> Unit) {
    val (bg, fg, icon) = when (kind) {
        BannerKind.Success -> Triple(Color(0xFFE3F2E8), Pitch.Win, Icons.Filled.CheckCircle)
        BannerKind.Info -> Triple(Color(0xFFE6EEF8), Color(0xFF1F5FA8), Icons.Filled.Info)
        BannerKind.Error -> Triple(Color(0xFFFBE7E6), Pitch.Whistle, Icons.Filled.Warning)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (inset) 20.dp else 0.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp)).background(bg).padding(start = 14.dp, top = 6.dp, bottom = 6.dp)
            .testTag("banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Pitch.Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss message", tint = Pitch.Muted) }
    }
}

enum class BannerKind { Success, Info, Error }

data class BannerMessage(val text: String, val kind: BannerKind)

// ---------- Scoreboard pieces ----------

/** The scoreboard tile: condensed white numerals in a dark inset block. */
@Composable
fun ScoreTile(value: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val fontSize = (size.value * 0.62f).sp
    Box(
        modifier
            .widthIn(min = size * 0.85f)
            .heightIn(min = size)
            .clip(RoundedCornerShape(size * 0.16f))
            .background(Color(0xFF0F1A22))
            .padding(horizontal = size * 0.16f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value,
            color = Color.White,
            style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = fontSize, fontFeatureSettings = TABULAR),
            maxLines = 1,
        )
    }
}

/** Team crest stand-in: initials on a colour derived from the team id (same team = same colour). */
@Composable
fun TeamBadge(name: String, id: String, size: Dp = 36.dp) {
    val hues = listOf(0xFF2F6FAD, 0xFF8E3B46, 0xFF2E7D5B, 0xFF6B4FA0, 0xFFB5652B, 0xFF37606B, 0xFF9C4A8B, 0xFF5A6B2E)
    val color = Color(hues[id.hashCode().absoluteValue % hues.size])
    val initials = name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials.ifBlank { "?" }, color = Color.White, fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
    }
}

/** Match status as icon-free text pill; "Live" gets the floodlight dot. Meaning never by colour alone. */
@Composable
fun MatchStatusPill(status: String) {
    val label = when (status) {
        "in_progress" -> "Live"
        "completed" -> "Full time"
        "upcoming" -> "Upcoming"
        "postponed" -> "Postponed"
        "abandoned" -> "Abandoned"
        "cancelled" -> "Cancelled"
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    val live = status == "in_progress"
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (live) Pitch.Ink else Pitch.Wash)
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .semantics { contentDescription = "Status: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Pitch.Floodlight))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (live) Color.White else Pitch.Ink)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = contentOn(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A selectable pill (chips) with a 48dp touch target. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> Pitch.Muted.copy(alpha = 0.6f)
                selected -> contentOn(primary)
                else -> Pitch.Ink
            },
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) primary else Color.White)
                .border(1.dp, if (selected) primary else Pitch.Line, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}
