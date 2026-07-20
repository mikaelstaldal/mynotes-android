package nu.staldal.mynotes.util

/**
 * Provides the JavaScript needed to render ```mermaid diagrams inside the note WebView.
 *
 * A ` ```mermaid ` fenced code block survives Markdown rendering and the [NoteHtmlRenderer]
 * sanitizer as `<pre><code class="language-mermaid">…escaped source…</code></pre>` (the `class`
 * attribute is allow-listed; the source is HTML-escaped text). Mermaid rendering needs a live DOM
 * and a JS engine, so — unlike the icon/MathML inlining that happens in the synchronous Kotlin
 * pipeline — it runs in the WebView after the HTML is loaded, mirroring the web client
 * (mynotes/web/ts/util/mermaid.ts renderMermaidBlocks).
 *
 * The Mermaid engine is large (~2.7 MB) and only needed when a note actually contains a diagram, so
 * the WebView keeps JavaScript disabled and the strict CSP for the common note with no diagrams (see
 * NoteDetailScreen); only when [containsDiagram] is true does the caller enable JS, relax the CSP to
 * permit the injected scripts, and inline [scriptTags].
 *
 * The engine is a verbatim copy of the same Mermaid package the web client renders with
 * (app/src/main/resources/mermaid/mermaid.min.js; regenerate via tools/gen-mermaid.sh), so on-device
 * diagrams can never drift from the web previews. It is the global build, which assigns
 * `globalThis.mermaid` when evaluated as a classic script.
 */
object MermaidRenderer {
    private const val RESOURCE = "/mermaid/mermaid.min.js"

    // The vendored engine source, read once from the classpath on first use (and only when a note
    // actually contains a diagram — see the call sites) and cached for the process lifetime.
    private val engineJs: String by lazy { loadEngine() }

    /** True when the rendered, sanitized note HTML contains at least one ```mermaid block. */
    fun containsDiagram(html: String): Boolean = html.contains("class=\"language-mermaid\"")

    /**
     * The `<script>` markup to inline at the end of the note `<body>`: the Mermaid engine followed by
     * a small driver that renders every `<pre><code class="language-mermaid">` block in place. [dark]
     * selects Mermaid's built-in dark or default theme so diagrams follow the app's light/dark theme.
     *
     * Rendering mirrors the web client: initialize with `securityLevel: 'strict'` (Mermaid sanitizes
     * diagram labels with its own bundled DOMPurify) and `htmlLabels: false` (emit SVG `<text>`, not
     * HTML `<foreignObject>`). A block that is invalid or fails to render is left as its original
     * source rather than throwing, so a note always renders.
     */
    fun scriptTags(dark: Boolean): String {
        val theme = if (dark) "dark" else "default"
        return "<script>$engineJs</script>\n<script>${driverJs(theme)}</script>"
    }

    // Kept in sync with mynotes/web/ts/util/mermaid.ts. Written without ES module syntax so it runs as
    // a classic inline <script>; `mermaid` is the global the engine bundle installs.
    private fun driverJs(theme: String): String = """
        (function () {
          var blocks = document.querySelectorAll('pre > code.language-mermaid');
          if (!blocks.length || typeof mermaid === 'undefined') return;
          mermaid.initialize({
            startOnLoad: false,
            securityLevel: 'strict',
            theme: '$theme',
            suppressErrorRendering: true,
            htmlLabels: false,
            flowchart: { htmlLabels: false }
          });
          var seq = 0;
          Array.prototype.forEach.call(blocks, function (code) {
            var pre = code.parentElement;
            if (!pre) return;
            var source = code.textContent || '';
            // Validate first: mermaid.parse with suppressErrors returns false instead of throwing on
            // a malformed diagram, which we leave as plain code.
            Promise.resolve(mermaid.parse(source, { suppressErrors: true })).then(function (valid) {
              if (!valid) return;
              return mermaid.render('mermaid-' + (++seq), source).then(function (res) {
                var wrapper = document.createElement('div');
                wrapper.className = 'mermaid-diagram';
                wrapper.innerHTML = res.svg;
                pre.replaceWith(wrapper);
              });
            }).catch(function () {
              // Parsed but failed to render: keep the source visible, flagged for CSS.
              pre.classList.add('mermaid-error');
            });
          });
        })();
    """.trimIndent()

    private fun loadEngine(): String {
        val stream = MermaidRenderer::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing bundled Mermaid engine: $RESOURCE")
        return stream.reader(Charsets.UTF_8).use { it.readText() }
    }
}
