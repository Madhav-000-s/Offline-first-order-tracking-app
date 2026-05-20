package com.ordertracking.core.network

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
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): TokenPairResponseDto

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): TokenPairResponseDto

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): RefreshResponseDto

    @GET("v1/restaurants")
    suspend fun restaurants(@Query("cursor") cursor: String?, @Query("limit") limit: Int): RestaurantPageDto

    @GET("v1/restaurants/{id}/menu")
    suspend fun menu(@Path("id") restaurantId: String): List<MenuItemDto>

    @POST("v1/orders")
    suspend fun placeOrder(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: PlaceOrderRequestDto,
    ): OrderDto

    @GET("v1/orders/{id}")
    suspend fun getOrder(@Path("id") orderId: String): OrderDto

    @POST("v1/orders/{id}/cancel")
    suspend fun cancelOrder(
        @Path("id") orderId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CancelOrderRequestDto,
    ): OrderDto

    @GET("v1/sync")
    suspend fun sync(@Query("cursor") cursor: String?, @Query("limit") limit: Int): SyncResponseDto

    @POST("v1/devices")
    suspend fun registerDevice(@Body body: DeviceInDto)
}
