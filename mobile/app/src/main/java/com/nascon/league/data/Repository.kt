package com.nascon.league.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nascon.league.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class Session(val user: User, val tenant: Tenant? = null)

/** Data plus where it came from. savedAt != null means it was read from the offline cache. */
data class Loaded<T>(val data: T, val savedAt: Long? = null)

/** Result of a scoring/roster action: sent now, or saved in the offline queue. */
sealed interface ActionResult {
    data class Sent(val message: String) : ActionResult
    data class Queued(val message: String) : ActionResult
}

data class SyncOutcome(val label: String, val status: String, val message: String)

/**
 * The single source of data for every screen. Screens never call Retrofit directly.
 * GETs go to the network and fall back to the SQLite cache when offline.
 * Actions go to the network, or into the offline queue when offline.
 */
class Repository(context: Context) {
    private val secure = SecureStore(context, BuildConfig.API_BASE_URL)
    private val db = LocalDb(context)
    private val client = buildHttpClient { secure.token }
    private var api = buildApi(secure.serverUrl, client)
    private val syncLock = Mutex()

    val session = MutableStateFlow(
        secure.sessionJson?.let { runCatching { AppJson.decodeFromString(Session.serializer(), it) }.getOrNull() }
    )
    val networkAvailable = MutableStateFlow(true)
    val serverReachable = MutableStateFlow(true)
    val syncing = MutableStateFlow(false)
    val pending = MutableStateFlow(db.pendingActions())
    val lastSync = MutableStateFlow<List<SyncOutcome>>(emptyList())

