package com.ordertracking.feature.tracking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ordertracking.core.data.repository.OrderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

data class TrackingUiState(
    val routePoints: List<Pair<Double, Double>> = emptyList(),
    val courierPosition: CourierPositionUi? = null,
    val followCamera: Boolean = true,
    val isLoading: Boolean = true,
)

sealed interface TrackingIntent {
    /** Auto-disabled the moment the user pans -- never fight the user's gesture (DESIGN.md §12). */
    data object UserPanned : TrackingIntent
    data object RecenterClicked : TrackingIntent
}

class TrackingViewModel(
    orderLocalId: String,
    orderRepository: OrderRepository,
    private val trackingRepository: TrackingRepository,
    private val accessTokenProvider: () -> String?,
) : ViewModel() {

    private val followCamera = MutableStateFlow(true)

    init {
        // Tracking can only start once this device knows the order's serverId
        // (WS subscribe is keyed by the server's id, never the local one) --
        // distinctUntilChanged so a reconnect isn't spammed on every Room emit.
        orderRepository.observeOrder(orderLocalId)
            .map { it?.serverId }
            .distinctUntilChanged()
            .onEach { serverId ->
                if (serverId != null) {
                    accessTokenProvider()?.let { token -> trackingRepository.start(viewModelScope, token, serverId) }
                }
            }
            .launchIn(viewModelScope)
    }

    val uiState: StateFlow<TrackingUiState> = combine(
        orderRepository.observeOrder(orderLocalId),
        trackingRepository.courierPosition,
        followCamera,
    ) { order, position, follow ->
        TrackingUiState(
            routePoints = order?.routePolyline?.let(::decodePolyline) ?: emptyList(),
            courierPosition = position,
            followCamera = follow,
            isLoading = order == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackingUiState())

    fun onIntent(intent: TrackingIntent) {
        when (intent) {
            is TrackingIntent.UserPanned -> followCamera.value = false
            is TrackingIntent.RecenterClicked -> followCamera.value = true
        }
    }

    override fun onCleared() {
        trackingRepository.stop()
        super.onCleared()
    }
}
