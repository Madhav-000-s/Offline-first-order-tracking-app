package com.ordertracking.app

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.ordertracking.core.common.AppClock
import com.ordertracking.core.common.SystemAppClock
import com.ordertracking.core.data.merge.OrderWriter
import com.ordertracking.core.data.repository.OrderRepository
import com.ordertracking.core.data.repository.RestaurantRepository
import com.ordertracking.core.data.repository.SyncRepository
import com.ordertracking.core.database.AppDatabase
import com.ordertracking.core.datastore.SessionManager
import com.ordertracking.core.network.ApiService
import com.ordertracking.core.network.NetworkModule
import com.ordertracking.core.network.auth.TokenStore
import com.ordertracking.core.network.ws.OrderWebSocketClient
import com.ordertracking.sync.CancelOrderUseCase
import com.ordertracking.sync.DeltaSyncWorkerFactory
import com.ordertracking.sync.OutboxDrainWorkerFactory
import com.ordertracking.sync.PlaceOrderUseCase
import com.ordertracking.sync.SyncManager
import kotlinx.serialization.json.Json

/**
 * Manual dependency container -- a deliberate scope decision, not an
 * oversight. Every class below already takes its dependencies through its
 * constructor (the same shape Hilt would inject into); wiring Hilt on top
 * would mean adding `@Inject`/`@HiltViewModel` across every module built in
 * phases 5-8, which is a mechanical but invasive change against a codebase
 * that's already fully tested against manual construction. This container
 * is the thing a `@Module` would otherwise do.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.DATABASE_NAME).build()

    val tokenStore: TokenStore = TokenStore(appContext)
    val sessionManager: SessionManager = SessionManager(appContext)

    private val networkModule = NetworkModule(baseUrl = BASE_URL, tokenStore = tokenStore, debug = BuildConfig.DEBUG)
    val apiService: ApiService = networkModule.apiService
    val json: Json = networkModule.json

    val clock: AppClock = SystemAppClock()

    val orderWriter: OrderWriter = OrderWriter(database.orderDao(), database.syncLogDao(), clock)
    val orderRepository: OrderRepository = OrderRepository(database.orderDao())
    val restaurantRepository: RestaurantRepository =
        RestaurantRepository(apiService, database.restaurantDao(), database.menuItemDao())
    val syncRepository: SyncRepository = SyncRepository(
        apiService,
        orderWriter,
        database.orderDao(),
        database.restaurantDao(),
        database.menuItemDao(),
        database.syncCursorDao(),
        clock,
    )

    val placeOrderUseCase: PlaceOrderUseCase = PlaceOrderUseCase(database, clock, json)
    val cancelOrderUseCase: CancelOrderUseCase = CancelOrderUseCase(database, clock, json)

    val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }
    val syncManager: SyncManager by lazy { SyncManager(workManager) }

    val outboxDrainWorkerFactory = OutboxDrainWorkerFactory(database, apiService, orderWriter, json)
    val deltaSyncWorkerFactory = DeltaSyncWorkerFactory(syncRepository)

    fun newWebSocketClient(): OrderWebSocketClient = OrderWebSocketClient(networkModule.okHttpClient, WS_URL)

    private companion object {
        // 10.0.2.2 is the Android emulator's alias for the host machine's
        // localhost -- swap for a real backend URL for a physical device.
        const val BASE_URL = "http://10.0.2.2:8000/"
        const val WS_URL = "ws://10.0.2.2:8000/v1/ws/orders"
    }
}
