package com.ordertracking.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ordertracking.core.model.Order
import com.ordertracking.core.model.SyncState

/** Stateless, hoisted: takes UiState + lambdas, knows nothing about ViewModels or Room. */
@Composable
fun OrdersListScreen(
    state: OrdersListUiState,
    onIntent: (OrdersListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { padding ->
        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            state.orders.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No orders yet", style = MaterialTheme.typography.bodyLarge)
            }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp, padding.calculateTopPadding())) {
                items(state.orders, key = { it.localId }) { order ->
                    OrderRow(order = order, onClick = { onIntent(OrdersListIntent.OrderClicked(order.localId)) })
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: Order, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Order ${order.localId.take(8)}", style = MaterialTheme.typography.titleMedium)
            Text(text = order.status.name, style = MaterialTheme.typography.bodyMedium)
            if (order.syncState == SyncState.PENDING_CREATE) {
                AssistChip(onClick = {}, label = { Text("Waiting to send") })
            } else if (order.syncState == SyncState.FAILED) {
                AssistChip(onClick = {}, label = { Text("Failed — tap to retry") })
            }
        }
    }
}
