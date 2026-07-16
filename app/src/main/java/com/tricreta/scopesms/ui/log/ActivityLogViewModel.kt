package com.tricreta.scopesms.ui.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopesms.di.AppContainer
import com.tricreta.scopesms.domain.log.ActivityRecord
import com.tricreta.scopesms.domain.log.MatchType
import com.tricreta.scopesms.domain.log.NotifyStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class LogFilter(
    val query: String = "",
    val matchType: MatchType? = null,
    val notifyStatus: NotifyStatus? = null,
) {
    val isActive: Boolean get() = query.isNotBlank() || matchType != null || notifyStatus != null
}

/** Drives the activity log: the record the agent uses to diagnose the app. */
class ActivityLogViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val _filter = MutableStateFlow(LogFilter())
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    /**
     * `flatMapLatest`: each filter change replaces the query rather than
     * layering another collector on the old one — otherwise a few taps on the
     * status chips leave several live Room queries feeding the same list.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val records: StateFlow<List<ActivityRecord>> = _filter
        .flatMapLatest { f ->
            container.activityLog.search(
                query = f.query,
                matchType = f.matchType,
                notifyStatus = f.notifyStatus,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    /** Tapping the selected chip clears it — a filter you can't undo is a trap. */
    fun toggleMatchType(type: MatchType) {
        _filter.value = _filter.value.copy(
            matchType = if (_filter.value.matchType == type) null else type,
        )
    }

    fun toggleNotifyStatus(status: NotifyStatus) {
        _filter.value = _filter.value.copy(
            notifyStatus = if (_filter.value.notifyStatus == status) null else status,
        )
    }

    fun clearFilters() {
        _filter.value = LogFilter()
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                ActivityLogViewModel(app, AppContainer.from(app))
            }
        }
    }
}
