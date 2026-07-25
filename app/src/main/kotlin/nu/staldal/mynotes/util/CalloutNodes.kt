package nu.staldal.mynotes.util

import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode

/**
 * Custom commonmark AST nodes for the application-specific icon/box/callout transforms (see
 * mynotes/markdown-spec.md, "Inline icons, boxes, and callouts"). They mirror the web client's
 * markdown-it token manipulation (mynotes/web/ts/util/markdown.ts) but expressed as AST nodes, so
 * they survive to render time where [CalloutNodeRenderer] turns them into HTML. All three are gated
 * by the same OWASP sanitizer as every other node in [NoteHtmlRenderer].
 */

/**
 * An inline Lucide icon produced from the `[!name]` syntax by [IconLinkProcessor].
 *
 * [svg] is the pre-rendered inline `<svg>` (from [LucideIcons.renderIconSvg], stroke `currentColor`
 * so it follows the surrounding text colour). [alias] / [family] are non-null only when `name`
 * resolved through the callout [IconLinkProcessor.ICON_ALIASES] table (never for the explicit
 * `[!lucide-<name>]` form, which carries no colour/callout semantics); [CalloutProcessor] reads them
 * back to build callout boxes and tint paragraphs.
 */
class IconNode(val svg: String, val alias: String?, val family: String?) : CustomNode()

/**
 * A rendered box / foldable box / callout — replaces a [org.commonmark.node.BlockQuote] that carried
 * a `>*`/`>-`/`>+` marker or an alias icon on its first line (see [CalloutProcessor]).
 *
 * [tag] is `blockquote` for a static box or `details` for a foldable one; [cssClass] carries the
 * `callout …` classes the note CSS styles; [open] adds the `open` attribute (an expanded `>+`
 * foldable). Its children are a [CalloutTitle] followed by the body block content.
 *
 * A [CustomBlock] (not [CustomNode]) so it can hold block children — commonmark requires a block's
 * parent to also be a block.
 */
class CalloutBlock(val tag: String, val cssClass: String, val open: Boolean) : CustomBlock()

/** The title row of a [CalloutBlock]: [tag] is `summary` when foldable, else `p`. */
class CalloutTitle(val tag: String) : CustomBlock()
