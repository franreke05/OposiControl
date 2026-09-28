package com.oposicontrol.backend.openapi

import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Lector estricto de un subconjunto de YAML suficiente para `openapi/` (no hay parser YAML
 * en el classpath de test y SLICE 0 no anade dependencias).
 *
 * Soporta: mapas y secuencias en bloque con indentacion por espacios, `- clave: valor`,
 * colecciones flow en una sola linea (`[a, b]`, `{ k: v }`), escalares planos y entre
 * comillas simples o dobles, y comentarios `#`. Rechaza (falla el test) lo que no entiende:
 * claves duplicadas, tabuladores, anclas/alias, escalares de bloque `|`/`>` e indentacion
 * inesperada. Asi el parser nunca "adivina" y el contrato queda en un YAML simple.
 */
internal object OpenApiMiniYaml {

    class YamlError(message: String) : IllegalArgumentException(message)

    private data class Line(val indent: Int, val text: String, val number: Int)

    fun parse(text: String, source: String): Any? {
        val lines = text.replace("\r\n", "\n").split('\n').mapIndexedNotNull { index, raw ->
            val stripped = stripComment(raw).trimEnd()
            if (stripped.isBlank()) return@mapIndexedNotNull null
            val body = stripped.trimStart()
            val lead = stripped.substring(0, stripped.length - body.length)
            if (lead.contains('\t')) throw YamlError("$source:${index + 1}: tab indentation is not allowed")
            Line(lead.length, body, index + 1)
        }.toMutableList()
        if (lines.isEmpty()) return null
        val parser = Parser(lines, source)
        val value = parser.block(lines[0].indent)
        if (parser.pos < lines.size) {
            throw YamlError("$source:${lines[parser.pos].number}: unexpected content or indentation")
        }
        return value
    }

