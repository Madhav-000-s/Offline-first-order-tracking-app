package com.ordertracking.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ordertracking.core.data.repository.OrderRepository
import com.ordertracking.core.model.Order
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

data class OrdersListUiState(
    val orders: List<Order> = emptyList(),
    val isLoading: Boolean = true,
)

sealed interface OrdersListIntent {
    data object PullToRefresh : OrdersListIntent
    data class OrderClicked(val localId: String) : OrdersListIntent
}

sealed interface OrdersListEffect {
    data class NavigateToDetail(val localId: String) : OrdersListEffect
    data class ShowSnackbar(val message: String) : OrdersListEffect
}

/**
 * UDF: single immutable UiState, sealed Intent, Channel<Effect> for
 * one-shots like navigation (DESIGN.md §3). `uiState` is sourced directly
 * from a Room Flow via the repository -- this ViewModel never talks to the
 * network, and it never needs to: Room already has the answer, and Room
 * will emit again the moment any channel writes a new row.
 */
class OrdersListViewModel(
    private val orderRepository: OrderRepository,
    private val onPullToRefresh: () -> Unit,
) : ViewModel() {

    private val effectChannel = Channel<OrdersListEffect>(Channel.BUFFERED)
    val effects: Flow<OrdersListEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<OrdersListUiState> = orderRepository.observeOrders()
        .map { orders -> OrdersListUiState(orders = orders, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrdersListUiState())

    fun onIntent(intent: OrdersListIntent) {
        when (intent) {
            is OrdersListIntent.PullToRefresh -> onPullToRefresh()
            is OrdersListIntent.OrderClicked -> effectChannel.trySend(OrdersListEffect.NavigateToDetail(intent.localId))
        }
    }
}
