package com.nascon.league.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import com.nascon.league.ui.theme.contentOn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nascon.league.data.Session
import com.nascon.league.ui.components.ConnectionStrip
import com.nascon.league.ui.screens.CartScreen
import com.nascon.league.ui.screens.ClubScreen
import com.nascon.league.ui.screens.FixturesScreen
import com.nascon.league.ui.screens.MatchScreen
import com.nascon.league.ui.screens.MeScreen
import com.nascon.league.ui.screens.ProductScreen
import com.nascon.league.ui.screens.ReceiptScreen
import com.nascon.league.ui.screens.RosterScreen
import com.nascon.league.ui.screens.SettingsScreen
import com.nascon.league.ui.screens.StandingsScreen
import com.nascon.league.ui.screens.StoreScreen
import com.nascon.league.ui.screens.StoreViewModel
import com.nascon.league.ui.screens.SyncScreen
import com.nascon.league.ui.screens.ManagementScreen
import com.nascon.league.ui.theme.Pitch

/** The league every screen starts on. Organizers start on their own league. */
const val DEFAULT_LEAGUE = "league-alpha-1"
const val DEFAULT_CLUB = "club-alpha-1"

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("fixtures", "Matches", Icons.Filled.DateRange),
    Tab("standings", "Table", Icons.AutoMirrored.Filled.List),
    Tab("store", "Store", Icons.Filled.ShoppingCart),
    Tab("club", "Club", Icons.Filled.Home),
    Tab("me", "Me", Icons.Filled.Person),
)

/** Role helpers: which tools each role can see (spec 8.6 "right screen for the right role"). */
fun Session.canScore(leagueId: String?): Boolean =
    user.role == "super_admin" || (user.role == "league_organizer" && (user.league_id == null || user.league_id == leagueId))

fun Session.canManageRoster(clubId: String?): Boolean =
    user.role == "super_admin" || (user.role == "club_manager" && user.club_id == clubId)

@Composable
fun MainShell(app: AppViewModel, session: Session, requestedMatch: String? = null, onMatchOpened: () -> Unit = {}) {
    val manager = session.user.role in setOf("super_admin", "league_organizer", "club_manager")
    val roleTabs = if (manager) tabs.map { tab ->
        if (tab.route == "store") Tab("manage", "Manage", Icons.AutoMirrored.Filled.List) else tab
    } else tabs
    val nav = rememberNavController()
    val store: StoreViewModel = viewModel(key = "store-${session.user.user_id}")
    val connection by app.connection.collectAsStateWithLifecycle()
    val queuedCount by app.queuedCount.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        topBar = { ConnectionStrip(connection, queuedCount) { nav.navigate("sync") } },
        containerColor = Pitch.Chalk,
        bottomBar = { FloatingTabBar(currentRoute, roleTabs) { nav.goToTab(it) } },
    ) { inner ->
        // Role-aware home: club managers land on their club, everyone else on matches.
        val home = if (manager) "manage" else "fixtures"
        androidx.compose.runtime.CompositionLocalProvider(com.nascon.league.ui.components.LocalTenant provides session.tenant) {
        NavHost(
            navController = nav,
            startDestination = home,
            modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding(),
            // Gentle slide + fade between screens; back goes the other way.
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 10 } },
            popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 10 } },
        ) {
            val back: () -> Unit = { nav.popBackStack() }
            composable("fixtures") { FixturesScreen(session, onManage = { nav.goToTab("manage") }, onOpenMatch = { id -> nav.navigate("match/$id") }) }
            composable("manage") {
                ManagementScreen(session, onRoster = { nav.navigate("roster/$it") })
            }
            composable("orders") { ManagementScreen(session, initialPage = "orders", onBack = back, onRoster = { nav.navigate("roster/$it") }) }
            composable("match/{id}") { entry ->
                MatchScreen(
                    matchId = entry.arguments?.getString("id").orEmpty(),
                    session = session,
                    onBack = back,
                    onOpenProduct = { nav.navigate("product/$it") },
                )
            }
            composable("standings") {
                StandingsScreen(session, onOpenClub = { nav.navigate("club/$it") })
            }
            composable("store") { StoreScreen(store, onOpenProduct = { nav.navigate("product/$it") }, onOpenCart = { nav.navigate("cart") }) }
            composable("product/{id}") { entry ->
                ProductScreen(store, entry.arguments?.getString("id").orEmpty(), onBack = back, onOpenCart = { nav.navigate("cart") })
            }
            composable("cart") { CartScreen(store, onBack = back, onPaid = { nav.navigate("receipt") { popUpTo("store") } }) }
            composable("receipt") { ReceiptScreen(store, onDone = { nav.goToTab("store") }) }
            composable("club") {
                ClubScreen(session.user.club_id ?: DEFAULT_CLUB, session, onBack = null, onManageRoster = { nav.navigate("roster/$it") })
            }
            composable("club/{id}") { entry ->
                ClubScreen(entry.arguments?.getString("id").orEmpty(), session, onBack = back, onManageRoster = { nav.navigate("roster/$it") })
            }
            composable("roster/{clubId}") { entry ->
                RosterScreen(entry.arguments?.getString("clubId").orEmpty(), session, onBack = back)
            }
            composable("me") {
                MeScreen(
                    session = session,
                    pendingCount = queuedCount,
                    onOpenSync = { nav.navigate("sync") },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenRoster = { nav.navigate("roster/${session.user.club_id ?: DEFAULT_CLUB}") },
                    onLogout = app::logout,
                    onManagement = if (manager) ({ nav.goToTab("manage") }) else null,
                    onOrders = { nav.navigate("orders") },
                )
            }
            composable("sync") { SyncScreen(app, onBack = back) }
            composable("settings") { SettingsScreen(onBack = back) }
        }
        androidx.compose.runtime.LaunchedEffect(requestedMatch) {
            if (requestedMatch != null) {
                nav.navigate("match/${android.net.Uri.encode(requestedMatch)}") { launchSingleTop = true }
                onMatchOpened()
            }
        }
        }
    }
}

/** Switch bottom-nav tab without stacking copies of screens. */
private fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}


/** Floating rounded tab bar from the mockup; the selected tab fills with the tenant colour. */
@Composable
private fun FloatingTabBar(currentRoute: String?, roleTabs: List<Tab>, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(26.dp), spotColor = Pitch.Ink.copy(alpha = 0.25f))
            .clip(RoundedCornerShape(26.dp))
            .background(Pitch.Card)
            .padding(6.dp),
    ) {
        roleTabs.forEach { tab ->
            val selected = currentRoute?.substringBefore('/') == tab.route ||
                (tab.route == "fixtures" && currentRoute?.startsWith("match") == true) ||
                (tab.route == "store" && (currentRoute == "product/{id}" || currentRoute == "cart" || currentRoute == "receipt")) ||
                (tab.route == "club" && currentRoute?.startsWith("roster") == true)
            val primary = MaterialTheme.colorScheme.primary
            val background by animateColorAsState(if (selected) primary else Color.Transparent, tween(250), label = "tabBg")
            val content by animateColorAsState(if (selected) contentOn(primary) else Pitch.Muted, tween(250), label = "tabFg")
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(background)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab.route) })
                    .heightIn(min = 56.dp)
                    .padding(vertical = 8.dp)
                    .testTag("tab_${tab.route}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                Text(tab.label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
            }
        }
    }
}
