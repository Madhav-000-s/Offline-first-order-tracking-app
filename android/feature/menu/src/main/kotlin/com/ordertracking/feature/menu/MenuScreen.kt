package com.ordertracking.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ordertracking.core.designsystem.FullScreenLoading
import com.ordertracking.core.model.MenuItem

@Composable
fun MenuScreen(
    state: MenuUiState,
    onIntent: (MenuIntent) -> Unit,
    modifier: Modifier = Modifier,
    // Defaulted so a caller that has no interest in one-shot messages
    // (Paparazzi, previews) still compiles unchanged.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.cart.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Total: ${state.totalMinor / 100.0}", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { onIntent(MenuIntent.PlaceOrder) }, enabled = !state.isPlacingOrder) {
                        Text(if (state.isPlacingOrder) "Placing..." else "Place order")
                    }
                }
            }
        },
    ) { padding ->
        if (state.isLoading) {
            FullScreenLoading(Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(state.menuItems, key = { it.id }) { item ->
                    val quantity = state.cart.firstOrNull { it.menuItem.id == item.id }?.quantity ?: 0
                    MenuItemRow(item, quantity, onIntent)
                }
            }
        }
    }
}

@Composable
private fun MenuItemRow(item: MenuItem, quantity: Int, onIntent: (MenuIntent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            Text("${item.currency} ${item.priceMinor / 100.0}", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onIntent(MenuIntent.Decrement(item.id)) }, enabled = quantity > 0) {
                Text("−")
            }
            Text(quantity.toString())
            IconButton(onClick = { onIntent(MenuIntent.Increment(item.id)) }) {
                Text("+")
            }
        }
    }
}
