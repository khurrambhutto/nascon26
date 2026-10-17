package com.nascon.league.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import com.nascon.league.data.Product
import com.nascon.league.ui.components.Pill
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.data.ActionResult
import com.nascon.league.data.Loaded
import com.nascon.league.data.MatchDetail
import com.nascon.league.data.MatchEvent
import com.nascon.league.data.ServiceLocator
import com.nascon.league.data.Session
import com.nascon.league.data.Side
import com.nascon.league.data.SportConfig
import com.nascon.league.data.friendlyError
import com.nascon.league.ui.LoadViewModel
import com.nascon.league.ui.UiState
import com.nascon.league.ui.canScore
import com.nascon.league.ui.components.Banner
import com.nascon.league.ui.components.DialogTags
import com.nascon.league.ui.components.BannerKind
import com.nascon.league.ui.components.BannerMessage
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.MatchStatusPill
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.RefreshButton
import com.nascon.league.ui.components.SavedDataNotice
import com.nascon.league.ui.components.ScoreTile
import com.nascon.league.ui.components.ScreenHeader
import com.nascon.league.ui.components.SectionLabel
import com.nascon.league.ui.components.TeamBadge
import com.nascon.league.ui.humanize
import com.nascon.league.ui.money
import com.nascon.league.ui.theme.Pitch
import com.nascon.league.ui.theme.contentOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class MatchViewModel(private val matchId: String) : LoadViewModel<MatchDetail>() {
    private val repo = ServiceLocator.repo
    val sport = MutableStateFlow<SportConfig?>(null)
    val teamNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val message = MutableStateFlow<BannerMessage?>(null)
    val busy = MutableStateFlow(false)

    override suspend fun fetch(): Loaded<MatchDetail> {
        val result = repo.match(matchId)
        if (sport.value == null) {
            sport.value = runCatching { repo.sport(result.data.sport_id).data }.getOrNull()
        }
        if (teamNames.value.isEmpty()) {
            teamNames.value = runCatching {
                repo.fixtures(result.data.league_id).data.flatMap {
                    listOfNotNull(it.home_name?.let { n -> it.home_team_id to n }, it.away_name?.let { n -> it.away_team_id to n })
                }.toMap()
            }.getOrDefault(emptyMap())
        }
        return result
    }

    init {
        load()
        startLive()
    }

    /** Live score: Server-Sent Events first; if the stream fails, poll every 3 s instead. */
    private fun startLive() {
        viewModelScope.launch {
            while (true) {
                try {
                    repo.matchStream(matchId).collect { replace(it) }
                } catch (_: Exception) {
                    // Stream closed or not available: fall back to a poll.
                }
                delay(3_000)
                load()
            }
        }
    }

    fun send(type: String, payload: JsonObject, label: String) {
        val current = (state.value as? UiState.Ready)?.data ?: return
        busy.value = true
        viewModelScope.launch {
            message.value = try {
                when (val r = repo.sendEvent(matchId, type, payload, current.version, label)) {
                    is ActionResult.Sent -> BannerMessage(r.message, BannerKind.Success)
                    is ActionResult.Queued -> BannerMessage(r.message, BannerKind.Info)
                }
            } catch (e: Exception) {
                BannerMessage(friendlyError(e), BannerKind.Error)
            }
            busy.value = false
            load()
        }
    }

    val products = MutableStateFlow<List<Product>>(emptyList())

    fun loadProducts() {
        if (products.value.isNotEmpty()) return
        viewModelScope.launch { products.value = runCatching { repo.products().data }.getOrDefault(emptyList()) }
    }

    /** Organizer: postpone / abandon / cancel / resume (server checks the state machine). */
    fun setStatus(status: String, label: String) = online(label) { repo.setMatchStatus(matchId, status) }

    /** Organizer: feature a store product on this match; connected clients update over the live stream. */
    fun pin(productId: String?, label: String) = online(label) { repo.pinProduct(matchId, productId) }

    private fun online(label: String, block: suspend () -> Unit) {
        busy.value = true
        viewModelScope.launch {
            message.value = try {
                block()
                BannerMessage("$label done.", BannerKind.Success)
            } catch (e: Exception) {
                BannerMessage(friendlyError(e), BannerKind.Error)
            }
            busy.value = false
            load()
        }
    }

    fun dismissMessage() { message.value = null }
}

