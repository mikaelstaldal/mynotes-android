package nu.staldal.mynotes.ui.note

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import nu.staldal.mynotes.util.MermaidRenderer
import nu.staldal.mynotes.util.NoteDateUtils
import nu.staldal.mynotes.util.NoteHtmlRenderer
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
    var renderedNote by remember { mutableStateOf<RenderedNote?>(null) }

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
            val dark = colorScheme.background.luminance() < 0.5f
            // Only diagrams need JavaScript; keep it disabled (and the CSP strict) otherwise.
            val hasMermaid = MermaidRenderer.containsDiagram(withImages)
            val mermaidScripts = if (hasMermaid) MermaidRenderer.scriptTags(dark = dark) else null
            renderedNote = RenderedNote(
                html = wrapHtmlDocument(
                    withImages,
                    colorScheme.background,
                    colorScheme.onBackground,
                    colorScheme.primary,
                    colorScheme.error,
                    dark,
                    mermaidScripts,
                ),
                enableJavaScript = hasMermaid,
            )
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
                    val rendered = renderedNote
                    if (rendered == null) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxSize().weight(1f),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    webViewClient = ExternalNavigationWebViewClient(onNavigateToNote, onNavigateToTag)
                                }
                            },
                            update = { webView ->
                                // JavaScript is enabled only for notes that contain a Mermaid diagram; the
                                // rendered HTML then carries the (relaxed) CSP that permits the injected engine.
                                webView.settings.javaScriptEnabled = rendered.enableJavaScript
                                webView.loadDataWithBaseURL(null, rendered.html, "text/html", "utf-8", null)
                            },
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
 * Routes taps inside the rendered note. Internal wikilinks (the app-only `mynotes://note/<slug>` and
 * `mynotes://tag/<slug>` synthesized by WikiLinkProcessor) navigate within the app; http(s)/mailto
 * links open in an external app instead of loading in-place — the WebView only ever hosts the note's
 * own rendered content. Any other scheme is blocked outright.
 */
private class ExternalNavigationWebViewClient(
    private val onNavigateToNote: (String) -> Unit,
    private val onNavigateToTag: (String) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.scheme?.lowercase() == "mynotes") {
            uri.lastPathSegment?.let { slug ->
                when (uri.host) {
                    "note" -> onNavigateToNote(slug)
                    "tag" -> onNavigateToTag(slug)
                }
            }
            return true
        }
        val intent = when (uri.scheme?.lowercase()) {
            "http", "https" -> Intent(Intent.ACTION_VIEW, uri)
            "mailto" -> Intent(Intent.ACTION_SENDTO, uri)
            else -> return true
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val context = view.context
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this link: $uri", Toast.LENGTH_SHORT).show()
        }
        return true
    }
}

private fun Color.toCssHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/** A rendered note document plus whether its WebView needs JavaScript (only Mermaid diagrams do). */
private data class RenderedNote(val html: String, val enableJavaScript: Boolean)

/**
 * Wraps a sanitized HTML body fragment into a complete document, styled to match the app's
 * Material theme. For the common note the CSP forbids scripts entirely (JavaScript is also disabled
 * via WebSettings) and restricts images to already-resolved data: URIs so the WebView never makes
 * its own (unauthenticated) network requests.
 *
 * [mermaidScripts] is non-null only when the note contains a ```mermaid diagram: it carries the
 * bundled Mermaid engine plus its driver (see [MermaidRenderer]), injected at the end of the body,
 * and the CSP is relaxed to permit those inline scripts. The engine renders entirely on-device (no
 * network), so `img-src`/`default-src` stay locked down.
 */