    private fun stripComment(raw: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val tokenStart = i == 0 || raw[i - 1] in " [{,:-"
            when {
                inDouble -> if (c == '\\') i++ else if (c == '"') inDouble = false
                inSingle -> if (c == '\'') inSingle = false
                c == '\'' && tokenStart -> inSingle = true
                c == '"' && tokenStart -> inDouble = true
                c == '#' && (i == 0 || raw[i - 1] == ' ') -> return raw.substring(0, i)
            }
            i++
        }
        return raw
    }

    /** Lee un escalar entre comillas que empieza en [start]; devuelve (valor, indice tras la comilla). */
    private fun readQuoted(s: String, start: Int, fail: (String) -> Nothing): Pair<String, Int> {
        val quote = s[start]
        val out = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (quote == '\'') {
                if (c == '\'') {
                    if (i + 1 < s.length && s[i + 1] == '\'') {
                        out.append('\'')
                        i += 2
                        continue
                    }
                    return out.toString() to i + 1
                }
                out.append(c)
            } else {
                when (c) {
                    '"' -> return out.toString() to i + 1
                    '\\' -> {
                        i++
                        out.append(
                            when (val e = s.getOrNull(i)) {
                                'n' -> '\n'
                                't' -> '\t'
                                '"', '\\', '/' -> e
                                else -> fail("unsupported escape \\$e")
                            },
                        )
                    }
                    else -> out.append(c)
                }
            }
            i++
        }
        fail("unterminated quoted scalar")
    }

    private fun resolvePlain(raw: String): Any? {
        val s = raw.trim()
        return when {
            s == "null" || s == "~" || s.isEmpty() -> null
            s == "true" -> true
            s == "false" -> false
            Regex("-?\\d+").matches(s) -> s.toLong()
            Regex("-?\\d+\\.\\d+").matches(s) -> s.toDouble()
            else -> s
        }
    }

    private class Parser(val lines: MutableList<Line>, val source: String) {
        var pos = 0

        fun fail(message: String): Nothing =
            throw YamlError("$source:${lines.getOrNull(pos)?.number ?: "EOF"}: $message")

        fun isSeqItem(text: String) = text == "-" || text.startsWith("- ")

        fun block(indent: Int): Any? {
            val line = lines[pos]
            if (line.indent != indent) fail("bad indentation")
            return if (isSeqItem(line.text)) sequence(indent) else mapping(indent)
        }

        fun mapping(indent: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (pos < lines.size) {
                val line = lines[pos]
                if (line.indent < indent) break
                if (line.indent > indent) fail("unexpected indentation")
                if (isSeqItem(line.text)) break
                val (key, rest) = splitKey(line.text)
                if (map.containsKey(key)) fail("duplicate key '$key'")
                pos++
                map[key] = value(rest, indent)
            }
            return map
        }

        fun sequence(indent: Int): List<Any?> {
            val list = ArrayList<Any?>()
            while (pos < lines.size) {
                val line = lines[pos]
                if (line.indent < indent) break
                if (line.indent > indent) fail("unexpected indentation")
                if (!isSeqItem(line.text)) break
                val content = line.text.removePrefix("-").trimStart()
                when {
                    content.isEmpty() -> {
                        pos++
                        list.add(value("", indent))
                    }
                    looksLikeMappingEntry(content) -> {
                        val childIndent = indent + (line.text.length - content.length)
                        lines[pos] = Line(childIndent, content, line.number)
                        list.add(mapping(childIndent))
                    }
                    else -> {
                        pos++
                        list.add(scalarOrFlow(content))
                    }
                }
            }
            return list
        }

        fun value(rest: String, indent: Int): Any? {
            if (rest.isNotEmpty()) return scalarOrFlow(rest)
            if (pos < lines.size && lines[pos].indent > indent) return block(lines[pos].indent)
            return null
        }

        fun looksLikeMappingEntry(content: String): Boolean {
            val first = content[0]
            if (first == '[' || first == '{') return false
            if (first == '\'' || first == '"') {
                val (_, end) = readQuoted(content, 0) { fail(it) }
                return content.substring(end).trimStart().startsWith(":")
            }
            return content.contains(": ") || content.endsWith(":")
        }

        fun splitKey(text: String): Pair<String, String> {
            if (text[0] == '\'' || text[0] == '"') {
                val (key, end) = readQuoted(text, 0) { fail(it) }
                val after = text.substring(end).trimStart()
                if (!after.startsWith(":")) fail("expected ':' after quoted key")
                return key to after.substring(1).trim()
            }
            var idx = text.indexOf(": ")
            if (idx < 0 && text.endsWith(":")) idx = text.length - 1
            if (idx <= 0) fail("expected 'key: value'")
            return text.substring(0, idx).trim() to text.substring(idx + 1).trim()
        }

        fun scalarOrFlow(s: String): Any? {
            when (s[0]) {
                '|', '>' -> fail("block scalars are not supported")
                '&', '*' -> fail("anchors and aliases are not supported")
                '[', '{' -> {
                    val flow = Flow(s) { fail(it) }
                    val v = flow.value()
                    flow.ws()
                    if (flow.i != s.length) fail("trailing content after flow collection")
                    return v
                }
                '\'', '"' -> {
                    val (v, end) = readQuoted(s, 0) { fail(it) }
                    if (s.substring(end).isNotBlank()) fail("trailing content after quoted scalar")
                    return v
                }
            }
            return resolvePlain(s)
        }
    }

    private class Flow(val s: String, val fail: (String) -> Nothing) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] == ' ') i++
        }

        fun value(): Any? {
            ws()
            return when (s.getOrNull(i)) {
                '[' -> list()
                '{' -> map()
                '\'', '"' -> quoted()
                else -> resolvePlain(plainRaw(",]}"))
            }
        }

        fun quoted(): String {
            val (v, end) = readQuoted(s, i, fail)
            i = end
            return v
        }

        fun plainRaw(stops: String): String {
            val start = i
            while (i < s.length && s[i] !in stops) i++
            return s.substring(start, i).trim()
        }

        fun list(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s.getOrNull(i) == ']') {
                i++
                return out
            }
            while (true) {
                out.add(value())
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> fail("malformed flow sequence")
                }
            }
        }

        fun map(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s.getOrNull(i) == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                val key = if (s.getOrNull(i) == '\'' || s.getOrNull(i) == '"') quoted() else plainRaw(":,}")
                ws()
                if (s.getOrNull(i) != ':') fail("expected ':' in flow mapping")
                i++
                if (out.containsKey(key)) fail("duplicate key '$key' in flow mapping")
                out[key] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> fail("malformed flow mapping")
                }
            }
        }
    }
}

/**
 * Carga los ficheros de `openapi/`, resuelve `$ref` entre ficheros (JSON Pointer) y expone las
 * operaciones de `root.yaml` con el fichero en el que vive cada nodo (para las `$ref` locales).
 */
internal class OpenApiDocs(val dir: File) {
    data class Node(val value: Any?, val file: File)

    data class Operation(
        val path: String,
        val method: String,
        val op: Map<String, Any?>,
        val file: File,
        val pathLevelParameters: List<Any?>,
    ) {
        val contractId: String get() = op["x-contract-id"] as String
        val label: String get() = "${method.uppercase()} $path (${op["x-contract-id"]})"
    }

    private val cache = HashMap<String, Any?>()

    val rootFile: File = File(dir, "root.yaml")

    fun load(file: File): Any? =
        cache.getOrPut(file.canonicalPath) { OpenApiMiniYaml.parse(file.readText(), file.path) }

