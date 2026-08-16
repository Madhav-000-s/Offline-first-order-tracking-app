package com.ordertracking.feature.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asSuccess
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
    val errorMessage: String? = null,
)

sealed interface MenuIntent {
    data class Increment(val menuItemId: String) : MenuIntent
    data class Decrement(val menuItemId: String) : MenuIntent
    data object PlaceOrder : MenuIntent
    data object Retry : MenuIntent
}

sealed interface MenuEffect {
    data class OrderPlaced(val localId: String) : MenuEffect
    data class ShowSnackbar(val message: String) : MenuEffect
}

class MenuViewModel(
    private val restaurantId: String,
    restaurantRepository: RestaurantRepository,
    private val placeOrder: suspend (PlaceOrderInput) -> Outcome<String>,
    /**
     * Pulls this restaurant's menu from the network into Room.
     *
     * Without it the screen depends entirely on the background delta sync
     * having already happened to reach this particular restaurant -- and
     * since that sync pages 200 rows at a time across every resource, a
     * restaurant near the end of the list renders an empty menu for an
     * unbounded amount of time. Observing Room is the right read path; it
     * still needs something to put the rows there on demand.
     */
    private val refreshMenu: suspend (restaurantId: String) -> Outcome<Unit> = { Unit.asSuccess() },
) : ViewModel() {

    private val cartQuantities = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val isPlacingOrder = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(true)
    private val errorMessage = MutableStateFlow<String?>(null)

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            errorMessage.value = null
            when (val outcome = refreshMenu(restaurantId)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> errorMessage.value = outcome.error.message ?: "Couldn't load the menu"
            }
            isRefreshing.value = false
        }
    }

    private val effectChannel = Channel<MenuEffect>(Channel.BUFFERED)
    val effects: Flow<MenuEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<MenuUiState> = combine(
        restaurantRepository.observeMenu(restaurantId),
        cartQuantities,
        isPlacingOrder,
        isRefreshing,
        errorMessage,
    ) { menuItems, quantities, placing, refreshing, error ->
        val cart = menuItems.mapNotNull { item ->
            val qty = quantities[item.id] ?: 0
            if (qty > 0) CartLine(item, qty) else null
        }
        MenuUiState(
            menuItems = menuItems,
            cart = cart,
            totalMinor = cart.sumOf { it.menuItem.priceMinor * it.quantity },
            isPlacingOrder = placing,
            // Only a spinner when there is genuinely nothing to show. A
            // cached menu keeps rendering while the refresh runs behind it,
            // which is the whole point of reading from Room.
            isLoading = refreshing && menuItems.isEmpty(),
            errorMessage = error.takeIf { menuItems.isEmpty() },
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
            is MenuIntent.Retry -> refresh()
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
