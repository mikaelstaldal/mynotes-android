package nu.staldal.mynotes.ui.note

import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nu.staldal.mynotes.data.ArtifactRepository
import org.json.JSONObject

/**
 * Builds a standalone HTML document for a note — this app's equivalent of the web UI's
 * "Download HTML" (mynotes' web/ts/util/export.ts), used by the note view's "Share as HTML".
 *
 * The document is produced by the same vendored render kit that draws the on-screen note
 * ([NoteRendererWebView]), so an exported note matches the web UI's export by construction and no
 * Markdown is rendered in Kotlin. Rendering happens in a throwaway, off-screen WebView rather than
 * the visible one: the export is then independent of what the screen currently shows, and it renders
 * under the kit's canonical palette instead of the Material colours the note view pushes in.
 *
 * "Standalone" means the file needs neither the MyNotes server nor this app to display:
 *  - the kit's own note.css is inlined, with a small supplement for the page frame and printing;
 *  - the theme is baked in via `data-theme` on `<html>` (as the web export does), so the reader sees
 *    what the sharer saw, and Mermaid diagrams — which bake their colours into the generated SVG —
 *    agree with it;
 *  - every image the app can resolve is inlined as a `data:` URI, from the local cache or, when
 *    online, from the server. One that cannot be resolved is left as a URL and renders broken, which
 *    is what the web export does too.
 */

/** The kit's canonical stylesheet for rendered note content, vendored with the rest of the kit. */
private const val NOTE_CSS_ASSET = "renderer/render/note.css"

/** Name the export's JS bridge is exposed under, on the export WebView only. */
private const val EXPORT_BRIDGE = "MyNotesExport"

/** Viewport the off-screen WebView lays out at, so Mermaid has sensible dimensions to measure in. */
private const val EXPORT_VIEWPORT_WIDTH = 800
private const val EXPORT_VIEWPORT_HEIGHT = 1200

/** Upper bound on a single export; a note whose diagrams never finish must not hang the share. */
private const val EXPORT_TIMEOUT_MS = 30_000L

/**
 * Page frame for the exported document. note.css styles `.note-content` and owns the colour
 * variables, but deliberately carries no app-chrome rules, so the body and the print behaviour are
 * supplied here. The text column matches the web export exactly: its body is `max-width: 65ch` with
 * a 1.25rem gutter under `border-box`, which is the `calc(65ch - 2.5rem)` cap note.css applies to
 * `.note-content`.
 */
private const val PAGE_STYLESHEET = """
* { box-sizing: border-box; }
body {
  margin: 0 auto;
  max-width: 65ch;
  padding: 2rem 1.25rem;
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  background: var(--bg);
  color: var(--fg);
}
/* On paper a collapsed <details> would hide its body; reveal every callout's content (and drop the
   now-meaningless disclosure arrow) so nothing is lost in print. Two collapse models are in play:
   older engines hide the non-summary children (display:none), newer ones (Chromium's
   ::details-content) hide the content via content-visibility — override both. */
@media print {
  .note-content details.callout > :not(summary) { display: block !important; }
  .note-content details.callout::details-content { content-visibility: visible !important; }
  .note-content .callout-foldable > .callout-title::after { display: none; }
}
"""

/**
 * note.css's first `:root { … }` block, which holds the light palette. Lifted verbatim into an
 * `@media print` rule for a dark export (see [wrapNoteDocument]) rather than restated here, so the
 * two cannot drift when the kit is re-synced.
 *
 * Both braces are escaped: Android's regex engine (ICU) rejects a bare `}` that the JVM's engine —
 * the one unit tests run against — accepts.
 */
private val ROOT_VARS = Regex(""":root\s*\{([^}]*)\}""")

/** Renders [markdown] and returns a complete, self-contained HTML document. */
suspend fun buildStandaloneNoteHtml(
    context: Context,
    title: String,
    markdown: String,
    dark: Boolean,
    artifactRepository: ArtifactRepository,
): String {
    val noteCss = withContext(Dispatchers.IO) {
        context.assets.open(NOTE_CSS_ASSET).bufferedReader().use { it.readText() }
    }
    val fragment = renderFragment(context, markdown, dark, artifactRepository)
    // Off the main thread: resolving an image reads the artifact cache and, for one not cached yet,
    // fetches it from the server.
    return withContext(Dispatchers.IO) {
        // The renderer emits an artifact reference as a URL path; artifactRefFor maps it back to
        // something ArtifactRepository can resolve, as the note view's request allow-list does.
        val inlined = artifactRepository.rewriteImageSrcToDataUris(fragment) { src ->
            artifactRefFor(Uri.parse(src).path.orEmpty())
        }
        wrapNoteDocument(title, inlined, dark, noteCss)
    }
}

/**
 * Drives the render kit in an off-screen WebView and returns the rendered note fragment.
 *
 * The kit's `render()` resolves only once Mermaid diagrams have been drawn, so the fragment is
 * collected in its continuation and handed back through [ExportBridge]. The WebView is never
 * attached to the view hierarchy; it is measured and laid out by hand so the page has a viewport to
 * render into.
 */