    fun yamlFiles(): List<File> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "yaml" && !it.name.startsWith(".") && it.name != "redocly.yaml" }
            .sortedBy { it.path }
            .toList()

    fun resolveRef(ref: String, from: File): Node {
        val filePart = ref.substringBefore('#')
        val pointer = if (ref.contains('#')) ref.substringAfter('#') else ""
        val target = if (filePart.isEmpty()) from else File(from.parentFile, filePart).canonicalFile
        require(target.isFile) { "\$ref '$ref' in ${from.path}: file ${target.path} does not exist" }
        var current: Any? = load(target)
        pointer.split('/').drop(1).forEach { raw ->
            val segment = raw.replace("~1", "/").replace("~0", "~")
            current = when (val c = current) {
                is Map<*, *> -> {
                    require(c.containsKey(segment)) { "\$ref '$ref' in ${from.path}: segment '$segment' not found" }
                    c[segment]
                }
                is List<*> -> c[segment.toInt()]
                else -> throw IllegalArgumentException("\$ref '$ref' in ${from.path}: cannot descend into '$segment'")
            }
        }
        return Node(current, target)
    }

    fun deref(node: Node): Node {
        var n = node
        repeat(32) {
            val v = n.value
            if (v is Map<*, *> && v["\$ref"] is String) n = resolveRef(v["\$ref"] as String, n.file) else return n
        }
        error("\$ref chain too deep from ${node.file.path}")
    }

    @Suppress("UNCHECKED_CAST")
    fun operations(): List<Operation> {
        val root = load(rootFile) as Map<String, Any?>
        val paths = root["paths"] as Map<String, Any?>
        return paths.flatMap { (path, item) ->
            val resolved = deref(Node(item, rootFile))
            val pathItem = resolved.value as Map<String, Any?>
            val shared = (pathItem["parameters"] as? List<Any?>).orEmpty()
            HTTP_METHODS.filter { pathItem.containsKey(it) }.map { method ->
                Operation(path, method, pathItem[method] as Map<String, Any?>, resolved.file, shared)
            }
        }
    }

    /** Parametros efectivos (nivel path + nivel operacion), ya resueltos. */
    @Suppress("UNCHECKED_CAST")
    fun parameters(op: Operation): List<Node> =
        (op.pathLevelParameters + (op.op["parameters"] as? List<Any?>).orEmpty())
            .map { deref(Node(it, op.file)) }
            .onEach { require(it.value is Map<*, *>) { "parameter of ${op.label} is not an object" } }

    fun rawParameterRefs(op: Operation): List<String> =
        (op.pathLevelParameters + (op.op["parameters"] as? List<*>).orEmpty())
            .mapNotNull { (it as? Map<*, *>)?.get("\$ref") as? String }

    /** Esquemas del requestBody, por media type. */
    @Suppress("UNCHECKED_CAST")
    fun requestSchemas(op: Operation): Map<String, Node> {
        val body = op.op["requestBody"] ?: return emptyMap()
        val resolved = deref(Node(body, op.file))
        val content = (resolved.value as Map<String, Any?>)["content"] as Map<String, Any?>
        return content.mapValues { (_, media) -> Node((media as Map<String, Any?>)["schema"], resolved.file) }
    }

    @Suppress("UNCHECKED_CAST")
    fun responses(op: Operation): Map<String, Node> =
        (op.op["responses"] as Map<String, Any?>).mapValues { (_, r) -> deref(Node(r, op.file)) }

    /**
     * Recorre un esquema en profundidad (properties, items, allOf/oneOf/anyOf,
     * additionalProperties) resolviendo `$ref`; visita cada objeto esquema una sola vez.
     */
    @Suppress("UNCHECKED_CAST")
    fun walkSchema(
        node: Node,
        seen: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
        visit: (Map<String, Any?>, File) -> Unit,
    ) {
        val n = deref(node)
        val m = n.value as? Map<String, Any?> ?: return
        if (!seen.add(m)) return
        visit(m, n.file)
        (m["properties"] as? Map<String, Any?>)?.values?.forEach { walkSchema(Node(it, n.file), seen, visit) }
        listOf("allOf", "oneOf", "anyOf").forEach { key ->
            (m[key] as? List<Any?>)?.forEach { walkSchema(Node(it, n.file), seen, visit) }
        }
        m["items"]?.let { walkSchema(Node(it, n.file), seen, visit) }
        (m["additionalProperties"] as? Map<String, Any?>)?.let { walkSchema(Node(it, n.file), seen, visit) }
    }

    /** Visita cada mapa de cada fichero YAML (sin resolver `$ref`). */
    fun walkAllMaps(visit: (Map<*, *>, File) -> Unit) {
        fun walk(node: Any?, file: File) {
            when (node) {
                is Map<*, *> -> {
                    visit(node, file)
                    node.values.forEach { walk(it, file) }
                }
                is List<*> -> node.forEach { walk(it, file) }
            }
        }
        yamlFiles().forEach { walk(load(it), it) }
    }

    companion object {
        val HTTP_METHODS = listOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

        fun locate(): OpenApiDocs {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, "openapi/root.yaml")
                if (candidate.isFile) return OpenApiDocs(candidate.parentFile.canonicalFile)
                dir = dir.parentFile
            }
            error("openapi/root.yaml not found from ${File("").absolutePath}")
        }
    }
}
