package app.mini.hub.android.connector

sealed interface InstagramReadUiState {
    data object Idle : InstagramReadUiState
    data object Syncing : InstagramReadUiState
    data class Loaded(val view: InstagramReadView) : InstagramReadUiState
    data class Failed(val code: String) : InstagramReadUiState
}
