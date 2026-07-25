package nu.staldal.mynotes.util

import org.commonmark.node.BlockQuote
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

/**
 * Builds boxes, foldable boxes and callouts from blockquotes — the application-specific transforms
 * described in mynotes/markdown-spec.md ("Box and foldable blockquotes" + "Callouts"). Mirrors the
 * web client's `blockquote_box` preprocessing rule and `callouts` core rule
 * (mynotes/web/ts/util/markdown.ts), split here into two phases around commonmark parsing:
 *
 *  1. [preprocessMarkers] runs on the raw Markdown *before* parsing: it strips the `>*`/`>-`/`>+`
 *     marker that sits immediately after the first `>` of a blockquote (so the standard blockquote
 *     rule parses clean content — `>- x` becomes `> x` rather than a blockquote containing a list)
 *     and records the marker per source line. Markers inside top-level fenced code blocks are left
 *     untouched.
 *
 *  2. [restructure] runs on the parsed AST: it reads the markers back (matched by each blockquote's
 *     source line) and detects a leading alias [IconNode], then rewrites qualifying blockquotes into
 *     [CalloutBlock] / [CalloutTitle] nodes. A blockquote is boxed when it has a marker or an alias
 *     icon; foldable for `-`/`+` (or the Obsidian `[!alias]-`/`+` form); a callout when it has an
 *     alias. [CalloutNodeRenderer] then renders these to HTML.
 *
 * Alias-tinted paragraphs (an `[!alias]` at the start of any non-blockquote paragraph) are handled
 * separately by [AliasParagraphAttributeProvider] at render time.
 */
object CalloutProcessor {
    // A single marker char right after the first '>' of a blockquote. Leading `>` markers are allowed
    // so nested blockquotes (`> >- inner`) work. Mirrors the web client's BQ_MARKER_RE.
    private val BQ_MARKER = Regex("^( {0,3}(?:> ?)*>)([-+*])")

    // A top-level fenced code block open/close fence. Mirrors the web client's FENCE_RE.
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})")

    /**
     * Strips `>*`/`>-`/`>+` box markers from [markdown] and records them by 0-based source line, so
     * [restructure] can recover them after parsing. Returns the cleaned Markdown (same line count, so
     * line indices still line up with the parsed source spans) and the line -> marker map. Markers
     * inside a top-level fenced code block are left untouched.
     */
    fun preprocessMarkers(markdown: String): Pair<String, Map<Int, Char>> {
        if (!markdown.contains('>')) return markdown to emptyMap()
        val lines = markdown.split('\n').toMutableList()
        val markers = HashMap<Int, Char>()
        var fence = "" // the open fence marker run ("```"/"~~~…"), or "" when not in a fence
        for (i in lines.indices) {
            val line = lines[i]
            if (fence.isNotEmpty()) {
                val c = FENCE.find(line)
                if (c != null && c.groupValues[1][0] == fence[0] &&
                    c.groupValues[1].length >= fence.length &&
                    line.substring(c.groupValues[1].length).isBlank()
                ) {
                    fence = ""
                }
                continue
            }
            val f = FENCE.find(line)
            if (f != null) {
                fence = f.groupValues[1]
                continue
            }
            val m = BQ_MARKER.find(line) ?: continue
            markers[i] = m.groupValues[2][0]
            lines[i] = m.groupValues[1] + line.substring(m.value.length)
        }
        return lines.joinToString("\n") to markers
    }

    /** Rewrites qualifying blockquotes in [document] into [CalloutBlock] nodes (see class doc). */
    fun restructure(document: Node, markers: Map<Int, Char>) {
        val blockquotes = ArrayList<BlockQuote>()
        collectBlockQuotes(document, blockquotes)
        for (bq in blockquotes) processBlockQuote(bq, markers)
    }

    private fun collectBlockQuotes(node: Node, out: MutableList<BlockQuote>) {
        var child = node.firstChild
        while (child != null) {
            if (child is BlockQuote) out.add(child)
            collectBlockQuotes(child, out)
            child = child.next
        }
    }

    private fun processBlockQuote(bq: BlockQuote, markers: Map<Int, Char>) {
        val para = bq.firstChild as? Paragraph ?: return
        val iconNode = para.firstChild as? IconNode
        val alias = iconNode?.alias // non-null only for a recognized callout alias
        val family = iconNode?.family

        var marker: Char? = bq.sourceSpans.firstOrNull()?.lineIndex?.let { markers[it] }

        // Obsidian-compat fold: `[!alias]-` / `[!alias]+` — a fold marker directly after the alias
        // icon (no leading space) when no `>-`/`>+` marker was given.
        if (alias != null && marker == null) {
            val afterIcon = iconNode.next
            if (afterIcon is Text && afterIcon.literal.isNotEmpty() &&
                (afterIcon.literal[0] == '-' || afterIcon.literal[0] == '+')
            ) {
                marker = afterIcon.literal[0]
                afterIcon.literal = afterIcon.literal.substring(1)
            }
        }

        val foldable = marker == '-' || marker == '+'
        val boxed = marker != null || alias != null
        if (!boxed) return

        var cls = "callout"
        if (alias != null) cls += " callout-$alias callout-color-$family"
        if (foldable) cls += " callout-foldable"

        val tag = if (foldable) "details" else "blockquote"
        val open = marker == '+'

        // Title = first paragraph's inline children up to the first line break; body = after it.
        val inlineChildren = childList(para)
        var breakIdx = inlineChildren.indexOfFirst { it is SoftLineBreak || it is HardLineBreak }
        if (breakIdx < 0) breakIdx = inlineChildren.size
        var titleChildren = inlineChildren.subList(0, breakIdx).toMutableList()
        val bodyChildren = if (breakIdx < inlineChildren.size) {
            inlineChildren.subList(breakIdx + 1, inlineChildren.size).toList()
        } else {
            emptyList()
        }

        if (alias != null) {
            // Strip the space left after the alias icon; fall back to the capitalized alias name when
            // the title is otherwise empty. Keeps any inline title markup.
            (titleChildren.getOrNull(1) as? Text)?.let { it.literal = it.literal.trimStart(' ', '\t') }
            val hasText = titleChildren.drop(1).any { it !is Text || it.literal.isNotBlank() }
            if (!hasText) {
                val label = Text(alias.replaceFirstChar { it.uppercase() })
                titleChildren = mutableListOf(titleChildren[0], label)
            }
        }

        val callout = CalloutBlock(tag, cls, open)
        val title = CalloutTitle(if (foldable) "summary" else "p")
        titleChildren.forEach { title.appendChild(it) } // appendChild moves each node
        callout.appendChild(title)
        if (bodyChildren.isNotEmpty()) {
            val body = Paragraph()
            bodyChildren.forEach { body.appendChild(it) }
            callout.appendChild(body)
        }
        // Move the remaining block children of the blockquote (after the first paragraph) into the box.
        var block = para.next
        while (block != null) {
            val nextBlock = block.next
            callout.appendChild(block)
            block = nextBlock
        }
        bq.insertBefore(callout)
        bq.unlink()
    }

    private fun childList(node: Node): List<Node> {
        val out = ArrayList<Node>()
        var c = node.firstChild
        while (c != null) {
            out.add(c)
            c = c.next
        }
        return out
    }
}
