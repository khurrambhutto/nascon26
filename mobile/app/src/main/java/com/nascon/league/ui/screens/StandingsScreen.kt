package com.nascon.league.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.data.Loaded
import com.nascon.league.data.ServiceLocator
import com.nascon.league.data.Session
import com.nascon.league.data.StandingRow
import com.nascon.league.data.Standings
import com.nascon.league.ui.DEFAULT_LEAGUE
import com.nascon.league.ui.UiState
import com.nascon.league.ui.components.ChunkyCard
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.MessageState
import com.nascon.league.ui.components.RefreshButton
import com.nascon.league.ui.components.SavedDataNotice
import com.nascon.league.ui.components.ScreenHeader
import com.nascon.league.ui.components.TeamBadge
import com.nascon.league.ui.humanize
import com.nascon.league.ui.theme.Condensed
import com.nascon.league.ui.theme.Pitch
import com.nascon.league.ui.theme.TABULAR
import com.nascon.league.ui.theme.edge
import kotlin.math.floor

/** Table + team names (names come from the league's fixtures, which are cached too). */
data class TableData(val standings: Standings, val names: Map<String, String>)

class StandingsViewModel(startLeague: String) : LeagueScopedViewModel<TableData>(startLeague) {
    override suspend fun fetch(): Loaded<TableData> {
        val repo = ServiceLocator.repo
        val table = repo.standings(selectedLeague.value)
        val names = runCatching {
            repo.fixtures(selectedLeague.value).data.flatMap {
                listOf(it.home_team_id to (it.home_name ?: it.home_team_id), it.away_team_id to (it.away_name ?: it.away_team_id))
            }.toMap()
        }.getOrDefault(emptyMap())
        return Loaded(TableData(table.data, names), table.savedAt)
    }

    init { load() }
}

private val numStyle = TextStyle(fontFeatureSettings = TABULAR)

@Composable
fun StandingsScreen(session: Session, onOpenClub: (String) -> Unit) {
    val vm: StandingsViewModel = viewModel { StandingsViewModel(session.user.league_id ?: DEFAULT_LEAGUE) }
    val state by vm.state.collectAsStateWithLifecycle()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val selected by vm.selectedLeague.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Table",
            subtitle = leagues.firstOrNull { it.league_id == selected }?.name,
            actions = { RefreshButton(vm::load) },
        )
        LeaguePicker(leagues, selected, vm::selectLeague)
        when (val s = state) {
            UiState.Loading -> LoadingState("Loading table")
            is UiState.Failed -> ErrorState(s.message, vm::load)
            is UiState.Ready -> {
                val table = s.data.standings
                if (table.table.isEmpty()) {
                    MessageState(Icons.AutoMirrored.Filled.List, "No table yet", "The table fills in once the first match is played.")
                } else {
                    val clubId = session.user.club_id
                    val myRow = clubId?.let { id -> table.table.firstOrNull { it.team_id == id } }
                    LazyColumn(Modifier.fillMaxSize().testTag("standings_table")) {
                        item { SavedDataNotice(s.savedAt) }
                        myRow?.let { row -> item { RankBadge(row) } }
                        item { HeaderRow() }
                        itemsIndexed(table.table, key = { _, row -> row.team_id }) { index, row ->
                            StandingLine(row, s.data.names[row.team_id] ?: humanize(row.team_id), clubId) { onOpenClub(row.team_id) }
                            val isCutLine = index + 1 == table.qualification_spots && index < table.table.lastIndex
                            if (isCutLine) QualificationLine(table.qualification_spots)
                            else HorizontalDivider(color = Pitch.Line)
                        }
                        item { Legend() }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(
        Modifier.fillMaxWidth().background(Pitch.Wash).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell("#", 24)
        Text("Team", style = MaterialTheme.typography.labelMedium, color = Pitch.Muted, modifier = Modifier.weight(1f))
        listOf("P", "W", "D", "L").forEach { Cell(it, 26, muted = true) }
        Cell("GD", 46, muted = true)
        Cell("Pts", 36, muted = true)
    }
}

@Composable
private fun Cell(text: String, width: Int, bold: Boolean = false, muted: Boolean = false, accent: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(width.dp),
        textAlign = TextAlign.Center,
        style = (if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium).merge(numStyle),
        fontWeight = if (bold) FontWeight.Bold else null,
        color = if (accent) MaterialTheme.colorScheme.primary else if (muted) Pitch.Muted else Pitch.Ink,
        maxLines = 1,
    )
}

private fun ordinal(n: Int): String = when {
    n % 100 in 11..13 -> "${n}th"
    n % 10 == 1 -> "${n}st"
    n % 10 == 2 -> "${n}nd"
    n % 10 == 3 -> "${n}rd"
    else -> "${n}th"
}

@Composable
private fun RankBadge(row: StandingRow) {
    ChunkyCard(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = Pitch.Mint,
        edgeColor = Pitch.Mint.edge(),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Your club is ${ordinal(row.rank)}",
                style = MaterialTheme.typography.headlineSmall,
                color = Pitch.Ink,
                textAlign = TextAlign.Center,
            )
            Text(
                "${row.points} points from ${row.played} games",
                style = MaterialTheme.typography.bodyMedium,
                color = Pitch.MintDeep,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StandingLine(row: StandingRow, name: String, clubId: String?, onClick: () -> Unit) {
    val (statusText, statusIcon) = when (row.status) {
        "clinched" -> "Qualified" to Icons.Filled.CheckCircle
        "eliminated" -> "Out of contention" to Icons.Filled.Close
        else -> "Can still qualify" to null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (row.team_id == clubId) Modifier.background(Pitch.MintSoft) else Modifier)
            .clickable(onClickLabel = "Open club", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "$name, position ${row.rank}, ${row.points} points, $statusText" }
            .testTag("standing_${row.team_id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${row.rank}",
            modifier = Modifier.width(24.dp),
            fontFamily = Condensed,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium.merge(numStyle),
            textAlign = TextAlign.Center,
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TeamBadge(name, row.team_id, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (statusIcon != null) {
                        Icon(
                            statusIcon,
                            contentDescription = null,
                            tint = if (row.status == "clinched") Pitch.Win else Pitch.Muted,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(statusText, style = MaterialTheme.typography.labelMedium, color = Pitch.Muted, maxLines = 1)
                }
            }
        }
        Cell("${row.played}", 26)
        Cell("${row.won}", 26)
        Cell("${row.drawn}", 26)
        Cell("${row.lost}", 26)
        Cell(formatDiff(row.diff), 46)
        Cell("${row.points}", 36, bold = true, accent = true)
    }
}

/** Whole numbers for goal/point/map difference; three decimals for net run rate. */
private fun formatDiff(diff: Double): String {
    val text = if (diff == floor(diff)) diff.toLong().toString() else "%.3f".format(diff)
    return if (diff > 0) "+$text" else text
}

@Composable
private fun QualificationLine(spots: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
        Text(
            "Top $spots qualify",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Legend() {
    Text(
        "P played, W won, D drawn, L lost, GD score difference (net run rate for innings sports), Pts points. Tap a team to open its club.",
        style = MaterialTheme.typography.bodyMedium,
        color = Pitch.Muted,
        modifier = Modifier.padding(20.dp),
    )
    Spacer(Modifier.height(16.dp))
}
