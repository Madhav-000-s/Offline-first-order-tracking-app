package com.ordertracking.feature.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.ordertracking.core.designsystem.FullScreenLoading
import com.ordertracking.core.model.Restaurant
import kotlinx.coroutines.flow.Flow

@Composable
fun FeedScreen(
    restaurants: Flow<androidx.paging.PagingData<Restaurant>>,
    onIntent: (FeedIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items: LazyPagingItems<Restaurant> = restaurants.collectAsLazyPagingItems()

    Scaffold(modifier = modifier) { padding ->
        when {
            items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> FullScreenLoading(Modifier.padding(padding))
            else -> LazyColumn(contentPadding = PaddingValues(16.dp, padding.calculateTopPadding())) {
                items(
                    count = items.itemCount,
                    key = items.itemKey { it.id },
                    contentType = items.itemContentType { "restaurant" },
                ) { index ->
                    val restaurant = items[index]
                    if (restaurant != null) {
                        RestaurantRow(restaurant, onClick = { onIntent(FeedIntent.RestaurantClicked(restaurant.id)) })
                    }
                }
                if (items.loadState.append is LoadState.Loading) {
                    item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
                }
                // LoadState.append reports the error non-fatally: the cached
                // feed keeps rendering even when the next page fails (DESIGN.md §11).
            }
        }
    }
}

@Composable
private fun RestaurantRow(restaurant: Restaurant, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.padding(vertical = 6.dp)) {
        Column {
            AsyncImage(
                model = restaurant.imageUrl,
                contentDescription = restaurant.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(restaurant.name, style = MaterialTheme.typography.titleMedium)
            Text("${restaurant.cuisine} • ${restaurant.rating}★", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
