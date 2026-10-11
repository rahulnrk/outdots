package app.mini.hub.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Retains one [InboxViewModel] across configuration changes (rotation), so its state, including
 * any typed or generated draft, is not lost when the Activity is recreated. Uses the androidx
 * ViewModel already on the classpath through activity-compose; no new dependency.
 */
class InboxViewModelHolder(val viewModel: InboxViewModel) : ViewModel() {
    override fun onCleared() {
        viewModel.releaseLocalDraftResources()
    }

    companion object {
        fun factory(create: () -> InboxViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = InboxViewModelHolder(create()) as T
        }
    }
}
