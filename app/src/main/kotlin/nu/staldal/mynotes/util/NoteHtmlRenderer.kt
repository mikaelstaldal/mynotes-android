package nu.staldal.mynotes.util

import android.text.TextUtils
import java.util.function.Predicate
import java.util.regex.Pattern
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.owasp.html.HtmlPolicyBuilder
import org.owasp.html.PolicyFactory

/**
 * Converts note Markdown to sanitized HTML for on-device (offline-capable) rendering.
 *
 * Mirrors the server's Goldmark extension set (tables, strikethrough, linkify — see
 * mynotes/internal/service/markdown.go) and the web frontend's DOMPurify allow-list (see
 * mynotes/web/ts/util/markdown.ts), so the same note renders equivalently across the Go
 * server, the web client, and this app. Content is already validated server-side before
 * sync, but this sanitizer runs regardless so offline-authored (not-yet-synced) content
 * and any future validator/renderer divergence can never reach the WebView unsanitized.
 */
object NoteHtmlRenderer {
    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        AutolinkExtension.create(),
    )
    private val parser = Parser.builder().extensions(extensions).build()
    private val htmlRenderer = HtmlRenderer.builder().extensions(extensions).build()

    fun renderToSanitizedHtml(markdown: String): String =
        try {
            SANITIZER_POLICY.sanitize(htmlRenderer.render(parser.parse(markdown)))
        } catch (e: StackOverflowError) {
            // Pathologically nested input; fall back to a plain, escaped rendering rather
            // than crashing. The server's write-time validator bounds nesting depth for
            // synced content — this only guards unsynced, not-yet-validated local content.
            "<pre>${TextUtils.htmlEncode(markdown)}</pre>"
        }
}

// Mirrors DOMPurify's ALLOWED_URI_REGEXP: https/mailto scheme, or no scheme (relative).
// Notably excludes data: (allowed only on <img src>/<image href>, see IMG_SRC_PATTERN).
private val LINK_HREF_PATTERN = Pattern.compile(
    "^(?:(?:https?|mailto):|[^a-z]|[a-z+.\\-]+(?:[^a-z+.\\-:]|$))",
    Pattern.CASE_INSENSITIVE,
)

// Mirrors the server/web canonical <img src> allow-list (https, relative, raster data:),
// plus local-artifact:// — an Android-only scheme for offline-attached, not-yet-uploaded
// images (see ArtifactRepository) that has no equivalent on the server or web client.
private val IMG_SRC_PATTERN = Pattern.compile(
    "^(https:|data:image/(gif|png|jpeg|webp);|local-artifact://|[^:/?#]*(?:[/?#]|$))",
    Pattern.CASE_INSENSITIVE,
)

// <textpath>/<mpath> href must reference an element in the same SVG document.
private val SVG_FRAGMENT_HREF_PATTERN = Pattern.compile("^#[\\w:.\\-]+$")

private val PROSE_ELEMENTS = arrayOf(
    "a", "abbr", "acronym", "b", "blockquote", "br", "cite", "code",
    "dd", "del", "dfn", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6",
    "hr", "i", "ins", "kbd", "li", "mark", "ol", "p", "pre", "q",
    "s", "samp", "small", "span", "strike", "strong", "sub", "sup",
    "tt", "u", "ul", "var",
)

private val TABLE_ELEMENTS = arrayOf(
    "caption", "col", "colgroup", "table", "tbody", "td", "tfoot", "th", "thead", "tr",
)

private val MISC_ELEMENTS = arrayOf(
    "details", "summary", "section", "nav", "figure", "figcaption", "img",
)

