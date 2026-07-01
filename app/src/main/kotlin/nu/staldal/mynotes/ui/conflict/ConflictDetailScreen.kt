package nu.staldal.mynotes.ui.conflict

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import nu.staldal.mynotes.util.NoteDateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictDetailScreen(
    slug: String,
    onNavigateBack: () -> Unit,
    viewModel: ConflictViewModel = viewModel(),
) {
    val conflicts by viewModel.conflicts.collectAsState()
    val conflict = conflicts.firstOrNull { it.slug == slug }

    LaunchedEffect(conflict) {
        if (conflicts.isNotEmpty() && conflict == null) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Resolve Conflict") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (conflict == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "This note was edited on this device and on the server since the last sync. Choose which version to keep.",
                style = MaterialTheme.typography.bodyMedium,
            )

            ConflictSideCard(
                heading = "Your local edit",
                title = conflict.localTitle,
                content = conflict.localContent,
                onKeep = { viewModel.resolve(slug, keepLocal = true); onNavigateBack() },
                keepLabel = "Keep mine",
            )

            ConflictSideCard(
                heading = "Server version (updated ${NoteDateUtils.formatDisplayDateTime(conflict.serverUpdatedAt)})",
                title = conflict.serverTitle,
                content = conflict.serverContent,
                onKeep = { viewModel.resolve(slug, keepLocal = false); onNavigateBack() },
                keepLabel = "Keep server",
            )
        }
    }
}

@Composable
private fun ConflictSideCard(
    heading: String,
    title: String,
    content: String,
    keepLabel: String,
    onKeep: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(content, style = MaterialTheme.typography.bodyMedium, maxLines = 8)
            Button(onClick = onKeep, modifier = Modifier.fillMaxWidth()) { Text(keepLabel) }
        }
    }
}
