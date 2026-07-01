package nu.staldal.mynotes.ui.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import nu.staldal.mynotes.data.local.NoteEntity
import nu.staldal.mynotes.util.NoteDateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToNote: (String) -> Unit,
    onNavigateToNewNote: () -> Unit,
    onNavigateToConflicts: () -> Unit,
    viewModel: NoteListViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showSearch by remember { mutableStateOf(false) }

    if (!state.isConfigured) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Welcome to MyNotes", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Configure your server to get started")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onNavigateToSettings) { Text("Open Settings") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.enableOfflineMode() }) { Text("Work Offline") }
            }
        }
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(state.syncMessage) {
        state.syncMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearSyncMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showSearch) {
                TopAppBar(
                    title = {
                        TextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.search(it) },
                            placeholder = { Text("Search notes...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { showSearch = false; viewModel.clearSearch() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Notes") },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        if (state.conflictCount > 0) {
                            BadgedBox(badge = { Badge { Text("${state.conflictCount}") } }) {
                                IconButton(onClick = onNavigateToConflicts) {
                                    Icon(Icons.Default.ReportProblem, contentDescription = "Conflicts")
                                }
                            }
                        }
                        if (!state.isOfflineMode && state.isOnline) {
                            if (state.pendingChangesCount > 0) {
                                BadgedBox(badge = { Badge { Text("${state.pendingChangesCount}") } }) {
                                    IconButton(onClick = { viewModel.syncNow() }) {
                                        Icon(Icons.Default.Sync, contentDescription = "Sync now")
                                    }
                                }
                            } else {
                                IconButton(onClick = { viewModel.syncNow() }) {
                                    Icon(Icons.Default.Sync, contentDescription = "Sync now")
                                }
                            }
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!showSearch) {
                FloatingActionButton(onClick = onNavigateToNewNote) {
                    Icon(Icons.Default.Add, contentDescription = "New Note")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (!state.isOfflineMode && !state.isOnline) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Offline",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            if (showSearch && state.searchQuery.isNotBlank()) {
                if (state.isSearching) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (state.searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results found")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.searchResults) { note ->
                            NoteListItem(note = note, onClick = { onNavigateToNote(note.slug) })
                        }
                    }
                }
            } else if (state.notes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notes yet — tap + to create one", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.notes) { note ->
                        NoteListItem(note = note, onClick = { onNavigateToNote(note.slug) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteListItem(note: NoteEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = note.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val preview = note.excerpt.ifBlank { note.content }
        if (preview.isNotBlank()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "created ${NoteDateUtils.formatDisplayDateTime(note.createdAt)} · " +
                "updated ${NoteDateUtils.formatDisplayDateTime(note.updatedAt)} · v${note.version}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}
