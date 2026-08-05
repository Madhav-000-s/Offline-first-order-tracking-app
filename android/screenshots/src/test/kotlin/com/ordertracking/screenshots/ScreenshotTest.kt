package com.ordertracking.screenshots

import androidx.compose.runtime.Composable
import androidx.paging.PagingData
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.ordertracking.core.designsystem.OrderTrackingTheme
import com.ordertracking.core.model.MenuItem
import com.ordertracking.core.model.Order
import com.ordertracking.core.model.OrderEvent
import com.ordertracking.core.model.OrderItem
import com.ordertracking.core.model.OrderStatus
import com.ordertracking.core.model.Restaurant
import com.ordertracking.core.model.SyncState
import com.ordertracking.feature.feed.FeedScreen
import com.ordertracking.feature.menu.CartLine
import com.ordertracking.feature.menu.MenuScreen
import com.ordertracking.feature.menu.MenuUiState
import com.ordertracking.feature.orders.OrderDetailScreen
import com.ordertracking.feature.orders.OrderDetailUiState
import com.ordertracking.feature.orders.OrdersListScreen
import com.ordertracking.feature.orders.OrdersListUiState
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val now = Instant.parse("2026-05-20T18:30:00Z")

    private fun snapshot(name: String, content: @Composable () -> Unit) {
        paparazzi.snapshot(name = name) { OrderTrackingTheme { content() } }
    }

    private val sampleRestaurants = listOf(
        Restaurant("r1", "The Golden Kitchen #12", "Italian", 4.6, "", 12.97, 77.59),
        Restaurant("r2", "Blue Noodle Bar #7", "Vietnamese", 4.3, "", 12.98, 77.60),
        Restaurant("r3", "Rustic Grill #3", "BBQ", 4.8, "", 12.96, 77.58),
        Restaurant("r4", "Corner Bistro #21", "French", 4.1, "", 12.95, 77.61),
    )

    @Test
    fun feedScreen() {
        val items = flowOf(PagingData.from(sampleRestaurants))
        snapshot("feed") {
            FeedScreen(restaurants = items, onIntent = {})
        }
    }

    private val sampleOrders = listOf(
        Order(
            localId = "local-1", serverId = "server-1", restaurantId = "r1", status = OrderStatus.PICKED_UP,
            serverVersion = 4, placedAtLocal = now, serverUpdatedAt = now, totalMinor = 2097, currency = "USD",
            syncState = SyncState.SYNCED, lastError = null, etaAtServer = now.plusSeconds(600),
            deliveryNote = null, tipMinor = 0, routePolyline = null, items = emptyList(), events = emptyList(),
        ),
        Order(
            localId = "local-2", serverId = null, restaurantId = "r2", status = OrderStatus.PLACED,
            serverVersion = 0, placedAtLocal = now, serverUpdatedAt = null, totalMinor = 1199, currency = "USD",
            syncState = SyncState.PENDING_CREATE, lastError = null, etaAtServer = null,
            deliveryNote = null, tipMinor = 0, routePolyline = null, items = emptyList(), events = emptyList(),
        ),
        Order(
            localId = "local-3", serverId = "server-3", restaurantId = "r3", status = OrderStatus.DELIVERED,
            serverVersion = 6, placedAtLocal = now, serverUpdatedAt = now, totalMinor = 3499, currency = "USD",
            syncState = SyncState.SYNCED, lastError = null, etaAtServer = null,
            deliveryNote = null, tipMinor = 200, routePolyline = null, items = emptyList(), events = emptyList(),
        ),
    )

    @Test
    fun ordersListScreen() {
        snapshot("orders_list") {
            OrdersListScreen(state = OrdersListUiState(orders = sampleOrders, isLoading = false), onIntent = {})
        }
    }

    @Test
    fun orderDetailScreen() {
        val order = sampleOrders[0].copy(
            items = listOf(
                OrderItem("i1", "local-1", "m1", "Margherita Pizza", 1099, 1),
                OrderItem("i2", "local-1", "m2", "Garlic Bread", 499, 2),
            ),
            events = listOf(
                OrderEvent("e1", "local-1", OrderStatus.PLACED, now, null),
                OrderEvent("e2", "local-1", OrderStatus.ACCEPTED, now.plusSeconds(60), null),
                OrderEvent("e3", "local-1", OrderStatus.PREPARING, now.plusSeconds(180), null),
                OrderEvent("e4", "local-1", OrderStatus.READY, now.plusSeconds(420), null),
                OrderEvent("e5", "local-1", OrderStatus.PICKED_UP, now.plusSeconds(480), null),
            ),
        )
        snapshot("order_detail") {
            OrderDetailScreen(state = OrderDetailUiState(order = order, isLoading = false), onIntent = {})
        }
    }

    @Test
    fun menuScreen() {
        val menuItems = listOf(
            MenuItem("m1", "r1", "Margherita Pizza", "Fresh basil, mozzarella", 1099, "USD", ""),
            MenuItem("m2", "r1", "Garlic Bread", "Toasted, with herb butter", 499, "USD", ""),
            MenuItem("m3", "r1", "Tiramisu", "Classic Italian dessert", 699, "USD", ""),
            MenuItem("m4", "r1", "Sparkling Water", "500ml", 299, "USD", ""),
        )
        val cart = listOf(CartLine(menuItems[0], 1), CartLine(menuItems[1], 2))
        snapshot("menu") {
            MenuScreen(
                state = MenuUiState(
                    menuItems = menuItems,
                    cart = cart,
                    totalMinor = cart.sumOf { it.menuItem.priceMinor * it.quantity },
                    isLoading = false,
                ),
                onIntent = {},
            )
        }
    }
}
