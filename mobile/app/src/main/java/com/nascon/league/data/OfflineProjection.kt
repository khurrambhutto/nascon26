package com.nascon.league.data

import kotlinx.serialization.json.*

/** Preview the immutable event log plus pending actions. The server remains authoritative. */
fun projectPending(base: MatchDetail, sport: SportConfig, pending: List<EventRequest>): MatchDetail {
    val additions = pending.filter { it.match_id == base.match_id && base.events.none { e -> e.event_id == it.event_id } }
    if (additions.isEmpty()) return base
    var events = base.events + additions.mapIndexed { index, request ->
        MatchEvent(base.version + index + 1, request.event_id, request.type, request.payload)
    }
    val undone = events.filter { it.type == "compensation" }.associate {
        it.payload["compensates"]?.jsonPrimitive?.content.orEmpty() to it.event_id
    }
    events = events.map { it.copy(compensated = it.event_id in undone, compensated_by = undone[it.event_id]) }
    var home = base.home.copy(score = 0, fair_play = 0, wickets = base.home.wickets?.let { 0 }, balls = base.home.balls?.let { 0 })
    var away = base.away.copy(score = 0, fair_play = 0, wickets = base.away.wickets?.let { 0 }, balls = base.away.balls?.let { 0 })
    var status = "upcoming"
    var tiebreak: String? = null
    events.forEach { event ->
        when (event.type) { "match_start" -> status = "in_progress"; "match_end" -> status = "completed" }
        if (event.compensated) return@forEach
        val team = event.payload["team_id"]?.jsonPrimitive?.contentOrNull
        if (event.type == "tiebreak_winner") tiebreak = team
        fun apply(side: Side): Side {
            if (team != side.team_id) return side
            if (event.type == "discipline") return side.copy(fair_play = side.fair_play + (event.payload["points"]?.jsonPrimitive?.intOrNull ?: 0))
            if (event.type != "score") return side
            val rule = sport.scoring_events[event.payload["event_type"]?.jsonPrimitive?.contentOrNull] ?: return side
            val points = listOf("points", "runs", "rounds").firstNotNullOfOrNull { rule[it]?.jsonPrimitive?.intOrNull } ?: 0
            val balls = side.balls?.plus(if (rule["legal_ball"]?.jsonPrimitive?.booleanOrNull == true) 1 else 0)
            // Rules can define different balls per over; passed through SportConfig below.
            val overSize = sport.periods["balls_per_over"]?.jsonPrimitive?.intOrNull ?: 6
            return side.copy(score = side.score + points,
                wickets = side.wickets?.plus(if (rule["wicket"]?.jsonPrimitive?.booleanOrNull == true) 1 else 0),
                balls = balls, overs = balls?.let { "${it / overSize}.${it % overSize}" })
        }
        home = apply(home); away = apply(away)
    }
    val winner = if (home.score > away.score) home.team_id else if (away.score > home.score) away.team_id else tiebreak
    return base.copy(home = home, away = away, status = status, events = events, version = base.version + additions.size,
        tiebreak_winner_team_id = tiebreak, winner_team_id = winner,
        outcome = if (status != "completed") "pending" else when (winner) { home.team_id -> "home_win"; away.team_id -> "away_win"; else -> "draw" })
}
