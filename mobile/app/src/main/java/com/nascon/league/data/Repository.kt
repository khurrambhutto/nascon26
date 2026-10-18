package com.nascon.league.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nascon.league.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

@Serializable
data class SyncOutcome(val label: String, val status: String, val message: String, val actionId: String = "")

/**
 * The single source of data for every screen. Screens never call Retrofit directly.
 * GETs go to the network and fall back to the SQLite cache when offline.
 * Actions go to the network, or into the offline queue when offline.
 */
class Repository(context: Context) {
    private val appContext = context.applicationContext
    private val secure = SecureStore(context, BuildConfig.API_BASE_URL)
    private val db = LocalDb(context)
    private val client = buildHttpClient { secure.token }
    private var api = buildApi(secure.serverUrl, client)
    private val syncLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var warmJob: Job? = null

    val session = MutableStateFlow(
        secure.sessionJson?.let { runCatching { AppJson.decodeFromString(Session.serializer(), it) }.getOrNull() }
    )
    val networkAvailable = MutableStateFlow(true)
    val serverReachable = MutableStateFlow(true)
    val syncing = MutableStateFlow(false)
    private fun owner(): String = secure.serverIdentity + "|" + (session.value?.user?.user_id ?: "signed-out")
    val pending = MutableStateFlow(db.pendingActions(owner()))
    val queuedAccounts = MutableStateFlow(db.pendingOwners())
    val queuedCount = MutableStateFlow(db.pendingCount())
    val syncRevision = MutableStateFlow(0)
    val backgroundSyncError = MutableStateFlow<String?>(null)
    val lastSync = MutableStateFlow<List<SyncOutcome>>(emptyList())
    val syncError = MutableStateFlow<String?>(null)
    val preparingOffline = MutableStateFlow(false)
    val reminders = com.nascon.league.notifications.MatchReminders(context)
    val reminderOwner: String get() = owner()

    val serverUrl: String get() = secure.serverUrl
    val wirelessUrl: String get() = secure.wirelessUrl

