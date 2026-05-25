package com.ordertracking.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ordertracking.core.model.Order
import com.ordertracking.core.model.OrderEvent
import com.ordertracking.core.model.OrderStatus

@Composable
fun OrderDetailScreen(
    state: OrderDetailUiState,
    onIntent: (OrderDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { padding ->
        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            state.order == null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("Order not found") }

            else -> OrderDetailContent(order = state.order, isCancelling = state.isCancelling, onIntent = onIntent, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun OrderDetailContent(
    order: Order,
    isCancelling: Boolean,
    onIntent: (OrderDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Order ${order.localId.take(8)}", style = MaterialTheme.typography.headlineSmall)
        Text("Status: ${order.status}", style = MaterialTheme.typography.titleMedium)
        Text("Total: ${order.currency} ${order.totalMinor / 100.0}", style = MaterialTheme.typography.bodyLarge)

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!order.status.isTerminal) {
                OutlinedButton(onClick = { onIntent(OrderDetailIntent.CancelOrder) }, enabled = !isCancelling) {
                    Text(if (isCancelling) "Cancelling..." else "Cancel order")
                }
            }
            if (order.status == OrderStatus.PICKED_UP) {
                Button(onClick = { onIntent(OrderDetailIntent.TrackOrder) }) {
                    Text("Track courier")
                }
            }
        }

        Text("Timeline", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(order.events, key = { it.id }) { event -> TimelineRow(event) }
        }
    }
}

@Composable
private fun TimelineRow(event: OrderEvent) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(event.status.name, style = MaterialTheme.typography.bodyMedium)
        Text(event.occurredAt.toString(), style = MaterialTheme.typography.bodySmall)
    }
}
