package com.healthyagent.running.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.data.repository.RunRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 历史记录 ViewModel
 */
class HistoryViewModel(
    private val repository: RunRepository,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<RunSession>>(emptyList())
    val sessions: StateFlow<List<RunSession>> = _sessions.asStateFlow()

    private val _expandedSessionId = MutableStateFlow<String?>(null)
    val expandedSessionId: StateFlow<String?> = _expandedSessionId.asStateFlow()

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _sessions.value = repository.getAllSessions()
        }
    }

    fun toggleExpand(sessionId: String) {
        _expandedSessionId.value = if (_expandedSessionId.value == sessionId) null else sessionId
    }
}
