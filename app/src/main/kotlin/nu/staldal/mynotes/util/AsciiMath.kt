package nu.staldal.mynotes.util

/**
 * Converts an [AsciiMath](https://asciimath.org) expression to a MathML string.
 *
 * This is a faithful Kotlin port of the vendored `asciimath2ml` (v1.0.8) library the web frontend
 * uses (see mynotes/web/ts/vendor and its use in mynotes/web/ts/util/markdown.ts), so `$…$` / `$$…$$`
 * math renders equivalently in this app and the web client. The parser is a self-contained
 * recursive-descent scanner working purely with strings — no external dependencies. Malformed input
 * never throws: an unrecognized symbol becomes a `<merror>` node, so a note always renders.
 *
 * The produced `<math>` markup flows through the same OWASP sanitizer as all other rendered HTML in
 * [NoteHtmlRenderer] (whose allow-list already covers the MathML element/attribute set), so math
 * markup is sanitized like everything else — nothing bypasses the render-time gate.
 */
object AsciiMath {
    /**
     * Convert [input] AsciiMath to a MathML string. [inline] selects inline vs. display style; when
     * false the output is a display (block) equation. [escapePunctuation] escapes non-alphanumeric
     * characters in `"…"` text fragments with character entities (matches the upstream flag; the web
     * client leaves it off and relies on sanitization, so callers here do too).
     */
    fun asciiToMathML(input: String, inline: Boolean = false, escapePunctuation: Boolean = false): String {
        val scanner = Scanner(input, escapePunctuation)
        val display = if (inline) "inline" else "block"
        return "<math display=\"$display\"><mstyle displaystyle=\"true\">${exprParser(scanner)}</mstyle></math>"
    }
}

// ## Symbols
//
// Symbols are objects returned by the scanner. Each symbol has a [SymbolKind]. `Default` symbols do
// not affect syntax rules — they usually transform directly to a MathML fragment. The other kinds
// trigger special processing in the parser.
private enum class SymbolKind {
    Default, UnderOver, LeftBracket, RightBracket,
    MatrixLeftBracket, MatrixRightBracket, MatrixCellSep, MatrixRowSep, Eof,
}

private class Symbol(val kind: SymbolKind, val input: String, val parser: (Scanner) -> String)

/**
 * The scanner tokenizes the AsciiMath input. To output a variable in a special font (blackboard,
 * calligraphic, fraktur) it maps character codes to another unicode range; when a font command is in
 * effect the corresponding table is pushed onto [charTables].
 */
private class Scanner(val input: String, val escapePunctuation: Boolean) {
    var pos = 0
    private val charTables = ArrayDeque<Array<String>>()

    /** Skip whitespace; return the index of the next token, or -1 past the end of input. */
    private fun skipWhitespace(): Int {
        while (pos < input.length && input[pos].isWhitespace()) pos++
        return if (pos < input.length) pos else -1
    }

    /**
     * Peek the next symbol without consuming it, returning the symbol and the position to advance to
     * in order to skip it. Whitespace preceding the symbol is skipped (mutating [pos]). A negative
     * position and an [eof] symbol are returned at the end of input. The symbol table lists symbols
     * per starting character in descending length order, so the first match is the longest one.
     */
    fun peekSymbol(): Pair<Symbol, Int> {
        val start = skipWhitespace()
        if (start < 0) return eof() to start
        val curr = input[start]
        // Text `"..."` string in doublequotes. With escapePunctuation, non-alphanumerics are escaped.
        if (curr == '"') {
            var p = start
            while (++p < input.length && input[p] != '"') { /* scan to closing quote */ }
            var txt = input.substring(start + 1, minOf(p, input.length))
            if (escapePunctuation) txt = escapePunct(txt)
            return text(txt) to p + 1
        }
        // Number; the only accepted decimal separator is dot `.`.
        if (curr in '0'..'9') {
            var p = start
            while (p < input.length && (input[p] in '0'..'9' || input[p] == '.')) p++
            return number(input.substring(start, p)) to p
        }
        // Longest matching symbol from the table.
        val syms = symbols[curr]
        if (syms != null) {
            for (sym in syms) {
                val len = sym.input.length
                if (start + len <= input.length && input.regionMatches(start, sym.input, 0, len)) {
                    return sym to start + len
                }
            }
        }
        // No matching symbol: skip the current character and return an error.
        return error(curr.toString()) to start + 1
    }