// SVG presentation and filter elements, mirroring DOMPurify's svg + svgFilters profiles
// (sans <use>, <animate>, <set>, <style>, <metadata>, <foreignObject>, <script>).
private val SVG_ELEMENTS = arrayOf(
    "svg", "g", "defs", "desc", "title", "symbol", "switch",
    "circle", "ellipse", "line", "path", "polygon", "polyline", "rect",
    "text", "tspan", "textpath", "tref",
    "image",
    "lineargradient", "radialgradient", "pattern", "stop",
    "clippath", "mask", "marker",
    "view",
    "font", "glyph", "glyphref", "hkern", "vkern",
    "altglyph", "altglyphdef", "altglyphitem",
    "animatecolor", "animatemotion", "animatetransform",
    "mpath",
    "filter",
    "feblend", "fecolormatrix", "fecomponenttransfer", "fecomposite",
    "feconvolvematrix", "fediffuselighting", "fedisplacementmap",
    "fedistantlight", "fedropshadow", "feflood",
    "fefunca", "fefuncb", "fefuncg", "fefuncr",
    "fegaussianblur", "feimage", "femerge", "femergenode",
    "femorphology", "feoffset", "fepointlight",
    "fespecularlighting", "fespotlight", "fetile", "feturbulence",
)

// MathML elements, mirroring DOMPurify's mathMl profile (sans <maction>, <semantics>,
// <annotation>, <annotation-xml>, <none>).
private val MATHML_ELEMENTS = arrayOf(
    "math", "menclose", "merror", "mfenced", "mfrac", "mglyph",
    "mi", "mlabeledtr", "mmultiscripts", "mn", "mo", "mover",
    "mpadded", "mphantom", "mroot", "mrow", "ms", "mspace",
    "msqrt", "mstyle", "msub", "msup", "msubsup", "mtable",
    "mtd", "mtext", "mtr", "munder", "munderover", "mprescripts",
)

// Non-URL attributes, allowed on any allowed element (DOMPurify's ALLOWED_ATTR/ADD_ATTR are
// flat allow-lists, not scoped per-element, so this mirrors that broader model rather than
// bluemonday's element+attribute pairing).
private val GLOBAL_ATTRIBUTES = arrayOf(
    "hreflang", "title", "alt", "height", "width",
    "cite", "datetime",
    "abbr", "align", "bgcolor", "border", "cellpadding", "cellspacing",
    "colspan", "headers", "rowspan", "scope", "span", "valign",
    "reversed", "start", "type",
    "open",
    "dir", "lang",
    // SVG geometry/presentation attributes (DOMPurify svg profile, sans "style" and "href",
    // the latter handled per-element below with scheme restrictions).
    "accent-height", "accumulate", "additive", "alignment-baseline",
    "amplitude", "ascent", "attributename", "attributetype",
    "azimuth", "basefrequency", "baseline-shift", "begin", "bias", "by",
    "class", "clip", "clippathunits", "clip-path", "clip-rule",
    "color", "color-interpolation", "color-interpolation-filters",
    "color-profile", "color-rendering",
    "cx", "cy", "d", "dx", "dy",
    "diffuseconstant", "direction", "display", "divisor", "dur",
    "edgemode", "elevation", "end", "exponent",
    "fill", "fill-opacity", "fill-rule", "filter", "filterunits",
    "flood-color", "flood-opacity",
    "font-family", "font-size", "font-size-adjust", "font-stretch",
    "font-style", "font-variant", "font-weight",
    "fx", "fy", "g1", "g2", "glyph-name", "glyphref",
    "gradientunits", "gradienttransform",
    "image-rendering", "in", "in2", "intercept",
    "k", "k1", "k2", "k3", "k4", "kerning",
    "keypoints", "keysplines", "keytimes",
    "lengthadjust", "letter-spacing",
    "kernelmatrix", "kernelunitlength", "lighting-color", "local",
    "marker-end", "marker-mid", "marker-start",
    "markerheight", "markerunits", "markerwidth",
    "maskcontentunits", "maskunits", "mask", "mask-type",
    "media", "method", "mode", "numoctaves",
    "offset", "operator", "opacity", "order",
    "orient", "orientation", "origin", "overflow",
    "paint-order", "path", "pathlength",
    "patterncontentunits", "patterntransform", "patternunits",
    "points", "preservealpha", "preserveaspectratio", "primitiveunits",
    "r", "rx", "ry", "radius", "refx", "refy",
    "repeatcount", "repeatdur", "restart", "result", "rotate",
    "scale", "seed", "shape-rendering", "slope",
    "specularconstant", "specularexponent", "spreadmethod",
    "startoffset", "stddeviation", "stitchtiles",
    "stop-color", "stop-opacity",
    "stroke-dasharray", "stroke-dashoffset", "stroke-linecap",
    "stroke-linejoin", "stroke-miterlimit", "stroke-opacity",
    "stroke", "stroke-width",
    "surfacescale", "systemlanguage", "tablevalues", "targetx", "targety",
    "transform", "transform-origin",
    "text-anchor", "text-decoration", "text-rendering", "textlength",
    "u1", "u2", "unicode", "values",
    "viewbox", "visibility", "version",
    "vert-adv-y", "vert-origin-x", "vert-origin-y",
    "word-spacing", "wrap", "writing-mode",
    "xchannelselector", "ychannelselector",
    "x", "x1", "x2", "xmlns", "y", "y1", "y2", "z", "zoomandpan",
    // MathML-specific attributes (DOMPurify mathMl profile).
    "accent", "accentunder", "bevelled", "close",
    "columnalign", "columnlines", "columnspacing", "columnspan",
    "denomalign", "displaystyle", "encoding",
    "fence", "frame", "largeop", "length", "linethickness",
    "lquote", "lspace", "mathbackground", "mathcolor",
    "mathsize", "mathvariant", "maxsize", "minsize", "movablelimits",
    "notation", "numalign", "rowalign", "rowlines", "rowspacing",
    "rspace", "rquote", "scriptlevel", "scriptminsize",
    "scriptsizemultiplier", "selection", "separator", "separators",
    "stretchy", "subscriptshift", "supscriptshift", "symmetric", "voffset",
)

