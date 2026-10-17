package com.nascon.league.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.data.Fixture
import com.nascon.league.data.League
import com.nascon.league.data.Loaded
import com.nascon.league.data.ServiceLocator
import com.nascon.league.data.Session
import com.nascon.league.ui.DEFAULT_LEAGUE
import com.nascon.league.ui.LoadViewModel
import com.nascon.league.ui.UiState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.MatchStatusPill
import com.nascon.league.ui.components.MessageState
import com.nascon.league.ui.components.Pill
import com.nascon.league.ui.components.RefreshButton
import com.nascon.league.ui.components.SavedDataNotice
import com.nascon.league.ui.components.ScoreTile
import com.nascon.league.ui.components.ScreenHeader
import com.nascon.league.ui.components.SectionLabel
import com.nascon.league.ui.components.TeamBadge
import com.nascon.league.ui.prettyTime
import com.nascon.league.ui.theme.Pitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Loads the league list once, then fixtures for the chosen league. Shared by Matches and Table tabs. */
abstract class LeagueScopedViewModel<T>(startLeague: String) : LoadViewModel<T>() {
    val leagues = MutableStateFlow<List<League>>(emptyList())
    val selectedLeague = MutableStateFlow(startLeague)

    init {
        viewModelScope.launch {
            runCatching { ServiceLocator.repo.leagues().data }.onSuccess { leagues.value = it }
        }
    }

    fun selectLeague(id: String) {
        if (id == selectedLeague.value) return
        selectedLeague.value = id
        load()
    }
}

class FixturesViewModel(startLeague: String) : LeagueScopedViewModel<List<Fixture>>(startLeague) {
    override suspend fun fetch(): Loaded<List<Fixture>> = ServiceLocator.repo.fixtures(selectedLeague.value)
    init { load() }
}

@Composable
fun FixturesScreen(session: Session, onOpenMatch: (String) -> Unit) {
    val vm: FixturesViewModel = viewModel { FixturesViewModel(session.user.league_id ?: DEFAULT_LEAGUE) }
    val state by vm.state.collectAsStateWithLifecycle()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val selected by vm.selectedLeague.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Matches",
            subtitle = leagues.firstOrNull { it.league_id == selected }?.name,
            actions = { RefreshButton(vm::load) },
        )
        LeaguePicker(leagues, selected, vm::selectLeague)

        when (val s = state) {
            UiState.Loading -> LoadingState("Loading matches")
            is UiState.Failed -> ErrorState(s.message, vm::load)
            is UiState.Ready -> {
                if (s.data.isEmpty()) {
                    MessageState(Icons.Filled.DateRange, "No matches yet", "Fixtures appear here once the organizer schedules them.")
                } else {
                    FixtureList(s.data, s.savedAt, onOpenMatch)
                }
            }
        }
    }
}

@Composable
fun LeaguePicker(leagues: List<League>, selected: String, onSelect: (String) -> Unit) {
    if (leagues.size < 2) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leagues.forEach { league ->
            Pill(
                text = league.name.ifBlank { league.league_id },
                selected = league.league_id == selected,
                onClick = { onSelect(league.league_id) },
                modifier = Modifier.testTag("league_${league.league_id}"),
            )
        }
    }
}

@Composable
private fun FixtureList(fixtures: List<Fixture>, savedAt: Long?, onOpenMatch: (String) -> Unit) {
    // Real grouping by what the user cares about: what's on now, what's next, what happened.
    val groups = listOf(
        "Live now" to fixtures.filter { it.status == "in_progress" },
        "Coming up" to fixtures.filter { it.status == "upcoming" },
        "Results" to fixtures.filter { it.status == "completed" },
        "Postponed or called off" to fixtures.filter { it.status in setOf("postponed", "abandoned", "cancelled") },
    ).filter { it.second.isNotEmpty() }

    LazyColumn(Modifier.fillMaxSize().testTag("fixtures_list")) {
        item { SavedDataNotice(savedAt) }
        groups.forEach { (title, list) ->
            item(key = "h-$title") { SectionLabel(title) }
            items(list, key = { it.match_id }) { fixture ->
                FixtureRow(fixture) { onOpenMatch(fixture.match_id) }
                HorizontalDivider(Modifier.padding(start = 20.dp), color = Pitch.Line)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FixtureRow(f: Fixture, onClick: () -> Unit) {
    val showScore = f.status != "upcoming" && f.home_score != null && f.away_score != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open match", onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag("fixture_${f.match_id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TeamLine(f.home_name ?: f.home_team_id, f.home_team_id)
            Spacer(Modifier.height(8.dp))
            TeamLine(f.away_name ?: f.away_team_id, f.away_team_id)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MatchStatusPill(f.status)
                val meta = listOfNotNull(prettyTime(f.scheduled_at), f.venue).joinToString(", ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = Pitch.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (showScore) {
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                ScoreTile(f.home_score.toString(), size = 34.dp)
                ScoreTile(f.away_score.toString(), size = 34.dp)
            }
        }
    }
}

@Composable
private fun TeamLine(name: String, id: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamBadge(name, id, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