    /** Get the next symbol and advance the position. */
    fun nextSymbol(): Symbol {
        val (sym, p) = peekSymbol()
        if (p >= 0) pos = p
        return sym
    }

    fun pushCharTable(table: Array<String>) = charTables.addLast(table)
    fun popCharTable() { charTables.removeLastOrNull() }
    fun charTable(): Array<String>? = charTables.lastOrNull()
}

// ## Character Tables
//
// Font commands remap upper- and lower-case latin letters to alternate unicode ranges. Other
// characters are left unchanged. The tables below cover calligraphic, fraktur and blackboard fonts.
private val calTable = arrayOf(
    "𝒜", "ℬ", "𝒞", "𝒟", "ℰ",
    "ℱ", "𝒢", "ℋ", "ℐ", "𝒥", "𝒦",
    "ℒ", "ℳ", "𝒩", "𝒪", "𝒫",
    "𝒬", "ℛ", "𝒮", "𝒯", "𝒰",
    "𝒱", "𝒲", "𝒳", "𝒴",
    "𝒵", "𝒶", "𝒷", "𝒸",
    "𝒹", "ℯ", "𝒻", "ℊ", "𝒽",
    "𝒾", "𝒿", "𝓀", "𝓁",
    "𝓂", "𝓃", "ℴ", "𝓅", "𝓆",
    "𝓇", "𝓈", "𝓉", "𝓊",
    "𝓋", "𝓌", "𝓍", "𝓎",
    "𝓏",
)
private val frkTable = arrayOf(
    "𝔄", "𝔅", "ℭ", "𝔇",
    "𝔈", "𝔉", "𝔊", "ℌ", "ℑ",
    "𝔍", "𝔎", "𝔏", "𝔐",
    "𝔑", "𝔒", "𝔓", "𝔔", "ℜ",
    "𝔖", "𝔗", "𝔘", "𝔙",
    "𝔚", "𝔛", "𝔜", "ℨ", "𝔞",
    "𝔟", "𝔠", "𝔡", "𝔢",
    "𝔣", "𝔤", "𝔥", "𝔦",
    "𝔧", "𝔨", "𝔩", "𝔪",
    "𝔫", "𝔬", "𝔭", "𝔮",
    "𝔯", "𝔰", "𝔱", "𝔲",
    "𝔳", "𝔴", "𝔵", "𝔶",
    "𝔷",
)
private val bbbTable = arrayOf(
    "𝔸", "𝔹", "ℂ", "𝔻",
    "𝔼", "𝔽", "𝔾", "ℍ", "𝕀",
    "𝕁", "𝕂", "𝕃", "𝕄", "ℕ",
    "𝕆", "ℙ", "ℚ", "ℝ", "𝕊", "𝕋",
    "𝕌", "𝕍", "𝕎", "𝕏",
    "𝕐", "ℤ", "𝕒", "𝕓", "𝕔",
    "𝕕", "𝕖", "𝕗", "𝕘",
    "𝕙", "𝕚", "𝕛", "𝕜",
    "𝕝", "𝕞", "𝕟", "𝕠",
    "𝕡", "𝕢", "𝕣", "𝕤",
    "𝕥", "𝕦", "𝕧", "𝕨",
    "𝕩", "𝕪", "𝕫",
)

/** Convert [text] using [table]; if no table is active the text is returned unchanged. */
private fun convertText(text: String, table: Array<String>?): String {
    if (table == null) return text
    val res = StringBuilder(text.length)
    for (ch in text) {
        val c = ch.code
        res.append(
            when {
                c in 65..90 -> table[c - 65]
                c in 97..122 -> table[c - 71]
                else -> ch.toString()
            },
        )
    }
    return res.toString()
}

