package com.nascon.league.ui.screens

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nascon.league.data.*
import com.nascon.league.ui.components.ScreenHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.Instant

/** Management is a separate workspace; shopping stays available to customer roles. */
@Composable
fun ManagementScreen(session: Session, initialPage: String = "home", onBack: (() -> Unit)? = null, onRoster: (String) -> Unit) {
    val repo = ServiceLocator.repo
    val scope = rememberCoroutineScope()
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var leagues by remember { mutableStateOf<List<League>>(emptyList()) }
    var sports by remember { mutableStateOf<List<SportConfig>>(emptyList()) }
    var clubs by remember { mutableStateOf<List<Club>>(emptyList()) }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var orders by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var leagueId by remember { mutableStateOf(session.user.league_id.orEmpty()) }
    var sportId by remember { mutableStateOf("") }
    var clubId by remember { mutableStateOf(session.user.club_id.orEmpty()) }
    var home by remember { mutableStateOf("") }
    var away by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var venue by remember { mutableStateOf("") }
    var scheduled by remember { mutableStateOf(Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()) }
    var duration by remember { mutableStateOf("90") }
    var price by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    var variantLabel by remember { mutableStateOf("Standard") }
    var category by remember { mutableStateOf("apparel") }
    var description by remember { mutableStateOf("") }
    var selectedTeams by remember { mutableStateOf<Set<String>>(emptySet()) }
    var leagueTeams by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmOrder by remember { mutableStateOf<Pair<String, String>?>(null) }
    val admin = session.user.role == "super_admin"
    val scheduleAllowed = admin || session.user.role == "league_organizer"
    val shopAllowed = admin || session.user.role == "club_manager"

    fun perform(success: String, action: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try { action(); message = success; revision++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = friendlyError(e) }
            finally { busy = false }
        }
    }

    LaunchedEffect(page, revision) {
        busy = true
        try {
            clubs = repo.clubs().data
            if (clubId.isBlank()) clubId = clubs.firstOrNull()?.club_id.orEmpty()
            if (scheduleAllowed) {
                leagues = repo.leagues().data.filter { admin || it.league_id == session.user.league_id }
                if (leagueId.isBlank()) leagueId = leagues.firstOrNull()?.league_id.orEmpty()
                sports = repo.sports().data
                if (sportId.isBlank()) sportId = sports.firstOrNull()?.sport_id.orEmpty()
            }
            if (page == "shop") products = repo.managedProducts()
            if (page == "orders") orders = repo.orders()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = friendlyError(e) }
        finally { busy = false }
    }
    LaunchedEffect(leagueId, page) {
        if (leagueId.isNotBlank() && page == "match") {
            try {
                leagueTeams = repo.leagueManagement(leagueId)["teams"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                home = leagueTeams.firstOrNull().orEmpty()
                away = leagueTeams.getOrNull(1).orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = friendlyError(e) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(title = when (page) {
            "match" -> "Schedule a match"; "league" -> "Create a league"
            "shop" -> "Manage shop"; "product" -> "Add product"; "orders" -> "Orders"
            "roster" -> "Club rosters"; else -> "Management"
        }, onBack = if (page != "home") ({
            if (page == initialPage && initialPage != "home" && onBack != null) onBack()
            else { page = "home"; message = null }
        }) else null)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, modifier = Modifier.testTag("management_message")) }
            when (page) {
                "home" -> {
                    Text("${session.user.name} · ${session.tenant?.name.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                    if (scheduleAllowed) {
                        ManagementButton("Schedule match", "manage_matches") { page = "match" }
                        if (admin) ManagementButton("Create league / choose sport", "manage_leagues") { page = "league" }
                        leagues.forEach { Text("${it.name} · ${it.sport_id}") }
                    }
                    if (shopAllowed) {
                        ManagementButton("Products, prices and inventory", "manage_shop") { page = "shop" }
                        ManagementButton("Fulfil orders and refunds", "manage_orders") { page = "orders" }
                        ManagementButton("Club rosters", "manage_rosters") { page = "roster" }
                    }
                }
                "league" -> {
                    ManagementField("League name", name, "league_name") { name = it }
                    ManagementChoice("Sport", sportId, sports.map { it.sport_id to it.name }, "league_sport") { sportId = it }
                    Text("Select at least two clubs", style = MaterialTheme.typography.titleMedium)
                    clubs.filter { it.tenant_id == session.user.tenant_id }.forEach { club ->
                        Row {
                            Checkbox(club.club_id in selectedTeams, onCheckedChange = { checked ->
                                selectedTeams = if (checked) selectedTeams + club.club_id else selectedTeams - club.club_id
                            }, modifier = Modifier.testTag("league_team_${club.club_id}"))
                            Text(club.name, Modifier.padding(top = 12.dp))
                        }
                    }
                    Button(enabled = !busy && name.isNotBlank() && selectedTeams.size >= 2 && sportId.isNotBlank(), onClick = {
                        perform("League created. You can schedule its first match.") {
                            val created = repo.createLeague(buildJsonObject {
                                put("name", name.trim()); put("sport_id", sportId)
                                put("teams", JsonArray(selectedTeams.map(::JsonPrimitive)))
                            })
                            leagueId = created.getValue("league_id").jsonPrimitive.content
                            name = ""; page = "match"
                        }
                    }, modifier = Modifier.fillMaxWidth().testTag("create_league")) { Text("Create league") }
                }
                "match" -> {
                    ManagementChoice("League and sport", leagueId, leagues.map { it.league_id to "${it.name} · ${it.sport_id}" }, "match_league") { leagueId = it }
                    val teams = leagueTeams.map { id -> id to (clubs.firstOrNull { it.club_id == id }?.name ?: id) }
                    ManagementChoice("Home club", home, teams, "match_home") { home = it }
                    ManagementChoice("Away club", away, teams, "match_away") { away = it }
                    ManagementField("Venue", venue, "match_venue") { venue = it }
                    ManagementField("Date and time (ISO 8601, e.g. 2026-10-18T12:00:00+05:00)", scheduled, "match_time") { scheduled = it }
                    ManagementField("Duration in minutes", duration, "match_duration") { duration = it }
                    Text("The sport's scoring rules are inherited from the selected league.")
                    Button(enabled = !busy && leagueId.isNotBlank() && home.isNotBlank() && away.isNotBlank() && home != away && venue.isNotBlank() && duration.toIntOrNull()?.let { it in 1..1440 } == true && runCatching { java.time.OffsetDateTime.parse(scheduled) }.isSuccess,
                        onClick = { perform("Match scheduled. Refresh Matches to view it.") {
                            repo.createMatch(buildJsonObject {
                                put("league_id", leagueId); put("home_team_id", home); put("away_team_id", away)
                                put("venue", venue.trim()); put("scheduled_at", java.time.OffsetDateTime.parse(scheduled).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
                                put("duration_minutes", duration.toInt())
                            })
                        } }, modifier = Modifier.fillMaxWidth().testTag("create_match")) { Text("Schedule match") }
                }
                "shop" -> {
                    ManagementButton("Add product", "add_product") { name = ""; price = ""; stock = ""; page = "product" }
                    products.forEach { product ->
                        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            com.nascon.league.ui.components.ProductArtwork(product)
                        }
                        Text(product.name, style = MaterialTheme.typography.titleMedium)
                        product.variants.forEach { variant ->
                            VariantEditor(product, variant, busy) { count, amount ->
                                perform("Inventory updated.") { repo.updateVariant(product.product_id, variant.variant_id, count, amount) }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                "product" -> {
                    ManagementChoice("Club", clubId, clubs.filter { admin || it.club_id == session.user.club_id }.map { it.club_id to it.name }, "product_club") { clubId = it }
                    ManagementField("Product name", name, "product_name") { name = it }
                    ManagementField("Description", description, "product_description") { description = it }
                    ManagementChoice("Category", category, listOf("apparel", "accessories", "digital").map { it to it }, "product_category") { category = it }
                    ManagementField("Variant (size / colour)", variantLabel, "product_variant") { variantLabel = it }
                    ManagementField("Price in PKR", price, "product_price") { price = it }
                    ManagementField("Stock quantity", stock, "product_stock") { stock = it }
                    Button(enabled = !busy && name.isNotBlank() && variantLabel.isNotBlank() && clubId.isNotBlank() && minorPrice(price) != null && stock.toIntOrNull()?.let { it >= 0 } == true,
                        onClick = { perform("Product added.") {
                            repo.createProduct(buildJsonObject {
                                put("name", name.trim()); put("description", description); put("category", category); put("club_id", clubId)
                                put("variants", buildJsonArray { add(buildJsonObject {
                                    put("label", variantLabel); put("price_minor", minorPrice(price)!!); put("stock", stock.toInt())
                                }) })
                            }); page = "shop"
                        } }, modifier = Modifier.fillMaxWidth().testTag("save_product")) { Text("Add product") }
                }
                "orders" -> {
                    ManagementButton("Refresh orders", "refresh_orders") { revision++ }
                    if (orders.isEmpty() && !busy) Text("No orders yet.")
                    orders.forEach { order ->
                        val id = order["order_id"]?.jsonPrimitive?.content.orEmpty()
                        val status = order["status"]?.jsonPrimitive?.content ?: "paid"
                        Text(id, style = MaterialTheme.typography.titleMedium)
                        Text("$status · PKR ${order["total_minor"]?.jsonPrimitive?.longOrNull?.div(100.0) ?: 0.0}")
                        if (shopAllowed) {
                            val next = when (status) {
                                "paid" -> listOf("packed", "cancelled", "refunded")
                                "packed" -> listOf("shipped", "cancelled", "refunded")
                                "shipped" -> listOf("delivered", "refunded")
                                "delivered" -> listOf("refunded")
                                else -> emptyList()
                            }
                            next.forEach { target ->
                                OutlinedButton(enabled = !busy, onClick = {
                                    if (target in setOf("refunded", "cancelled")) confirmOrder = id to target
                                    else perform("Order updated to $target.") { repo.orderStatus(id, target) }
                                }, modifier = Modifier.testTag("order_${id}_$target")) { Text(target.replaceFirstChar { it.uppercase() }) }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                "roster" -> clubs.filter { admin || it.club_id == session.user.club_id }.forEach { club ->
                    com.nascon.league.ui.components.TeamBadge(club.name, club.club_id, size = 48.dp)
                    ManagementButton(club.name, "roster_club_${club.club_id}") { onRoster(club.club_id) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    confirmOrder?.let { (id, target) ->
        AlertDialog(onDismissRequest = { confirmOrder = null }, title = { Text("Mark order $target?") },
            text = { Text("This returns the purchased stock and records the order as $target.") },
            confirmButton = { TextButton(onClick = {
                confirmOrder = null
                perform("Order updated to $target.") { repo.orderStatus(id, target) }
            }, modifier = Modifier.testTag("confirm_order_status")) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirmOrder = null }) { Text("Keep order") } })
    }
}

private fun minorPrice(value: String): Long? = runCatching {
    value.toBigDecimal().movePointRight(2).longValueExact().takeIf { it >= 0 }
}.getOrNull()

@Composable
private fun VariantEditor(product: Product, variant: Variant, busy: Boolean, save: (Int, Long) -> Unit) {
    var stock by remember(variant) { mutableStateOf(variant.stock.toString()) }
    var price by remember(variant) { mutableStateOf(java.math.BigDecimal.valueOf(variant.price_minor, 2).toPlainString()) }
    Text(variant.label)
    ManagementField("Stock", stock, "stock_${variant.variant_id}") { stock = it }
    ManagementField("Price in PKR", price, "price_${variant.variant_id}") { price = it }
    OutlinedButton(enabled = !busy && stock.toIntOrNull()?.let { it >= 0 } == true && minorPrice(price) != null,
        onClick = { save(stock.toInt(), minorPrice(price)!!) }, modifier = Modifier.testTag("save_${variant.variant_id}")) { Text("Save stock and price") }
}

@Composable
private fun ManagementButton(label: String, tag: String, click: () -> Unit) {
    OutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(label) }
}

@Composable
private fun ManagementField(label: String, value: String, tag: String, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun ManagementChoice(label: String, value: String, options: List<Pair<String, String>>, tag: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text("$label: ${options.firstOrNull { it.first == value }?.second ?: "Select"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) },
                onClick = { change(id); expanded = false }, modifier = Modifier.testTag("${tag}_$id")) }
        }
    }
}
