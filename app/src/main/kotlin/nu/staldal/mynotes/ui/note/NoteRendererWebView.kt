package nu.staldal.mynotes.ui.note

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.runBlocking
import nu.staldal.mynotes.data.ArtifactRepository
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Displays a note by driving the vendored MyNotes render kit in a WebView.
 *
 * The kit (app/src/main/assets/renderer/, refreshed by tools/sync-renderer.sh) is the web client's
 * own Markdown pipeline — markdown-it → DOMPurify, plus Mermaid, AsciiMath, inline Lucide icons,
 * emoji shortcodes, callouts and wikilinks — packaged as a static page. Rendering is therefore
 * identical to the web UI by construction, with no Kotlin implementation of the dialect to keep in
 * step. See the mynotes repo's spec/REQUIREMENTS.md ("Shared render kit").
 *
 * Data flows one way in and one way out:
 *  - **in**: Markdown and the theme are pushed through the page's JS API with [WebView.evaluateJavascript].
 *    Arguments are JSON-quoted, so note content is never spliced into HTML or into JS syntax; the
 *    kit's DOMPurify gate remains the only path from note content to the DOM.
 *  - **out**: taps surface through [WebViewClient.shouldOverrideUrlLoading] (see [NoteWebViewClient]).
 *
 * Everything the page loads is served from app assets or the local artifact cache — see
 * [NoteWebViewClient.shouldInterceptRequest], which is an allow-list.
 */

/** Origin [WebViewAssetLoader] serves app assets from; the WebView's real origin for this page. */
private const val ASSET_HOST = "appassets.androidplatform.net"

/** The render kit's host page. Its relative imports resolve under /assets/renderer/. */
internal const val RENDERER_URL = "https://$ASSET_HOST/assets/renderer/render/index.html"

/**
 * Root-relative path the note Markdown's `local-artifact://<id>` references are rewritten to before
 * rendering. The renderer's URL allow-list (DOMPurify) drops unknown schemes but keeps relative
 * URLs, so a locally-attached image that hasn't been uploaded yet has to travel as a path and be
 * resolved back in [NoteWebViewClient.shouldInterceptRequest].
 */
private const val LOCAL_ARTIFACT_PATH = "/local-artifact/"

private val LOCAL_ARTIFACT_PATH_REF = Regex("^$LOCAL_ARTIFACT_PATH([\\w-]+)$")
private val REMOTE_ARTIFACT_PATH_REF = Regex("^(?:.*/)?api/v1/artifacts/([0-9a-f]{64})$")

/** Served for any request the allow-list rejects, so nothing silently reaches the network. */
private fun blockedResponse() =
    WebResourceResponse(null, null, 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/**
 * Rewrites `local-artifact://<id>` image references to [LOCAL_ARTIFACT_PATH] so they survive the
 * renderer's sanitization (see [LOCAL_ARTIFACT_PATH]). Applied to the Markdown, not the rendered
 * HTML — the app no longer produces HTML.
 */
internal fun rewriteLocalArtifactRefs(markdown: String): String =
    markdown.replace("local-artifact://", LOCAL_ARTIFACT_PATH)

/**
 * The reference [ArtifactRepository.resolveImage] understands for an image request path, or null
 * when the path is not an image this app can resolve — in which case the request is blocked.
 *
 * Matching is on the path alone, so an artifact reference resolves whether the note stores it
 * root-relative or absolute (uploads rewrite placeholders to an absolute URL against the server's
 * base, see [ArtifactRepository.uploadPendingArtifactsAndRewrite]).
 *
 * Icon requests (`/api/v1/icons/lucide/<name>`) are deliberately not matched: the renderer draws
 * every icon it knows inline as `<svg>` from the same vendored geometry the server serves, so a
 * request only escapes for a name the vendored kit does not have — which renders broken until
 * tools/sync-renderer.sh picks up a newer kit.
 */
internal fun artifactRefFor(path: String): String? {
    LOCAL_ARTIFACT_PATH_REF.find(path)?.let { return "local-artifact://${it.groupValues[1]}" }
    REMOTE_ARTIFACT_PATH_REF.find(path)?.let { return "/api/v1/artifacts/${it.groupValues[1]}" }
    return null
}

@Composable
fun NoteRendererWebView(
    markdown: String,
    dark: Boolean,
    background: Color,
    onBackground: Color,
    linkColor: Color,
    artifactRepository: ArtifactRepository,
    onNavigateToNote: (String) -> Unit,
    onNavigateToTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pushed to the page once it has loaded, and on every later change.
    val script = renderScript(markdown, dark, background, onBackground, linkColor)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            renderKitWebView(
                ctx,
                NoteWebViewClient(ctx.applicationContext, artifactRepository, onNavigateToNote, onNavigateToTag),
            ).apply { loadUrl(RENDERER_URL) }
        },
        update = { webView ->
            val client = webView.webViewClient as NoteWebViewClient
            client.pending = script
            // evaluateJavascript before the page has finished loading is dropped, so the first push
            // is made by onPageFinished instead.
            if (client.loaded) webView.evaluateJavascript(script, null)
        },
        onRelease = { it.destroy() },
    )
}