private fun wrapHtmlDocument(
    bodyHtml: String,
    background: Color,
    onBackground: Color,
    linkColor: Color,
    errorColor: Color,
    dark: Boolean,
    mermaidScripts: String?,
): String {
    val scriptSrc = if (mermaidScripts != null) " script-src 'unsafe-inline';" else ""
    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline';$scriptSrc">
    <style>
      body { margin: 0; padding: 0; font-family: sans-serif; background: ${background.toCssHex()}; color: ${onBackground.toCssHex()}; line-height: 1.4; }
      a { color: ${linkColor.toCssHex()}; }
      img, svg { max-width: 100%; height: auto; }
      /* Sit inline Lucide icons on the text baseline (mirrors the server's note CSS). */
      svg.lucide, img[src*="/api/v1/icons/"] { vertical-align: text-bottom; }
      pre, code { white-space: pre-wrap; word-break: break-word; }
      table { border-collapse: collapse; }
      th, td { border: 1px solid ${onBackground.toCssHex()}; padding: 4px 8px; }
      li:has(input[type="checkbox"]) { list-style: none; }
      input[type="checkbox"] { margin: 0 0.4em 0 -1.3em; vertical-align: middle; }
${calloutCss(dark, background)}
      /* Rendered Mermaid diagram: centered, never wider than the content column (mirrors the web). */
      .mermaid-diagram { margin: 0.9em 0; text-align: center; }
      /* A diagram that failed to render keeps its source visible, flagged in the error color. */
      pre.mermaid-error { border: 1px solid ${errorColor.toCssHex()}; }
    </style>
    </head>
    <body>
    $bodyHtml${mermaidScripts?.let { "\n$it" } ?: ""}
    </body>
    </html>
""".trimIndent()
}

/** A callout colour family and its accent, per theme (mirrors app.css --callout-* in the web client). */
private data class CalloutFamily(val name: String, val light: Int, val dark: Int)

private val CALLOUT_FAMILIES = listOf(
    CalloutFamily("blue", 0x2563EB, 0x3B82F6),
    CalloutFamily("green", 0x16A34A, 0x22C55E),
    CalloutFamily("cyan", 0x0891B2, 0x22D3EE),
    CalloutFamily("amber", 0xD97706, 0xF59E0B),
    CalloutFamily("red", 0xDC2626, 0xEF4444),
    CalloutFamily("gray", 0x6B7280, 0x9CA3AF),
)

/**
 * CSS for icon/box/callout rendering (see NoteHtmlRenderer / CalloutProcessor), mirroring the web
 * client's app.css `.callout*` rules. The web uses CSS `color-mix()` over `--callout-*` variables;
 * here the accent/border/background tints are precomputed in Kotlin so the styling works on every
 * WebView regardless of `color-mix` support. `gray` is the default family for a static box (`>*`)
 * that carries no alias.
 */
private fun calloutCss(dark: Boolean, background: Color): String {
    fun accent(f: CalloutFamily) = if (dark) f.dark else f.light
    val gray = CALLOUT_FAMILIES.first { it.name == "gray" }
    val lines = mutableListOf(
        // Base box: geometry + the default (gray) accent for a marker-only static box.
        ".callout { margin: 0.75em 0; padding: 0.6em 1em; border-style: solid; border-width: 1px; border-left-width: 4px; border-radius: 6px; border-color: ${mixHex(accent(gray), background, 0.35f)}; border-left-color: ${hex(accent(gray))}; background: ${mixHex(accent(gray), background, 0.08f)}; }",
        ".callout > .callout-title { color: ${hex(accent(gray))}; }",
        ".callout > :nth-child(2) { margin-top: 0.4em; }",
        ".callout > :last-child { margin-bottom: 0; }",
        ".callout-title { display: flex; align-items: center; gap: 0.4em; margin: 0; font-weight: 600; }",
        ".callout-title svg.lucide { vertical-align: middle; }",
        ".callout-foldable > .callout-title { cursor: pointer; list-style: none; }",
        ".callout-foldable > .callout-title::-webkit-details-marker { display: none; }",
        """.callout-foldable > .callout-title::after { content: ""; width: 0.5em; height: 0.5em; margin-left: auto; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; transform: rotate(-45deg); transition: transform 0.15s ease; }""",
        "details.callout-foldable[open] > .callout-title::after { transform: rotate(45deg); }",
    )
    // Per-family accents: box tint (only when the element is also a .callout) and text colour (box
    // title and alias-tinted paragraph).
    for (f in CALLOUT_FAMILIES) {
        val a = accent(f)
        lines += ".callout.callout-color-${f.name} { border-color: ${mixHex(a, background, 0.35f)}; border-left-color: ${hex(a)}; background: ${mixHex(a, background, 0.08f)}; }"
        lines += ".callout.callout-color-${f.name} > .callout-title { color: ${hex(a)}; }"
        lines += ".callout-para.callout-color-${f.name} { color: ${hex(a)}; }"
    }
    return lines.joinToString("\n") { "      $it" }
}

/** `#RRGGBB` for a packed 0xRRGGBB int. */
private fun hex(rgb: Int): String = String.format("#%06X", rgb and 0xFFFFFF)

/** `#RRGGBB` for [rgb] blended over [bg] at [alpha] (mirrors the web `color-mix` box tints). */
private fun mixHex(rgb: Int, bg: Color, alpha: Float): String {
    val br = (bg.red * 255f)
    val bgc = (bg.green * 255f)
    val bb = (bg.blue * 255f)
    val r = ((rgb shr 16 and 0xFF) * alpha + br * (1 - alpha)).toInt().coerceIn(0, 255)
    val g = ((rgb shr 8 and 0xFF) * alpha + bgc * (1 - alpha)).toInt().coerceIn(0, 255)
    val b = ((rgb and 0xFF) * alpha + bb * (1 - alpha)).toInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", r, g, b)
}

private fun shareNoteAsMarkdown(context: android.content.Context, title: String, content: String) {
    val sharedDir = File(context.cacheDir, "shared")
    // Drop any previously shared note so old content (and its FileProvider grant) can't linger.
    sharedDir.deleteRecursively()
    // Write each share to a fresh random subdirectory so a stale grant can't be replayed against a newer note.
    val file = File(sharedDir, "${java.util.UUID.randomUUID()}/note.md").apply { parentFile?.mkdirs() }
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
