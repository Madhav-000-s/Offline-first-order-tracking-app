package com.ordertracking.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ordertracking.core.network.ws.OrderWebSocketClient
import com.ordertracking.feature.feed.FeedEffect
import com.ordertracking.feature.feed.FeedIntent
import com.ordertracking.feature.feed.FeedScreen
import com.ordertracking.feature.feed.FeedViewModel
import com.ordertracking.feature.menu.MenuEffect
import com.ordertracking.feature.menu.MenuScreen
import com.ordertracking.feature.menu.MenuViewModel
import com.ordertracking.feature.orders.OrderDetailEffect
import com.ordertracking.feature.orders.OrderDetailScreen
import com.ordertracking.feature.orders.OrderDetailViewModel
import com.ordertracking.feature.orders.OrdersListEffect
import com.ordertracking.feature.orders.OrdersListScreen
import com.ordertracking.feature.orders.OrdersListViewModel
import com.ordertracking.feature.tracking.TrackingRepository
import com.ordertracking.feature.tracking.TrackingScreen
import com.ordertracking.feature.tracking.TrackingViewModel

private object Routes {
    const val FEED = "feed"
    const val ORDERS = "orders"
    const val MENU = "menu/{restaurantId}"
    const val ORDER_DETAIL = "orderDetail/{localId}"
    const val TRACKING = "tracking/{localId}"
    const val DEBUG_LOG = "debugLog"

    fun menu(restaurantId: String) = "menu/$restaurantId"
    fun orderDetail(localId: String) = "orderDetail/$localId"
    fun tracking(localId: String) = "tracking/$localId"
}

@Composable
fun OrderTrackingNavHost(navController: NavHostController = rememberNavController()) {
    val container = LocalAppContainer.current

    NavHost(navController = navController, startDestination = Routes.FEED) {
        composable(Routes.FEED) {
            val viewModel: FeedViewModel = viewModel(
                factory = viewModelFactory { initializer { FeedViewModel(container.restaurantRepository) } },
            )
            LaunchedEffect(Unit) {
                viewModel.effects.collectAndHandle { effect ->
                    when (effect) {
                        is FeedEffect.NavigateToMenu -> navController.navigate(Routes.menu(effect.restaurantId))
                    }
                }
            }
            FeedScreen(restaurants = viewModel.restaurants, onIntent = viewModel::onIntent)
        }

        composable(Routes.MENU) { backStackEntry ->
            val restaurantId = backStackEntry.arguments?.getString("restaurantId").orEmpty()
            val viewModel: MenuViewModel = viewModel(
                key = restaurantId,
                factory = viewModelFactory {
                    initializer {
                        MenuViewModel(
                            restaurantId = restaurantId,
                            restaurantRepository = container.restaurantRepository,
                            placeOrder = { input -> container.placeOrderUseCase.invoke(input) },
                        )
                    }
                },
            )
            LaunchedEffect(Unit) {
                viewModel.effects.collectAndHandle { effect ->
                    when (effect) {
                        is MenuEffect.OrderPlaced -> navController.navigate(Routes.orderDetail(effect.localId)) {
                            popUpTo(Routes.FEED)
                        }
                        is MenuEffect.ShowSnackbar -> Unit // wired to a SnackbarHost in a fuller build
                    }
                }
            }
            val state by viewModel.uiState.collectAsState()
            MenuScreen(state = state, onIntent = viewModel::onIntent)
        }

        composable(Routes.ORDERS) {
            val viewModel: OrdersListViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        OrdersListViewModel(
                            orderRepository = container.orderRepository,
                            onPullToRefresh = { container.syncManager.enqueuePullToRefresh() },
                        )
                    }
                },
            )
            LaunchedEffect(Unit) {
                viewModel.effects.collectAndHandle { effect ->
                    when (effect) {
                        is OrdersListEffect.NavigateToDetail -> navController.navigate(Routes.orderDetail(effect.localId))
                        is OrdersListEffect.ShowSnackbar -> Unit
                    }
                }
            }
            val state by viewModel.uiState.collectAsState()
            Box(modifier = Modifier.fillMaxSize()) {
                OrdersListScreen(state = state, onIntent = viewModel::onIntent)
                // The single best interview demo in the project (DESIGN.md §4):
                // fifteen seconds in here makes the merge engine's decisions
                // visible instead of just trusted.
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(Routes.DEBUG_LOG) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                ) {
                    Text("Sync log")
                }
            }
        }

        composable(Routes.DEBUG_LOG) {
            val viewModel: DebugLogViewModel = viewModel(
                factory = viewModelFactory { initializer { DebugLogViewModel(container.database.syncLogDao()) } },
            )
            DebugLogScreen(viewModel = viewModel)
        }

        composable(Routes.ORDER_DETAIL) { backStackEntry ->
            val localId = backStackEntry.arguments?.getString("localId").orEmpty()
            val viewModel: OrderDetailViewModel = viewModel(
                key = localId,
                factory = viewModelFactory {
                    initializer {
                        OrderDetailViewModel(
                            orderLocalId = localId,
                            orderRepository = container.orderRepository,
                            cancelOrder = { id -> container.cancelOrderUseCase.invoke(id) },
                        )
                    }
                },
            )
            LaunchedEffect(Unit) {
                viewModel.effects.collectAndHandle { effect ->
                    when (effect) {
                        is OrderDetailEffect.NavigateToTracking -> navController.navigate(Routes.tracking(effect.localId))
                        is OrderDetailEffect.ShowSnackbar -> Unit
                    }
                }
            }
            val state by viewModel.uiState.collectAsState()
            OrderDetailScreen(state = state, onIntent = viewModel::onIntent)
        }

        composable(Routes.TRACKING) { backStackEntry ->
            val localId = backStackEntry.arguments?.getString("localId").orEmpty()
            val context = LocalContext.current
            val viewModel: TrackingViewModel = viewModel(
                key = localId,
                factory = viewModelFactory {
                    initializer {
                        val wsClient: OrderWebSocketClient = container.newWebSocketClient()
                        val trackingRepository = TrackingRepository(
                            wsClient = wsClient,
                            courierLastKnownDao = container.database.courierLastKnownDao(),
                            orderWriter = container.orderWriter,
                            onGapDetected = { container.syncManager.enqueueDeltaSync() },
                        )
                        TrackingViewModel(
                            orderLocalId = localId,
                            orderRepository = container.orderRepository,
                            trackingRepository = trackingRepository,
                            accessTokenProvider = { container.tokenStore.read()?.accessToken },
                        )
                    }
                },
            )
            val state by viewModel.uiState.collectAsState()
            TrackingScreen(state = state, onIntent = viewModel::onIntent)
        }
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectAndHandle(action: (T) -> Unit) {
    collect { action(it) }
}
