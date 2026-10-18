package com.oposicontrol.admin.domain.model

/**
 * Marcado de texto de los razonados (carga masiva y editor de Control), versión 1.
 *
 * ### Gramática (lista blanca estricta; nada fuera de ella tiene efecto)
 * | Marcado                         | Efecto                         |
 * |---------------------------------|--------------------------------|
 * | `**texto**`                     | negrita                        |
 * | `__texto__`                     | cursiva                        |
 * | `++texto++`                     | subrayado                      |
 * | `~~texto~~`                     | tachado                        |
 * | `[color:NOMBRE]texto[/color]`   | color de la paleta fija        |
 * | `[size:NOMBRE]texto[/size]`     | tamaño de la escala fija       |
 * | `[align:NOMBRE]…[/align]`       | alineación de los bloques      |
 * | `[p]`                           | corte de párrafo               |
 * | `[-]` al principio de una línea | viñeta                         |
 * | `\*` `\_` `\+` `\~` `\[` `\\`   | el carácter, literal           |
 *
 * ### Decisiones que resuelven las ambigüedades de la lista del cliente
 * - **Marcadores DOBLES** para negrita, cursiva, subrayado y tachado. Un `*`, un `_` o un `~` sueltos son texto: así un
 *   `_` dentro de `codigo_examen` o un `*` de una multiplicación no abren nada por accidente.
 * - **Color y tamaño salen de conjuntos CERRADOS** ([MarkupColor], [MarkupSize]). No hay `#ff0000` ni `[size:18]`: un valor
 *   arbitrario se rechaza y se avisa. Cada nombre se asocia en la pantalla a un token del diseño; añadir un valor es una
 *   decisión de diseño documentada, no un número que alguien teclea en un PDF. (Pendiente de confirmar con el cliente que
 *   la paleta y la escala cubren lo que usa: ver `E6-V1-PANEL-08.md`.)
 * - Un salto de línea suelto es un ESPACIO (el texto de un PDF viene cortado en líneas): el corte de párrafo es `[p]`.
 *
 * ### Seguridad
 * El resultado es un ÁRBOL TIPADO, nunca HTML ni una cadena para incrustar. Lo que parece HTML (`<b>`, `<script>`,
 * `javascript:`) es texto literal. Se eliminan los caracteres de control, de formato (bidi, ancho cero) y los separadores
 * de línea/párrafo Unicode. Hay límites de longitud, profundidad y número de nodos: una entrada hostil se rechaza, no se
 * procesa entera. [normalize] devuelve la forma canónica que se puede guardar: parsearla de nuevo da el mismo árbol.
 *
 * Contrato con el servidor: `E1-V1-MARKUP-CONTRACT.md` (pendiente de publicarse esta noche). Este analizador implementa la
 * gramática de la lista del cliente con las decisiones de arriba; si E1 fija otra cosa, se cambia AQUÍ y en la prueba.
 */
object RazonadoMarkup {
    const val MAX_LENGTH = 20_000
    const val MAX_DEPTH = 6
    const val MAX_NODES = 5_000

    enum class Severity { ERROR, WARNING }

    data class Issue(val position: Int, val severity: Severity, val code: String, val message: String)

    data class Result(val document: List<MarkupBlock>, val issues: List<Issue>) {
        val hasErrors: Boolean get() = issues.any { it.severity == Severity.ERROR }
        val plainText: String get() = document.joinToString("\n") { it.plainText() }
    }

    fun parse(input: String): Result = Parser(input).run()

    /** Forma canónica del documento: estable, con los literales escapados. `parse(normalize(d)) == d`. */
    fun normalize(document: List<MarkupBlock>): String = document.joinToString("[p]") { block ->
        val bullet = if (block.bullet) "[-]" else ""
        val body = block.inlines.joinToString("") { serialize(it) }
        val wrapped = if (block.align != MarkupAlign.LEFT) "[align:${block.align.wire}]$bullet$body[/align]" else "$bullet$body"
        wrapped
    }