private fun escapePunct(s: String): String = buildString {
    for (ch in s) {
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9') append(ch) else append("&#${ch.code};")
    }
}

// ### Terminal symbol constructors

/** Regular text inside equations, rendered upright in `<mtext>` (with font translation applied). */
private fun text(input: String) = Symbol(SymbolKind.Default, input) { s -> "<mtext>${convertText(input, s.charTable())}</mtext>" }

/** Numbers become `<mn>` elements. */
private fun number(input: String) = Symbol(SymbolKind.Default, input) { "<mn>$input</mn>" }

/** Invalid/unrecognized input becomes a `<merror>` node (typically rendered in a red/yellow box). */
private fun error(msg: String) = Symbol(SymbolKind.Default, "") { "<merror><mtext>$msg</mtext></merror>" }

/** End-of-input marker; terminates expression parsing and produces no output. */
private fun eof() = Symbol(SymbolKind.Eof, "") { "" }

/** Variables/identifiers become `<mi>` elements (with font translation applied). */
private fun ident(input: String, output: String = input) =
    Symbol(SymbolKind.Default, input) { s -> "<mi>${convertText(output, s.charTable())}</mi>" }

/** Simple operators become `<mo>` elements. */
private fun oper(input: String, output: String) = Symbol(SymbolKind.Default, input) { "<mo>$output</mo>" }

/** Operators rendered as normal text (e.g. `and`, `or`, `mod`), padded with spaces. */
private fun textOper(input: String, output: String = input) = Symbol(SymbolKind.Default, input) {
    "<mrow><mspace width=\"1ex\"/><mtext>$output</mtext><mspace width=\"1ex\"/></mrow>"
}

/** Operators that can carry stuff under and over them (e.g. `sum`, `lim`). */
private fun underOverOper(input: String, oper: String = input) = Symbol(SymbolKind.UnderOver, input) { "<mo>$oper</mo>" }

/** Left bracket; also triggers expression parsing. An undefined [output] renders nothing (invisible). */
private fun leftBracket(input: String, output: String? = null) =
    Symbol(SymbolKind.LeftBracket, input) { if (output != null) "<mo>$output</mo>" else "" }

/** Right bracket; terminates expression parsing. An undefined [output] is invisible. */
private fun rightBracket(input: String, output: String? = null) =
    Symbol(SymbolKind.RightBracket, input) { if (output != null) "<mo>$output</mo>" else "" }

// ### Unary symbol parsers (commands taking one argument)

/** `sin`, `log`, … — operator followed by its argument inside an `<mrow>`, honoring sub/superscripts. */
private fun unaryParser(oper: String): (Scanner) -> String = { scanner ->
    val (sub, sup) = subSupParser(scanner)
    val soper = when {
        sub != null && sup != null -> "<msubsup>$oper$sub$sup</msubsup>"
        sub != null -> "<msub>$oper$sub</msub>"
        sup != null -> "<msup>$oper$sup</msup>"
        else -> oper
    }
    val arg = sexprParser(scanner)
    "<mrow>$soper$arg</mrow>"
}

private fun unary(input: String, oper: String = input) =
    Symbol(SymbolKind.Default, input, unaryParser("<mo>$oper</mo>"))

/** Embed the argument inside a given tag (e.g. `sqrt` → `<msqrt>`, `text` → `<mtext>`). */
private fun unaryEmbed(input: String, tag: String) =
    Symbol(SymbolKind.Default, input) { scanner -> "<$tag>${sexprParser(scanner)}</$tag>" }

/** Embed the argument in a tag together with a hard-coded second argument (accents over/under). */
private fun unaryUnderOver(input: String, tag: String, arg2: String) =
    Symbol(SymbolKind.UnderOver, input) { scanner -> "<$tag>${sexprParser(scanner)}<mo>$arg2</mo></$tag>" }

/** Surround the argument with given left/right brackets (e.g. `abs`, `floor`). */
private fun unarySurround(input: String, left: String, right: String) =
    Symbol(SymbolKind.Default, input) { scanner -> "<mrow><mo>$left</mo>${sexprParser(scanner)}<mo>$right</mo></mrow>" }

