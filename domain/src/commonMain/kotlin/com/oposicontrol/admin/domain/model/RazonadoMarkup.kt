package com.oposicontrol.admin.domain.model

/**
 * Marcado de los razonados (FA38): gramática estricta de lista blanca y su analizador saneador. Contrato de servidor:
 * `E1-V1-MARKUP-CONTRACT.md`; la autoridad es el servidor (`RichMarkup.kt` + `private.markup_valid`), que vuelve a analizar
 * TODO lo que se guarda. Este analizador es una copia PARIDAD para dos usos del panel: avisar al instante de qué está mal
 * y enseñar la vista previa EXACTA que verá la app. Los vectores compartidos con la base de datos están en
 * `RazonadoMarkupTest` (mismas fuentes, mismo JSON, mismo texto plano).
 *
 * ### Gramática
 * | Marcado                        | Efecto                                                                    |
 * |--------------------------------|---------------------------------------------------------------------------|
 * | `**x**` `__x__` `++x++` `~~x~~` | negrita, cursiva, subrayado, tachado. SOLO el marcador DOBLE es sintaxis.  |
 * | `[color:NOMBRE]x[/color]`       | NOMBRE de la paleta cerrada o `#RRGGBB`.                                   |
 * | `[size:TOKEN]x[/size]`          | `xs sm md lg xl` (alias de autoría `12 14 16 18 20`). Nunca un número libre.|
 * | `[align:left\|center\|right\|justify]…[/align]` | alinea bloques enteros; no anida.                    |
 * | `[p]`                           | corte de párrafo explícito: un salto de línea suelto es un ESPACIO.        |
 * | `[-]` al principio de línea     | viñeta, hasta el siguiente `[-]`, `[p]`, `[/align]` o el final.            |
 * | `\* \_ \+ \~ \[ \] \\`          | el carácter, literal.                                                      |
 *
 * Un `*`, `_`, `+` o `~` suelto, o una racha de tres o más, es texto. `[sic]`, `[1]`, `[p. 5]` son texto. Una etiqueta
 * con dos puntos cuya palabra no es una etiqueta (`[colour:red]`, `[img:…]`), una etiqueta conocida mal escrita (`[P]`,
 * `[color=red]`, `[/p]`), una viñeta fuera de principio de línea, un marcador sin cerrar o cruzado, o un valor no
 * permitido RECHAZAN el texto: nada se deja pasar. No hay HTML, enlaces ni imágenes: `<b>` y `http://x` son texto.
 *
 * El resultado es un ÁRBOL TIPADO (bloques y tramos), nunca HTML ni una cadena para incrustar.
 */
object RazonadoMarkup {
    const val VERSION = 1
    const val MAX_SOURCE = 20_000
    const val MAX_BLOCKS = 200
    const val MAX_SPANS = 2_000
    const val MAX_DEPTH = 4
    const val MAX_ISSUES = 20

