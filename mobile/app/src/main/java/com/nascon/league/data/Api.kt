package com.nascon.league.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

interface LeagueApi {
    // Day 2 guest endpoints (no login): used by nascon:// deep links.
    @GET("api/v1/public/matches/{id}")
    suspend fun publicMatch(@Path("id") id: String): okhttp3.ResponseBody

    @GET("api/v1/chat/{room}")
    suspend fun chatRoom(@Path("room") room: String): okhttp3.ResponseBody

    @GET("api/v1/sports")
    suspend fun sports(): List<SportConfig>

    @GET("api/v1/clubs")
    suspend fun clubs(): List<Club>

    @GET("api/v1/leagues/{id}/management")
    suspend fun leagueManagement(@Path("id") id: String): kotlinx.serialization.json.JsonObject

    @POST("api/v1/leagues")
    suspend fun createLeague(@Body body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject

    @POST("api/v1/matches")
    suspend fun createMatch(@Body body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject

    @GET("api/v1/management/products")
    suspend fun managedProducts(): List<Product>

    @POST("api/v1/products")
    suspend fun createProduct(@Body body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject

    @PATCH("api/v1/products/{id}/variants/{variant}")
    suspend fun updateVariant(@Path("id") id: String, @Path("variant") variant: String, @Body body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject

    @GET("api/v1/orders")
    suspend fun orders(): List<kotlinx.serialization.json.JsonObject>

    @PATCH("api/v1/orders/{id}/status")
    suspend fun orderStatus(@Path("id") id: String, @Body body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/v1/ping")
    suspend fun ping(): kotlinx.serialization.json.JsonObject

    @GET("api/v1/leagues")
    suspend fun leagues(): List<League>

    @GET("api/v1/fixtures")
    suspend fun fixtures(@Query("league_id") leagueId: String?): List<Fixture>

    @GET("api/v1/fixtures/highlights")
    suspend fun highlights(): List<Fixture>

    @GET("api/v1/matches/{id}")
    suspend fun match(@Path("id") matchId: String): MatchDetail

    @GET("api/v1/sports/{id}")
    suspend fun sport(@Path("id") sportId: String): SportConfig

    @GET("api/v1/standings/{id}")
    suspend fun standings(@Path("id") leagueId: String): Standings

    @GET("api/v1/clubs/{id}")
    suspend fun club(@Path("id") clubId: String): Club

    @POST("api/v1/clubs/{id}/players")
    suspend fun addPlayer(@Path("id") clubId: String, @Body body: AddPlayerRequest): kotlinx.serialization.json.JsonObject

    @POST("api/v1/matches/{id}/status")
    suspend fun setStatus(@Path("id") matchId: String, @Body body: StatusRequest): kotlinx.serialization.json.JsonObject

    @POST("api/v1/matches/{id}/pin")
    suspend fun pin(@Path("id") matchId: String, @Body body: PinRequest): MatchDetail

    @GET("api/v1/products")
    suspend fun products(): List<Product>

    @POST("api/v1/checkout")
    suspend fun checkout(@Body body: CheckoutRequest): CheckoutResponse

    @GET("api/v1/orders/{id}/receipt-email")
    suspend fun receiptDelivery(@Path("id") orderId: String): ReceiptDelivery

    @GET("api/v1/checkout/preferences")
    suspend fun checkoutPreferences(): CheckoutPreferences

    @POST("audit/v1/events")
    suspend fun event(@Body body: EventRequest): EventResponse

    @POST("audit/v1/roster/transfer")
    suspend fun transfer(@Body body: TransferRequest): TransferResponse

    @GET("audit/v1/roster/history/{id}")
    suspend fun history(@Path("id") playerId: String): PlayerHistory

    @POST("api/v1/offline-sync")
    suspend fun sync(@Body body: SyncRequest): SyncResponse
}

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = true
}

/** An error the server answered with (4xx/5xx). Network failures stay as IOException. */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

fun buildApi(baseUrl: String, client: OkHttpClient): LeagueApi =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(LeagueApi::class.java)

fun buildHttpClient(tokenProvider: () -> String?): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = tokenProvider()
            val request = if (token == null) chain.request()
            else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            chain.proceed(request)
        }
        .build()

/** Runs an API call and turns HTTP errors into ApiException with the backend's error code. */
suspend fun <T> apiCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: HttpException) {
        val raw = e.response()?.errorBody()?.string().orEmpty()
        val detail = runCatching { AppJson.decodeFromString(ErrorBody.serializer(), raw).error }.getOrNull()
        throw ApiException(e.code(), detail?.code ?: "HTTP_${e.code()}", detail?.message ?: "")
    }

/** Plain-language text for any failure. Never shows raw codes to the user. */
fun friendlyError(e: Throwable): String = when (e) {
    is ApiException -> if (e.code == "SYNC_REJECTED") e.message else friendlyCode(e.code)
    is IOException -> "Can't reach the server. Check your connection or the server address in Settings."
    else -> "Something went wrong. Please try again."
}

fun friendlyCode(code: String?): String = when (code) {
    null -> "Done."
    "INVALID_CREDENTIALS", "UNAUTHENTICATED" -> "Your email or password is wrong, or your session expired. Sign in again."
    "FORBIDDEN" -> "Your role isn't allowed to do this."
    "VALIDATION_ERROR" -> "Some details are missing or invalid. Check the form and try again."
    "RATE_LIMITED" -> "Too many requests at once. Wait a few seconds and try again."
    "OUT_OF_STOCK" -> "That size just sold out. Pick another size or lower the quantity."
    "PAYMENT_DECLINED" -> "The card was declined. Nothing was charged."
    "PAYMENT_CAPTURE_FAILED" -> "The bank couldn't complete the payment. Nothing was charged."
    "INVALID_DISCOUNT_CODE" -> "That discount code doesn't exist."
    "VARIANT_NOT_FOUND" -> "This item is no longer in the store."
    "MATCH_NOT_FOUND" -> "This match doesn't exist any more."
    "MATCH_NOT_IN_PROGRESS" -> "The match isn't live, so scores can't be added. Start the match first."
    "ILLEGAL_TRANSITION" -> "The match can't move to that state from where it is now."
    "TIE_NOT_ALLOWED" -> "This sport can't end in a draw. Record a tie-break winner first."
    "UNKNOWN_EVENT_TYPE" -> "That scoring type isn't part of this sport."
    "INNINGS_COMPLETE" -> "This team's innings is over, so no more runs can be added."
    "SERIES_ALREADY_DECIDED" -> "The series is already decided."
    "INVALID_COMPENSATION" -> "That event was already undone, or can't be undone."
    "EVENT_NOT_FOUND" -> "The event to undo wasn't found."
    "DUPLICATE_EVENT_ID", "DUPLICATE_ORDER_ID" -> "This was already sent with different details."
    "PLAYER_NOT_FOUND" -> "That player doesn't exist."
    "SQUAD_NOT_FOUND" -> "That squad doesn't exist."
    "LEAGUE_NOT_FOUND" -> "That league doesn't exist."
    "CROSS_TENANT_TRANSFER" -> "Players can't move to a club in another organisation."
    "NOT_IN_SQUAD" -> "The player is no longer in that squad. Someone changed the roster first."
    "TRANSFER_WINDOW_CLOSED" -> "The transfer window is closed on that date."
    "PLAYER_ON_LOAN" -> "The player is on loan, so they can't be moved until they return."
    "SQUAD_FULL" -> "The destination squad is full."
    "MIN_POSITION_VIOLATION" -> "This would leave the squad without enough players in that position."
    "SPORT_NOT_FOUND" -> "That sport isn't set up on the server."
    "INVALID_BASE_VERSION" -> "Your phone had newer data than the server. Refresh and try again."
    "SCHEDULE_CONFLICT" -> "A team already has a match at that time. Choose another time."
    else -> "The server refused this ($code)."
}
