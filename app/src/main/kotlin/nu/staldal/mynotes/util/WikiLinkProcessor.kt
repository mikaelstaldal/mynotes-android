package nu.staldal.mynotes.util

import java.util.regex.Pattern
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.InlineParserContext
import org.commonmark.parser.beta.LinkInfo
import org.commonmark.parser.beta.LinkProcessor
import org.commonmark.parser.beta.LinkResult
import org.commonmark.parser.beta.Scanner

/**
 * Renders internal wikilinks — the application-specific `[[...]]` syntax that is not part of
 * CommonMark or GFM (see the "Internal wikilinks" section of mynotes/openapi.yaml). Mirrors the web
 * client's markdown-it rule (mynotes/web/ts/util/markdown.ts) so the same note renders equivalently:
 *
 *   [[slug]]          -> a note      (`mynotes://note/<slug>`), display "slug"
 *   [[slug|Display]]  -> a note,     display "Display"
 *   [[#slug]]         -> a tag list  (`mynotes://tag/<slug>`),  display "#slug"
 *   [[#slug|Display]] -> a tag list, display "Display"
 *
 * The slug must match the API slug pattern; the optional label may be any run of characters except
 * `]` and newline. A `[[...]]` that does not match these forms is left as literal text. The
 * synthesized `mynotes://` links are resolved to in-app navigation by the note detail WebView (see
 * NoteDetailScreen) and are never fetched; the `[[` / `]]` delimiters never collide with Markdown,
 * HTML, SVG or MathML (none of which use them).
 *
 * commonmark-java offers no pluggable hook for the leading `[` — the core bracket parser consumes it
 * before any custom inline parser runs — so this piggybacks on the LinkProcessor API instead: `[[x]]`
 * parses as an outer `[` followed by the shortcut link `[x]`, and this processor rewrites that
 * shortcut into the wikilink and drops the stray outer `[`. It inspects the *raw* bracket text, so a
 * label is taken verbatim (e.g. `[[a|*x*]]` keeps the literal asterisks), matching the web client.
 * Code spans and code blocks parse to Code / *CodeBlock nodes rather than links, so a `[[x]]` inside
 * them never reaches this processor and stays literal.
 */
object WikiLinkProcessor : LinkProcessor {
    // Sigil, slug, optional label — mirrors WIKI_LINK_RE in the web client. Anchored to a full match
    // because the raw bracket text is exactly the content between the inner `[` `]`.
    private val WIKI_LINK = Pattern.compile("^(#?)([a-z0-9]+(?:-[a-z0-9]+)*)(?:\\|([^\\]\\n]+))?$")

    override fun process(linkInfo: LinkInfo, scanner: Scanner, context: InlineParserContext): LinkResult? {
        // Only a bare shortcut bracket `[text]` can be a wikilink — skip images (marker), inline
        // links (destination) and full/collapsed reference links (label).
        if (linkInfo.marker() != null || linkInfo.destination() != null || linkInfo.label() != null) {
            return LinkResult.none()
        }

        // Require a second `[` immediately before the opening bracket so this only fires on `[[...]]`.
        val preceding = linkInfo.openingBracket()?.previous
        if (preceding !is Text || preceding.literal != "[") return LinkResult.none()

        val matcher = WIKI_LINK.matcher(linkInfo.text())
        if (!matcher.matches()) return LinkResult.none()

        // The shortcut link consumed the first `]`; require the second to complete the `]]` closer.
        val closeStart = scanner.position()
        if (!scanner.next(']')) {
            scanner.setPosition(closeStart)
            return LinkResult.none()
        }

        val isTag = matcher.group(1) == "#"
        val slug = matcher.group(2)
        val label = matcher.group(3)
        val destination = if (isTag) "mynotes://tag/$slug" else "mynotes://note/$slug"
        val display = label ?: if (isTag) "#$slug" else slug

        preceding.unlink() // drop the stray outer `[`
        val link = Link(destination, null).apply { appendChild(Text(display)) }
        return LinkResult.replaceWith(link, scanner.position())
    }
}
