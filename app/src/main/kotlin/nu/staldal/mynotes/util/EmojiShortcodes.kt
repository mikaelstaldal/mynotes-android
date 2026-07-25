package nu.staldal.mynotes.util

import com.google.gson.JsonParser
import org.commonmark.node.Text
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner

/**
 * Renders emoji shortcodes — the application-specific `:shortcode:` syntax that is not part of
 * CommonMark or GFM (see the "Emoji shortcodes" section of mynotes/markdown-spec.md). Mirrors the web
 * client's markdown-it rule (mynotes/web/ts/util/markdown.ts):
 *
 *   `:rocket:` -> 🚀   `:+1:` -> 👍   `:smile:` -> 😄
 *
 * `shortcode` is any GitHub-compatible shortcode in the vendored [EMOJI_SHORTCODES] map. The literal
 * `:shortcode:` is stored verbatim in the note `content`; this transform runs at render time. An
 * unknown shortcode is left as literal text, so ordinary colon use (`12:30`, `a:b`) is unaffected.
 * A `:shortcode:` inside a code span or code fence never reaches this parser (those are parsed by
 * other rules), so it stays literal — matching the spec and the web client.
 *
 * The shortcode -> emoji map is a verbatim copy of the same `EMOJI_SHORTCODES` object the web bundle
 * embeds (app/src/main/resources/emoji/emoji-shortcodes.json; regenerate via tools/gen-emoji.sh), so
 * the app's shortcodes can never drift from the web client's. The emitted node is a plain [Text] node
 * carrying the raw Unicode emoji, so nothing HTML-bearing bypasses the sanitizer in [NoteHtmlRenderer].
 */
object EmojiShortcodes {
    private const val RESOURCE = "/emoji/emoji-shortcodes.json"

    // shortcode (lower-case) -> raw Unicode emoji. Parsed lazily on first use (and only when a note
    // actually contains a ':' that opens a candidate shortcode) from the vendored resource, then
    // cached for the process lifetime.
    private val shortcodes: Map<String, String> by lazy { load() }

    /** The raw Unicode emoji for a GitHub shortcode (case-insensitive), or null when unknown. */
    fun lookup(name: String): String? = shortcodes[name.lowercase()]

    private fun load(): Map<String, String> {
        val stream = EmojiShortcodes::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing bundled emoji shortcodes: $RESOURCE")
        val root = stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it) }.asJsonObject
        return root.entrySet().associate { (code, char) -> code to char.asString }
    }

    class Factory : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf(':')
        override fun create(): InlineContentParser = EmojiInlineParser()
    }
}

/**
 * Parses a single `:shortcode:` run into the raw Unicode emoji [Text] node (see [EmojiShortcodes]).
 * Mirrors the web client's `EMOJI_SHORTCODE_RE` (`^:([a-zA-Z0-9_+-]+):`): an opening `:`, one or more
 * shortcode characters, a closing `:`, resolving against the vendored shortcode map. On any miss the
 * scanner is reset and the trigger `:` is left literal by the core parser.
 */
private class EmojiInlineParser : InlineContentParser {
    override fun tryParse(state: InlineParserState): ParsedInline? {
        val scanner = state.scanner()
        val start = scanner.position()
        scanner.next() // consume the opening ':'
        val nameStart = scanner.position()
        var len = 0
        while (isShortcodeChar(scanner.peek())) {
            scanner.next()
            len++
        }
        if (len == 0 || scanner.peek() != ':') return fail(scanner, start)
        val name = scanner.getSource(nameStart, scanner.position()).content
        val emoji = EmojiShortcodes.lookup(name) ?: return fail(scanner, start)
        scanner.next() // consume the closing ':'
        return ParsedInline.of(Text(emoji), scanner.position())
    }

    private fun fail(scanner: Scanner, start: org.commonmark.parser.beta.Position): ParsedInline? {
        scanner.setPosition(start)
        return ParsedInline.none()
    }

    private fun isShortcodeChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '+' || c == '-'
}
