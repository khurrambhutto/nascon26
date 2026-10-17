package com.nascon.league.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.data.Club
import com.nascon.league.data.Loaded
import com.nascon.league.data.ServiceLocator
import com.nascon.league.data.Session
import com.nascon.league.ui.LoadViewModel
import com.nascon.league.ui.UiState
import com.nascon.league.ui.canManageRoster
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.RefreshButton
import com.nascon.league.ui.components.SavedDataNotice
import com.nascon.league.ui.components.ScreenHeader
import com.nascon.league.ui.components.SectionLabel
import com.nascon.league.ui.components.TeamBadge
import com.nascon.league.ui.humanize
import com.nascon.league.ui.theme.Pitch
import com.nascon.league.ui.theme.contentOn

class ClubViewModel(private val clubId: String) : LoadViewModel<Club>() {
    override suspend fun fetch(): Loaded<Club> = ServiceLocator.repo.club(clubId)
    init { load() }
}

fun roleLabel(role: String): String = when (role) {
    "captain" -> "Captain"
    "vice_captain" -> "Vice-captain"
    "starter" -> "Starter"
    "substitute" -> "Substitute"
    else -> humanize(role)
}

@Composable
fun ClubScreen(clubId: String, session: Session, onBack: (() -> Unit)?, onManageRoster: (String) -> Unit) {
    val vm: ClubViewModel = viewModel(key = "club-$clubId") { ClubViewModel(clubId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Club", onBack = onBack, actions = { RefreshButton(vm::load) })
        when (val s = state) {
            UiState.Loading -> LoadingState("Loading club")
            is UiState.Failed -> ErrorState(s.message, vm::load)
            is UiState.Ready -> {
                val club = s.data
                LazyColumn(Modifier.fillMaxSize().testTag("club_profile")) {
                    item { SavedDataNotice(s.savedAt) }
                    item { ClubHero(club, session.tenant?.name) }
                    if (session.canManageRoster(club.club_id)) {
                        item {
                            PrimaryButton(
                                text = "Manage squad",
                                onClick = { onManageRoster(club.club_id) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("manage_roster"),
                            )
                        }
                    }

                    club.home_venue?.let { venue ->
                        item {
                            SectionLabel("Home ground")
                            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(venue.name.ifBlank { "Venue to be announced" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (venue.lat != null && venue.lng != null) {
                                    TextButton(
                                        onClick = {
                                            val uri = Uri.parse("geo:${venue.lat},${venue.lng}?q=${venue.lat},${venue.lng}(${Uri.encode(venue.name)})")
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("open_map"),
                                    ) { Text("Open map") }
                                }
                            }
                        }
                    }

                    val socials = club.socials.filterValues { !it.isNullOrBlank() }
                    if (socials.isNotEmpty()) {
                        item {
                            SectionLabel("Follow the club")
                            socials.forEach { (network, handle) ->
                                InfoLine(humanize(network), handle.orEmpty())
                            }
                        }
                    }

                    if (club.staff.isNotEmpty()) {
                        item { SectionLabel("Staff") }
                        items(club.staff) { InfoLine(it.name, humanize(it.role)) }
                    }

                    item { SectionLabel("Squad (${club.roster.size})") }
                    if (club.roster.isEmpty()) {
                        item { Text("No players registered yet.", color = Pitch.Muted, modifier = Modifier.padding(horizontal = 20.dp)) }
                    }
                    items(club.roster, key = { it.player_id }) { player ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 6.dp)
                                .testTag("player_${player.player_id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TeamBadge(player.name, player.player_id, size = 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(player.name, style = MaterialTheme.typography.titleMedium)
                                Text(humanize(player.position), style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted)
                            }
                            if (player.role.isNotBlank()) Text(roleLabel(player.role), style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = Pitch.Line)
                    }

                    if (club.stats.isNotEmpty()) {
                        item { SectionLabel("Season record") }
                        items(club.stats) { stat ->
                            InfoLine(stat.season, "Played ${stat.played}, won ${stat.won}, drawn ${stat.drawn}, lost ${stat.lost}")
                        }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ClubHero(club: Club, tenantName: String?) {
    val brand = MaterialTheme.colorScheme.primary
    val onBrand = contentOn(brand)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp)).background(brand).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamBadge(club.name, club.club_id, size = 64.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(club.name, style = MaterialTheme.typography.headlineSmall, color = onBrand)
            tenantName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = onBrand.copy(alpha = 0.85f)) }
        }
    }
}

@Composable
private fun InfoLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted)
    }
}
