package com.ordertracking.feature.menu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.ordertracking.core.common.AppError
import com.ordertracking.core.common.Outcome
import com.ordertracking.core.common.asFailure
import com.ordertracking.core.common.asSuccess
import com.ordertracking.core.data.repository.RestaurantRepository
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.database.entity.MenuItemEntity
import com.ordertracking.core.database.entity.RestaurantEntity
import com.ordertracking.core.network.ApiService
import com.ordertracking.core.network.dto.CancelOrderRequestDto
import com.ordertracking.core.network.dto.DeviceInDto
import com.ordertracking.core.network.dto.LoginRequestDto
import com.ordertracking.core.network.dto.MenuItemDto
import com.ordertracking.core.network.dto.OrderDto
import com.ordertracking.core.network.dto.PlaceOrderRequestDto
import com.ordertracking.core.network.dto.RefreshRequestDto
import com.ordertracking.core.network.dto.RefreshResponseDto
import com.ordertracking.core.network.dto.RegisterRequestDto
import com.ordertracking.core.network.dto.RestaurantPageDto
import com.ordertracking.core.network.dto.SyncResponseDto
import com.ordertracking.core.network.dto.TokenPairResponseDto
import com.ordertracking.sync.PlaceOrderInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Only Room-backed reads matter for this ViewModel; no test exercises a real network call. */
private object FakeApi : ApiService {
    override suspend fun register(body: RegisterRequestDto): TokenPairResponseDto = error("not used")
    override suspend fun login(body: LoginRequestDto): TokenPairResponseDto = error("not used")
    override suspend fun refresh(body: RefreshRequestDto): RefreshResponseDto = error("not used")
    override suspend fun restaurants(cursor: String?, limit: Int): RestaurantPageDto = error("not used")
    override suspend fun menu(restaurantId: String): List<MenuItemDto> = error("not used")
    override suspend fun placeOrder(idempotencyKey: String, body: PlaceOrderRequestDto): OrderDto = error("not used")
    override suspend fun getOrder(orderId: String): OrderDto = error("not used")
    override suspend fun cancelOrder(orderId: String, idempotencyKey: String, body: CancelOrderRequestDto): OrderDto = error("not used")
    override suspend fun sync(cursor: String?, limit: Int): SyncResponseDto = error("not used")
    override suspend fun registerDevice(body: DeviceInDto) = error("not used")
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MenuViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var restaurantRepository: RestaurantRepository
    private val dispatcher = StandardTestDispatcher()
    private var placedInput: PlaceOrderInput? = null
    private var placeOrderResult: Outcome<String> = "local-1".asSuccess()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        restaurantRepository = RestaurantRepository(api = FakeApi, restaurantDao = db.restaurantDao(), menuItemDao = db.menuItemDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedMenu() {
        db.restaurantDao().upsertAll(
            listOf(RestaurantEntity(id = "rest-1", name = "Diner", cuisine = "American", rating = 4.5, imageUrl = "", lat = 0.0, lng = 0.0)),
        )
        db.menuItemDao().upsertAll(
            listOf(
                MenuItemEntity(id = "menu-1", restaurantId = "rest-1", name = "Burger", description = "", priceMinor = 899, currency = "USD", imageUrl = ""),
                MenuItemEntity(id = "menu-2", restaurantId = "rest-1", name = "Fries", description = "", priceMinor = 299, currency = "USD", imageUrl = ""),
            ),
        )
    }

    private fun viewModel() = MenuViewModel(
        restaurantId = "rest-1",
        restaurantRepository = restaurantRepository,
        placeOrder = { input -> placedInput = input; placeOrderResult },
    )

    @Test
    fun `incrementing items updates the cart total`() = runTest(dispatcher) {
        seedMenu()
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem() // initial (possibly loading/empty)
            val loaded = awaitItem()
            assertEquals(2, loaded.menuItems.size)

            viewModel.onIntent(MenuIntent.Increment("menu-1"))
            val afterOne = awaitItem()
            assertEquals(1, afterOne.cart.size)
            assertEquals(899L, afterOne.totalMinor)

            viewModel.onIntent(MenuIntent.Increment("menu-1"))
            val afterTwo = awaitItem()
            assertEquals(2, afterTwo.cart.first().quantity)
            assertEquals(1798L, afterTwo.totalMinor)

            viewModel.onIntent(MenuIntent.Increment("menu-2"))
            val afterAddingFries = awaitItem()
            assertEquals(2097L, afterAddingFries.totalMinor)

            viewModel.onIntent(MenuIntent.Decrement("menu-1"))
            viewModel.onIntent(MenuIntent.Decrement("menu-1"))
            val afterRemovingBurger = awaitItem()
            assertEquals(1, afterRemovingBurger.cart.size)
            assertEquals(299L, afterRemovingBurger.totalMinor)
        }
    }

    @Test
    fun `placing an order clears the cart and emits OrderPlaced on success`() = runTest(dispatcher) {
        seedMenu()
        placeOrderResult = "local-99".asSuccess()
        val viewModel = viewModel()

        viewModel.uiState.test { awaitItem(); awaitItem() }
        viewModel.onIntent(MenuIntent.Increment("menu-1"))
        // Without this, submitOrder() below reads uiState.value before the
        // increment has propagated through the eagerly-shared combine(),
        // sees an empty cart, and returns early -- no effect ever fires.
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onIntent(MenuIntent.PlaceOrder)
            // submitOrder() runs on viewModelScope.launch, an independently
            // scheduled coroutine -- StandardTestDispatcher won't run it
            // just because the test coroutine is awaiting a channel receive.
            advanceUntilIdle()
            val effect = awaitItem()
            assertEquals(MenuEffect.OrderPlaced("local-99"), effect)
        }

        assertEquals("rest-1", placedInput?.restaurantId)
        assertEquals(0, viewModel.uiState.value.cart.size)
    }

    @Test
    fun `a failed placement surfaces a snackbar and keeps the cart`() = runTest(dispatcher) {
        seedMenu()
        placeOrderResult = AppError.Network("no connection").asFailure()
        val viewModel = viewModel()

        viewModel.uiState.test { awaitItem(); awaitItem() }
        viewModel.onIntent(MenuIntent.Increment("menu-1"))
        // Without this, submitOrder() below reads uiState.value before the
        // increment has propagated through the eagerly-shared combine(),
        // sees an empty cart, and returns early -- no effect ever fires.
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onIntent(MenuIntent.PlaceOrder)
            advanceUntilIdle()
            val effect = awaitItem()
            assertEquals(MenuEffect.ShowSnackbar("no connection"), effect)
        }

        assertEquals(1, viewModel.uiState.value.cart.size)
    }
}
