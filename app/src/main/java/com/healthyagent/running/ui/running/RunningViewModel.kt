package com.healthyagent.running.ui.running

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthyagent.running.coordinator.AppCoordinator
import com.healthyagent.running.coordinator.CoordinatorEvent
import com.healthyagent.running.coordinator.RunCommandDispatcher
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.RunState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Compose 安全回退页的薄状态适配器，业务会话由 AppCoordinator 持有。 */
class RunningViewModel(
    coordinator: AppCoordinator,
    private val commandDispatcher: RunCommandDispatcher,
) : ViewModel() {

    val uiState: StateFlow<RunningUiState> = coordinator.snapshot
        .map { snapshot ->
            RunningUiState(
                runState = snapshot.session.state.toRunState(),
                sensorStatus = snapshot.connection.state.toConnectionState(),
                deviceName = snapshot.connection.deviceName.orEmpty(),
                display = RunningDisplayState.from(snapshot.metrics),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RunningUiState())

    private val _events = Channel<RunningUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            coordinator.events.collect { event ->
                when (event) {
                    is CoordinatorEvent.ShowError -> _events.send(RunningUiEvent.ShowError(event.message))
                }
            }
        }
    }

    fun connectSensor() {
        commandDispatcher.connectSensor()
    }

    fun startRun() {
        commandDispatcher.startRun()
    }

    fun pauseRun() {
        commandDispatcher.pauseRun()
    }

    fun resumeRun() {
        commandDispatcher.resumeRun()
    }

    fun stopRun() {
        commandDispatcher.stopRun()
    }
}

data class RunningUiState(
    val runState: RunState = RunState.IDLE,
    val sensorStatus: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceName: String = "",
    val display: RunningDisplayState = RunningDisplayState(),
)

sealed interface RunningUiEvent {
    data class ShowError(val message: String) : RunningUiEvent
}

private fun String.toRunState(): RunState =
    RunState.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: RunState.ERROR

private fun String.toConnectionState(): ConnectionState =
    ConnectionState.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
        ?: ConnectionState.ERROR
