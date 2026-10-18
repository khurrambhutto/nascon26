package com.nascon.league.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Field names mirror the backend JSON exactly (snake_case), so no @SerialName mapping is needed.

// ---------- Auth ----------
@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class User(
    val user_id: String,
    val email: String,
    val name: String = "",
    val role: String,
    val tenant_id: String? = null,
    val league_id: String? = null,
    val club_id: String? = null,
    val player_id: String? = null,
)

@Serializable
data class Tenant(
    val tenant_id: String,
    val name: String,
    val primary_color: String? = null,
    val logo_url: String? = null,
)

@Serializable
data class LoginResponse(val token: String, val user: User, val tenant: Tenant? = null)

// ---------- Leagues, fixtures, matches ----------
@Serializable
data class League(val league_id: String, val name: String = "", val sport_id: String = "", val tenant_id: String? = null)

@Serializable
data class Fixture(
    val match_id: String,
    val league_id: String? = null,
    val sport_id: String = "",
    val home_team_id: String,
    val home_name: String? = null,
    val away_team_id: String,
    val away_name: String? = null,
    val status: String = "upcoming",
    val scheduled_at: String? = null,
    val venue: String? = null,
    val home_score: Int? = null,
    val away_score: Int? = null,
)

@Serializable
data class Side(
    val team_id: String,
    val name: String? = null,
    val score: Int = 0,
    val wickets: Int? = null,
    val balls: Int? = null,
    val overs: String? = null,
    val fair_play: Int = 0,
)

@Serializable
data class MatchEvent(
    val sequence: Int,
    val event_id: String,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val signature: String? = null,
    val compensated: Boolean = false,
    val compensated_by: String? = null,
)

@Serializable
data class MatchDetail(
    val match_id: String,
    val sport_id: String = "",
    val league_id: String? = null,
    val status: String = "upcoming",
    val outcome: String = "pending",
    val winner_team_id: String? = null,
    val tiebreak_winner_team_id: String? = null,
    val home: Side,
    val away: Side,
    val version: Int = 0,
    val events: List<MatchEvent> = emptyList(),
    val pinned_product: Product? = null,
    val scheduled_at: String? = null,
    val venue: String? = null,
)

/** Sport rules as data. scoring_events values differ per model, so they stay as raw JSON. */
@Serializable
data class SportConfig(
    val sport_id: String,
    val name: String = "",
    val scoring_model: String = "event",
    val scoring_events: Map<String, JsonObject> = emptyMap(),
    val draw_allowed: Boolean = true,
    val periods: JsonObject = JsonObject(emptyMap()),
)

// ---------- Standings ----------
@Serializable
data class StandingRow(
    val rank: Int,
    val team_id: String,
    val played: Int = 0,
    val won: Int = 0,
    val drawn: Int = 0,
    val lost: Int = 0,
    val scored: Double = 0.0,
    val conceded: Double = 0.0,
    val diff: Double = 0.0,
    val points: Int = 0,
    val fair_play: Int = 0,
    val status: String = "still_possible",
    val resolved_by: String? = null,
)

@Serializable
data class Standings(
    val league_id: String,
    val sport_id: String = "",
    val qualification_spots: Int = 0,
    val table: List<StandingRow> = emptyList(),
)

// ---------- Clubs and roster ----------
@Serializable
data class Venue(val name: String = "", val lat: Double? = null, val lng: Double? = null)

@Serializable
data class Staff(val name: String, val role: String)

@Serializable
data class RosterPlayer(
    val player_id: String,
    val name: String,
    val position: String = "",
    val role: String = "",
    val squad_id: String? = null,
)

@Serializable
data class SeasonStat(val season: String, val played: Int = 0, val won: Int = 0, val drawn: Int = 0, val lost: Int = 0)

@Serializable
data class Club(
    val club_id: String,
    val name: String,
    val tenant_id: String? = null,
    val home_venue: Venue? = null,
    val socials: Map<String, String?> = emptyMap(),
    val staff: List<Staff> = emptyList(),
    val roster: List<RosterPlayer> = emptyList(),
    val stats: List<SeasonStat> = emptyList(),
)

