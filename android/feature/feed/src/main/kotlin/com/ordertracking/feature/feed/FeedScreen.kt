package com.ordertracking.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
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
        val refresh = items.loadState.refresh
        when {
            refresh is LoadState.Loading && items.itemCount == 0 -> FullScreenLoading(Modifier.padding(padding))

            // An empty cache is the only situation with genuinely nothing to
            // render. Once even one page is cached the list wins and any
            // failure is reported non-fatally at the bottom instead, because
            // replacing a working feed with an error screen is exactly the
            // behaviour offline-first exists to avoid.
            refresh is LoadState.Error && items.itemCount == 0 -> FeedPlaceholder(
                padding = padding,
                title = "Couldn't reach the server",
                detail = refresh.error.message ?: "No cached restaurants to fall back on yet.",
                actionLabel = "Retry",
                onAction = { items.retry() },
            )

            refresh !is LoadState.Loading && items.itemCount == 0 -> FeedPlaceholder(
                padding = padding,
                title = "No restaurants yet",
                detail = "The backend has no seed data. Run the seed script, then refresh.",
                actionLabel = "Refresh",
                onAction = { items.refresh() },
            )

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
                when (val append = items.loadState.append) {
                    is LoadState.Loading -> item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
                    // Non-fatal by design: the cached feed keeps rendering
                    // even when the next page fails (DESIGN.md §11).
                    is LoadState.Error -> item {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                append.error.message ?: "Couldn't load more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { items.retry() }) { Text("Try again") }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun FeedPlaceholder(
    padding: PaddingValues,
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
    }
}

@Composable
private fun RestaurantRow(restaurant: Restaurant, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column {
            // The seed fixtures ship empty image_url values, and an AsyncImage
            // with no source and no height collapses to nothing while still
            // occupying a slot in the layout. Skipping it entirely keeps the
            // card looking deliberate rather than half-loaded.
            if (restaurant.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = restaurant.imageUrl,
                    contentDescription = restaurant.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text(restaurant.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${restaurant.cuisine} • ${restaurant.rating}★",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