    private fun serialize(node: MarkupInline): String = when (node) {
        is MarkupInline.Text -> escape(node.text)
        is MarkupInline.Styled -> {
            val m = node.style.marker
            m + node.children.joinToString("") { serialize(it) } + m
        }
        is MarkupInline.Colored -> "[color:${node.color.wire}]" + node.children.joinToString("") { serialize(it) } + "[/color]"
        is MarkupInline.Sized -> "[size:${node.size.wire}]" + node.children.joinToString("") { serialize(it) } + "[/size]"
    }

    private fun escape(text: String): String = buildString {
        text.forEach { c -> if (c in SPECIAL) append('\\'); append(c) }
    }

    private const val SPECIAL = "*_+~[\\"

    /** Elimina control, formato (bidi/ancho cero) y separadores Unicode; el salto de línea se conserva para el análisis. */
    internal fun sanitize(raw: String): String = buildString {
        raw.forEach { c ->
            when {
                c == '\n' -> append(c)
                c == '\r' || c == '\t' -> append(' ')
                c.isISOControl() -> Unit
                c.category == CharCategory.FORMAT -> Unit
                c.category == CharCategory.LINE_SEPARATOR || c.category == CharCategory.PARAGRAPH_SEPARATOR -> Unit
                else -> append(c)
            }
        }
    }

    private class Frame(
        val kind: String,
        val style: MarkupStyle? = null,
        val color: MarkupColor? = null,
        val size: MarkupSize? = null,
        /** Abierto con un valor no permitido: se cierra sin efecto y su contenido pasa al padre tal cual. */
        val neutral: Boolean = false,
    ) {
        val children = mutableListOf<MarkupInline>()
    }

    private class Parser(raw: String) {
        private val src: String
        private val issues = mutableListOf<Issue>()
        private val blocks = mutableListOf<MarkupBlock>()
        private val stack = ArrayDeque<Frame>()
        private var pos = 0
        private var nodes = 0
        private var currentAlign = MarkupAlign.LEFT
        private var alignOpenAt = -1
        private var bullet = false
        private var atLineStart = true
        private val text = StringBuilder()

        init {
            if (raw.length > MAX_LENGTH) {
                issues += Issue(MAX_LENGTH, Severity.ERROR, "TOO_LONG", "El texto supera $MAX_LENGTH caracteres.")
            }
            src = sanitize(raw.take(MAX_LENGTH))
            stack.addLast(Frame("root"))
        }

        fun run(): Result {
            while (pos < src.length) {
                if (nodes > MAX_NODES) {
                    issues += Issue(pos, Severity.ERROR, "TOO_MANY_NODES", "El texto tiene demasiados elementos.")
                    break
                }
                val c = src[pos]
                when {
                    c == '\n' -> {
                        // Un salto de línea suelto es un espacio; el corte de párrafo es [p].
                        if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
                        pos++
                        atLineStart = true
                    }
                    c == ' ' && atLineStart -> pos++
                    c == '\\' && pos + 1 < src.length && src[pos + 1] in SPECIAL -> {
                        text.append(src[pos + 1])
                        pos += 2
                        atLineStart = false
                    }
                    c == '[' -> {
                        if (!tag()) {
                            if (UNKNOWN_TAG.containsMatchIn(src.substring(pos, minOf(src.length, pos + 40)))) {
                                issues += Issue(pos, Severity.WARNING, "UNKNOWN_TAG", "Etiqueta desconocida: se deja como texto.")
                            }
                            text.append('[')
                            pos++
                            atLineStart = false
                        }
                    }
                    pair(c) -> atLineStart = false
                    else -> {
                        text.append(c)
                        pos++
                        atLineStart = false
                    }
                }
            }
            finish()
            return Result(blocks.toList(), issues.toList())
        }

