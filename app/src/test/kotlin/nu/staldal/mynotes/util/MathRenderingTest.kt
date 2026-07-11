package nu.staldal.mynotes.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises AsciiMath math rendering end-to-end through [NoteHtmlRenderer] (commonmark parse +
 * MathInlineParser + AsciiMath conversion + OWASP sanitize), mirroring the web client's math test
 * suite (mynotes/web/ts/markdown.test.mjs) so `$…$` / `$$…$$` renders equivalently across clients.
 */
class MathRenderingTest {
    private fun render(markdown: String): String = NoteHtmlRenderer.renderToSanitizedHtml(markdown)

    @Test
    fun `inline dollar renders inline MathML`() {
        val out = render("Energy \$x^2\$ here.")
        assertTrue(out, out.contains("<math display=\"inline\""))
        assertTrue(out, out.contains("<msup>"))
        assertTrue(out, out.contains("<mn>2</mn>"))
    }

    @Test
    fun `inline double-dollar on one line renders display MathML`() {
        val out = render("See \$\$a/b\$\$ inline.")
        assertTrue(out, out.contains("<math display=\"block\""))
        assertTrue(out, out.contains("<mfrac>"))
    }

    @Test
    fun `double-dollar spanning lines renders a display MathML block`() {
        val out = render("Before\n\n\$\$\nsum_(i=1)^n i\n\$\$\n\nafter")
        assertTrue(out, out.contains("<math display=\"block\""))
        assertTrue(out, out.contains("<munderover>"))
        assertTrue(out, out.contains("after"))
    }

    @Test
    fun `single-line double-dollar as its own block renders display MathML`() {
        val out = render("\$\$x+1\$\$")
        assertTrue(out, out.contains("<math display=\"block\""))
    }

    @Test
    fun `currency stays literal text`() {
        val out = render("I paid \$5 and \$10 today.")
        assertFalse(out, out.contains("<math"))
        assertTrue(out, out.contains("\$5 and \$10"))
    }

    @Test
    fun `escaped dollar is a literal dollar not a math delimiter`() {
        val out = render("Escaped \\\$x\\\$ literal.")
        assertFalse(out, out.contains("<math"))
        assertTrue(out, out.contains("\$x\$"))
    }

    @Test
    fun `an unpaired dollar is left as literal text`() {
        val out = render("Price is \$ and more.")
        assertFalse(out, out.contains("<math"))
        assertTrue(out, out.contains("\$ and more"))
    }

    @Test
    fun `dollar inside a code span stays literal`() {
        val out = render("`\$x^2\$`")
        assertFalse(out, out.contains("<math"))
        assertTrue(out, out.contains("\$x^2\$"))
    }

    @Test
    fun `script payload inside math cannot inject markup`() {
        val out = render("\$text(<script>alert(1)</script>)\$ end")
        assertFalse(out, out.contains("<script"))
        assertFalse(out, out.contains("alert(1)"))
    }

    @Test
    fun `malformed AsciiMath degrades to a merror node`() {
        val out = render("\$sqrt(\$")
        assertTrue(out, out.contains("<math"))
        assertTrue(out, out.contains("<merror>"))
    }
}
