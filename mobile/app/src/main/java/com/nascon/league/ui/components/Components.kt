package com.nascon.league.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import com.nascon.league.ui.theme.edge
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
        Connection.Online -> "Online" to Pitch.Chalk
        Connection.Syncing -> "Syncing" to Color(0xFF2F6E8E)
        Connection.ServerDown -> "Server not reachable" to Color(0xFF8A5A00)
        Connection.Offline -> "Offline, showing saved data" to Pitch.Whistle
    }
    val textColor = if (connection == Connection.Online) Pitch.MintDeep else Color.White
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
            CircularProgressIndicator(Modifier.size(12.dp), color = textColor, strokeWidth = 2.dp)
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (connection == Connection.Online) Pitch.Win else Color.White))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = textColor, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("connection_text"))
        Spacer(Modifier.weight(1f))
        if (pendingText != null) {
            Text(pendingText, color = textColor, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("pending_count"))
        }
    }
}

// ---------- Tenant branding ----------

/** The signed-in tenant, so every top-level header can show its logo mark. */
val LocalTenant = androidx.compose.runtime.staticCompositionLocalOf<com.nascon.league.data.Tenant?> { null }

/** Tenant logo mark: initials on the tenant colour (logo_url is not set in the seed data). */
@Composable
fun TenantMark(tenant: com.nascon.league.data.Tenant) {
    val brand = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(brand)
            .semantics { contentDescription = "${tenant.name} logo" },
        contentAlignment = Alignment.Center,
    ) {
        val initials = tenant.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
        Text(initials, color = contentOn(brand), fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
        val tenant = LocalTenant.current
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else if (tenant != null) {
            TenantMark(tenant)
            Spacer(Modifier.width(12.dp))
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

/** Score numerals; the number rolls up when it changes (the app's one playful motion). */
@Composable
fun ScoreTile(value: String, size: Dp = 40.dp, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val fontSize = (size.value * 0.66f).sp
    Box(
        modifier
            .widthIn(min = size * 0.85f)
            .heightIn(min = size)
            .clip(RoundedCornerShape(size * 0.2f))
            .background(if (onDark) Color.Black.copy(alpha = 0.18f) else Pitch.Ink)
            .padding(horizontal = size * 0.16f),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
            },
            label = "score",
        ) { shown ->
            Text(
                shown,
                color = Color.White,
                style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = fontSize, fontFeatureSettings = TABULAR),
                maxLines = 1,
            )
        }
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

/** Match status label; "Live" gets a pulsing coral dot. Meaning is in the text, never colour alone. */
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
    val pulse = rememberInfiniteTransition(label = "live")
    val dotAlpha by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (live) Color.White else Pitch.Wash)
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .semantics { contentDescription = "Status: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Pitch.Floodlight.copy(alpha = dotAlpha)))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (live) Pitch.Whistle else Pitch.Ink)
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

/** Chunky primary button from the mockup: darker bottom edge that squishes down when pressed. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val active = enabled && !busy
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth by animateDpAsState(if (pressed) 1.dp else 4.dp, label = "depth")
    val face = if (active) primary else Pitch.Line
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) primary.edge() else Pitch.Edge)
            .padding(bottom = depth)
            .clip(RoundedCornerShape(14.dp))
            .background(face)
            .clickable(interactionSource = source, indication = ripple(), enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = contentOn(primary))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (active) contentOn(primary) else Pitch.Muted)
        }
    }
}

/** Chunky selectable pill (mockup "sport" chips): white with a sand edge, ink when selected. 48dp target. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val face by animateColorAsState(if (selected) Pitch.Ink else Pitch.Card, label = "pill")
    val edgeColor by animateColorAsState(if (selected) Color(0xFF16231F) else Pitch.Edge, label = "pillEdge")
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> Pitch.Muted.copy(alpha = 0.6f)
                selected -> Color.White
                else -> Pitch.Ink
            },
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(edgeColor)
                .padding(bottom = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(face)
                .border(1.5.dp, if (selected) Pitch.Ink else Pitch.Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Raised card with the mockup's chunky darker bottom edge. */
@Composable
fun ChunkyCard(
    modifier: Modifier = Modifier,
    color: Color = Pitch.Card,
    edgeColor: Color = Pitch.Edge,
    corner: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(edgeColor)
            .padding(bottom = 5.dp)
            .clip(RoundedCornerShape(corner))
            .background(color),
    ) { content() }
}