@Serializable
data class TransferRequest(
    val request_id: String,
    val league_id: String,
    val player_id: String,
    val type: String, // transfer | loan | release
    val from_squad_id: String,
    val to_squad_id: String?,
    val effective_date: String,
    val loan_end_date: String?,
    val reason: String,
    val requested_by: String,
)

@Serializable
data class TransferResponse(
    val request_id: String,
    val status: String = "",
    val state: String = "active",
    val blocking_match_id: String? = null,
)

@Serializable
data class AuditEntry(
    val audit_id: String = "",
    val action: String,
    val from_squad_id: String? = null,
    val to_squad_id: String? = null,
    val effective_date: String? = null,
    val state: String? = null,
    val who: String = "",
    val `when`: String = "",
    val why: String? = null,
)

@Serializable
data class PlayerHistory(
    val player_id: String,
    val current_squad_id: String? = null,
    val parent_squad_id: String? = null,
    val history: List<AuditEntry> = emptyList(),
)

// ---------- Store ----------
@Serializable
data class Variant(
    val variant_id: String,
    val label: String = "",
    val size: String? = null,
    val color: String? = null,
    val price_minor: Long,
    val currency: String = "PKR",
    val stock: Int = 0,
)

@Serializable
data class Product(
    val product_id: String,
    val name: String,
    val category: String = "",
    val description: String? = null,
    val variants: List<Variant> = emptyList(),
)

@Serializable
data class CartLine(val variant_id: String, val quantity: Int)

@Serializable
data class PaymentToken(val token: String)

@Serializable
data class CheckoutRequest(
    val order_id: String,
    val customer_id: String,
    val items: List<CartLine>,
    val discount_code: String?,
    val payment: PaymentToken,
    val receipt_email: String? = null,
)

@Serializable
data class ReceiptDelivery(val email: String? = null, val status: String = "not_requested", val sent_at: Double? = null, val mode: String = "external")

@Serializable
data class CheckoutPreferences(val default_receipt_email: String? = null, val email_delivery_configured: Boolean = false, val delivery_mode: String = "external")

@Serializable
data class OrderItem(val variant_id: String, val quantity: Int, val unit_price_minor: Long)

@Serializable
data class PaymentResult(val authorization_id: String? = null, val capture_id: String? = null, val state: String = "")

@Serializable
data class Receipt(val payload: JsonObject, val signature: String, val pdf_base64: String)

@Serializable
data class CheckoutResponse(
    val order_id: String,
    val status: String,
    val currency: String = "PKR",
    val items: List<OrderItem> = emptyList(),
    val subtotal_minor: Long,
    val discount_minor: Long,
    val total_minor: Long,
    val payment: PaymentResult = PaymentResult(),
    val receipt: Receipt,
    val receipt_delivery: ReceiptDelivery = ReceiptDelivery(),
)

// ---------- Events ----------
@Serializable
data class EventRequest(val event_id: String, val match_id: String, val type: String, val payload: JsonObject)

@Serializable
data class EventResponse(
    val event_id: String,
    val match_id: String = "",
    val type: String = "",
    val sequence: Int = 0,
    val match_version: Int = 0,
    val signature: String? = null,
)

// ---------- Offline sync (spec 5.14) ----------
@Serializable
data class OfflineAction(
    val action_id: String,
    val type: String, // score_event | roster_transfer
    val queued_at: String,
    val base_version: Int,
    val payload: JsonElement,
)

@Serializable
data class SyncRequest(val device_id: String, val actions: List<OfflineAction>)

@Serializable
data class SyncResult(
    val action_id: String,
    val status: String,
    val resolution: String = "",
    val error_code: String? = null,
    val processed_order: Int = 0,
    val server_version: Int? = null,
    val signature: String? = null,
)

@Serializable
data class SyncResponse(val device_id: String = "", val results: List<SyncResult> = emptyList())

// ---------- Errors ----------
@Serializable
data class ErrorDetail(val code: String, val message: String = "", val details: JsonObject? = null)

@Serializable
data class ErrorBody(val error: ErrorDetail)

// ---------- Mobile-only actions ----------
@Serializable
data class AddPlayerRequest(val name: String, val position: String, val role: String, val reason: String)

@Serializable
data class StatusRequest(val status: String)

@Serializable
data class PinRequest(val product_id: String?)