// AttributeBuilder.matching(Pattern) requires a *full* match (Matcher.matches()), but
// LINK_HREF_PATTERN/IMG_SRC_PATTERN are deliberately anchored only at the start (mirroring
// DOMPurify's partial-match ALLOWED_URI_REGEXP semantics) — a scheme prefix is enough to decide
// allow/deny, the rest of the URL is unconstrained. Match via find() instead of matches() so
// the leading "^" is honored without requiring the whole value to satisfy the pattern.
private fun matchingPrefix(pattern: Pattern) = Predicate<String> { pattern.matcher(it).find() }

private val SANITIZER_POLICY: PolicyFactory = HtmlPolicyBuilder()
    .allowElements(*PROSE_ELEMENTS)
    .allowElements(*TABLE_ELEMENTS)
    .allowElements(*MISC_ELEMENTS)
    .allowElements(*SVG_ELEMENTS)
    .allowElements(*MATHML_ELEMENTS)
    .allowAttributes(*GLOBAL_ATTRIBUTES).globally()
    .allowAttributes("href").matching(matchingPrefix(LINK_HREF_PATTERN)).onElements("a")
    .allowAttributes("src").matching(matchingPrefix(IMG_SRC_PATTERN)).onElements("img")
    .allowAttributes("href").matching(matchingPrefix(IMG_SRC_PATTERN)).onElements("image")
    .allowAttributes("href").matching(SVG_FRAGMENT_HREF_PATTERN).onElements("textpath", "mpath")
    // "href"/"src"/"cite" are treated as URL attributes internally and are gated by this
    // protocol allow-list *in addition to* the per-element patterns above (both must pass) —
    // this union just has to be a superset; the tighter per-element patterns above do the real
    // scheme restriction (e.g. "data:" is allowed here but still rejected by LINK_HREF_PATTERN
    // on <a href>).
    .allowUrlProtocols("https", "http", "mailto", "data", "local-artifact")
    .toFactory()
