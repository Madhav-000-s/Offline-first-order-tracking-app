package com.ordertracking.feature.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.data.repository.RestaurantRepository
import com.ordertracking.core.model.MenuItem
import com.ordertracking.sync.PlaceOrderInput
import com.ordertracking.sync.PlaceOrderItemInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CartLine(val menuItem: MenuItem, val quantity: Int)

data class MenuUiState(
    val menuItems: List<MenuItem> = emptyList(),
    val cart: List<CartLine> = emptyList(),
    val totalMinor: Long = 0,
    val isPlacingOrder: Boolean = false,
    val isLoading: Boolean = true,
)

sealed interface MenuIntent {
    data class Increment(val menuItemId: String) : MenuIntent
    data class Decrement(val menuItemId: String) : MenuIntent
    data object PlaceOrder : MenuIntent
}

sealed interface MenuEffect {
    data class OrderPlaced(val localId: String) : MenuEffect
    data class ShowSnackbar(val message: String) : MenuEffect
}

class MenuViewModel(
    private val restaurantId: String,
    restaurantRepository: RestaurantRepository,
    private val placeOrder: suspend (PlaceOrderInput) -> Outcome<String>,
) : ViewModel() {

    private val cartQuantities = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val isPlacingOrder = MutableStateFlow(false)

    private val effectChannel = Channel<MenuEffect>(Channel.BUFFERED)
    val effects: Flow<MenuEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<MenuUiState> = combine(
        restaurantRepository.observeMenu(restaurantId),
        cartQuantities,
        isPlacingOrder,
    ) { menuItems, quantities, placing ->
        val cart = menuItems.mapNotNull { item ->
            val qty = quantities[item.id] ?: 0
            if (qty > 0) CartLine(item, qty) else null
        }
        MenuUiState(
            menuItems = menuItems,
            cart = cart,
            totalMinor = cart.sumOf { it.menuItem.priceMinor * it.quantity },
            isPlacingOrder = placing,
            isLoading = false,
        )
    // Eagerly, not WhileSubscribed: submitOrder() below reads `uiState.value`
    // directly to decide what to place, so it can't be relying on a shared
    // flow that may have paused because nothing was actively collecting it
    // (WhileSubscribed's whole point is to allow exactly that pause).
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MenuUiState())

    fun onIntent(intent: MenuIntent) {
        when (intent) {
            is MenuIntent.Increment -> cartQuantities.update(intent.menuItemId, +1)
            is MenuIntent.Decrement -> cartQuantities.update(intent.menuItemId, -1)
            is MenuIntent.PlaceOrder -> submitOrder()
        }
    }

    private fun MutableStateFlow<Map<String, Int>>.update(menuItemId: String, delta: Int) {
        value = value.toMutableMap().apply {
            val next = (this[menuItemId] ?: 0) + delta
            if (next <= 0) remove(menuItemId) else this[menuItemId] = next
        }
    }

    private fun submitOrder() {
        val state = uiState.value
        if (state.cart.isEmpty() || state.isPlacingOrder) return

        viewModelScope.launch {
            isPlacingOrder.value = true
            val input = PlaceOrderInput(
                restaurantId = restaurantId,
                currency = state.cart.first().menuItem.currency,
                items = state.cart.map {
                    PlaceOrderItemInput(
                        menuItemId = it.menuItem.id,
                        nameSnapshot = it.menuItem.name,
                        unitPriceMinor = it.menuItem.priceMinor,
                        quantity = it.quantity,
                    )
                },
            )
            when (val outcome = placeOrder(input)) {
                is Outcome.Success -> {
                    cartQuantities.value = emptyMap()
                    effectChannel.trySend(MenuEffect.OrderPlaced(outcome.value))
                }
                is Outcome.Failure -> effectChannel.trySend(
                    MenuEffect.ShowSnackbar(outcome.error.message ?: "Couldn't place order"),
                )
            }
            isPlacingOrder.value = false
        }
    }
}
