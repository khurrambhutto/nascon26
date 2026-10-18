package com.nascon.league.ui.screens

import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.data.ActionResult
import com.nascon.league.data.AuditEntry
import com.nascon.league.data.Club
import com.nascon.league.data.RosterPlayer
import com.nascon.league.data.ServiceLocator
import com.nascon.league.data.Session
import com.nascon.league.data.TransferRequest
import com.nascon.league.data.friendlyError
import com.nascon.league.ui.DEFAULT_LEAGUE
import com.nascon.league.ui.UiState
import com.nascon.league.ui.canManageRoster
import com.nascon.league.ui.components.Banner
import com.nascon.league.ui.components.DialogTags
import com.nascon.league.ui.components.BannerKind
import com.nascon.league.ui.components.BannerMessage
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.MessageState
import com.nascon.league.ui.components.Pill
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.ScreenHeader
import com.nascon.league.ui.components.SectionLabel
import com.nascon.league.ui.humanize
import com.nascon.league.ui.theme.Pitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

class RosterViewModel(private val clubId: String, private val leagueId: String) : ViewModel() {
    private val repo = ServiceLocator.repo
    val club = MutableStateFlow<UiState<Club>>(UiState.Loading)
    val destinations = MutableStateFlow<List<Pair<String, String>>>(emptyList()) // squad id -> club name
    val history = MutableStateFlow<List<AuditEntry>>(emptyList())
    val message = MutableStateFlow<BannerMessage?>(null)
    val busy = MutableStateFlow(false)

    init { load() }

    fun load() {
        viewModelScope.launch {
            club.value = try {
                val loaded = repo.club(clubId)
                UiState.Ready(loaded.data, loaded.savedAt)
            } catch (e: Exception) {
                UiState.Failed(friendlyError(e))
            }
            // Other clubs in the league are the possible destinations. The transfer needs each
            // club's squad id, which we read from that club's roster (falls back to the club id).
            destinations.value = runCatching {
                repo.fixtures(leagueId).data
                    .flatMap { listOf(it.home_team_id to (it.home_name ?: it.home_team_id), it.away_team_id to (it.away_name ?: it.away_team_id)) }
                    .distinctBy { it.first }
                    .filter { it.first != clubId }
                    .map { (otherClub, name) ->
                        val squadId = runCatching { repo.club(otherClub).data.roster.firstOrNull()?.squad_id }.getOrNull()
                        (squadId ?: otherClub) to name
                    }
            }.getOrDefault(emptyList())
        }
    }

    fun loadHistory(playerId: String) {
        viewModelScope.launch {
            history.value = runCatching { repo.history(playerId).data.history }.getOrDefault(emptyList())
        }
    }

    fun submit(player: RosterPlayer, type: String, toSquad: String?, effective: String, loanEnd: String?, reason: String, userId: String) {
        busy.value = true
        val body = TransferRequest(
            request_id = "tr-" + UUID.randomUUID().toString().take(12),
            league_id = leagueId,
            player_id = player.player_id,
            type = type,
            from_squad_id = player.squad_id ?: clubId,
            to_squad_id = if (type == "release") null else toSquad,
            effective_date = effective,
            loan_end_date = if (type == "loan") loanEnd else null,
            reason = reason,
            requested_by = userId,
        )
        val label = when (type) {
            "transfer" -> "Transfer ${player.name}"
            "loan" -> "Loan ${player.name}"
            else -> "Release ${player.name}"
        }
        viewModelScope.launch {
            message.value = try {
                // base_version for roster actions = number of audit entries the phone has seen for this player.
                when (val r = repo.sendTransfer(body, history.value.size, label)) {
                    is ActionResult.Sent -> BannerMessage(r.message, BannerKind.Success)
                    is ActionResult.Queued -> BannerMessage(r.message, BannerKind.Info)
                }
            } catch (e: Exception) {
                BannerMessage(friendlyError(e), BannerKind.Error)
            }
            busy.value = false
            load()
            loadHistory(player.player_id)
        }
    }

    /** Adds a brand-new player to this club's squad (needs a connection). */
    fun addPlayer(name: String, position: String, role: String, reason: String, onDone: () -> Unit) {
        busy.value = true
        viewModelScope.launch {
            message.value = try {
                repo.addPlayer(clubId, name, position, role, reason)
                onDone()
                BannerMessage("$name joined the squad.", BannerKind.Success)
            } catch (e: Exception) {
                BannerMessage(friendlyError(e), BannerKind.Error)
            }
            busy.value = false
            load()
        }
    }

