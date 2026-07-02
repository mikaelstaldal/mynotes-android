package nu.staldal.mynotes.ui.note

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import nu.staldal.mynotes.util.NoteDateUtils
import nu.staldal.mynotes.util.NoteHtmlRenderer
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    slug: String,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (String) -> Unit,
    viewModel: NoteViewModel = viewModel(),
) {
    val state by viewModel.detailState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renderedHtml by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(slug) {
        viewModel.loadNote(slug)
    }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onNavigateBack()
    }

    val colorScheme = MaterialTheme.colorScheme
    LaunchedEffect(state.slug, state.content) {
        if (state.slug != null) {
            val sanitizedBody = NoteHtmlRenderer.renderToSanitizedHtml(state.content)
            val withImages = viewModel.artifactRepository.rewriteImageSrcToDataUris(sanitizedBody)
            renderedHtml = wrapHtmlDocument(withImages, colorScheme.background, colorScheme.onBackground, colorScheme.primary)
        }
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
                    IconButton(onClick = { shareNoteAsMarkdown(context, state.title, state.content) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "created ${NoteDateUtils.formatDisplayDateTime(state.createdAt)} · " +
                            "updated ${NoteDateUtils.formatDisplayDateTime(state.updatedAt)} · v${state.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.tags.forEach { tag ->
                                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text(
                                        text = tag.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    val html = renderedHtml
                    if (html == null) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxSize().weight(1f),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = false
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    webViewClient = ExternalNavigationWebViewClient()
                                }
                            },
                            update = { webView -> webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
                            onRelease = { it.destroy() },
                        )
                    }
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
 * Sends http(s)/mailto navigations (e.g. a tapped link) to an external app instead of loading
 * them in-place — the WebView only ever hosts the note's own rendered content. Any other scheme
 * is blocked outright.
 */
private class ExternalNavigationWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        when (uri.scheme?.lowercase()) {
            "http", "https" -> view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            "mailto" -> view.context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
        }
        return true
    }
}

private fun Color.toCssHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * Wraps a sanitized HTML body fragment into a complete document, styled to match the app's
 * Material theme. Script execution is already disabled via WebSettings; the CSP below is
 * defense-in-depth, and restricts images to already-resolved data: URIs so the WebView never
 * makes its own (unauthenticated) network requests.
 */
private fun wrapHtmlDocument(bodyHtml: String, background: Color, onBackground: Color, linkColor: Color): String = """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline';">
    <style>
      body { margin: 0; padding: 0; font-family: sans-serif; background: ${background.toCssHex()}; color: ${onBackground.toCssHex()}; line-height: 1.4; }
      a { color: ${linkColor.toCssHex()}; }
      img, svg { max-width: 100%; height: auto; }
      pre, code { white-space: pre-wrap; word-break: break-word; }
      table { border-collapse: collapse; }
      th, td { border: 1px solid ${onBackground.toCssHex()}; padding: 4px 8px; }
    </style>
    </head>
    <body>
    $bodyHtml
    </body>
    </html>
""".trimIndent()

private fun shareNoteAsMarkdown(context: android.content.Context, title: String, content: String) {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(sharedDir, "note.md")
    file.writeText(content)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share note"))
}
