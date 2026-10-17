package com.nascon.league.ui

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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
fun MainShell(app: AppViewModel, session: Session) {
    val nav = rememberNavController()
    val store: StoreViewModel = viewModel()
    val connection by app.connection.collectAsStateWithLifecycle()
    val pending by app.pending.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        topBar = { ConnectionStrip(connection, pending.size) { nav.navigate("sync") } },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    val selected = currentRoute?.substringBefore('/') == tab.route ||
                        (tab.route == "fixtures" && currentRoute?.startsWith("match") == true) ||
                        (tab.route == "store" && (currentRoute == "product/{id}" || currentRoute == "cart" || currentRoute == "receipt"))
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.goToTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = Pitch.Ink,
                            unselectedIconColor = Pitch.Muted,
                            unselectedTextColor = Pitch.Muted,
                        ),
                        modifier = Modifier.testTag("tab_${tab.route}"),
                    )
                }
            }
        },
    ) { inner ->
        // Role-aware home: club managers land on their club, everyone else on matches.
        val home = if (session.user.role == "club_manager") "club" else "fixtures"
        androidx.compose.runtime.CompositionLocalProvider(com.nascon.league.ui.components.LocalTenant provides session.tenant) {
        NavHost(
            navController = nav,
            startDestination = home,
            modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding(),
        ) {
            val back: () -> Unit = { nav.popBackStack() }
            composable("fixtures") { FixturesScreen(session) { id -> nav.navigate("match/$id") } }
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
                    pendingCount = pending.size,
                    onOpenSync = { nav.navigate("sync") },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenRoster = { nav.navigate("roster/${session.user.club_id ?: DEFAULT_CLUB}") },
                    onLogout = app::logout,
                )
            }
            composable("sync") { SyncScreen(app, onBack = back) }
            composable("settings") { SettingsScreen(onBack = back) }
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