    fun dismiss() { message.value = null }
}

private val dateRegex = Regex("""\d{4}-\d{2}-\d{2}""")

private fun validDate(text: String) = dateRegex.matches(text) && runCatching { LocalDate.parse(text) }.isSuccess

@Composable
fun RosterScreen(clubId: String, session: Session, onBack: () -> Unit) {
    val leagueId = DEFAULT_LEAGUE
    val vm: RosterViewModel = viewModel(key = "roster-$clubId") { RosterViewModel(clubId, leagueId) }
    val clubState by vm.club.collectAsStateWithLifecycle()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var player by remember { mutableStateOf<RosterPlayer?>(null) }
    var action by remember { mutableStateOf("transfer") }
    var destination by remember { mutableStateOf<String?>(null) }
    var effective by remember { mutableStateOf(LocalDate.now().toString()) }
    var loanEnd by remember { mutableStateOf(LocalDate.now().plusDays(30).toString()) }
    var reason by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Manage squad", subtitle = (clubState as? UiState.Ready)?.data?.name, onBack = onBack)

        if (!session.canManageRoster(clubId)) {
            MessageState(
                Icons.Filled.Lock,
                "Only the club manager can change this squad",
                "Sign in as the manager of this club to transfer, loan or release players.",
            )
            return
        }

        when (val s = clubState) {
            UiState.Loading -> { LoadingState("Loading squad"); return }
            is UiState.Failed -> { ErrorState(s.message, vm::load); return }
            is UiState.Ready -> Unit
        }
        val club = (clubState as UiState.Ready).data

        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Move a player", selected = !adding, onClick = { adding = false }, modifier = Modifier.testTag("mode_move"))
            Pill("Add a new player", selected = adding, onClick = { adding = true }, modifier = Modifier.testTag("mode_add"))
        }
        if (adding) {
            AddPlayerForm(message, busy, vm::dismiss) { name, position, role, reason, onDone ->
                focus.clearFocus()
                vm.addPlayer(name, position, role, reason, onDone)
            }
            return
        }

        // Validation messages written for people, shown next to the submit button.
        val problem = when {
            player == null -> "Pick a player."
            action != "release" && destination == null -> "Pick the club they're moving to."
            !validDate(effective) -> "Enter the start date as YYYY-MM-DD."
            action == "loan" && !validDate(loanEnd) -> "Enter the loan end date as YYYY-MM-DD."
            action == "loan" && loanEnd <= effective -> "The loan must end after it starts."
            reason.isBlank() -> "Add a short reason. It's saved in the player's history."
            else -> null
        }

        // After a result arrives, jump to the top so the message is visible.
        val scroll = rememberScrollState()
        LaunchedEffect(message) { if (message != null) scroll.animateScrollTo(0) }

        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            message?.let { Banner(it.text, it.kind, onDismiss = vm::dismiss) }

            SectionLabel("1. Player")
            club.roster.forEach { p ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = player?.player_id == p.player_id, role = Role.RadioButton, onClick = {
                            player = p
                            vm.loadHistory(p.player_id)
                        })
                        .heightIn(min = 56.dp).padding(horizontal = 8.dp)
                        .testTag("pick_${p.player_id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = player?.player_id == p.player_id, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                    Column {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("${humanize(p.position)}, ${roleLabel(p.role)}", style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted)
                    }
                }
            }
            if (club.roster.isEmpty()) {
                Text("No players in this squad.", color = Pitch.Muted, modifier = Modifier.padding(horizontal = 20.dp))
            }

            SectionLabel("2. What should happen")
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("transfer" to "Transfer", "loan" to "Loan", "release" to "Release").forEach { (key, label) ->
                    Pill(label, selected = action == key, onClick = { action = key }, modifier = Modifier.testTag("action_$key"))
                }
            }
            Text(
                when (action) {
                    "transfer" -> "The player joins the other club permanently as a substitute."
                    "loan" -> "The player plays for the other club until the end date, then returns automatically."
                    else -> "The player leaves the league and no longer belongs to any squad."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Pitch.Muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            if (action != "release") {
                SectionLabel("3. Moving to")
                if (destinations.isEmpty()) {
                    Text("No other clubs found in this league.", color = Pitch.Muted, modifier = Modifier.padding(horizontal = 20.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    destinations.forEach { (id, name) ->
                        Pill(name, selected = destination == id, onClick = { destination = id }, modifier = Modifier.testTag("dest_$id"))
                    }
                }
            }

            SectionLabel(if (action == "release") "3. Details" else "4. Details")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = effective, onValueChange = { effective = it.trim() },
                    label = { Text("Starts on (YYYY-MM-DD)") }, singleLine = true,
                    isError = !validDate(effective),
                    modifier = Modifier.fillMaxWidth().testTag("effective_date"),
                )
                if (action == "loan") {
                    OutlinedTextField(
                        value = loanEnd, onValueChange = { loanEnd = it.trim() },
                        label = { Text("Loan ends on (YYYY-MM-DD)") }, singleLine = true,
                        isError = !validDate(loanEnd),
                        modifier = Modifier.fillMaxWidth().testTag("loan_end_date"),
                    )
                }
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it },
                    label = { Text("Reason") }, placeholder = { Text("For example: needs more game time") },
                    modifier = Modifier.fillMaxWidth().testTag("reason_input"),
                )
            }

            if (player != null) {
                SectionLabel("${player!!.name}'s history")
                if (history.isEmpty()) {
                    Text("No squad changes yet.", color = Pitch.Muted, modifier = Modifier.padding(horizontal = 20.dp))
                }
                history.reversed().forEach { entry ->
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(
                            "${humanize(entry.action)}: ${entry.from_squad_id ?: "none"} to ${entry.to_squad_id ?: "none"}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "On ${entry.effective_date ?: entry.`when`.take(10)} by ${entry.who}${entry.why?.let { ". $it" } ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Pitch.Muted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (problem != null) {
                Text(problem, style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted, modifier = Modifier.padding(bottom = 6.dp))
            }
            PrimaryButton(
                text = when (action) { "transfer" -> "Transfer player"; "loan" -> "Loan player"; else -> "Release player" },
                enabled = problem == null,
                busy = busy,
                onClick = { confirm = true },
                modifier = Modifier.fillMaxWidth().testTag("submit_roster"),
            )
        }
    }

    if (confirm && player != null) {
        val p = player!!
        val toName = destinations.firstOrNull { it.first == destination }?.second
        AlertDialog(
            modifier = DialogTags,
            onDismissRequest = { confirm = false },
            title = { Text(if (action == "release") "Release ${p.name}?" else "${humanize(action)} ${p.name} to $toName?") },
            text = { Text("This is saved permanently in the player's history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        focus.clearFocus()
                        vm.submit(p, action, destination, effective, loanEnd, reason.trim(), session.user.user_id)
                        reason = ""
                        destination = null
                    },
                    modifier = Modifier.testTag("confirm_roster"),
                ) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}


@Composable
private fun AddPlayerForm(
    message: BannerMessage?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String, () -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var position by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("substitute") }
    var reason by remember { mutableStateOf("") }
    val problem = when {
        name.isBlank() -> "Enter the player's name."
        position.isBlank() -> "Enter a position, for example gk, df or fw."
        else -> null
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        message?.let { Banner(it.text, it.kind, onDismiss = onDismiss) }
        SectionLabel("New player")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("new_player_name"))
            OutlinedTextField(value = position, onValueChange = { position = it }, label = { Text("Position") }, singleLine = true,
                placeholder = { Text("For example gk, df, fw") }, modifier = Modifier.fillMaxWidth().testTag("new_player_position"))
            Text("Role", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("captain", "vice_captain", "starter", "substitute").forEach { r ->
                    Pill(roleLabel(r), selected = role == r, onClick = { role = r }, modifier = Modifier.testTag("new_role_$r"))
                }
            }
            OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Note (optional)") },
                placeholder = { Text("For example: academy graduate") }, modifier = Modifier.fillMaxWidth().testTag("new_player_reason"))
            if (problem != null) Text(problem, style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted)
            PrimaryButton(
                text = "Add player",
                enabled = problem == null,
                busy = busy,
                onClick = {
                    onAdd(name, position, role, reason.ifBlank { "New signing" }) { name = ""; position = ""; reason = "" }
                },
                modifier = Modifier.fillMaxWidth().testTag("add_player_button"),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