/** Embed the argument in a tag carrying a fixed attribute string (e.g. `cancel`, `bb`). */
private fun unaryAttr(input: String, tag: String, attr: String) =
    Symbol(SymbolKind.Default, input) { scanner -> "<$tag $attr>${sexprParser(scanner)}</$tag>" }

/** Font command: switch the character table on while parsing the argument. */
private fun unaryCharTable(input: String, table: Array<String>) =
    Symbol(SymbolKind.Default, input) { scanner ->
        scanner.pushCharTable(table)
        val res = sexprParser(scanner)
        scanner.popCharTable()
        res
    }

// ### Binary symbol parsers (commands taking two arguments)

/** Embed two arguments inside a tag (e.g. `frac` → `<mfrac>`, `root` → `<mroot>`). */
private fun binaryEmbed(input: String, tag: String) =
    Symbol(SymbolKind.Default, input) { scanner -> "<$tag>${sexprParser(scanner)}${sexprParser(scanner)}</$tag>" }

/** First argument becomes an attribute value read from input, second becomes the tag content. */
private fun binaryAttr(input: String, tag: String, attr: String) =
    Symbol(SymbolKind.Default, input) { scanner ->
        val arg1 = scanner.nextSymbol().input
        val arg2 = sexprParser(scanner)
        "<$tag $attr=\"$arg1\">$arg2</$tag>"
    }

// ## Grammar
//
// ```
// v ::= [A-Za-z] | greek letters | numbers | other constant symbols
// u ::= sqrt | text | bb | other unary symbols for font commands
// b ::= frac | root | stackrel | other binary symbols
// l ::= ( | [ | { | (: | {: | other left brackets
// r ::= ) | ] | } | :) | :} | other right brackets
// S ::= v | lEr | uS | bSS             Simple expression
// I ::= S_S | S^S | S_S^S | S          Intermediate expression
// E ::= IE | I/I                       Expression
// ```

/** Simple expression `S`. Returns the MathML and the root symbol (needed by the `I` rule). */
private fun parseSExpr(scanner: Scanner): Pair<String, Symbol> {
    val sym = scanner.nextSymbol()
    if (sym.kind == SymbolKind.LeftBracket) {
        val lbrac = sym.parser(scanner)
        var (sym2, _) = scanner.peekSymbol()
        val exp = if (sym2.kind == SymbolKind.RightBracket) "" else exprParser(scanner)
        sym2 = scanner.nextSymbol()
        val rbrac = (if (sym2.kind == SymbolKind.RightBracket) sym2 else error("Missing closing paren")).parser(scanner)
        return "<mrow>$lbrac$exp$rbrac</mrow>" to sym
    }
    return sym.parser(scanner) to sym
}

private fun sexprParser(scanner: Scanner): String = parseSExpr(scanner).first

/** Intermediate expression `I` — attaches subscripts/superscripts, under/over for `UnderOver` bases. */
private fun iexprParser(scanner: Scanner): String {
    val (res, sym) = parseSExpr(scanner)
    val (sub, sup) = subSupParser(scanner)
    return if (sym.kind == SymbolKind.UnderOver) {
        when {
            sub != null && sup != null -> "<munderover>$res$sub$sup</munderover>"
            sub != null -> "<munder>$res$sub</munder>"
            sup != null -> "<mover>$res$sup</mover>"
            else -> res
        }
    } else {
        when {
            sub != null && sup != null -> "<msubsup>$res$sub$sup</msubsup>"
            sub != null -> "<msub>$res$sub</msub>"
            sup != null -> "<msup>$res$sup</msup>"
            else -> res
        }
    }
}

/** Parse the subscript (`_`) and superscript (`^`) expressions, if present. */
private fun subSupParser(scanner: Scanner): Pair<String?, String?> {
    var sub: String? = null
    var sup: String? = null
    var (next, p) = scanner.peekSymbol()
    if (next.input == "_") {
        scanner.pos = p
        sub = sexprParser(scanner)
        val r = scanner.peekSymbol()
        next = r.first
        p = r.second
    }
    if (next.input == "^") {
        scanner.pos = p
        sup = sexprParser(scanner)
    }
    return sub to sup
}