        private fun pair(c: Char): Boolean {
            val style = when (c) {
                '*' -> MarkupStyle.BOLD
                '_' -> MarkupStyle.ITALIC
                '+' -> MarkupStyle.UNDERLINE
                '~' -> MarkupStyle.STRIKE
                else -> return false
            }
            if (pos + 1 >= src.length || src[pos + 1] != c) return false
            val open = stack.indexOfLast { it.style == style }
            when {
                open > 0 && open == stack.lastIndex -> closeFrame()
                open > 0 -> issues += Issue(pos, Severity.ERROR, "BAD_NESTING", "El marcador ${style.marker} cierra algo que no está abierto en ese orden.")
                stack.size > MAX_DEPTH -> issues += Issue(pos, Severity.ERROR, "TOO_DEEP", "Demasiados marcadores anidados.")
                else -> {
                    flushText()
                    stack.addLast(Frame("style", style = style))
                }
            }
            pos += 2
            return true
        }

        private fun tag(): Boolean {
            val m = TAG.find(src.substring(pos, minOf(src.length, pos + 40))) ?: return false
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val arg = m.groupValues[3].ifEmpty { null }
            val at = pos
            val len = m.value.length
            when (name) {
                "p" -> {
                    if (closing || arg != null) return false
                    endBlock()
                    atLineStart = true
                }
                "-" -> {
                    if (closing || arg != null) return false
                    if (!atLineStart) {
                        issues += Issue(at, Severity.ERROR, "BULLET_NOT_AT_LINE_START", "La viñeta [-] va al principio de una línea.")
                    } else {
                        endBlock()
                        bullet = true
                    }
                    atLineStart = false
                }
                "color", "size" -> {
                    if (closing) {
                        val top = stack.last()
                        if (top.kind == name) closeFrame() else issues += Issue(at, Severity.ERROR, "BAD_NESTING", "[/$name] no cierra la etiqueta abierta.")
                    } else {
                        val color = if (name == "color") arg?.let { MarkupColor.fromWire(it) } else null
                        val size = if (name == "size") arg?.let { MarkupSize.fromWire(it) } else null
                        val allowed = arg != null && ((name == "color" && color != null) || (name == "size" && size != null))
                        if (!allowed) issues += Issue(at, Severity.ERROR, "VALUE_NOT_ALLOWED", "[$name:${arg.orEmpty()}] no es un valor permitido.")
                        if (stack.size > MAX_DEPTH) {
                            issues += Issue(at, Severity.ERROR, "TOO_DEEP", "Demasiados marcadores anidados.")
                        } else {
                            flushText()
                            stack.addLast(Frame(name, color = color, size = size, neutral = !allowed))
                        }
                    }
                    atLineStart = false
                }
                "align" -> {
                    if (closing) {
                        if (alignOpenAt < 0) {
                            issues += Issue(at, Severity.ERROR, "BAD_NESTING", "[/align] sin [align] abierto.")
                        } else {
                            endBlock()
                            currentAlign = MarkupAlign.LEFT
                            alignOpenAt = -1
                        }
                    } else {
                        val align = arg?.let { MarkupAlign.fromWire(it) }
                        when {
                            align == null -> issues += Issue(at, Severity.ERROR, "VALUE_NOT_ALLOWED", "[align:${arg.orEmpty()}] no es un valor permitido.")
                            alignOpenAt >= 0 -> issues += Issue(at, Severity.ERROR, "BAD_NESTING", "No se puede anidar [align].")
                            else -> {
                                endBlock()
                                currentAlign = align
                                alignOpenAt = at
                            }
                        }
                    }
                    atLineStart = true
                }
                else -> return false
            }
            pos += len
            return true
        }

        private fun flushText() {
            if (text.isEmpty()) return
            stack.last().children += MarkupInline.Text(text.toString())
            nodes++
            text.setLength(0)
        }

        private fun closeFrame() {
            flushText()
            val frame = stack.removeLast()
            val parent = stack.last().children
            if (frame.neutral) {
                parent += frame.children
                return
            }
            if (frame.children.isEmpty()) return
            nodes++
            parent += when (frame.kind) {
                "style" -> MarkupInline.Styled(frame.style!!, frame.children.toList())
                "color" -> MarkupInline.Colored(frame.color!!, frame.children.toList())
                else -> MarkupInline.Sized(frame.size!!, frame.children.toList())
            }
        }

