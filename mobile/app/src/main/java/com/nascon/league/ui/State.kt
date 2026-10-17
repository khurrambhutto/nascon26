package com.nascon.league.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nascon.league.data.Loaded
import com.nascon.league.data.friendlyError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What every data screen can be showing. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Ready<T>(val data: T, val savedAt: Long? = null) : UiState<T>
    data class Failed(val message: String) : UiState<Nothing>
}

/** Base ViewModel for "load something and show it" screens. */
abstract class LoadViewModel<T> : ViewModel() {
    private val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state

    protected abstract suspend fun fetch(): Loaded<T>

    fun load() {
        viewModelScope.launch {
            if (_state.value !is UiState.Ready) _state.value = UiState.Loading
            _state.value = try {
                val result = fetch()
                UiState.Ready(result.data, result.savedAt)
            } catch (e: Exception) {
                // Keep showing old data if we have it; only show the error on an empty screen.
                (_state.value as? UiState.Ready) ?: UiState.Failed(friendlyError(e))
            }
        }
    }

    protected fun replace(data: T) {
        _state.value = UiState.Ready(data)
    }
}

// ---------- Formatting helpers ----------

private val moneyFormat = NumberFormat.getNumberInstance(Locale.US).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}

/** 450000 paisa -> "PKR 4,500.00" */
fun money(minor: Long, currency: String = "PKR"): String = "$currency ${moneyFormat.format(minor / 100.0)}"

/** "three_pointer" -> "Three pointer" */
fun humanize(key: String): String =
    key.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }

private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

/** ISO time from the server shown in the phone's time zone, e.g. "Sat 17 Oct, 14:30". */
fun prettyTime(iso: String?): String? = iso?.let {
    runCatching { timeFormat.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrDefault(it)
}

fun savedAgo(savedAt: Long): String {
    val minutes = (System.currentTimeMillis() - savedAt) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60} h ago"
    }
}

fun roleName(role: String): String = when (role) {
    "super_admin" -> "Platform admin"
    "league_organizer" -> "League organizer"
    "club_manager" -> "Club manager"
    "player" -> "Player"
    else -> "Viewer"
}