private val terminators = setOf(
    SymbolKind.Eof, SymbolKind.RightBracket,
    SymbolKind.MatrixCellSep, SymbolKind.MatrixRowSep, SymbolKind.MatrixRightBracket,
)

/** Expression `E` — a sequence of intermediate expressions, also handling the `/` fraction operator. */
private fun exprParser(scanner: Scanner): String {
    var res = ""
    while (true) {
        var exp = iexprParser(scanner)
        var (next, pos) = scanner.peekSymbol()
        if (next.kind in terminators) return res + exp
        if (next.input == "/") {
            scanner.pos = pos
            val quot = iexprParser(scanner)
            exp = "<mfrac>$exp$quot</mfrac>"
            next = scanner.peekSymbol().first
            if (next.kind in terminators) return res + exp
        }
        res += exp
    }
}

// ## Matrices
//
// This differs from the official AsciiMath syntax: matrices use dedicated opening/closing brackets,
// cells are separated by `;` and rows by `;;`, which keeps parsing simple and unambiguous.
private fun matrixParser(leftBracket: String): (Scanner) -> String = { scanner ->
    val res = StringBuilder()
    var result: String? = null
    while (result == null) {
        val (sym, pos) = scanner.peekSymbol()
        if (sym.kind == SymbolKind.Eof || sym.kind == SymbolKind.MatrixRightBracket) {
            scanner.pos = pos
            val rightBracket = sym.parser(scanner)
            result = if (leftBracket.isNotEmpty() || rightBracket.isNotEmpty()) {
                "<mrow>$leftBracket<mtable>$res</mtable>$rightBracket</mrow>"
            } else {
                "<mtable>$res</mtable>"
            }
        } else {
            val row = matrixRowParser(scanner)
            res.append("<mtr>$row</mtr>")
        }
    }
    result
}

private fun matrixRowParser(scanner: Scanner): String {
    val res = StringBuilder()
    while (true) {
        val (sym, pos) = scanner.peekSymbol()
        if (sym.kind == SymbolKind.Eof || sym.kind == SymbolKind.MatrixRowSep) {
            scanner.pos = pos
            return res.toString()
        }
        if (sym.kind == SymbolKind.MatrixRightBracket) return res.toString()
        val cell = exprParser(scanner)
        res.append("<mtd>$cell</mtd>")
    }
}

private fun leftMatrix(input: String, output: String? = null) =
    Symbol(SymbolKind.MatrixLeftBracket, input, matrixParser(if (output != null) "<mo>$output</mo>" else ""))

private fun rightMatrix(input: String, output: String? = null) =
    Symbol(SymbolKind.MatrixRightBracket, input) { if (output != null) "<mo>$output</mo>" else "" }

private fun matrixCellSep(input: String) = Symbol(SymbolKind.MatrixCellSep, input) { "" }

private fun matrixRowSep(input: String) = Symbol(SymbolKind.MatrixRowSep, input) { "" }