        /** Un bloque termina con todos sus marcadores cerrados: los que quedaron abiertos se cierran con aviso de error. */
        private fun endBlock() {
            while (stack.size > 1) {
                issues += Issue(pos, Severity.ERROR, "UNCLOSED", "Hay un marcador sin cerrar antes del final del bloque.")
                closeFrame()
            }
            flushText()
            val inlines = trimmed(stack.first().children.toList())
            stack.first().children.clear()
            if (inlines.isNotEmpty() || bullet) blocks += MarkupBlock(inlines, bullet, currentAlign)
            bullet = false
        }

        private fun finish() {
            endBlock()
            if (alignOpenAt >= 0) {
                issues += Issue(alignOpenAt, Severity.ERROR, "UNCLOSED", "[align] sin [/align].")
                alignOpenAt = -1
            }
        }

        /** Quita el espacio inicial/final del bloque sin tocar el de dentro. */
        private fun trimmed(list: List<MarkupInline>): List<MarkupInline> {
            if (list.isEmpty()) return list
            val out = list.toMutableList()
            (out.first() as? MarkupInline.Text)?.let { out[0] = MarkupInline.Text(it.text.trimStart()) }
            (out.last() as? MarkupInline.Text)?.let { out[out.lastIndex] = MarkupInline.Text(it.text.trimEnd()) }
            return out.filterNot { it is MarkupInline.Text && it.text.isEmpty() }
        }
    }

    private val TAG = Regex("^\\[(/?)(color|size|align|p|-)(?::([A-Za-z0-9#_-]{1,20}))?]")
    private val UNKNOWN_TAG = Regex("^\\[/?[A-Za-z]{1,12}(?::[^\\]\\n]{0,20})?]")
}

enum class MarkupStyle(val marker: String) { BOLD("**"), ITALIC("__"), UNDERLINE("++"), STRIKE("~~") }

/** Paleta cerrada. Cada nombre se asocia a un token de diseño en la pantalla; aquí solo el vocabulario. */
enum class MarkupColor(val wire: String, val label: String) {
    RED("red", "Rojo"),
    GREEN("green", "Verde"),
    BLUE("blue", "Azul"),
    ORANGE("orange", "Naranja"),
    PURPLE("purple", "Morado"),
    GRAY("gray", "Gris"),
    ;

    companion object {
        fun fromWire(value: String): MarkupColor? = entries.firstOrNull { it.wire == value }
    }
}

/** Escala cerrada de tamaños (nombres, no números). */
enum class MarkupSize(val wire: String, val label: String) {
    SMALL("small", "Pequeño"),
    NORMAL("normal", "Normal"),
    LARGE("large", "Grande"),
    XLARGE("xlarge", "Muy grande"),
    ;

    companion object {
        fun fromWire(value: String): MarkupSize? = entries.firstOrNull { it.wire == value }
    }
}

enum class MarkupAlign(val wire: String) {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right"),
    JUSTIFY("justify"),
    ;

    companion object {
        fun fromWire(value: String): MarkupAlign? = entries.firstOrNull { it.wire == value }
    }
}

sealed interface MarkupInline {
    data class Text(val text: String) : MarkupInline
    data class Styled(val style: MarkupStyle, val children: List<MarkupInline>) : MarkupInline
    data class Colored(val color: MarkupColor, val children: List<MarkupInline>) : MarkupInline
    data class Sized(val size: MarkupSize, val children: List<MarkupInline>) : MarkupInline
}

data class MarkupBlock(val inlines: List<MarkupInline>, val bullet: Boolean = false, val align: MarkupAlign = MarkupAlign.LEFT) {
    fun plainText(): String = (if (bullet) "• " else "") + inlines.joinToString("") { plain(it) }

    private fun plain(node: MarkupInline): String = when (node) {
        is MarkupInline.Text -> node.text
        is MarkupInline.Styled -> node.children.joinToString("") { plain(it) }
        is MarkupInline.Colored -> node.children.joinToString("") { plain(it) }
        is MarkupInline.Sized -> node.children.joinToString("") { plain(it) }
    }
}