    val serverUrl: String get() = secure.serverUrl

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        networkAvailable.value = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkAvailable.value = true }
            override fun onLost(network: Network) {
                networkAvailable.value = false
                serverReachable.value = false // assume the server is gone until a request succeeds again
            }
        })
    }

    // ---------- Settings & session ----------

    fun setServerUrl(url: String) {
        val clean = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        secure.serverUrl = clean
        api = buildApi(clean, client)
    }

    suspend fun login(email: String, password: String): Session {
        val response = network { api.login(LoginRequest(email.trim(), password)) }
        secure.saveToken(response.token)
        val newSession = Session(response.user, response.tenant)
        secure.sessionJson = AppJson.encodeToString(Session.serializer(), newSession)
        session.value = newSession
        return newSession
    }

    fun logout() {
        secure.saveToken(null)
        secure.sessionJson = null
        session.value = null
    }

    /** Cheap check used to find out when the server is back. */
    suspend fun probe() {
        runCatching { network { api.ping() } }
    }

    // ---------- Reads (network first, cache fallback) ----------

    suspend fun leagues() = cached("leagues", ListSerializer(League.serializer())) { api.leagues() }
    suspend fun fixtures(leagueId: String?) =
        cached("fixtures/$leagueId", ListSerializer(Fixture.serializer())) { api.fixtures(leagueId) }
    suspend fun match(id: String) = cached("match/$id", MatchDetail.serializer()) { api.match(id) }
    suspend fun sport(id: String) = cached("sport/$id", SportConfig.serializer()) { api.sport(id) }
    suspend fun standings(id: String) = cached("standings/$id", Standings.serializer()) { api.standings(id) }
    suspend fun club(id: String) = cached("club/$id", Club.serializer()) { api.club(id) }
    suspend fun products() = cached("products", ListSerializer(Product.serializer())) { api.products() }
    suspend fun history(playerId: String) = cached("history/$playerId", PlayerHistory.serializer()) { api.history(playerId) }

    private suspend fun <T> cached(key: String, serializer: KSerializer<T>, fetch: suspend () -> T): Loaded<T> =
        try {
            val data = network { fetch() }
            withContext(Dispatchers.IO) { db.putCache(key, AppJson.encodeToString(serializer, data)) }
            Loaded(data)
        } catch (e: IOException) {
            val (json, savedAt) = withContext(Dispatchers.IO) { db.getCache(key) } ?: throw e
            Loaded(AppJson.decodeFromString(serializer, json), savedAt)
        }

    /** Wraps every network call: converts HTTP errors and tracks whether the server answered. */
    private suspend fun <T> network(block: suspend () -> T): T =
        try {
            apiCall(block).also { serverReachable.value = true }
        } catch (e: IOException) {
            serverReachable.value = false
            throw e
        } catch (e: ApiException) {
            serverReachable.value = true
            if (e.status == 401 && session.value != null && e.code != "INVALID_CREDENTIALS") logout()
            throw e
        }

    /** Live match updates over Server-Sent Events. Throws when the stream can't be opened. */
    fun matchStream(matchId: String): Flow<MatchDetail> = flow {
        val streamClient = client.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
        val request = Request.Builder()
            .url(secure.serverUrl + "api/v1/matches/$matchId/stream")
            .header("Accept", "text/event-stream")
            .build()
        val call = streamClient.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("stream ${response.code}")
            val source = response.body.source()
            val data = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isEmpty() && data.isNotEmpty() -> {
                        runCatching { AppJson.decodeFromString(MatchDetail.serializer(), data.toString()) }
                            .getOrNull()?.let { emit(it) }
                        data.clear()
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---------- Store ----------

    suspend fun checkout(items: List<CartLine>, discountCode: String?, paymentToken: String): CheckoutResponse {
        val user = session.value?.user ?: throw ApiException(401, "UNAUTHENTICATED", "")
        val request = CheckoutRequest(
            order_id = "ord-" + UUID.randomUUID().toString().take(12),
            customer_id = user.user_id,
            items = items,
            discount_code = discountCode?.trim()?.uppercase()?.ifBlank { null },
            payment = PaymentToken(paymentToken),
        )
        return network { api.checkout(request) }
    }

    // ---------- Online-only organizer/manager actions ----------

    suspend fun addPlayer(clubId: String, name: String, position: String, role: String, reason: String) {
        network { api.addPlayer(clubId, AddPlayerRequest(name.trim(), position.trim(), role, reason.trim())) }
    }

    suspend fun setMatchStatus(matchId: String, status: String) {
        network { api.setStatus(matchId, StatusRequest(status)) }
    }

    suspend fun pinProduct(matchId: String, productId: String?): MatchDetail = network { api.pin(matchId, PinRequest(productId)) }

    // ---------- Actions that work offline ----------

    /** Appends a match event, or queues it when offline. baseVersion = match version the user saw. */
    suspend fun sendEvent(matchId: String, type: String, payload: kotlinx.serialization.json.JsonObject, baseVersion: Int, label: String): ActionResult {
        val body = EventRequest("evt-" + UUID.randomUUID().toString().take(12), matchId, type, payload)
        return sendOrQueue(
            type = "score_event",
            payload = AppJson.encodeToJsonElement(body),
            baseVersion = baseVersion,
            label = label,
        ) { network { api.event(body) }; "$label saved." }
    }

    suspend fun sendTransfer(body: TransferRequest, baseVersion: Int, label: String): ActionResult =
        sendOrQueue("roster_transfer", AppJson.encodeToJsonElement(body), baseVersion, label) {
            val result = network { api.transfer(body) }
            if (result.state == "deferred_until_match_end")
                "$label accepted. It takes effect after the live match ends."
            else "$label done."
        }

    private suspend fun sendOrQueue(
        type: String,
        payload: JsonElement,
        baseVersion: Int,
        label: String,
        send: suspend () -> String,
    ): ActionResult {
        try {
            return ActionResult.Sent(send())
        } catch (_: IOException) {
            // Server unreachable (offline, airplane mode, server down): queue it instead.
        }
        val action = OfflineAction(
            action_id = "act-" + UUID.randomUUID().toString().take(12),
            type = type,
            queued_at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            base_version = baseVersion,
            payload = payload,
        )
        withContext(Dispatchers.IO) { db.addAction(PendingAction(action, label)) }
        pending.value = db.pendingActions()
        return ActionResult.Queued("You're offline. \"$label\" is saved and will sync when you reconnect.")
    }

    /** Sends the whole offline queue to /audit/v1/offline-sync and clears every action the server answered. */
    suspend fun syncNow(): Boolean = syncLock.withLock {
        val queue = pending.value
        if (queue.isEmpty()) return true
        syncing.value = true
        try {
            val response = network { api.sync(SyncRequest(secure.deviceId, queue.map { it.action })) }
            val labels = queue.associate { it.action.action_id to it.label }
            lastSync.value = response.results.map { r ->
                val message = when (r.status) {
                    "applied" -> "Synced."
                    "duplicate" -> "Already on the server, skipped."
                    else -> friendlyCode(r.error_code)
                }
                SyncOutcome(labels[r.action_id] ?: r.action_id, r.status, message)
            }
            val answered = response.results.map { it.action_id }.toSet()
            withContext(Dispatchers.IO) { db.removeActions(answered) }
            pending.value = db.pendingActions()
            true
        } catch (_: Exception) {
            false
        } finally {
            syncing.value = false
        }
    }
}

object ServiceLocator {
    lateinit var repo: Repository
}