    val COLORS: List<String> = listOf("black", "gray", "red", "orange", "yellow", "green", "blue", "purple")
    val SIZES: List<String> = listOf("xs", "sm", "md", "lg", "xl")
    private val SIZE_ALIASES = mapOf("12" to "xs", "14" to "sm", "16" to "md", "18" to "lg", "20" to "xl")
    val ALIGNS: List<String> = listOf("left", "center", "right", "justify")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        /** Nombre de la paleta o `#RRGGBB` en mayúsculas. */
        val color: String? = null,
        val size: String? = null,
    )

    enum class Kind(val wire: String) { PARAGRAPH("p"), BULLET("li") }

    data class Block(val kind: Kind, val align: String?, val spans: List<Span>)

    data class Doc(val blocks: List<Block>)

    /** [at] es el desplazamiento en caracteres del texto limpio donde se detectó el problema. */
    data class Issue(val code: String, val at: Int)

    sealed interface Result {
        data class Ok(val doc: Doc) : Result
        data class Rejected(val issues: List<Issue>) : Result
    }

    // ---------------------------------------------------------------- higiene del texto

    /** Quita lo que nunca debe llegar a un pintor: controles C0/C1 (salvo `\n`), bidi, ancho cero, BOM y sustitutos sueltos. */
    internal fun cleanChars(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val code = c.code
            when {
                c == '\r' -> {
                    if (i + 1 < source.length && source[i + 1] == '\n') i++
                    out.append('\n')
                }
                c == '\n' -> out.append('\n')
                c == '\t' || code == 0xA0 -> out.append(' ')
                code < 0x20 || code in 0x7F..0x9F -> Unit
                code in 0x200B..0x200F || code in 0x202A..0x202E || code in 0x2060..0x2064 ||
                    code in 0x2066..0x2069 || code == 0xFEFF || code == 0x2028 || code == 0x2029 -> Unit
                c.isSurrogate() -> {
                    // solo los pares bien formados
                    if (c.isHighSurrogate() && i + 1 < source.length && source[i + 1].isLowSurrogate()) {
                        out.append(c).append(source[i + 1])
                        i++
                    }
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Texto que, analizado de nuevo, da exactamente [plain]: escapa todo carácter que podría ser sintaxis. */
    fun escape(plain: String): String = buildString {
        for (c in plain) {
            if (c == '\\' || c == '*' || c == '_' || c == '+' || c == '~' || c == '[' || c == ']') append('\\')
            append(c)
        }
    }

    // ---------------------------------------------------------------- analizador

    private class Open(val kind: String, val arg: String?, val at: Int)

    private val TAG = Regex("^\\[(/)?([A-Za-z]+)(?::([^\\[\\]\\n]{0,40}))?]")

    /** Un nombre de etiqueta conocido justo tras `[` o `[/` y seguido de `]`, `:` o `=`: se quiso como etiqueta. `[p. 5]` es una cita. */
    private val NEAR_MISS = Regex("^\\[/?(p|color|size|align)(?=[\\]:=])", RegexOption.IGNORE_CASE)
    private val KNOWN_TAGS = setOf("p", "color", "size", "align")
    private val MARKERS = mapOf('*' to "bold", '_' to "italic", '+' to "underline", '~' to "strike")

    fun parse(rawSource: String): Result {
        val issues = ArrayList<Issue>()
        fun issue(code: String, at: Int) {
            if (issues.size < MAX_ISSUES) issues += Issue(code, at)
        }
        if (rawSource.length > MAX_SOURCE) return Result.Rejected(listOf(Issue("TOO_LONG", MAX_SOURCE)))
        val src = cleanChars(rawSource)

        val blocks = ArrayList<Block>()
        var totalSpans = 0
        var kind = Kind.PARAGRAPH
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
            if (last != null && last.copy(text = "") == st) {
                spans[spans.size - 1] = last.copy(text = last.text + buf)
            } else {
                spans += st.copy(text = buf.toString())
            }
            buf.setLength(0)
        }

        fun finishBlock(at: Int) {
            flush()
            if (stack.isNotEmpty()) {
                issue("UNCLOSED_MARKER", stack.first().at)
                stack.clear()
            }
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
            kind = Kind.PARAGRAPH
        }

        fun addChar(c: Char) {
            if (c == ' ' || c == '\n') {
                val prevSpace = if (buf.isNotEmpty()) buf.last() == ' ' else (spans.lastOrNull()?.text?.endsWith(' ') ?: true)
                if (!prevSpace) buf.append(' ')
            } else {
                buf.append(c)
            }
        }

        fun push(kindName: String, arg: String?, at: Int) {
            flush()
            if (stack.size >= MAX_DEPTH) {
                issue("TOO_DEEP", at)
                return
            }
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
            if (lineStart) {
                // Los blancos iniciales de una línea no cuentan; `[-]` aquí abre una viñeta.
                var j = i
                while (j < src.length && src[j] == ' ') j++
                if (src.startsWith("[-]", j)) {
                    finishBlock(i)
                    kind = Kind.BULLET
                    i = j + 3
                    lineStart = false
                    continue
                }
                i = j
                lineStart = false
                if (i >= src.length) break
                if (src[i] == '\n') {
                    addChar(' ')
                    i++
                    lineStart = true
                    continue
                }
            }
            val ch = src[i]
            when {
                ch == '\n' -> {
                    addChar(' ')
                    i++
                    lineStart = true
                }
                ch == '\\' && i + 1 < src.length && src[i + 1] in "\\*_+~[]" -> {
                    buf.append(src[i + 1])
                    i += 2
                }
                MARKERS.containsKey(ch) -> {
                    var run = 1
                    while (i + run < src.length && src[i + run] == ch) run++
                    if (run == 2) {
                        val name = MARKERS.getValue(ch)
                        when {
                            stack.lastOrNull()?.kind == name -> pop(name, i)
                            stack.any { it.kind == name } -> {
                                issue("MISALIGNED_NESTING", i)
                                flush()
                            }
                            else -> push(name, null, i)
                        }
                    } else {
                        repeat(run) { buf.append(ch) }
                    }
                    i += run
                }
                ch == '[' -> {
                    if (src.startsWith("[-]", i)) {
                        issue("BULLET_NOT_AT_LINE_START", i)
                        i += 3
                        continue
                    }
                    val m = TAG.find(src.substring(i, minOf(src.length, i + 80)))
                    if (m == null) {
                        if (NEAR_MISS.containsMatchIn(src.substring(i, minOf(src.length, i + 12)))) {
                            issue("MALFORMED_TAG", i)
                        } else {
                            buf.append('[')
                        }
                        i++
                        continue
                    }
                    val closing = m.groupValues[1] == "/"
                    val name = m.groupValues[2]
                    val arg = m.groups[3]?.value
                    val known = name.lowercase() in KNOWN_TAGS
                    when {
                        !known && arg != null -> issue("UNKNOWN_TAG", i)
                        !known -> buf.append(m.value) // [sic], [1]: texto
                        name != name.lowercase() -> issue("MALFORMED_TAG", i)
                        name == "p" -> if (closing || arg != null) issue("MALFORMED_TAG", i) else finishBlock(i)
                        closing -> {
                            if (arg != null) {
                                issue("MALFORMED_TAG", i)
                            } else if (name == "align") {
                                if (align == null) {
                                    issue("UNMATCHED_CLOSE", i)
                                } else if (stack.isNotEmpty()) {
                                    issue("UNCLOSED_MARKER", stack.first().at)
                                    stack.clear()
                                    finishBlock(i)
                                    align = null
                                } else {
                                    finishBlock(i)
                                    align = null
                                }
                            } else {
                                pop(name, i)
                            }
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
                        name == "align" -> when {
                            arg !in ALIGNS -> issue("BAD_ALIGN", i)
                            align != null -> issue("ALIGN_NESTED", i)
                            stack.isNotEmpty() -> issue("ALIGN_INSIDE_INLINE", i)
                            else -> {
                                finishBlock(i)
                                align = arg
                                alignAt = i
                            }
                        }
                    }
                    i += m.value.length
                }
                else -> {
                    addChar(ch)
                    i++
                }
            }
        }
        finishBlock(src.length)
        if (align != null) issue("UNCLOSED_ALIGN", alignAt)
        return if (issues.isEmpty()) Result.Ok(Doc(blocks.toList())) else Result.Rejected(issues.toList())
    }

    // ---------------------------------------------------------------- salidas

    /** El texto plano de respaldo (lo que guarda `razonamiento`): un párrafo por línea y las viñetas con `• `. */
    fun plain(doc: Doc): String =
        doc.blocks.joinToString("\n") { b -> (if (b.kind == Kind.BULLET) "• " else "") + b.spans.joinToString("") { it.text } }

    /** `true` si el documento no lleva ningún estilo: entonces solo vale la pena guardar el texto plano. */
    fun isPlain(doc: Doc): Boolean =
        doc.blocks.size <= 1 && doc.blocks.all { b ->
            b.kind == Kind.PARAGRAPH && b.align == null &&
                b.spans.all { s -> !s.bold && !s.italic && !s.underline && !s.strike && s.color == null && s.size == null }
        }

    /** La estructura versionada tal como la sirve el servidor (`razonamientoRico`), con las claves en el mismo orden. */
    fun toWireJson(doc: Doc): String = buildString {
        append("{\"v\":$VERSION,\"blocks\":[")
        doc.blocks.forEachIndexed { bi, b ->
            if (bi > 0) append(',')
            append("{\"k\":\"${b.kind.wire}\"")
            b.align?.let { append(",\"a\":\"$it\"") }
            append(",\"spans\":[")
            b.spans.forEachIndexed { si, s ->
                if (si > 0) append(',')
                append("{\"text\":\"${jsonEscape(s.text)}\"")
                if (s.bold) append(",\"bold\":true")
                if (s.italic) append(",\"italic\":true")
                if (s.underline) append(",\"underline\":true")
                if (s.strike) append(",\"strike\":true")
                s.color?.let { append(",\"color\":\"$it\"") }
                s.size?.let { append(",\"size\":\"$it\"") }
                append('}')
            }
            append("]}")
        }
        append("]}")
    }

    private fun jsonEscape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(c)
            }
        }
    }
}

