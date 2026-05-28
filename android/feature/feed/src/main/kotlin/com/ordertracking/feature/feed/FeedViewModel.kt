package com.ordertracking.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ordertracking.core.data.repository.RestaurantRepository
import com.ordertracking.core.model.Restaurant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

sealed interface FeedIntent {
    data class RestaurantClicked(val restaurantId: String) : FeedIntent
}

sealed interface FeedEffect {
    data class NavigateToMenu(val restaurantId: String) : FeedEffect
}

/** `cachedIn(viewModelScope)` is what survives a config change without re-paging from scratch. */
class FeedViewModel(restaurantRepository: RestaurantRepository) : ViewModel() {

    val restaurants: Flow<PagingData<Restaurant>> = restaurantRepository.pagedRestaurants()
        .cachedIn(viewModelScope)

    private val effectChannel = Channel<FeedEffect>(Channel.BUFFERED)
    val effects: Flow<FeedEffect> = effectChannel.receiveAsFlow()

    fun onIntent(intent: FeedIntent) {
        when (intent) {
            is FeedIntent.RestaurantClicked -> effectChannel.trySend(FeedEffect.NavigateToMenu(intent.restaurantId))
        }
    }
}