// ## Symbol Table
//
// Covers all possible inputs except literal strings and numbers. Each per-character list is ordered
// longest-input-first so the scanner matches the longest token.
private val symbols: Map<Char, List<Symbol>> = mapOf(
    'a' to listOf(
        unary("arcsin"), unary("arccos"), unary("arctan"),
        ident("alpha", "&#x03B1;"), oper("aleph", "&#x2135;"),
        unarySurround("abs", "&#124;", "&#124;"), textOper("and"), ident("a"),
    ),
    'A' to listOf(
        unary("Arcsin"), unary("Arccos"), unary("Arctan"),
        unarySurround("Abs", "&#124;", "&#124;"), oper("AA", "&#x2200;"), ident("A"),
    ),
    'b' to listOf(
        ident("beta", "&#x03B2;"), unaryUnderOver("bar", "mover", "&#x00AF;"),
        unaryCharTable("bbb", bbbTable), unaryAttr("bb", "mstyle", "style=\"font-weight: bold\""), ident("b"),
    ),
    'B' to listOf(ident("B")),
    'c' to listOf(
        unaryAttr("cancel", "menclose", "notation=\"updiagonalstrike\""),
        binaryAttr("color", "mstyle", "mathcolor"), binaryAttr("class", "mrow", "class"),
        oper("cdots", "&#x22EF;"), unarySurround("ceil", "&#x2308;", "&#x2309;"),
        unary("cosh"), unary("csch"), unary("cos"), unary("cot"), unary("csc"),
        ident("chi", "&#x03C7;"), unaryCharTable("cc", calTable), ident("c"),
    ),
    'C' to listOf(
        unary("Cosh"), unary("Cos"), unary("Cot"), unary("Csc"), oper("CC", "&#x2102;"), ident("C"),
    ),
    'd' to listOf(
        oper("diamonds", "&#x22C4;"), ident("delta", "&#x03B4;"), oper("ddots", "&#x22F1;"),
        unaryUnderOver("ddot", "mover", ".."), oper("darr", "&#x2193;"), oper("del", "&#x2202;"),
        unary("det"), unaryUnderOver("dot", "mover", "."), textOper("dim"), ident("d"),
    ),
    'D' to listOf(oper("Delta", "&#x0394;"), ident("D")),
    'e' to listOf(
        ident("epsilon", "&#x03B5;"), ident("eta", "&#x03B7;"), unary("exp"), ident("e"),
    ),
    'E' to listOf(oper("EE", "&#x2203;"), ident("E")),
    'f' to listOf(
        unarySurround("floor", "&#x230A;", "&#x230B;"), oper("frown", "&#x2322;"),
        binaryEmbed("frac", "mfrac"), unaryCharTable("fr", frkTable), ident("f"),
    ),
    'F' to listOf(ident("F")),
    'g' to listOf(
        ident("gamma", "&#x03B3;"), oper("grad", "&#x2207;"), unary("gcd"), textOper("glb"), ident("g"),
    ),
    'G' to listOf(oper("Gamma", "&#x0393;"), ident("G")),
    'h' to listOf(
        oper("harr", "&#x2194;"), oper("hArr", "&#x21D4;"),
        unaryUnderOver("hat", "mover", "&#x005E;"), ident("h"),
    ),
    'H' to listOf(ident("H")),
    'i' to listOf(
        ident("iota", "&#x03B9;"), oper("int", "&#x222B;"), oper("in", "&#x2208;"),
        textOper("if"), binaryAttr("id", "mrow", "id"), ident("i"),
    ),
    'I' to listOf(ident("I")),
    'j' to listOf(ident("j")),
    'J' to listOf(ident("J")),
    'k' to listOf(ident("kappa", "&#x03BA;"), ident("k")),
    'K' to listOf(ident("K")),
    'l' to listOf(
        ident("lambda", "&#x03BB;"), oper("larr", "&#x2190;"), oper("lArr", "&#x21D0;"),
        underOverOper("lim", "lim"), unary("log"), unary("lcm"), textOper("lub"), unary("ln"), ident("l"),
    ),
    'L' to listOf(
        oper("Lambda", "&#x039B;"), underOverOper("Lim", "Lim"), unary("Log"), unary("Ln"), ident("L"),
    ),
    'm' to listOf(
        underOverOper("min"), underOverOper("max"), textOper("mod"), ident("mu", "&#x03BC;"), ident("m"),
    ),
    'M' to listOf(ident("M")),
    'n' to listOf(
        unarySurround("norm", "&#x2225;", "&#x2225;"), underOverOper("nnn", "&#x22C2;"),
        oper("not", "&#x00AC;"), oper("nn", "&#x2229;"), ident("nu", "&#x03BD;"), ident("n"),
    ),
    'N' to listOf(oper("NN", "&#x2115;"), ident("N")),
    'o' to listOf(
        unaryUnderOver("overarc", "mover", "&#x23DC;"), binaryEmbed("overset", "mover"),
        unaryUnderOver("obrace", "mover", "&#x23DE;"), ident("omega", "&#x03C9;"),
        oper("oint", "&#x222E;"), textOper("or"), oper("o+", "&#x2295;"), oper("ox", "&#x2295;"),
        oper("o.", "&#x2299;"), oper("oo", "&#x221E;"), ident("o"),
    ),
    'O' to listOf(oper("Omega", "&#x03A9;"), oper("O/", "&#x2205;"), ident("O")),
    'p' to listOf(
        underOverOper("prod", "&#x220F;"), ident("prop", "&#x221D;"), ident("phi", "&#x03D5;"),
        ident("psi", "&#x03C8;"), ident("pi", "&#x03C0;"), ident("p"),
    ),
    'P' to listOf(
        oper("Phi", "&#x03A6;"), ident("Psi", "&#x03A8;"), oper("Pi", "&#x03A0;"), ident("P"),
    ),
    'q' to listOf(
        oper("qquad", "    "), oper("quad", "  "), ident("q"),
    ),
    'Q' to listOf(oper("QQ", "&#x211A;"), ident("Q")),
    'r' to listOf(
        oper("rarr", "&#x2192;"), oper("rArr", "&#x21D2;"), binaryEmbed("root", "mroot"),
        ident("rho", "&#x03C1;"), ident("r"),
    ),
    'R' to listOf(oper("RR", "&#x211D;"), ident("R")),
    's' to listOf(
        binaryEmbed("stackrel", "mover"), oper("setminus", "&#92;"), oper("square", "&#x25A1;"),
        ident("sigma", "&#x03C3;"), underOverOper("sube", "&#x2286;"), underOverOper("supe", "&#x2287;"),
        unaryEmbed("sqrt", "msqrt"), unary("sinh"), unary("sech"), underOverOper("sum", "&#x2211;"),
        underOverOper("sub", "&#x2282;"), underOverOper("sup", "&#x2283;"), unary("sin"), unary("sec"),
        unaryAttr("sf", "mstyle", "style=\"font-family: var(--sans-font), sans-serif\""), ident("s"),
    ),
    'S' to listOf(
        oper("Sigma", "&#x03A3;"), unary("Sinh"), unary("Sin"), unary("Sec"), ident("S"),
    ),
    't' to listOf(
        ident("theta", "&#x03B8;"), unaryUnderOver("tilde", "mover", "&#126;"),
        unaryEmbed("text", "mtext"), unary("tanh"), unary("tan"), ident("tau", "&#x03C4;"),
        unaryAttr("tt", "mstyle", "style=\"font-family: var(--mono-font), monospace\""), ident("t"),
    ),
    'T' to listOf(
        oper("Theta", "&#x0398;"), unary("Tanh"), unary("Tan"), oper("TT", "&#x22A4;"), ident("T"),
    ),
    'u' to listOf(
        binaryEmbed("underset", "munder"), ident("upsilon", "&#x03C5;"),
        unaryUnderOver("ubrace", "munder", "&#x23DF;"), oper("uarr", "&#x2191;"),
        underOverOper("uuu", "&#x22C3;"), oper("uu", "&#x222A;"),
        unaryUnderOver("ul", "munder", "&#x0332;"), ident("u"),
    ),
    'U' to listOf(ident("U")),
    'v' to listOf(
        ident("varepsilon", "&#x025B;"), ident("vartheta", "&#x03D1;"), ident("varphi", "&#x03C6;"),
        oper("vdots", "&#x22EE;"), unaryUnderOver("vec", "mover", "&#x2192;"),
        underOverOper("vvv", "&#x22C1;"), oper("vv", "&#x2228;"), ident("v"),
    ),
    'V' to listOf(ident("V")),
    'w' to listOf(ident("w")),
    'W' to listOf(ident("W")),
    'x' to listOf(ident("xi", "&#x03BE;"), oper("xx", "&#x00D7;"), ident("x")),
    'X' to listOf(ident("Xi", "&#x039E;"), ident("X")),
    'y' to listOf(ident("y")),
    'Y' to listOf(ident("Y")),
    'z' to listOf(ident("zeta", "&#x03B6;"), ident("z")),
    'Z' to listOf(oper("ZZ", "&#x2124;"), ident("Z")),
    '-' to listOf(
        oper("__|", "&#x230B;"), oper("-<=", "&#x2AAF;"), oper("->>", "&#x21A0;"), oper("->", "&#x2192;"),
        oper("-<", "&#x227A;"), oper("-:", "&#x00F7;"), oper("-=", "&#x2261;"), oper("-+", "&#x2213;"),
        oper("-", "&#x2212;"),
    ),
    '*' to listOf(oper("***", "&#x22C6;"), oper("**", "&#x2217;"), oper("*", "&#x22C5;")),
    '+' to listOf(oper("+-", "&#x00B1;"), oper("+", "&#43;")),
    '/' to listOf(oper("/_\\", "&#x25B3;"), oper("/_", "&#x2220;"), oper("//", "&#47;"), oper("/", "")),
    '\\' to listOf(oper("\\\\", "&#92;"), oper("\\", "&#x00A0;")),
    '|' to listOf(
        oper("|><|", "&#x22C8;"), oper("|><", "&#x22C9;"), oper("|->", "&#x21A6;"), oper("|--", "&#x22A2;"),
        oper("|==", "&#x22A8;"), oper("|__", "&#x230A;"), leftMatrix("||:", "&#124;"), leftMatrix("|::"),
        oper("|~", "&#x2308;"), leftBracket("|:", "&#124;"), rightMatrix("|)", "&#41;"),
        rightMatrix("|]", "&#93;"), rightMatrix("|}", "&#125;"), oper("|", "&#124;"),
    ),
    '<' to listOf(oper("<=>", "&#x21D4;"), oper("<=", "&#x2264;"), oper("<<", "&#x226A;"), oper("<", "&#60;")),
    '>' to listOf(
        oper(">->>", "&#x2916;"), oper(">->", "&#x21A3;"), oper("><|", "&#x22CA;"), oper(">-=", "&#x2AB0;"),
        oper(">=", "&#x2265;"), oper(">-", "&#x227B;"), oper(">>", "&#x226B;"), oper(">", "&#62;"),
    ),
    '=' to listOf(oper("=>", "&#x21D2;"), oper("=", "&#61;")),
    '@' to listOf(oper("@", "&#x2218;")),
    '^' to listOf(underOverOper("^^^", "&#x22C0;"), oper("^^", "&#x2227;"), oper("^", "")),
    '~' to listOf(oper("~~", "&#x2248;"), oper("~=", "&#x2245;"), oper("~|", "&#x2309;"), oper("~", "&#x223C;")),
    '!' to listOf(oper("!in", "&#x2209;"), oper("!=", "&#x2260;"), oper("!", "&#33;")),
    ':' to listOf(
        rightMatrix(":||", "&#124;"), rightMatrix("::|"), oper(":=", "&#58;&#61;"),
        rightBracket(":)", "&#x232A;"), rightBracket(":|", "&#124;"), rightBracket(":}", "&#125;"),
        oper(":.", "&#x2234;"), oper(":'", "&#x2235;"), oper(":", "&#58;"),
    ),
    ';' to listOf(matrixRowSep(";;"), matrixCellSep(";")),
    '.' to listOf(oper("...", "&#46;&#46;&#46;")),
    ',' to listOf(oper(",", "&#44;")),
    '_' to listOf(oper("_|_", "&#x22A5;"), oper("_", "")),
    '\'' to listOf(oper("'", "&#x2032;")),
    '(' to listOf(leftMatrix("(|", "&#40;"), leftBracket("(:", "&#x2329;"), leftBracket("(", "&#40;")),
    ')' to listOf(rightBracket(")", "&#41;")),
    '[' to listOf(leftMatrix("[|", "&#91;"), leftBracket("[", "&#91;")),
    ']' to listOf(rightBracket("]", "&#93;")),
    '{' to listOf(leftMatrix("{|", "&#123;"), leftBracket("{:", "&#123;"), leftBracket("{")),
    '}' to listOf(rightBracket("}")),
)
