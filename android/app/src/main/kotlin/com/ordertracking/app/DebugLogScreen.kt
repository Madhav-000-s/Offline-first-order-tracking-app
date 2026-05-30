package com.ordertracking.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ordertracking.core.database.dao.SyncLogDao
import com.ordertracking.core.database.entity.SyncLogEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The single best interview demo in the project (DESIGN.md §4): fifteen
 * seconds of scrolling this makes an otherwise-invisible merge engine
 * concrete -- "rejected WS v6, local at v7" instead of just trusting that
 * the sync logic works.
 */
class DebugLogViewModel(syncLogDao: SyncLogDao) : ViewModel() {
    val entries: StateFlow<List<SyncLogEntity>> = syncLogDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(viewModel: DebugLogViewModel, modifier: Modifier = Modifier) {
    val entries by viewModel.entries.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Sync log (last ${entries.size})") }) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(entries, key = { it.id }) { entry -> SyncLogRow(entry) }
        }
    }
}

@Composable
private fun SyncLogRow(entry: SyncLogEntity) {
    val decisionColor = when (entry.decision) {
        "ACCEPT", "INSERT" -> Color(0xFF2E7D32)
        "REJECT_STALE", "REJECT_REGRESSION" -> Color(0xFFC62828)
        else -> Color.Gray
    }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "${entry.channel}  •  ${entry.decision}",
                style = MaterialTheme.typography.titleSmall,
                color = decisionColor,
            )
            Text("order ${entry.orderLocalId.take(8)}", style = MaterialTheme.typography.bodySmall)
            Text(entry.detail, style = MaterialTheme.typography.bodySmall)
            Text(entry.occurredAt.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}
