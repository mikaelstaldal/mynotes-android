package nu.staldal.mynotes.util

import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * Inlines built-in Lucide icon references in rendered note HTML.
 *
 * The backend and web UI store a built-in icon as a compact Markdown image reference,
 * `![name](<base>/api/v1/icons/lucide/<name>)`. Rendered as a plain `<img>` it would load the
 * server-served SVG in its own document context — which needs the network/credentials and bakes in
 * a fixed grey stroke that can't follow the app's light/dark theme. Instead, like the web client
 * (mynotes/web/ts/util/markdown.ts) and the server's HTML export (mynotes/internal/icons/icons.go
 * GetInline), this replaces the `<img>` with the icon's inline `<svg>` whose stroke is
 * `currentColor`, so it matches the surrounding foreground and follows the theme.
 *
 * The icon geometry is a vendored copy of the same `LUCIDE_ICON_NODES` object the web bundle
 * embeds (app/src/main/resources/lucide/lucide-icon-nodes.json; regenerate via
 * tools/gen-lucide-icons.sh), so the app's inlined icons can never drift from the server/web ones.
 * The produced `<svg>` uses only elements/attributes already on the [NoteHtmlRenderer] sanitizer
 * allow-list, and is re-sanitized there regardless — the geometry is trusted vendored data, but the
 * sanitizer remains the sole gate.
 */
object LucideIcons {
    private const val RESOURCE = "/lucide/lucide-icon-nodes.json"

    // name -> array of [tag, {attrs}] children, mirroring Lucide's IconNode shape. Parsed lazily on
    // first use (and only when a note actually references an icon — see [inlineIcons]) from the
    // vendored resource on the classpath, then cached for the process lifetime.
    private val iconNodes: Map<String, JsonArray> by lazy { loadIconNodes() }

    // Matches an <img …> tag so its src can be inspected; the whole tag is replaced when it names a
    // known icon. commonmark's HtmlRenderer always double-quotes attributes.
    private val IMG_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val IMG_SRC = Regex("""\bsrc="([^"]*)"""", RegexOption.IGNORE_CASE)

    // Anchors on the path suffix so it matches root-relative, basepath-prefixed and absolute srcs
    // (query/fragment trimmed first), mirroring the server's iconSrcPattern and the web ICON_SRC_RE.
    private val ICON_SRC = Regex("""(?:^|/)api/v1/icons/lucide/([a-z0-9]+(?:-[a-z0-9]+)*)$""")

    /**
     * Replaces every `<img>` referencing a known built-in Lucide icon with that icon's inline
     * `<svg>`. Non-`<img>` markup, non-icon images, and unknown icon names are left untouched (an
     * unknown name stays an `<img>` and simply renders as a broken image), mirroring the web
     * client's fall-through to the default image rendering.
     */
    fun inlineIcons(html: String): String {
        // Cheap bail-out: skip the (lazy) geometry load entirely for the common note with no icons.
        if (!html.contains("/api/v1/icons/lucide/")) return html
        return IMG_TAG.replace(html) { tag ->
            val src = IMG_SRC.find(tag.value)?.groupValues?.get(1) ?: return@replace tag.value
            val name = iconNameFromSrc(src) ?: return@replace tag.value
            renderIconSvg(name) ?: tag.value
        }
    }

    /** Extracts the icon name from an internal icon image src, or null when it isn't one. */
    fun iconNameFromSrc(src: String): String? {
        val path = src.substringBefore('?').substringBefore('#')
        return ICON_SRC.find(path)?.groupValues?.get(1)
    }

    /**
     * Builds the inline `<svg>` markup for a Lucide icon from the vendored geometry, or null for an
     * unknown name. Matches the server's inline form (GetInline): a 24×24 viewBox, `currentColor`
     * stroke, no `xmlns` (redundant inline in HTML).
     */
    fun renderIconSvg(name: String): String? {
        val children = iconNodes[name] ?: return null
        val sb = StringBuilder(256)
        // viewBox is spelled lowercase deliberately: the OWASP sanitizer (NoteHtmlRenderer) matches
        // attribute names case-sensitively against its lower-cased allow-list, so a camelCase
        // "viewBox" would be dropped. Emitting "viewbox" survives sanitization, and the WebView's
        // HTML5 parser normalizes it back to "viewBox" for the SVG foreign element when rendering.
        sb.append("<svg width=\"24\" height=\"24\" viewbox=\"0 0 24 24\" fill=\"none\" ")
            .append("stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" ")
            .append("stroke-linejoin=\"round\" class=\"lucide lucide-").append(name).append("\">")
        for (child in children) {
            val pair = child.asJsonArray
            val tag = pair[0].asString
            val attrs = pair[1].asJsonObject
            sb.append('<').append(tag)
            for ((key, value) in attrs.entrySet()) {
                sb.append(' ').append(key).append("=\"").append(escapeAttr(value.asString)).append('"')
            }
            // Explicit close tag rather than self-closing "/>": SVG shape elements aren't void in
            // HTML, so the sanitizer's (and WebView's) HTML parser ignores the self-close and nests
            // every following child inside the first one — leaving only the first shape rendered.
            sb.append("></").append(tag).append('>')
        }
        sb.append("</svg>")
        return sb.toString()
    }

    // Mirrors escapeAttr in mynotes/web/ts/vendor/gen-lucide.mjs and the Go server (& first).
    private fun escapeAttr(v: String): String = v
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun loadIconNodes(): Map<String, JsonArray> {
        val stream = LucideIcons::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing bundled Lucide icon geometry: $RESOURCE")
        val root = stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it) }.asJsonObject
        return root.entrySet().associate { (name, nodes) -> name to nodes.asJsonArray }
    }
}
