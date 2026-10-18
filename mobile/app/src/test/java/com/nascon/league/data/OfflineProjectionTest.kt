package com.nascon.league.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OfflineProjectionTest {
    private fun event(id: String, type: String, vararg values: Pair<String, String>) =
        EventRequest(id, "match", type, buildJsonObject { values.forEach { (key, value) -> put(key, value) } })

    private val base = MatchDetail("match", home = Side("home"), away = Side("away"))

    @Test fun offlineStartScoreUndoAndFinishFollowEventOrder() {
        val sport = SportConfig("basketball", scoring_events = mapOf("basket" to buildJsonObject { put("points", 3) }))
        val events = listOf(event("start", "match_start"), event("score", "score", "team_id" to "home", "event_type" to "basket"))
        val scored = projectPending(base, sport, events)
        assertEquals("in_progress", scored.status)
        assertEquals(3, scored.home.score)
        assertEquals(2, scored.version)
        val undone = projectPending(base, sport, events + event("undo", "compensation", "compensates" to "score"))
        assertEquals(0, undone.home.score)
        assertTrue(undone.events.first { it.event_id == "score" }.compensated)
        val finished = projectPending(base, sport, events + event("end", "match_end"))
        assertEquals("completed", finished.status)
        assertEquals("home_win", finished.outcome)
    }

    @Test fun queuedEventAlreadyAcknowledgedByServerIsNotCountedTwice() {
        val sport = SportConfig("football", scoring_events = mapOf("goal" to buildJsonObject { put("points", 1) }))
        val events = listOf(event("start", "match_start"), event("goal", "score", "team_id" to "away", "event_type" to "goal"))
        val acknowledged = projectPending(base, sport, events)
        assertEquals(acknowledged, projectPending(acknowledged, sport, events))
    }

    @Test fun inningsRulesUpdateRunsWicketsAndLegalBalls() {
        val sport = SportConfig("cricket", scoring_model = "innings", periods = buildJsonObject { put("balls_per_over", 6) },
            scoring_events = mapOf(
                "wide" to buildJsonObject { put("runs", 1); put("legal_ball", false); put("wicket", false) },
                "wicket" to buildJsonObject { put("runs", 0); put("legal_ball", true); put("wicket", true) }))
        val cricket = base.copy(home = Side("home", wickets = 0, balls = 0, overs = "0.0"))
        val result = projectPending(cricket, sport, listOf(event("start", "match_start"),
            event("wide", "score", "team_id" to "home", "event_type" to "wide"),
            event("wicket", "score", "team_id" to "home", "event_type" to "wicket")))
        assertEquals(1, result.home.score)
        assertEquals(1, result.home.wickets)
        assertEquals("0.1", result.home.overs)
    }

    @Test fun roundRulesWorkWithoutHardCodedSportNames() {
        val sport = SportConfig("custom-round-game", scoring_model = "round", scoring_events = mapOf("win" to buildJsonObject { put("rounds", 1) }))
        val result = projectPending(base, sport, listOf(event("start", "match_start"), event("win", "score", "team_id" to "home", "event_type" to "win")))
        assertEquals(1, result.home.score)
    }
}
