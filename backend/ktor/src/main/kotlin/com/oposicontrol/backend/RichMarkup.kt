package com.oposicontrol.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * FA38: the STRICT, whitelist grammar for the rich text of razonados (explanations) and its sanitizing parser.
 * Contract: E1-V1-MARKUP-CONTRACT.md. One parser for every write path (bulk import, staging edits, question editor).
 *
 * ## Grammar (authoring source)
 * - `**bold**`  `__italic__`  `++underline++`  `~~strike~~`: ONLY the DOUBLE marker is syntax. A run of exactly two toggles the style; a single
 *   `*`/`_`/`+`/`~` or a run of three or more is plain text. (Arturo's single `*`/`_`/`~` are resolved as double markers.)
 * - `[color:NAME]...[/color]` with NAME in [COLORS] or `#RRGGBB`; `[size:TOKEN]...[/size]` with TOKEN in [SIZES] (aliases 12/14/16/18/20 =
 *   xs/sm/md/lg/xl; never a free number); `[align:left|center|right|justify]...[/align]` wraps whole blocks and cannot nest.
 * - `[p]` ends the paragraph. A raw newline is NOT a break (PDF text wraps lines): it is a space. `[-]` at the START of a line starts a bullet
 *   item that lasts until the next `[-]`/`[p]`/`[/align]`/end.
 * - Escapes `\\* \\_ \\+ \\~ \\[ \\] \\\\` give the literal character.
 * - Inline styles (bold, italic, underline, strike, color, size) must close inside the same block, nest properly (LIFO) and at most [MAX_DEPTH] deep.
 * - A bracket expression `[word:arg]` whose word is not a tag, a known tag with a wrong case/shape (`[P]`, `[color=red]`, `[/p]`) and a misplaced
 *   `[-]` are REJECTED (never passed through); `[sic]`, `[1]`... without a colon are plain text.
 * - There is no HTML, no link, no image: `<`, `&`, `http://...` are plain text and a renderer must draw text, never interpret it.
 *
 * ## Output
 * Structured JSON `{"v":1,"blocks":[{"k":"p"|"li","a":"center","spans":[{"text":"x","bold":true,"color":"red","size":"lg"}]}]}`: only keys,
 * values and sizes of the whitelist; the validator [validateJson] is mirrored by `private.markup_valid` in SQL (pinned by shared vectors).
 */
internal object RichMarkup {
    const val VERSION = 1
    const val MAX_SOURCE = 20_000
    const val MAX_BLOCKS = 200
    const val MAX_SPANS = 2_000
    const val MAX_DEPTH = 4
    const val MAX_ISSUES = 20

    val COLORS: Set<String> = setOf("black", "gray", "red", "orange", "yellow", "green", "blue", "purple")
    val SIZES: List<String> = listOf("xs", "sm", "md", "lg", "xl")
    private val SIZE_ALIASES = mapOf("12" to "xs", "14" to "sm", "16" to "md", "18" to "lg", "20" to "xl")
    val ALIGNS: Set<String> = setOf("left", "center", "right", "justify")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    private val HEX_NORMALIZED = Regex("^#[0-9A-F]{6}$")

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val color: String? = null,
        val size: String? = null,
    )

    enum class Kind(val wire: String) { P("p"), LI("li") }
    data class Block(val kind: Kind, val align: String?, val spans: List<Span>)
    data class Doc(val blocks: List<Block>)
    data class Issue(val code: String, val at: Int)

    sealed interface Result {
        data class Ok(val doc: Doc) : Result
        data class Rejected(val issues: List<Issue>) : Result
    }

    // ---------------------------------------------------------------- text hygiene

    /** Drops what must never reach a renderer: C0/C1 controls (except \n and \t, which become whitespace), bidi overrides/isolates, zero-width and BOM. */
    internal fun cleanChars(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val code = c.code
            when {
                c == '\r' -> { if (i + 1 < source.length && source[i + 1] == '\n') i++; out.append('\n') }
                c == '\n' -> out.append('\n')
                c == '\t' || code == 0xA0 -> out.append(' ')
                code < 0x20 || code in 0x7F..0x9F -> Unit
                code in 0x200B..0x200F || code in 0x202A..0x202E || code in 0x2060..0x2064 || code in 0x2066..0x2069 || code == 0xFEFF || code == 0x2028 || code == 0x2029 -> Unit
                Character.isSurrogate(c) -> {
                    // keep only well-formed pairs
                    if (Character.isHighSurrogate(c) && i + 1 < source.length && Character.isLowSurrogate(source[i + 1])) { out.append(c).append(source[i + 1]); i++ }
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Text that, parsed again, gives exactly [plain] (whitespace collapsed): escapes every character that could be syntax. */
    fun escape(plain: String): String = buildString {
        for (c in plain) {
            if (c == '\\' || c == '*' || c == '_' || c == '+' || c == '~' || c == '[' || c == ']') append('\\')
            append(c)
        }
    }

    // ---------------------------------------------------------------- parser

    private data class Open(val kind: String, val arg: String?, val at: Int)

    private val TAG = Regex("^\\[(/)?([A-Za-z]+)(?::([^\\[\\]\\n]{0,40}))?]")
    /** A known tag name right after `[` or `[/` and directly followed by `]`, `:` or `=`: it is meant as a tag even if misspelled in case/shape (`[P]`, `[color=red]`); `[p. 5]` is a citation. */
    private val NEAR_MISS = Regex("^\\[/?(?i:p|color|size|align)(?=[\\]:=])")
    private val MARKERS = mapOf('*' to "bold", '_' to "italic", '+' to "underline", '~' to "strike")

    fun parse(rawSource: String): Result {
        val issues = ArrayList<Issue>()
        fun issue(code: String, at: Int) { if (issues.size < MAX_ISSUES) issues += Issue(code, at) }
        if (rawSource.length > MAX_SOURCE) return Result.Rejected(listOf(Issue("TOO_LONG", MAX_SOURCE)))
        val src = cleanChars(rawSource)

        val blocks = ArrayList<Block>()
        var totalSpans = 0
        var kind = Kind.P
        var align: String? = null
        var alignAt = -1
        val spans = ArrayList<Span>()
        val buf = StringBuilder()
        val stack = ArrayList<Open>()

        fun style(): Span {
            var s = Span("")
            for (o in stack) {
                s = when (o.kind) {
                    "bold" -> s.copy(bold = true)
                    "italic" -> s.copy(italic = true)
                    "underline" -> s.copy(underline = true)
                    "strike" -> s.copy(strike = true)
                    "color" -> s.copy(color = o.arg)
                    "size" -> s.copy(size = o.arg)
                    else -> s
                }
            }
            return s
        }
        fun flush() {
            if (buf.isEmpty()) return
            val st = style()
            val last = spans.lastOrNull()
            if (last != null && last.copy(text = "") == st) spans[spans.size - 1] = last.copy(text = last.text + buf) else spans += st.copy(text = buf.toString())
            buf.setLength(0)
        }
        fun finishBlock(at: Int) {
            flush()
            if (stack.isNotEmpty()) {
                issue("UNCLOSED_MARKER", stack.first().at)
                stack.clear()
            }
            // trim the edges of the block
            while (spans.isNotEmpty() && spans.first().text.isBlank()) spans.removeAt(0)
            while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.size - 1)
            if (spans.isNotEmpty()) {
                spans[0] = spans[0].copy(text = spans[0].text.trimStart())
                spans[spans.size - 1] = spans.last().copy(text = spans.last().text.trimEnd())
                totalSpans += spans.size
                if (blocks.size >= MAX_BLOCKS) issue("TOO_MANY_BLOCKS", at) else blocks += Block(kind, align, spans.toList())
                if (totalSpans > MAX_SPANS) issue("TOO_MANY_SPANS", at)
            }
            spans.clear()
            kind = Kind.P
        }
        fun addChar(c: Char) {
            if (c == ' ' || c == '\n') {
                val prevSpace = if (buf.isNotEmpty()) buf.last() == ' ' else (spans.lastOrNull()?.text?.endsWith(' ') ?: true)
                if (!prevSpace) buf.append(' ')
            } else buf.append(c)
        }
        fun push(kindName: String, arg: String?, at: Int) {
            flush()
            if (stack.size >= MAX_DEPTH) { issue("TOO_DEEP", at); return }
            stack += Open(kindName, arg, at)
        }
        fun pop(kindName: String, at: Int): Boolean {
            flush()
            val top = stack.lastOrNull()
            if (top == null || top.kind != kindName) {
                issue(if (stack.any { it.kind == kindName }) "MISALIGNED_NESTING" else "UNMATCHED_CLOSE", at)
                return false
            }
            stack.removeAt(stack.size - 1)
            return true
        }

        var i = 0
        var lineStart = true
        while (i < src.length) {
            val c = src[i]
            if (lineStart) {
                // leading blanks of a line never count; `[-]` here opens a bullet
                var j = i
                while (j < src.length && src[j] == ' ') j++
                if (src.startsWith("[-]", j)) {
                    finishBlock(i)
                    kind = Kind.LI
                    i = j + 3
                    lineStart = false
                    continue
                }
                i = j
                lineStart = false
                if (i >= src.length) break
                if (src[i] == '\n') { addChar(' '); i++; lineStart = true; continue }
            }
            val ch = src[i]
            when {
                ch == '\n' -> { addChar(' '); i++; lineStart = true }
                ch == '\\' && i + 1 < src.length && src[i + 1] in "\\*_+~[]" -> { buf.append(src[i + 1]); i += 2 }
                ch in MARKERS -> {
                    var run = 1
                    while (i + run < src.length && src[i + run] == ch) run++
                    if (run == 2) {
                        val name = MARKERS.getValue(ch)
                        if (stack.lastOrNull()?.kind == name) pop(name, i)
                        else if (stack.any { it.kind == name }) { issue("MISALIGNED_NESTING", i); flush() }
                        else push(name, null, i)
                    } else {
                        repeat(run) { buf.append(ch) }
                    }
                    i += run
                }
                ch == '[' -> {
                    if (src.startsWith("[-]", i)) { issue("BULLET_NOT_AT_LINE_START", i); i += 3; continue }
                    val m = TAG.find(src.substring(i, minOf(src.length, i + 80)))
                    if (m == null) {
                        if (NEAR_MISS.containsMatchIn(src.substring(i, minOf(src.length, i + 12)))) { issue("MALFORMED_TAG", i); i++ }
                        else { buf.append('['); i++ }
                        continue
                    }
                    val closing = m.groupValues[1] == "/"
                    val name = m.groupValues[2]
                    val arg = m.groups[3]?.value
                    val known = name.lowercase() in setOf("p", "color", "size", "align")
                    when {
                        !known && arg != null -> issue("UNKNOWN_TAG", i)
                        !known -> { buf.append(m.value) } // [sic], [1]: plain text
                        name != name.lowercase() -> issue("MALFORMED_TAG", i)
                        name == "p" -> if (closing || arg != null) issue("MALFORMED_TAG", i) else { finishBlock(i) }
                        closing -> {
                            if (arg != null) issue("MALFORMED_TAG", i)
                            else if (name == "align") {
                                if (align == null) issue("UNMATCHED_CLOSE", i)
                                else if (stack.isNotEmpty()) { issue("UNCLOSED_MARKER", stack.first().at); stack.clear(); finishBlock(i); align = null }
                                else { finishBlock(i); align = null }
                            } else pop(name, i)
                        }
                        arg == null -> issue("MALFORMED_TAG", i)
                        name == "color" -> {
                            val v = if (HEX.matches(arg)) arg.uppercase() else arg.takeIf { it in COLORS }
                            if (v == null) issue("BAD_COLOR", i) else push("color", v, i)
                        }
                        name == "size" -> {
                            val v = if (arg in SIZES) arg else SIZE_ALIASES[arg]
                            if (v == null) issue("BAD_SIZE", i) else push("size", v, i)
                        }
                        name == "align" -> {
                            if (arg !in ALIGNS) issue("BAD_ALIGN", i)
                            else if (align != null) issue("ALIGN_NESTED", i)
                            else if (stack.isNotEmpty()) issue("ALIGN_INSIDE_INLINE", i)
                            else { finishBlock(i); align = arg; alignAt = i }
                        }
                    }
                    i += m.value.length
                }
                else -> { addChar(ch); i++ }
            }
        }
        finishBlock(src.length)
        if (align != null) issue("UNCLOSED_ALIGN", alignAt)
        return if (issues.isEmpty()) Result.Ok(Doc(blocks.toList())) else Result.Rejected(issues.toList())
    }

    // ---------------------------------------------------------------- output

    fun toJson(doc: Doc): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("blocks", JsonArray(doc.blocks.map { b ->
            buildJsonObject {
                put("k", b.kind.wire)
                b.align?.let { put("a", it) }
                put("spans", JsonArray(b.spans.map { s ->
                    buildJsonObject {
                        put("text", s.text)
                        if (s.bold) put("bold", true)
                        if (s.italic) put("italic", true)
                        if (s.underline) put("underline", true)
                        if (s.strike) put("strike", true)
                        s.color?.let { put("color", it) }
                        s.size?.let { put("size", it) }
                    }
                }))
            }
        }))
    }

    /** The plain-text fallback (also what the legacy `razonamiento` string carries): paragraphs on their own line, bullets as `• `. */
    fun plain(doc: Doc): String = doc.blocks.joinToString("\n") { b -> (if (b.kind == Kind.LI) "• " else "") + b.spans.joinToString("") { it.text } }

    /** `true` when the document has no styling at all (then only the plain text is worth storing). */
    fun isPlain(doc: Doc): Boolean = doc.blocks.all { it.kind == Kind.P && it.align == null && it.spans.all { s -> !s.bold && !s.italic && !s.underline && !s.strike && s.color == null && s.size == null } } &&
        doc.blocks.size <= 1

    // ---------------------------------------------------------------- validator of the STORED structure (mirrors private.markup_valid)

    fun validateJson(json: JsonElement): Boolean {
        val root = json as? JsonObject ?: return false
        if (root.keys != setOf("v", "blocks")) return false
        if ((root["v"] as? JsonPrimitive)?.intOrNull != VERSION) return false
        val blocks = root["blocks"] as? JsonArray ?: return false
        if (blocks.size > MAX_BLOCKS) return false
        var spanCount = 0
        for (b in blocks) {
            val o = b as? JsonObject ?: return false
            if (!setOf("k", "a", "spans").containsAll(o.keys) || "k" !in o || "spans" !in o) return false
            val k = (o["k"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (k != "p" && k != "li") return false
            if ("a" in o) { val a = (o["a"] as? JsonPrimitive)?.takeIf { it.isString }?.content; if (a !in ALIGNS) return false }
            val spans = o["spans"] as? JsonArray ?: return false
            if (spans.isEmpty()) return false
            spanCount += spans.size
            if (spanCount > MAX_SPANS) return false
            for (sp in spans) {
                val s = sp as? JsonObject ?: return false
                if (!setOf("text", "bold", "italic", "underline", "strike", "color", "size").containsAll(s.keys) || "text" !in s) return false
                val text = (s["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                if (text.isEmpty() || text.length > MAX_SOURCE) return false
                if (text != cleanChars(text) || text.contains('\n')) return false
                for (flag in listOf("bold", "italic", "underline", "strike")) if (flag in s && (s[flag] as? JsonPrimitive)?.booleanOrNull != true) return false
                if ("color" in s) { val c = (s["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content; if (c == null || (c !in COLORS && !HEX_NORMALIZED.matches(c))) return false }
                if ("size" in s) { val z = (s["size"] as? JsonPrimitive)?.takeIf { it.isString }?.content; if (z !in SIZES) return false }
            }
        }
        return true
    }

    /** What a write path stores for a razonamiento typed by an admin: the plain text for the legacy column and, only when styled, the structure. */
    data class Stored(val plain: String, val rico: JsonObject?)

    fun storeFor(source: String): Pair<Stored?, List<Issue>> {
        // A text without a single tag is stored exactly as typed (only hostile characters are removed): newlines the editor typed stay newlines.
        if (!looksStyled(source)) return Stored(cleanChars(source), null) to emptyList()
        return when (val r = parse(source)) {
            is Result.Ok -> Stored(plain(r.doc), toJson(r.doc).takeUnless { isPlain(r.doc) }) to emptyList()
            is Result.Rejected -> null to r.issues
        }
    }

    private val STYLE_HINT = Regex("(\\*\\*|__|\\+\\+|~~|\\[(?i:p|color|size|align)(?=[\\]:=])|\\[-]|\\[/(?i:p|color|size|align)(?=[\\]:=]))")

    /** `true` when the text uses (or tries to use) any syntax of the grammar; only then the strict parser decides. */
    fun looksStyled(source: String): Boolean = STYLE_HINT.containsMatchIn(source) || ESCAPE_HINT.containsMatchIn(source)

    /** A backslash escape of a syntax character (`\*`, `\[`...): meaningful only to this grammar. */
    private val ESCAPE_HINT = Regex("\\\\[\\\\*_+~\\[\\]]")
}
