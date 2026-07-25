package nu.staldal.mynotes.util

import java.util.regex.Pattern
import org.commonmark.parser.InlineParserContext
import org.commonmark.parser.beta.LinkInfo
import org.commonmark.parser.beta.LinkProcessor
import org.commonmark.parser.beta.LinkResult
import org.commonmark.parser.beta.Scanner

/** A callout alias: the Lucide icon it renders and the colour family it tints/boxes with. */
data class IconAlias(val icon: String, val family: String)

/**
 * Renders inline icons — the application-specific `[!name]` syntax that is not part of CommonMark or
 * GFM (see mynotes/markdown-spec.md, "Inline icons"). Mirrors the web client's `icon` inline rule
 * (mynotes/web/ts/util/markdown.ts):
 *
 *   `[!warning]`        -> the `warning` callout alias (triangle-alert icon, amber family)
 *   `[!rocket]`         -> the literal Lucide "rocket" icon (no alias -> no colour/callout semantics)
 *   `[!lucide-summary]` -> the literal Lucide "summary" icon, bypassing the `summary` alias
 *
 * `name` resolves to a callout [ICON_ALIASES] entry, or (failing that) any built-in Lucide icon. The
 * explicit `[!lucide-<name>]` form always renders the literal Lucide icon `<name>`, skipping the
 * alias table. An unrecognized name (neither alias nor Lucide icon) is left as literal text.
 *
 * Like [WikiLinkProcessor], this piggybacks on the [LinkProcessor] API because commonmark-java's core
 * bracket parser consumes the leading `[` before any custom inline parser runs: `[!name]` parses as a
 * shortcut link whose text is `!name`, which this rewrites into an [IconNode]. Alias icons carry their
 * alias/family so [CalloutProcessor] can build callout boxes and tint paragraphs; the explicit
 * `[!lucide-…]` form carries neither. The produced `<svg>` (from [LucideIcons]) is re-sanitized in
 * [NoteHtmlRenderer] regardless.
 */
object IconLinkProcessor : LinkProcessor {
    // Alias -> Lucide icon + colour family, a verbatim mirror of ICON_ALIASES in the web client
    // (mynotes/web/ts/util/markdown.ts) and the spec's alias table. Used here to resolve `[!alias]`
    // and by CalloutProcessor (via IconNode.family) to colour callouts and tinted paragraphs.
    val ICON_ALIASES: Map<String, IconAlias> = mapOf(
        "note" to IconAlias("pencil", "blue"),
        "info" to IconAlias("info", "blue"),
        "todo" to IconAlias("circle-check", "blue"),
        "tip" to IconAlias("flame", "green"),
        "hint" to IconAlias("flame", "green"),
        "important" to IconAlias("flame", "green"),
        "success" to IconAlias("check", "green"),
        "check" to IconAlias("check", "green"),
        "done" to IconAlias("check", "green"),
        "question" to IconAlias("circle-question-mark", "cyan"),
        "help" to IconAlias("circle-question-mark", "cyan"),
        "faq" to IconAlias("circle-question-mark", "cyan"),
        "warning" to IconAlias("triangle-alert", "amber"),
        "caution" to IconAlias("triangle-alert", "amber"),
        "attention" to IconAlias("triangle-alert", "amber"),
        "failure" to IconAlias("x", "red"),
        "fail" to IconAlias("x", "red"),
        "missing" to IconAlias("x", "red"),
        "danger" to IconAlias("zap", "red"),
        "error" to IconAlias("zap", "red"),
        "bug" to IconAlias("bug", "red"),
        "abstract" to IconAlias("clipboard-list", "gray"),
        "summary" to IconAlias("clipboard-list", "gray"),
        "tldr" to IconAlias("clipboard-list", "gray"),
        "example" to IconAlias("list", "gray"),
        "quote" to IconAlias("quote", "gray"),
        "cite" to IconAlias("quote", "gray"),
    )

    private const val LUCIDE_PREFIX = "lucide-"

    // The `!name` inside the shortcut bracket — mirrors the web client's ICON_RE (`^\[!([a-zA-Z][\w-]*)\]`).
    // Anchored to a full match because the shortcut text is exactly the content between `[` and `]`.
    private val ICON_TEXT = Pattern.compile("^!([a-zA-Z][\\w-]*)$")

    override fun process(linkInfo: LinkInfo, scanner: Scanner, context: InlineParserContext): LinkResult? {
        // Only a bare shortcut bracket `[!name]` can be an icon — skip images (marker), inline links
        // (destination) and full/collapsed reference links (label).
        if (linkInfo.marker() != null || linkInfo.destination() != null || linkInfo.label() != null) {
            return LinkResult.none()
        }

        val matcher = ICON_TEXT.matcher(linkInfo.text())
        if (!matcher.matches()) return LinkResult.none()

        val name = matcher.group(1)!!.lowercase() // group 1 is required, so non-null after a match
        val explicit = name.startsWith(LUCIDE_PREFIX)
        val alias = if (explicit) null else ICON_ALIASES[name]
        val iconName = when {
            explicit -> name.removePrefix(LUCIDE_PREFIX)
            alias != null -> alias.icon
            else -> name
        }
        val svg = LucideIcons.renderIconSvg(iconName) ?: return LinkResult.none() // unknown -> literal

        val node = IconNode(svg, alias?.let { name }, alias?.family)
        return LinkResult.replaceWith(node, scanner.position())
    }
}
