package com.ordertracking.core.data.mapper

import com.ordertracking.core.database.entity.MenuItemEntity
import com.ordertracking.core.database.entity.RestaurantEntity
import com.ordertracking.core.model.MenuItem
import com.ordertracking.core.model.Restaurant
import com.ordertracking.core.network.dto.MenuItemDto
import com.ordertracking.core.network.dto.RestaurantDto

fun RestaurantDto.toEntity(): RestaurantEntity = RestaurantEntity(
    id = id,
    name = name,
    cuisine = cuisine,
    rating = rating,
    imageUrl = image_url,
    lat = lat,
    lng = lng,
)

fun MenuItemDto.toEntity(): MenuItemEntity = MenuItemEntity(
    id = id,
    restaurantId = restaurant_id,
    name = name,
    description = description,
    priceMinor = price_minor,
    currency = currency,
    imageUrl = image_url,
)

fun RestaurantEntity.toDomain(): Restaurant = Restaurant(
    id = id,
    name = name,
    cuisine = cuisine,
    rating = rating,
    imageUrl = imageUrl,
    lat = lat,
    lng = lng,
)

fun MenuItemEntity.toDomain(): MenuItem = MenuItem(
    id = id,
    restaurantId = restaurantId,
    name = name,
    description = description,
    priceMinor = priceMinor,
    currency = currency,
    imageUrl = imageUrl,
)