/**
 * The JS pushed into the page: theme first, then content.
 *
 * The app's Material colours are passed as CSS custom-property overrides so the note blends into the
 * surrounding app chrome, while every other aspect of the styling (callout accents, code blocks,
 * tables) stays the canonical one from the kit's note.css. Overrides persist until replaced, so all
 * of them are sent on every call.
 */
private fun renderScript(
    markdown: String,
    dark: Boolean,
    background: Color,
    onBackground: Color,
    linkColor: Color,
): String {
    val vars = JSONObject(
        mapOf(
            "--bg" to background.toCssHex(),
            "--fg" to onBackground.toCssHex(),
            "--primary" to linkColor.toCssHex(),
        )
    )
    val theme = if (dark) "dark" else "light"
    val content = JSONObject.quote(rewriteLocalArtifactRefs(markdown))
    return "MyNotesRender.setTheme(\"$theme\", $vars); MyNotesRender.render($content);"
}

private fun Color.toCssHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * A WebView configured to host the render kit, with [client] serving it. The caller loads
 * [RENDERER_URL] — after any [WebView.addJavascriptInterface], which only takes effect for pages
 * loaded afterwards. Shared by the on-screen note view and the HTML export (see NoteHtmlExport.kt),
 * so both run the kit under the same settings and the same request allow-list.
 */
internal fun renderKitWebView(context: android.content.Context, client: RenderKitWebViewClient): WebView =
    WebView(context).apply {
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // The renderer is JavaScript; unlike the previous Kotlin-rendered document there is
        // no no-JS mode. The page's own Content-Security-Policy plus the request allow-list
        // below keep it to app assets and locally-resolved images.
        settings.javaScriptEnabled = true
        // Keep target="_blank" links (the renderer marks external links so) coming through
        // shouldOverrideUrlLoading rather than trying to open a second window.
        settings.setSupportMultipleWindows(false)
        webViewClient = client
    }

/**
 * Serves the render kit and the note's images to a WebView hosting the kit.
 *
 * [shouldInterceptRequest] is an **allow-list**: only the kit's own asset files and image references
 * the app can resolve locally are answered; everything else is blocked. So rendering a note never
 * produces an unauthenticated network request, and a note embedding a third-party image cannot phone
 * home — the same posture the app had when it rendered notes itself.
 *
 * Navigation is blocked outright; [NoteWebViewClient] overrides that to route taps in the note view.
 */
internal open class RenderKitWebViewClient(
    context: android.content.Context,
    private val artifactRepository: ArtifactRepository,
) : WebViewClient() {

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    final override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val path = uri.path ?: return blockedResponse()

        if (uri.host == ASSET_HOST && path.startsWith("/assets/")) {
            // The kit's own files (host page, compiled modules, vendored bundles, stylesheet).
            return assetLoader.shouldInterceptRequest(uri) ?: blockedResponse()
        }

        // An image the note references; anything else is blocked.
        val ref = artifactRefFor(path) ?: return blockedResponse()
        // Runs on a background thread, so blocking here is safe; resolveImage serves from the local
        // cache when it can and only reaches the network when online.
        val resolved = runBlocking { artifactRepository.resolveImage(ref) } ?: return blockedResponse()
        return WebResourceResponse(
            resolved.contentType.substringBefore(';'),
            null,
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(resolved.bytes),
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
}

/** Adds tap routing and the content push to [RenderKitWebViewClient], for the on-screen note view. */
private class NoteWebViewClient(
    context: android.content.Context,
    artifactRepository: ArtifactRepository,
    private val onNavigateToNote: (String) -> Unit,
    private val onNavigateToTag: (String) -> Unit,
) : RenderKitWebViewClient(context, artifactRepository) {

    /** The script to push once the page is ready; also re-pushed after a reload. */
    var pending: String? = null

    /** Whether the host page has finished loading, so evaluateJavascript will not be dropped. */
    var loaded: Boolean = false
        private set

    override fun onPageFinished(view: WebView, url: String) {
        loaded = true
        pending?.let { view.evaluateJavascript(it, null) }
    }

    /**
     * Routes taps inside the rendered note. The renderer emits the same URLs as the web UI, so
     * wikilinks arrive as root-relative `/notes/<slug>` and `/tags/<slug>` against the asset origin
     * and navigate within the app; http(s)/mailto links open in an external app instead of loading
     * in place — the WebView only ever hosts the render kit. Anything else is blocked.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.host == ASSET_HOST) {
            internalTarget(uri)?.let { (kind, slug) ->
                when (kind) {
                    "notes" -> onNavigateToNote(slug)
                    "tags" -> onNavigateToTag(slug)
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

    /** `("notes"|"tags", slug)` for an in-app wikilink URL, or null for any other same-origin URL. */
    private fun internalTarget(uri: Uri): Pair<String, String>? {
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val kind = segments[0]
        if (kind != "notes" && kind != "tags") return null
        return kind to segments[1]
    }
}
