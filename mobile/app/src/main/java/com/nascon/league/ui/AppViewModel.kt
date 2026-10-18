package com.nascon.league.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nascon.league.data.ServiceLocator
import com.nascon.league.data.Session
import com.nascon.league.ui.components.Connection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** App-wide state: who is signed in, connection status, and automatic syncing of the offline queue. */
class AppViewModel : ViewModel() {
    val repo = ServiceLocator.repo
    val session: StateFlow<Session?> = repo.session
    val pending = repo.pending
    val queuedCount = repo.queuedCount
    val lastSync = repo.lastSync

    val connection: StateFlow<Connection> =
        // "Online" means the league server is answering (works over Wi-Fi, hotspot or USB).
        combine(repo.networkAvailable, repo.serverReachable, repo.syncing) { network, reachable, syncing ->
            when {
                syncing -> Connection.Syncing
                reachable -> Connection.Online
                !network -> Connection.Offline
                else -> Connection.ServerDown
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, Connection.Online)

    init {
        // Sync as soon as the server is reachable again and something is waiting.
        viewModelScope.launch {
            combine(repo.serverReachable, repo.queuedAccounts) { reachable, accounts -> reachable && accounts.isNotEmpty() }
                .distinctUntilChanged()
                .collect { shouldSync -> if (shouldSync) repo.syncAllPending() }
        }
        // When the phone's network comes back, check the server straight away.
        viewModelScope.launch {
            repo.networkAvailable.collect { available -> if (available) repo.probe() }
        }
        // While the server is unreachable, check every 10 s; also retry waiting actions.
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                repo.probe()
                if (repo.serverReachable.value && repo.queuedAccounts.value.isNotEmpty()) repo.syncAllPending()
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch { repo.syncAllPending() }
    }

    fun logout() = repo.logout()
}