/** Texto en lenguaje de operador para cada motivo de rechazo del analizador (los códigos son los del servidor). */
object RazonadoMarkupMessages {
    fun describe(code: String): String = when (code) {
        "UNCLOSED_MARKER" -> "Hay un marcador (**, __, ++, ~~, [color] o [size]) sin cerrar."
        "MISALIGNED_NESTING" -> "Los marcadores se cruzan: ciérralos en el orden inverso al que los abriste."
        "UNMATCHED_CLOSE" -> "Hay una etiqueta de cierre sin su apertura."
        "UNKNOWN_TAG" -> "Etiqueta desconocida. Solo existen [color], [size], [align], [p] y [-]."
        "MALFORMED_TAG" -> "Etiqueta mal escrita: van en minúsculas y con el formato [color:red], [size:lg], [align:center], [p]."
        "BAD_COLOR" -> "Color no permitido. Usa ${RazonadoMarkup.COLORS.joinToString(", ")} o #RRGGBB."
        "BAD_SIZE" -> "Tamaño no permitido. Usa ${RazonadoMarkup.SIZES.joinToString(", ")} (o 12, 14, 16, 18, 20)."
        "BAD_ALIGN" -> "Alineación no permitida. Usa ${RazonadoMarkup.ALIGNS.joinToString(", ")}."
        "ALIGN_NESTED" -> "No se puede anidar [align]."
        "ALIGN_INSIDE_INLINE" -> "[align] no puede empezar dentro de un estilo: ábrelo antes."
        "UNCLOSED_ALIGN" -> "Falta [/align]."
        "BULLET_NOT_AT_LINE_START" -> "La viñeta [-] va al principio de una línea."
        "TOO_DEEP" -> "Demasiados estilos anidados (máximo ${RazonadoMarkup.MAX_DEPTH})."
        "TOO_LONG" -> "El texto supera ${RazonadoMarkup.MAX_SOURCE} caracteres."
        "TOO_MANY_BLOCKS" -> "Demasiados párrafos (máximo ${RazonadoMarkup.MAX_BLOCKS})."
        "TOO_MANY_SPANS" -> "Demasiados tramos con estilo (máximo ${RazonadoMarkup.MAX_SPANS})."
        else -> "El servidor no admite este texto ($code)."
    }
}
