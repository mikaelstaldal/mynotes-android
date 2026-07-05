package nu.staldal.mynotes.util

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drives [WikiLinkProcessor] through a bare commonmark Parser + HtmlRenderer (no sanitizer, no
 * Android dependencies) so the wikilink rewriting can be asserted at the HTML level in isolation.
 * The sanitizer's handling of the synthesized `mynotes://` scheme is a separate concern of
 * [NoteHtmlRenderer].
 */
class WikiLinkProcessorTest {
    private val parser = Parser.builder().linkProcessor(WikiLinkProcessor).build()
    private val renderer = HtmlRenderer.builder().build()

    private fun render(markdown: String): String = renderer.render(parser.parse(markdown)).trim()

    @Test
    fun `note wikilink uses slug as default text`() {
        assertEquals("""<p><a href="mynotes://note/my-note">my-note</a></p>""", render("[[my-note]]"))
    }

    @Test
    fun `note wikilink with display text`() {
        assertEquals(
            """<p><a href="mynotes://note/my-note">Display text</a></p>""",
            render("[[my-note|Display text]]"),
        )
    }

    @Test
    fun `tag wikilink defaults to hash-prefixed slug`() {
        assertEquals("""<p><a href="mynotes://tag/work">#work</a></p>""", render("[[#work]]"))
    }

    @Test
    fun `tag wikilink with display text`() {
        assertEquals("""<p><a href="mynotes://tag/work">My work</a></p>""", render("[[#work|My work]]"))
    }

    @Test
    fun `display text is taken verbatim without markdown expansion`() {
        // The label may be any run of characters except ']' and newline; markdown-active characters
        // inside it stay literal (matching the web client, which matches on the raw source).
        assertEquals("""<p><a href="mynotes://note/a">x *y* z</a></p>""", render("[[a|x *y* z]]"))
    }

    @Test
    fun `multiple wikilinks in one line`() {
        assertEquals(
            """<p>see <a href="mynotes://note/a">a</a> and <a href="mynotes://tag/b">#b</a> end</p>""",
            render("see [[a]] and [[#b]] end"),
        )
    }

    @Test
    fun `adjacent wikilinks`() {
        assertEquals(
            """<p><a href="mynotes://note/a">a</a><a href="mynotes://note/b">b</a></p>""",
            render("[[a]][[b]]"),
        )
    }

    @Test
    fun `wikilink surrounded by text`() {
        assertEquals("""<p>x<a href="mynotes://note/a">a</a>y</p>""", render("x[[a]]y"))
    }

    @Test
    fun `wikilink inside code span stays literal`() {
        assertEquals("<p><code>[[a]]</code></p>", render("`[[a]]`"))
    }

    @Test
    fun `wikilink inside fenced code block stays literal`() {
        assertEquals("<pre><code>[[a]]\n</code></pre>", render("```\n[[a]]\n```"))
    }

    @Test
    fun `invalid slug is left literal`() {
        // Upper-case and spaces are not valid slug characters.
        assertEquals("<p>[[Not Valid]]</p>", render("[[Not Valid]]"))
    }

    @Test
    fun `slug with leading hyphen is left literal`() {
        assertEquals("<p>[[-bad]]</p>", render("[[-bad]]"))
    }

    @Test
    fun `empty target is left literal`() {
        assertEquals("<p>[[]]</p>", render("[[]]"))
    }

    @Test
    fun `single brackets are not a wikilink`() {
        assertEquals("<p>[single]</p>", render("[single]"))
    }

    @Test
    fun `unterminated wikilink is left literal`() {
        assertEquals("<p>[[a</p>", render("[[a"))
    }

    @Test
    fun `label may not span a newline`() {
        // The '|...' label stops at a newline, so this does not form a valid wikilink.
        assertEquals("<p>[[a|one\ntwo]]</p>", render("[[a|one\ntwo]]"))
    }

    @Test
    fun `ordinary inline link is untouched`() {
        assertEquals(
            """<p><a href="https://example.com">text</a></p>""",
            render("[text](https://example.com)"),
        )
    }

    @Test
    fun `reference link is untouched`() {
        assertEquals(
            """<p><a href="https://example.com">text</a></p>""",
            render("[text][ref]\n\n[ref]: https://example.com"),
        )
    }

    @Test
    fun `wikilink followed by parenthesised text keeps the parentheses literal`() {
        // Mirrors the web client: the [[slug]] is consumed as a wikilink, and the trailing "(...)"
        // is rendered as literal text rather than an inline-link destination.
        assertEquals(
            """<p><a href="mynotes://note/slug">slug</a>(https://x)</p>""",
            render("[[slug]](https://x)"),
        )
    }
}
