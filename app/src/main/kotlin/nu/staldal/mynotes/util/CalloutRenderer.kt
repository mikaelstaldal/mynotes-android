package nu.staldal.mynotes.util

import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.AttributeProviderContext
import org.commonmark.renderer.html.AttributeProviderFactory
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlNodeRendererFactory

/**
 * Renders the custom [IconNode] / [CalloutBlock] / [CalloutTitle] nodes to HTML (see [CalloutNodes]
 * and [CalloutProcessor]). Mirrors the markup the web client emits from its token manipulation
 * (mynotes/web/ts/util/markdown.ts): inline `<svg>` icons, `<blockquote>`/`<details>` boxes with a
 * `<p>`/`<summary>` title row, all carrying the `callout …` classes the note CSS styles. The output
 * is re-sanitized by the OWASP policy in [NoteHtmlRenderer] regardless.
 */
class CalloutNodeRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {
    private val html = context.writer

    override fun getNodeTypes(): Set<Class<out Node>> =
        setOf(IconNode::class.java, CalloutBlock::class.java, CalloutTitle::class.java)

    override fun render(node: Node) {
        when (node) {
            is IconNode -> html.raw(node.svg)
            is CalloutBlock -> {
                html.line()
                val attrs = LinkedHashMap<String, String>()
                attrs["class"] = node.cssClass
                if (node.open) attrs["open"] = "" // boolean attribute; browsers treat open="" as present
                html.tag(node.tag, attrs)
                html.line()
                renderChildren(node)
                html.line()
                html.tag("/" + node.tag)
                html.line()
            }
            is CalloutTitle -> {
                html.line()
                html.tag(node.tag, mapOf("class" to "callout-title"))
                renderChildren(node)
                html.tag("/" + node.tag)
                html.line()
            }
        }
    }

    private fun renderChildren(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            val next = child.next
            context.render(child)
            child = next
        }
    }

    class Factory : HtmlNodeRendererFactory {
        override fun create(context: HtmlNodeRendererContext): NodeRenderer = CalloutNodeRenderer(context)
    }
}

/**
 * Tints any paragraph whose first inline child is an alias [IconNode] with that alias's colour family,
 * without a box — the "Alias-tinted paragraphs" transform (mynotes/markdown-spec.md), mirroring the
 * web client's callouts Pass B. Callout title rows are [CalloutTitle] nodes (not paragraphs) and body
 * paragraphs never start with the alias icon, so neither is double-tinted.
 */
class AliasParagraphAttributeProvider : AttributeProvider {
    override fun setAttributes(node: Node, tagName: String, attributes: MutableMap<String, String>) {
        if (node !is Paragraph) return
        val family = (node.firstChild as? IconNode)?.family ?: return // only alias icons carry a family
        val cls = "callout-para callout-color-$family"
        val existing = attributes["class"]
        attributes["class"] = if (existing.isNullOrEmpty()) cls else "$existing $cls"
    }

    class Factory : AttributeProviderFactory {
        override fun create(context: AttributeProviderContext): AttributeProvider = AliasParagraphAttributeProvider()
    }
}