@Composable
fun MatchScreen(matchId: String, session: Session, onBack: () -> Unit, onOpenProduct: (String) -> Unit) {
    val vm: MatchViewModel = viewModel(key = "match-$matchId") { MatchViewModel(matchId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sport by vm.sport.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val teamNames by vm.teamNames.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Match", subtitle = sport?.name, onBack = onBack, actions = { RefreshButton(vm::load) })
        when (val s = state) {
            UiState.Loading -> LoadingState("Loading match")
            is UiState.Failed -> ErrorState(s.message, vm::load)
            is UiState.Ready -> {
                val match = s.data
                val names = mapOf(
                    match.home.team_id to (match.home.name ?: teamNames[match.home.team_id] ?: humanize(match.home.team_id)),
                    match.away.team_id to (match.away.name ?: teamNames[match.away.team_id] ?: humanize(match.away.team_id)),
                )
                LazyColumn(Modifier.fillMaxSize().testTag("match_detail")) {
                    item { SavedDataNotice(s.savedAt) }
                    item { Scoreboard(match, names) }
                    message?.let { m -> item { Banner(m.text, m.kind, onDismiss = vm::dismissMessage) } }
                    if (session.canScore(match.league_id)) {
                        item { ScoringPanel(match, sport, names, busy, vm::send) }
                        item { StatusControls(match.status, busy, vm::setStatus) }
                        item {
                            val products by vm.products.collectAsStateWithLifecycle()
                            LaunchedEffect(Unit) { vm.loadProducts() }
                            PinControls(products, match.pinned_product?.product_id, busy, vm::pin)
                        }
                    }
                    match.pinned_product?.let { product ->
                        item {
                            SectionLabel("Featured in the store")
                            PinnedProduct(product.name, product.variants.minOfOrNull { it.price_minor }) { onOpenProduct(product.product_id) }
                        }
                    }
                    item { SectionLabel("Timeline") }
                    if (match.events.isEmpty()) {
                        item {
                            Text(
                                "Nothing has happened yet. Events appear here as they're recorded.",
                                color = Pitch.Muted,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }
                    items(match.events.reversed(), key = { it.event_id }) { event ->
                        TimelineRow(
                            event = event,
                            names = names,
                            sport = sport,
                            canUndo = session.canScore(match.league_id) && !event.compensated &&
                                event.type in setOf("score", "discipline", "tiebreak_winner"),
                            onUndo = { reason ->
                                vm.send(
                                    "compensation",
                                    JsonObject(mapOf("compensates" to JsonPrimitive(event.event_id), "reason" to JsonPrimitive(reason))),
                                    "Undo ${describe(event, names, sport).lowercase()}",
                                )
                            },
                        )
                        HorizontalDivider(Modifier.padding(start = 20.dp), color = Pitch.Line)
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

// ---------- Scoreboard (the app's signature element) ----------

@Composable
private fun Scoreboard(match: MatchDetail, names: Map<String, String>) {
    val brand = MaterialTheme.colorScheme.primary
    val onBrand = contentOn(brand)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(brand)
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .testTag("scoreboard"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MatchStatusPill(match.status)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SideColumn(match.home, names, onBrand, Modifier.weight(1f))
            ScoreTile(match.home.score.toString(), size = 64.dp, modifier = Modifier.testTag("home_score"))
            Text(":", color = onBrand.copy(alpha = 0.7f), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 6.dp))
            ScoreTile(match.away.score.toString(), size = 64.dp, modifier = Modifier.testTag("away_score"))
            SideColumn(match.away, names, onBrand, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Text(outcomeText(match, names), color = onBrand, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SideColumn(side: Side, names: Map<String, String>, textColor: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val name = names[side.team_id] ?: side.team_id
        TeamBadge(name, side.team_id, size = 44.dp)
        Spacer(Modifier.height(6.dp))
        Text(name, color = textColor, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        // Innings sports: wickets and overs. Hidden for other models (null).
        if (side.wickets != null) {
            Text("${side.score}/${side.wickets}, ${side.overs ?: "0.0"} ov", color = textColor.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun outcomeText(match: MatchDetail, names: Map<String, String>): String = when (match.outcome) {
    "home_win", "away_win" -> "${names[match.winner_team_id] ?: "Winner"} won"
    "draw" -> "Ended in a draw"
    "undecided" -> "Level, waiting for a tie-break result"
    else -> when (match.status) {
        "in_progress" -> "In play"
        "upcoming" -> "Not started"
        "postponed" -> "Waiting for a new date"
        "abandoned" -> "Stopped before the end"
        "cancelled" -> "Won't be played"
        else -> humanize(match.status)
    }
}

// ---------- Scoring entry (League Organizer) ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScoringPanel(
    match: MatchDetail,
    sport: SportConfig?,
    names: Map<String, String>,
    busy: Boolean,
    send: (String, JsonObject, String) -> Unit,
) {
    var confirmEnd by remember { mutableStateOf(false) }
    val empty = JsonObject(emptyMap())

    SectionLabel("Scoring")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (match.status) {
            "upcoming" -> PrimaryButton(
                text = "Start match",
                onClick = { send("match_start", empty, "Start match") },
                busy = busy,
                modifier = Modifier.fillMaxWidth().testTag("start_match"),
            )
            "in_progress" -> {
                if (sport == null) {
                    Text("Loading this sport's scoring rules.", color = Pitch.Muted)
                }
                listOf(match.home, match.away).forEach { side ->
                    val team = names[side.team_id] ?: side.team_id
                    Text(team, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Buttons come from the sport's config. No sport or event names are hard-coded.
                        sport?.scoring_events?.forEach { (key, rule) ->
                            ScoreButton(eventButtonLabel(key, rule), enabled = !busy, tag = "score_${side.team_id}_$key") {
                                send(
                                    "score",
                                    JsonObject(mapOf("team_id" to JsonPrimitive(side.team_id), "event_type" to JsonPrimitive(key))),
                                    "${humanize(key)} for $team",
                                )
                            }
                        }
                        ScoreButton("Fair-play −1", enabled = !busy, tag = "discipline_${side.team_id}") {
                            send(
                                "discipline",
                                JsonObject(mapOf("team_id" to JsonPrimitive(side.team_id), "points" to JsonPrimitive(-1))),
                                "Fair-play penalty for $team",
                            )
                        }
                        if (match.home.score == match.away.score && sport?.draw_allowed == false) {
                            ScoreButton("Won tie-break", enabled = !busy, tag = "tiebreak_${side.team_id}") {
                                send("tiebreak_winner", JsonObject(mapOf("team_id" to JsonPrimitive(side.team_id))), "Tie-break won by $team")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { confirmEnd = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("end_match"),
                ) { Text("End match") }
            }
            "completed" -> Text("This match is finished. You can still undo a wrong event from the timeline.", color = Pitch.Muted)
            else -> Text("Scoring is paused while the match is ${humanize(match.status).lowercase()}.", color = Pitch.Muted)
        }
    }

    if (confirmEnd) {
        AlertDialog(
            modifier = DialogTags,
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this match?") },
            text = { Text("The final score will count in the table. You can still undo individual events afterwards.") },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; send("match_end", empty, "End match") }, modifier = Modifier.testTag("confirm_end")) {
                    Text("End match")
                }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep playing") } },
        )
    }
}

@Composable
private fun ScoreButton(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
    ) { Text(text) }
}

/** "+3 Three pointer", "Wicket", "+1 Wide" or "+1 Map win", read from the sport's rule values. */
private fun eventButtonLabel(key: String, rule: JsonObject): String {
    val amount = rule["points"]?.jsonPrimitive?.intOrNull
        ?: rule["runs"]?.jsonPrimitive?.intOrNull
        ?: rule["rounds"]?.jsonPrimitive?.intOrNull
    val isWicket = rule["wicket"]?.jsonPrimitive?.booleanOrNull == true
    return when {
        isWicket && (amount ?: 0) == 0 -> humanize(key)
        amount != null && amount > 0 -> "+$amount ${humanize(key)}"
        else -> humanize(key)
    }
}

// ---------- Timeline ----------

private fun describe(event: MatchEvent, names: Map<String, String>, sport: SportConfig?): String {
    val p = event.payload
    val team = p["team_id"]?.jsonPrimitive?.contentOrNull?.let { names[it] ?: it }
    return when (event.type) {
        "match_init" -> "Match created"
        "match_start" -> "Match started"
        "match_end" -> "Match ended"
        "score" -> {
            val key = p["event_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val rule = sport?.scoring_events?.get(key)
            "${if (rule != null) eventButtonLabel(key, rule) else humanize(key)}, $team"
        }
        "discipline" -> "Fair-play ${p["points"]?.jsonPrimitive?.contentOrNull ?: ""}, $team"
        "tiebreak_winner" -> "Tie-break won by $team"
        "compensation" -> "Correction: ${p["reason"]?.jsonPrimitive?.contentOrNull ?: "no reason given"}"
        else -> humanize(event.type)
    }
}

@Composable
private fun TimelineRow(
    event: MatchEvent,
    names: Map<String, String>,
    sport: SportConfig?,
    canUndo: Boolean,
    onUndo: (String) -> Unit,
) {
    var askReason by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .testTag("event_${event.event_id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${event.sequence}",
            style = MaterialTheme.typography.labelMedium,
            color = Pitch.Muted,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                describe(event, names, sport),
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (event.compensated) TextDecoration.LineThrough else null,
                color = if (event.compensated) Pitch.Muted else Pitch.Ink,
            )
            if (event.compensated) {
                Text("Undone, doesn't count", style = MaterialTheme.typography.labelMedium, color = Pitch.Muted)
            }
        }
        if (canUndo) {
            TextButton(onClick = { askReason = true }, modifier = Modifier.testTag("undo_${event.event_id}")) { Text("Undo") }
        }
    }

    if (askReason) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            modifier = DialogTags,
            onDismissRequest = { askReason = false },
            title = { Text("Undo this event?") },
            text = {
                Column {
                    Text("A correction is added to the record. The original stays visible, crossed out.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason") },
                        placeholder = { Text("For example: offside, goal disallowed") },
                        modifier = Modifier.fillMaxWidth().testTag("undo_reason"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = reason.isNotBlank(),
                    onClick = { askReason = false; onUndo(reason.trim()) },
                    modifier = Modifier.testTag("confirm_undo"),
                ) { Text("Undo event") }
            },
            dismissButton = { TextButton(onClick = { askReason = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PinnedProduct(name: String, fromPrice: Long?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Pitch.Line, RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(14.dp)
            .testTag("pinned_product"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (fromPrice != null) Text("From ${money(fromPrice)}", color = Pitch.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("View item") }
    }
}


// ---------- Organizer: match status (FR-1.3 state machine) ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusControls(status: String, busy: Boolean, onChange: (String, String) -> Unit) {
    // Only the moves the server allows from the current state are offered.
    val options = when (status) {
        "upcoming" -> listOf("postponed" to "Postpone", "cancelled" to "Cancel match")
        "in_progress" -> listOf("postponed" to "Postpone", "abandoned" to "Abandon")
        "postponed" -> listOf("upcoming" to "Reschedule as upcoming", "cancelled" to "Cancel match")
        else -> emptyList()
    }
    if (options.isEmpty()) return
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    SectionLabel("Match status")
    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            ScoreButton(option.second, enabled = !busy, tag = "status_${option.first}") { pending = option }
        }
    }
    pending?.let { (next, label) ->
        AlertDialog(
            modifier = DialogTags,
            onDismissRequest = { pending = null },
            title = { Text("$label?") },
            text = { Text("Everyone following this match sees the change straight away.") },
            confirmButton = {
                TextButton(onClick = { pending = null; onChange(next, label) }, modifier = Modifier.testTag("confirm_status")) { Text(label) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep as is") } },
        )
    }
}

// ---------- Organizer: pin a product (FR-2.3) ----------

@Composable
private fun PinControls(products: List<Product>, pinnedId: String?, busy: Boolean, onPin: (String?, String) -> Unit) {
    if (products.isEmpty()) return
    SectionLabel("Feature a store item")
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        products.forEach { product ->
            Pill(
                text = product.name,
                selected = product.product_id == pinnedId,
                enabled = !busy,
                onClick = {
                    if (product.product_id == pinnedId) onPin(null, "Remove featured item")
                    else onPin(product.product_id, "Feature ${product.name}")
                },
                modifier = Modifier.testTag("pin_${product.product_id}"),
            )
        }
    }
    Text(
        if (pinnedId == null) "Tap an item to show it on this match." else "Tap the highlighted item again to remove it.",
        style = MaterialTheme.typography.bodyMedium,
        color = Pitch.Muted,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}
