package nu.staldal.mynotes.util

import org.commonmark.node.HtmlInline
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner

/**
 * Parses AsciiMath math spans and converts them to MathML at render time, mirroring the web client's
 * markdown-it rules (see the `math_inline` / `math_display` / `math_block` rules in
 * mynotes/web/ts/util/markdown.ts and the "Math (AsciiMath)" section of mynotes/openapi.yaml):
 *
 *   `$…$`   → inline MathML  (`<math display="inline">`)
 *   `$$…$$` → display MathML (`<math display="block">`), on one line or spanning several
 *
 * The AsciiMath source is stored verbatim in the note `content`; conversion to MathML happens here at
 * render time via [AsciiMath], and the produced `<math>` element flows through the same OWASP
 * sanitizer as all other output in [NoteHtmlRenderer]. The result is emitted as a raw [HtmlInline]
 * node so commonmark passes the MathML through unchanged (the sanitizer is the final gate).
 *
 * Delimiter rules match the web client:
 *  - An opening `$` must not be immediately followed by whitespace; a closing `$` must not be
 *    immediately preceded by whitespace nor immediately followed by a digit — so currency like
 *    `$5 and $10` stays literal text rather than an (empty) math span.
 *  - A `$` preceded by an odd number of backslashes is escaped and cannot delimit math. (A `\$` in
 *    ordinary text is already turned into a literal `$` by commonmark's backslash escaping before
 *    this parser is triggered, so escaped dollars never open math.)
 *  - Empty/blank content is rejected (left literal).
 *
 * commonmark's [Scanner] spans the whole block (yielding `\n` between lines), so a single `$`-triggered
 * parser handles inline `$…$`, one-line `$$…$$`, and multi-line `$$…$$` alike. `$…$` inside a code
 * span or code block never reaches this parser (those are parsed by other rules), so it stays literal.
 */
class MathInlineParser : InlineContentParser {

    override fun tryParse(state: InlineParserState): ParsedInline? {
        val scanner = state.scanner()
        val start = scanner.position()
        scanner.next() // consume the opening '$'
        return if (scanner.peek() == '$') {
            scanner.next() // consume the second '$'
            parseDisplay(scanner, start)
        } else {
            parseInline(scanner, start)
        }
    }

    /** Parse `$$…$$` (display math). The scanner is positioned just after the opening `$$`. */
    private fun parseDisplay(scanner: Scanner, start: org.commonmark.parser.beta.Position): ParsedInline? {
        val contentStart = scanner.position()
        var backslashes = 0
        while (true) {
            val c = scanner.peek()
            if (c == Scanner.END) return fail(scanner, start)
            if (c == '$' && backslashes % 2 == 0) {
                val beforeClose = scanner.position()
                scanner.next() // past the first '$' of a potential closing pair
                if (scanner.peek() == '$') {
                    val content = scanner.getSource(contentStart, beforeClose).content
                    scanner.next() // consume the second '$' of the closing pair
                    if (content.isBlank()) return fail(scanner, start)
                    return math(scanner, content, inline = false)
                }
                // A lone '$' inside display math: keep scanning.
                backslashes = 0
                continue
            }
            backslashes = if (c == '\\') backslashes + 1 else 0
            scanner.next()
        }
    }

    /** Parse `$…$` (inline math). The scanner is positioned just after the opening `$`. */
    private fun parseInline(scanner: Scanner, start: org.commonmark.parser.beta.Position): ParsedInline? {
        // An opening '$' must not be immediately followed by whitespace.
        val afterOpen = scanner.peek()
        if (afterOpen == ' ' || afterOpen == '\t') return fail(scanner, start)
        val contentStart = scanner.position()
        var prev = '$' // the character immediately before the scanner position
        var backslashes = 0
        while (true) {
            val c = scanner.peek()
            if (c == Scanner.END) return fail(scanner, start)
            if (c == '$' && backslashes % 2 == 0) {
                val closePos = scanner.position()
                scanner.next() // past the closing '$'
                val after = scanner.peek()
                val canClose = prev != ' ' && prev != '\t' && !(after in '0'..'9')
                if (canClose) {
                    val content = scanner.getSource(contentStart, closePos).content
                    if (content.isBlank()) return fail(scanner, start)
                    return math(scanner, content, inline = true)
                }
                // Not a valid closing delimiter (whitespace before / digit after): keep scanning.
                prev = '$'
                backslashes = 0
                continue
            }
            prev = c
            backslashes = if (c == '\\') backslashes + 1 else 0
            scanner.next()
        }
    }

    private fun math(scanner: Scanner, content: String, inline: Boolean): ParsedInline {
        val node = HtmlInline()
        node.literal = AsciiMath.asciiToMathML(content, inline = inline)
        return ParsedInline.of(node, scanner.position())
    }

    /** Reset the scanner and decline: the trigger `$` is then treated as literal text by the core parser. */
    private fun fail(scanner: Scanner, start: org.commonmark.parser.beta.Position): ParsedInline? {
        scanner.setPosition(start)
        return ParsedInline.none()
    }

    class Factory : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf('$')
        override fun create(): InlineContentParser = MathInlineParser()
    }
}