    init {
        reminders.restore(if (session.value == null) "" else owner())
        if (session.value != null) {
            db.adoptLegacy(owner())
            pending.value = db.pendingActions(owner())
            lastSync.value = readSyncHistory()
            if (pending.value.isNotEmpty()) secure.token?.let { secure.rememberOutboxToken(owner(), it) }
        }
        refreshQueues()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        networkAvailable.value = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable.value = true
                scope.launch { probe(); if (serverReachable.value) syncAllPending() }
            }
            override fun onLost(network: Network) {
                networkAvailable.value = false
                serverReachable.value = false // assume the server is gone until a request succeeds again
            }
        })
    }

    // ---------- Settings & session ----------

    fun setServerUrl(url: String) {
        val clean = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        require(clean.toHttpUrlOrNull() != null) { "Invalid server address" }
        // Treat the configured wireless address and USB loopback as the same installation.
        val host = java.net.URI(clean).host
        if (host != "127.0.0.1" && host != "localhost" && host != "10.0.2.2") secure.wirelessUrl = clean
        secure.serverUrl = clean
        api = buildApi(clean, client)
        serverReachable.value = false
    }

    suspend fun login(email: String, password: String): Session {
        warmJob?.cancel()
        val address = email.trim().lowercase()
        val response = try { network { api.login(LoginRequest(address, password)) } }
        catch (e: IOException) {
            val saved = withContext(Dispatchers.IO) { secure.offlineLogin(address, password) } ?: throw e
            val restored = AppJson.decodeFromString(Session.serializer(), saved.first)
            secure.saveToken(saved.second)
            secure.sessionJson = AppJson.encodeToString(Session.serializer(), restored)
            session.value = restored
            reminders.restore(owner())
            pending.value = db.pendingActions(owner())
            lastSync.value = readSyncHistory()
            if (pending.value.isNotEmpty()) secure.rememberOutboxToken(owner(), saved.second)
            refreshQueues()
            return restored
        }
        secure.saveToken(response.token)
        val newSession = Session(response.user, response.tenant)
        secure.sessionJson = AppJson.encodeToString(Session.serializer(), newSession)
        withContext(Dispatchers.IO) { secure.rememberLogin(address, password, secure.sessionJson!!, response.token) }
        session.value = newSession
        reminders.restore(owner())
        pending.value = db.pendingActions(owner())
        lastSync.value = readSyncHistory()
        if (pending.value.isNotEmpty()) secure.rememberOutboxToken(owner(), response.token)
        refreshQueues()
        scope.launch { syncAllPending() }
        syncError.value = null
        // Warm essentials so offline navigation works without visiting every screen first.
        warmJob = scope.launch {
          preparingOffline.value = true
          try {
            val available = leagues().data
            fixtures("__all_sports__")
            sports().data.forEach { sport(it.sport_id) }
            clubs().data.forEach { club(it.club_id) }
            products()
            available.forEach { league ->
                standings(league.league_id)
                fixtures(league.league_id).data.forEach { match(it.match_id) }
            }
          } catch (e: CancellationException) { throw e }
          catch (_: Exception) { /* Partial cache stays usable; later reads fill missing screens. */ }
          finally { preparingOffline.value = false }
        }
        return newSession
    }

    fun logout() {
        // Preserve a credential for already queued writes, never the viewer's credentials.
        if (db.pendingActions(owner()).isNotEmpty()) secure.token?.let { secure.rememberOutboxToken(owner(), it) }
        reminders.restore("")
        warmJob?.cancel()
        secure.saveToken(null)
        secure.sessionJson = null
        session.value = null
        pending.value = emptyList()
        lastSync.value = emptyList()
        syncError.value = null
        refreshQueues()
        scope.launch { if (serverReachable.value) syncAllPending() }
    }

    private fun refreshQueues() {
        queuedAccounts.value = db.pendingOwners()
        queuedCount.value = db.pendingCount()
        pending.value = if (session.value == null) emptyList() else db.pendingActions(owner())
        com.nascon.league.sync.QueueSyncJob.schedule(appContext, queuedCount.value > 0)
    }

    /** Cheap check used to find out when the server is back. */
    suspend fun probe() {
        try { network { api.ping() } } catch (_: IOException) {
            val wireless = secure.wirelessUrl
            if (wireless.isNotBlank() && wireless != secure.serverUrl) {
                val candidate = buildApi(wireless, client)
                try {
                    apiCall { candidate.ping() }
                    secure.serverUrl = wireless
                    api = candidate
                    serverReachable.value = true
                } catch (_: Exception) { }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    // ---------- Reads (network first, cache fallback) ----------

    suspend fun leagues() = cached("leagues", ListSerializer(League.serializer())) { api.leagues() }
    suspend fun fixtures(leagueId: String?): Loaded<List<Fixture>> {
        val loaded = cached("fixtures/$leagueId", ListSerializer(Fixture.serializer())) {
            if (leagueId == "__all_sports__") api.highlights() else api.fixtures(leagueId)
        }
        val pendingMatches = pending.value.filter { it.action.type == "score_event" }.mapNotNull {
            it.action.payload.jsonObject["match_id"]?.jsonPrimitive?.contentOrNull
        }.toSet()
        return loaded.copy(data = loaded.data.map { fixture ->
            if (fixture.match_id !in pendingMatches) fixture else {
                val preview = match(fixture.match_id).data
                fixture.copy(status = preview.status, home_score = preview.home.score, away_score = preview.away.score)
            }
        })
    }
    suspend fun match(id: String): Loaded<MatchDetail> {
        val loaded = cached("match/$id", MatchDetail.serializer()) { api.match(id) }
        reminders.update(owner(), id, loaded.data.scheduled_at, loaded.data.status)
        val events = pending.value.filter { it.action.type == "score_event" }.map {
            AppJson.decodeFromJsonElement(EventRequest.serializer(), it.action.payload)
        }.filter { it.match_id == id }
        if (events.isEmpty()) return loaded
        return loaded.copy(data = projectPending(loaded.data, sport(loaded.data.sport_id).data, events))
    }
    suspend fun sport(id: String) = cached("sport/$id", SportConfig.serializer()) { api.sport(id) }
    suspend fun standings(id: String) = cached("standings/$id", Standings.serializer()) { api.standings(id) }
    suspend fun club(id: String): Loaded<Club> {
        val loaded = cached("club/$id", Club.serializer()) { api.club(id) }
        val leaving = pending.value.filter { it.action.type == "roster_transfer" }.map {
            AppJson.decodeFromJsonElement(TransferRequest.serializer(), it.action.payload)
        }.filter { transfer -> loaded.data.roster.any { it.player_id == transfer.player_id } }
        return loaded.copy(data = loaded.data.copy(roster = loaded.data.roster.filter { player ->
            leaving.none { it.player_id == player.player_id }
        }))
    }
    suspend fun products() = cached("products", ListSerializer(Product.serializer())) { api.products() }
    suspend fun history(playerId: String) = cached("history/$playerId", PlayerHistory.serializer()) { api.history(playerId) }
    suspend fun sports() = cached("sports", ListSerializer(SportConfig.serializer())) { api.sports() }
    suspend fun clubs() = cached("clubs", ListSerializer(Club.serializer())) { api.clubs() }

    private suspend fun <T> cached(key: String, serializer: KSerializer<T>, fetch: suspend () -> T): Loaded<T> {
        val cacheKey = owner() + "/" + key
        return try {
            if (!serverReachable.value) {
                withContext(Dispatchers.IO) { db.getCache(cacheKey) }?.let { (json, time) ->
                    return Loaded(AppJson.decodeFromString(serializer, json), time)
                }
            }
            val data = network { fetch() }
            withContext(Dispatchers.IO) { db.putCache(cacheKey, AppJson.encodeToString(serializer, data)) }
            Loaded(data)
        } catch (e: IOException) {
            val (json, savedAt) = withContext(Dispatchers.IO) { db.getCache(cacheKey) } ?: throw e
            Loaded(AppJson.decodeFromString(serializer, json), savedAt)
        } catch (e: ApiException) {
            if (e.status != 401 && e.status < 500) throw e
            val (json, savedAt) = withContext(Dispatchers.IO) { db.getCache(cacheKey) } ?: throw e
            Loaded(AppJson.decodeFromString(serializer, json), savedAt)
        }
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
            // Keep the offline session and queue if the token expires; signing in refreshes it.
            throw e
        }

    /** Live match updates over Server-Sent Events. Throws when the stream can't be opened. */
    fun matchStream(matchId: String): Flow<MatchDetail> = flow {
        val cacheKey = owner() + "/match/$matchId"
        val streamClient = client.newBuilder().readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
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
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isEmpty() && data.isNotEmpty() -> {
                        runCatching { AppJson.decodeFromString(MatchDetail.serializer(), data.toString()) }
                            .getOrNull()?.let {
                                reminders.update(owner(), matchId, it.scheduled_at, it.status)
                                db.putCache(cacheKey, AppJson.encodeToString(MatchDetail.serializer(), it))
                                // Do not overwrite optimistic scores while local actions are pending.
                                if (pending.value.none { action -> action.action.type == "score_event" &&
                                    action.action.payload.jsonObject["match_id"]?.jsonPrimitive?.contentOrNull == matchId }) emit(it)
                            }
                        data.clear()
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---------- Store ----------

    suspend fun checkout(items: List<CartLine>, discountCode: String?, paymentToken: String, receiptEmail: String? = null): CheckoutResponse {
        val user = session.value?.user ?: throw ApiException(401, "UNAUTHENTICATED", "")
        val request = CheckoutRequest(
            order_id = "ord-" + UUID.randomUUID().toString().take(12),
            customer_id = user.user_id,
            items = items,
            discount_code = discountCode?.trim()?.uppercase()?.ifBlank { null },
            payment = PaymentToken(paymentToken),
            receipt_email = receiptEmail?.trim(),
        )
        return network { api.checkout(request) }
    }

    suspend fun receiptDelivery(orderId: String) = network { api.receiptDelivery(orderId) }
    suspend fun checkoutPreferences() = network { api.checkoutPreferences() }

    // ---------- Online-only organizer/manager actions ----------

    suspend fun leagueManagement(id: String) = network { api.leagueManagement(id) }
    suspend fun createLeague(body: kotlinx.serialization.json.JsonObject) = network { api.createLeague(body) }
    suspend fun createMatch(body: kotlinx.serialization.json.JsonObject) = network { api.createMatch(body) }
    suspend fun managedProducts() = network { api.managedProducts() }
    suspend fun createProduct(body: kotlinx.serialization.json.JsonObject) = network { api.createProduct(body) }
    suspend fun updateVariant(id: String, variant: String, stock: Int, price: Long) = network {
        api.updateVariant(id, variant, kotlinx.serialization.json.buildJsonObject {
            put("stock", kotlinx.serialization.json.JsonPrimitive(stock))
            put("price_minor", kotlinx.serialization.json.JsonPrimitive(price))
        })
    }
    suspend fun orders() = network { api.orders() }
    suspend fun orderStatus(id: String, status: String) = network {
        api.orderStatus(id, kotlinx.serialization.json.buildJsonObject {
            put("status", kotlinx.serialization.json.JsonPrimitive(status))
            put("request_id", kotlinx.serialization.json.JsonPrimitive("status-" + UUID.randomUUID().toString()))
        })
    }

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
        val action = OfflineAction(
            action_id = "act-" + UUID.randomUUID().toString().take(12),
            type = type,
            queued_at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            base_version = baseVersion,
            payload = payload,
        )
        val actionOwner = owner()
        val actionToken = secure.token ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in to save changes.")
        secure.rememberOutboxToken(actionOwner, actionToken)
        // Persist BEFORE the first request. A killed app or lost response cannot lose an action.
        withContext(Dispatchers.IO) { db.addAction(PendingAction(action, label), actionOwner) }
        refreshQueues()
        if (serverReachable.value && syncAccount(actionOwner)) {
            val outcome = lastSync.value.firstOrNull { it.actionId == action.action_id }
            if (outcome?.status == "rejected") throw ApiException(409, "SYNC_REJECTED", outcome.message)
            if (pending.value.none { it.action.action_id == action.action_id }) return ActionResult.Sent("$label saved.")
        }
        return ActionResult.Queued("\"$label\" is saved on this phone and is waiting to sync with the server.")
    }

    /** Sends a batch to the authenticated sync endpoint and clears answered actions. */
    suspend fun syncNow(): Boolean = syncAccount(owner())

    /** Delivery belongs to the durable outbox, independently of the visible account. */
    suspend fun syncAllPending(): Boolean {
        var success = true
        for (account in db.pendingOwners().filter { queueBelongsToServer(it, secure.serverIdentity) }) {
            if (!syncAccount(account)) success = false
        }
        return success && db.pendingOwners().none { queueBelongsToServer(it, secure.serverIdentity) }
    }

    private suspend fun syncAccount(actionOwner: String): Boolean = syncLock.withLock {
        val queue = db.pendingActions(actionOwner).take(100)
        if (queue.isEmpty()) return true
        if (!queueBelongsToServer(actionOwner, secure.serverIdentity)) return false
        val accountToken = secure.outboxToken(actionOwner) ?: if (owner() == actionOwner) secure.token else null
        if (accountToken == null) {
            backgroundSyncError.value = "Some saved actions need the original account to sign in again. They are still saved."
            return false
        }
        val accountApi = accountSyncClient(secure.serverUrl, accountToken, client)
        syncing.value = true
        try {
            val response = network { accountApi.sync(SyncRequest(secure.deviceId, queue.map { it.action })) }
            val labels = queue.associate { it.action.action_id to it.label }
            val outcomes = response.results.map { r ->
                val message = when (r.status) {
                    "applied" -> "Synced."
                    "duplicate" -> "Already on the server, skipped."
                    else -> friendlyCode(r.error_code)
                }
                SyncOutcome(labels[r.action_id] ?: r.action_id, r.status, message, r.action_id)
            }
            db.putCache(actionOwner + "/sync-history", AppJson.encodeToString(ListSerializer(SyncOutcome.serializer()), outcomes))
            if (owner() == actionOwner) {
                lastSync.value = outcomes
                syncError.value = null
            }
            val sentIds = queue.map { it.action.action_id }.toSet()
            val answered = response.results.map { it.action_id }.filter { it in sentIds }.toSet()
            // Save authoritative post-sync snapshots before removing local previews.
            // A failed refresh leaves the existing cache intact and never loses the queue result.
            run {
                queue.filter { it.action.type == "score_event" }.mapNotNull {
                    it.action.payload.jsonObject["match_id"]?.jsonPrimitive?.contentOrNull
                }.distinct().forEach { id ->
                    try {
                        val snapshot = apiCall { accountApi.match(id) }
                        db.putCache(actionOwner + "/match/$id", AppJson.encodeToString(MatchDetail.serializer(), snapshot))
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                queue.filter { it.action.type == "roster_transfer" }.forEach {
                    val transfer = AppJson.decodeFromJsonElement(TransferRequest.serializer(), it.action.payload)
                    val squadIds = setOfNotNull(transfer.from_squad_id, transfer.to_squad_id)
                    val cachedClubs = db.getCache(actionOwner + "/clubs")?.first?.let { json ->
                        AppJson.decodeFromString(ListSerializer(Club.serializer()), json)
                    }.orEmpty()
                    cachedClubs.forEach { club ->
                        val cached = db.getCache(actionOwner + "/club/${club.club_id}")?.first?.let { json -> AppJson.decodeFromString(Club.serializer(), json) }
                        if (cached?.roster?.any { player -> player.squad_id in squadIds } == true) {
                            try {
                                val snapshot = apiCall { accountApi.club(club.club_id) }
                                db.putCache(actionOwner + "/club/${club.club_id}", AppJson.encodeToString(Club.serializer(), snapshot))
                            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        }
                    }
                }
            }
            withContext(Dispatchers.IO) { db.removeActions(answered) }
            if (db.pendingActions(actionOwner).isEmpty()) secure.forgetOutboxToken(actionOwner)
            if (answered.isNotEmpty()) syncRevision.value++
            backgroundSyncError.value = null
            refreshQueues()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = if (e is ApiException && e.status in setOf(401, 403)) "The original account must sign in again to sync its saved actions. Nothing was discarded." else friendlyError(e)
            if (owner() == actionOwner) syncError.value = message
            else backgroundSyncError.value = message
            false
        } finally {
            syncing.value = false
        }
    }

    private fun readSyncHistory(): List<SyncOutcome> = runCatching {
        db.getCache(owner() + "/sync-history")?.first?.let {
            AppJson.decodeFromString(ListSerializer(SyncOutcome.serializer()), it)
        }.orEmpty()
    }.getOrDefault(emptyList())
}

object ServiceLocator {
    lateinit var repo: Repository
}