private suspend fun renderFragment(
    context: Context,
    markdown: String,
    dark: Boolean,
    artifactRepository: ArtifactRepository,
): String = withContext(Dispatchers.Main) {
    val loaded = CompletableDeferred<Unit>()
    val fragment = CompletableDeferred<String>()
    val webView = renderKitWebView(context, ExportWebViewClient(context.applicationContext, artifactRepository, loaded))
    try {
        // Before loadUrl: a JavaScript interface is only injected into pages loaded after it is
        // added. It is added to this throwaway WebView alone, never to the one showing the note.
        webView.addJavascriptInterface(ExportBridge(fragment), EXPORT_BRIDGE)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(EXPORT_VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(EXPORT_VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, EXPORT_VIEWPORT_WIDTH, EXPORT_VIEWPORT_HEIGHT)
        webView.loadUrl(RENDERER_URL)
        try {
            withTimeout(EXPORT_TIMEOUT_MS) {
                loaded.await()
                webView.evaluateJavascript(exportScript(markdown, dark), null)
                fragment.await()
            }
        } catch (_: TimeoutCancellationException) {
            // Not rethrown as a CancellationException: the caller's coroutine is alive and should
            // report the failure, not unwind as if it had been cancelled.
            throw IllegalStateException("Rendering the note timed out")
        }
    } finally {
        webView.destroy()
    }
}

/**
 * The JS pushed into the page: theme first (Mermaid reads it when rendering diagrams), then the
 * note, whose Markdown travels JSON-quoted — never spliced into HTML or JS syntax, so the kit's
 * DOMPurify gate stays the only path to the DOM, as in the on-screen view.
 *
 * The xmlns attribute is dropped from every embedded `<svg>` (icons, diagrams) as the web export
 * does: the document is opened as text/html, where the parser already places `<svg>` in the SVG
 * namespace.
 */
private fun exportScript(markdown: String, dark: Boolean): String {
    val theme = if (dark) "dark" else "light"
    val content = JSONObject.quote(rewriteLocalArtifactRefs(markdown))
    return """
        (function () {
          try {
            var note = document.getElementById('note');
            MyNotesRender.setTheme("$theme");
            MyNotesRender.render($content).then(function () {
              note.querySelectorAll('svg').forEach(function (svg) { svg.removeAttribute('xmlns'); });
              $EXPORT_BRIDGE.deliver(note.innerHTML);
            }, function (e) { $EXPORT_BRIDGE.fail(String(e)); });
          } catch (e) {
            $EXPORT_BRIDGE.fail(String(e));
          }
        })();
    """.trimIndent()
}

/**
 * Wraps a rendered note fragment in a complete HTML document. Pure, so it is unit-tested;
 * [noteCss] is the vendored render kit's stylesheet, read from app assets by the caller.
 */
internal fun wrapNoteDocument(title: String, fragmentHtml: String, dark: Boolean, noteCss: String): String {
    // A dark document still prints on white paper, where a dark background wastes ink and reads
    // poorly, so print resets the palette to note.css's own light values.
    val printLightReset = if (!dark) "" else ROOT_VARS.find(noteCss)?.let { match ->
        "@media print {\n  :root[data-theme=\"dark\"] {${match.groupValues[1]}}\n}\n"
    }.orEmpty()
    return buildString {
        append("<!DOCTYPE html>\n")
        append("<html lang=\"en\"").append(if (dark) " data-theme=\"dark\"" else "").append(">\n")
        append("<head>")
        append("<meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(escapeHtml(title)).append("</title>")
        append("<style>\n").append(noteCss).append(PAGE_STYLESHEET).append(printLightReset).append("</style>")
        append("</head>\n")
        append("<body>\n<div class=\"note-content\">\n")
        append(fragmentHtml)
        append("\n</div>\n</body>\n</html>\n")
    }
}

internal fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Reports the host page's readiness (or failure) to load; the kit itself is served by the base. */
private class ExportWebViewClient(
    context: Context,
    artifactRepository: ArtifactRepository,
    private val loaded: CompletableDeferred<Unit>,
) : RenderKitWebViewClient(context, artifactRepository) {

    override fun onPageFinished(view: WebView, url: String) {
        loaded.complete(Unit)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        // Subresource failures are the allow-list doing its job; only the host page failing to load
        // means there is nothing to render.
        if (request.isForMainFrame) {
            loaded.completeExceptionally(IllegalStateException("Could not load the renderer: ${error.description}"))
        }
    }
}

/**
 * Hands the rendered fragment back from the page. Exposed to the export WebView only, which loads
 * the app's own asset page under a CSP that permits no script but the kit's own; note content
 * reaches the DOM sanitized and cannot call this.
 */
private class ExportBridge(private val fragment: CompletableDeferred<String>) {
    @JavascriptInterface
    fun deliver(html: String) {
        fragment.complete(html)
    }

    @JavascriptInterface
    fun fail(message: String) {
        fragment.completeExceptionally(IllegalStateException("Could not render the note: $message"))
    }
}
