package nu.staldal.mynotes.ui.note

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nu.staldal.mynotes.util.NoteDateUtils
import nu.staldal.mynotes.util.SlugGenerator
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    slug: String,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (String) -> Unit,
    onNavigateToNote: (String) -> Unit,
    onNavigateToTag: (String) -> Unit,
    viewModel: NoteViewModel = viewModel(),
) {
    val state by viewModel.detailState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(slug) {
        viewModel.loadNote(slug)
    }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val context = LocalContext.current
                    // Sharing offers the note's own Markdown, or a standalone HTML document
                    // rendered by the same kit that draws the note (see NoteHtmlExport).
                    Box {
                        IconButton(onClick = { showShareMenu = true }, enabled = !isExporting) {
                            if (isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Share, contentDescription = "Share")
                            }
                        }
                        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                        DropdownMenu(expanded = showShareMenu, onDismissRequest = { showShareMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Share as Markdown") },
                                onClick = {
                                    showShareMenu = false
                                    scope.launch {
                                        shareNote(context, state.title, "${fileBaseName(slug)}.md", "text/markdown", state.content)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share as HTML") },
                                onClick = {
                                    showShareMenu = false
                                    isExporting = true
                                    scope.launch {
                                        try {
                                            val html = buildStandaloneNoteHtml(
                                                context = context,
                                                title = state.title,
                                                markdown = state.content,
                                                dark = dark,
                                                artifactRepository = viewModel.artifactRepository,
                                            )
                                            shareNote(context, state.title, "${fileBaseName(slug)}.html", "text/html", html)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Toast.makeText(
                                                context,
                                                "Could not build HTML: ${e.message}",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        } finally {
                                            isExporting = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                    IconButton(onClick = { onNavigateToEdit(slug) }, enabled = !state.isDeleting) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { showDeleteDialog = true }, enabled = !state.isDeleting) {
                        if (state.isDeleting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                // Tapping the note opens the editor, the same as the toolbar's Edit button. The
                // rendered body is a WebView, which consumes touch events itself, so it reports its
                // own taps through NoteRendererWebView's onClick; this covers the title, timestamps
                // and tags around it. No indication: a ripple across the whole note reads as the
                // note being a button, which it is not.
                val openEditor = { if (!state.isDeleting) onNavigateToEdit(slug) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = openEditor,
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.title, style = MaterialTheme.typography.headlineSmall)
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Version ${state.version}") } },
                        state = rememberTooltipState(),
                    ) {
                        Text(
                            "created ${NoteDateUtils.formatDisplayDateTime(state.createdAt)} · " +
                                "updated ${NoteDateUtils.formatDisplayDateTime(state.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.tags.forEach { tag ->
                                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text(
                                        text = tag.slug,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    // The note body is rendered by the vendored MyNotes render kit in a WebView, so
                    // it matches the web UI exactly; see NoteRendererWebView.
                    val colorScheme = MaterialTheme.colorScheme
                    NoteRendererWebView(
                        markdown = state.content,
                        dark = colorScheme.background.luminance() < 0.5f,
                        background = colorScheme.background,
                        onBackground = colorScheme.onBackground,
                        linkColor = colorScheme.primary,
                        artifactRepository = viewModel.artifactRepository,
                        onNavigateToNote = onNavigateToNote,
                        onNavigateToTag = onNavigateToTag,
                        modifier = Modifier.fillMaxSize().weight(1f),
                        onClick = openEditor,
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Note") },
            text = { Text("Are you sure you want to delete this note?") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; viewModel.deleteNote(slug) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The shared file's name, so the receiving app shows something better than "note": the note's slug,
 * which the server's slug rules already restrict to a filename-safe form. Anything else — a slug
 * from a future server, or one carrying path separators — falls back rather than being written
 * outside the share directory.
 */
private fun fileBaseName(slug: String): String = if (SlugGenerator.isValid(slug)) slug else "note"

private suspend fun shareNote(context: Context, title: String, fileName: String, mimeType: String, content: String) {
    // An exported note carries its images inline and can run to megabytes, so it is written off the
    // main thread.
    val uri = withContext(Dispatchers.IO) {
        val sharedDir = File(context.cacheDir, "shared")
        // Drop any previously shared note so old content (and its FileProvider grant) can't linger.
        sharedDir.deleteRecursively()
        // Write each share to a fresh random subdirectory so a stale grant can't be replayed against a newer note.
        val file = File(sharedDir, "${java.util.UUID.randomUUID()}/$fileName").apply { parentFile?.mkdirs() }
        file.writeText(content)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share note"))
}
