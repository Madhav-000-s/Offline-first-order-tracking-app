package com.ordertracking.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.data.repository.OrderRepository
import com.ordertracking.core.model.Order
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OrderDetailUiState(
    val order: Order? = null,
    val isLoading: Boolean = true,
    val isCancelling: Boolean = false,
)

sealed interface OrderDetailIntent {
    data object CancelOrder : OrderDetailIntent
    data object TrackOrder : OrderDetailIntent
}

sealed interface OrderDetailEffect {
    data class NavigateToTracking(val localId: String) : OrderDetailEffect
    data class ShowSnackbar(val message: String) : OrderDetailEffect
}

class OrderDetailViewModel(
    orderLocalId: String,
    orderRepository: OrderRepository,
    private val cancelOrder: suspend (String) -> Outcome<Unit>,
) : ViewModel() {

    private val effectChannel = Channel<OrderDetailEffect>(Channel.BUFFERED)
    val effects: Flow<OrderDetailEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<OrderDetailUiState> = orderRepository.observeOrder(orderLocalId)
        .map { order -> OrderDetailUiState(order = order, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrderDetailUiState())

    private val currentLocalId = orderLocalId

    fun onIntent(intent: OrderDetailIntent) {
        when (intent) {
            is OrderDetailIntent.CancelOrder -> viewModelScope.launch {
                when (val outcome = cancelOrder(currentLocalId)) {
                    is Outcome.Success -> effectChannel.trySend(OrderDetailEffect.ShowSnackbar("Cancelling order..."))
                    is Outcome.Failure -> effectChannel.trySend(
                        OrderDetailEffect.ShowSnackbar(outcome.error.message ?: "Couldn't cancel"),
                    )
                }
            }
            is OrderDetailIntent.TrackOrder -> effectChannel.trySend(OrderDetailEffect.NavigateToTracking(currentLocalId))
        }
    }
}
