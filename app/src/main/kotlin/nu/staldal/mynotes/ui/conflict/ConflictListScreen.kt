package nu.staldal.mynotes.ui.conflict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToConflict: (String) -> Unit,
    viewModel: ConflictViewModel = viewModel(),
) {
    val conflicts by viewModel.conflicts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conflicts") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (conflicts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No conflicts")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(conflicts) { conflict ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToConflict(conflict.slug) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(conflict.localTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Edited locally and on the server since last sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
