package com.oposicontrol.backend

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

internal data class ParsedPregunta(
    val numero: Int,
    val titulo: String,
    val opciones: List<Pair<String, String>>,
    val respuesta: String,
    val razonamiento: String,
    val fuente: String,
    val etiquetas: List<String>,
    /**
     * El texto CRUDO del bloque tal y como venía en el PDF.
     *
     * `preguntas_parseadas_staging.bloque_original` existe exactamente para
     * esto y hasta ahora guardaba una copia del título del campo de al lado
     * (`bloqueOriginal = p.titulo`), de modo que cuando una fila salía rara no
     * había forma de saber si el PDF decía eso o el parser se equivocó: la
     * revisión humana revisaba la interpretación del parser sin poder
     * compararla con nada.
     *
     * Con default para no obligar a tocar construcciones que no lo tengan.
     */
    val bloqueOriginal: String = "",
    /**
     * Un aviso que NO invalida el bloque: la fila se importa igual, pero algo se va
     * a perder o a ignorar y el revisor tiene que saberlo antes de publicar.
     *
     * Hoy sólo lo usa Ráfaga con explicación o fuente: esas dos columnas existen en
     * la pregunta, pero el modo Ráfaga no las enseña nunca. Sin aviso, quien redactó
     * la explicación creería que se publica.
     */
    val advertencia: String? = null,
)

internal data class RafagaParseError(
    val numero: Int,
    val campo: String,
    val detalle: String,
)

/**
 * Un bloque que NO se pudo convertir en pregunta, con todo lo que sí se pudo
 * leer de él.
 *
 * Existe porque sin esto la etapa de revisión que la pantalla de importación
 * promete no puede existir: [parseRafagaText] descartaba los bloques con error y
 * `crearLoteDesdePdf` rechazaba el PDF entero, así que a staging **nunca llegaba
 * una fila mala** y todas nacían `ok`. Un PDF de 300 preguntas con una errata en
 * la 297 se perdía entero, y el administrador no tenía nada que corregir porque
 * no había fila que corregir.
 *
 * Los campos son los del bloque, parciales: lo que el parser leyó antes de
 * encontrar el fallo. [detalle] es la explicación humana, ya compuesta, de todo
 * lo que le falta.
 */
internal data class RafagaBloqueConError(
    val numero: Int,
    val bloqueOriginal: String,
    val titulo: String,
    val opcionA: String,
    val opcionB: String,
    val opcionC: String,
    val opcionD: String,
    val respuesta: String,
    val razonamiento: String,
    val fuente: String,
    val etiquetas: String,
    val detalle: String,
)

/**
 * Resultado del parseo.
 *
 * El orden de los componentes es deliberado: `val (preguntas, errores) =
 * parseRafagaText(...)` sigue funcionando exactamente igual que con el `Pair`
 * anterior, así que ningún llamador cambia por existir el tercero.
 */
internal data class RafagaParseResult(
    val preguntas: List<ParsedPregunta>,
    val errores: List<RafagaParseError>,
    val bloquesConError: List<RafagaBloqueConError>,
)

private val KNOWN_FIELDS = setOf("TITULO", "A", "B", "C", "D", "RESPUESTA", "RAZONAMIENTO", "FUENTE", "ETIQUETA")
private val BLOCK_SEPARATOR = Regex("---\\s*PREGUNTA\\s*---")

/**
 * Una línea con forma de CLAVE de documentación antigua: `ID_GLOBAL:`, `ID_AUDIO:`,
 * `NIVEL_DIFICULTAD:`…
 *
 * Existe porque una clave desconocida se pegaba como CONTINUACIÓN del campo
 * anterior: un `ID_GLOBAL: 7` justo después del `TITULO` acababa dentro del
 * enunciado, y la pregunta se publicaba con ese texto dentro. El bloque se marca
 * como error y el revisor lo ve.
 *
 * El guion bajo es lo que distingue una clave de una frase: `NOTA:` o `IMPORTANTE:`
 * dentro de una explicación son texto normal y tienen que seguir siéndolo.
 */
private val LEGACY_KEY_LINE = Regex("^([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\\s*:")

/** Los tipos de pregunta que EXIGEN explicación (DEC-IMP-04). */
private val TYPES_REQUIRING_REASONING = setOf("razonada", "inmersiva")

/** El aviso de Ráfaga, literal, para que el test y la pantalla digan lo mismo. */
internal const val RAFAGA_DISCARDS_EXTRAS_WARNING =
    "Ráfaga no publica explicación ni fuente: se descartarán al publicar."

internal fun extractTextFromPdf(bytes: ByteArray): String =
    Loader.loadPDF(bytes).use { doc -> PDFTextStripper().getText(doc) }

/** Compatibilidad: el flujo de Ráfaga, que es el que existía antes de que hubiera tipos. */
internal fun parseRafagaText(text: String): RafagaParseResult = parseQuestionBlocks(text, "rafaga")

/**
 * Parsea el texto y devuelve las tres cosas: lo que salió bien, los errores, y
 * **los bloques que fallaron con su contenido parcial**.
 *
 * Las reglas comunes y el ORDEN en que se comprueban no cambian: título, luego las
 * cuatro opciones (se acumulan todas las que falten, no sólo la primera), luego la
 * respuesta. Un bloque que falla no se tira: se devuelve aparte para que llegue a
 * staging marcado y un humano lo corrija.
 *
 * ### Lo que depende de [tipoPregunta]
 *
 * El importador nació siendo sólo de Ráfaga y validaba igual para todos, así que un
 * PDF de preguntas razonadas SIN explicación se importaba entero y se publicaba a
 * los opositores sin la parte que da sentido al modo. Ahora:
 *
 * - `razonada`/`inmersiva`: `RAZONAMIENTO` vacío es un ERROR del bloque.
 * - `rafaga`: `RAZONAMIENTO` o `FUENTE` con texto es una ADVERTENCIA
 *   ([RAFAGA_DISCARDS_EXTRAS_WARNING]), no un error: la pregunta es válida, pero eso
 *   no se va a ver.
 */
internal fun parseQuestionBlocks(text: String, tipoPregunta: String): RafagaParseResult {
    val preguntas = mutableListOf<ParsedPregunta>()
    val errores = mutableListOf<RafagaParseError>()
    val bloquesConError = mutableListOf<RafagaBloqueConError>()
    val tipo = tipoPregunta.trim().lowercase()
    val exigeRazonamiento = tipo in TYPES_REQUIRING_REASONING

    text.split(BLOCK_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEachIndexed { i, block ->
            val numero = i + 1
            val (fields, clavesDesconocidas) = extractFields(block)
            val erroresDelBloque = mutableListOf<RafagaParseError>()
            clavesDesconocidas.forEach { clave ->
                erroresDelBloque += RafagaParseError(numero, clave, "Campo no admitido en el PDF")
            }

            val titulo = fields["TITULO"]?.trim()
            val opciones = mutableListOf<Pair<String, String>>()
            var respuesta: String? = null

            if (titulo.isNullOrBlank()) {
                erroresDelBloque += RafagaParseError(numero, "TITULO", "Obligatorio y no puede estar vacío")
            } else {
                var opcionError = false
                for (letra in listOf("A", "B", "C", "D")) {
                    val texto = fields[letra]?.trim()
                    if (texto.isNullOrBlank()) {
                        erroresDelBloque += RafagaParseError(numero, letra, "Opción $letra obligatoria")
                        opcionError = true
                    } else {
                        opciones += letra to texto
                    }
                }
                if (!opcionError) {
                    val valor = fields["RESPUESTA"]?.trim()?.uppercase()
                    if (valor.isNullOrBlank() || valor !in setOf("A", "B", "C", "D")) {
                        erroresDelBloque += RafagaParseError(
                            numero,
                            "RESPUESTA",
                            "Debe ser A, B, C o D — recibido: '${valor.orEmpty()}'",
                        )
                    } else {
                        respuesta = valor
                    }
                }
            }

            val razonamiento = fields["RAZONAMIENTO"]?.trim().orEmpty()
            val fuente = fields["FUENTE"]?.trim().orEmpty()
            if (exigeRazonamiento && razonamiento.isBlank()) {
                erroresDelBloque += RafagaParseError(
                    numero,
                    "RAZONAMIENTO",
                    "Obligatorio en preguntas razonadas e inmersivas",
                )
            }

            val etiquetas = fields["ETIQUETA"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            val respuestaValida = respuesta
            if (erroresDelBloque.isEmpty() && titulo != null && respuestaValida != null) {
                preguntas += ParsedPregunta(
                    numero = numero,
                    titulo = titulo,
                    opciones = opciones,
                    respuesta = respuestaValida,
                    razonamiento = razonamiento,
                    fuente = fuente,
                    etiquetas = etiquetas,
                    bloqueOriginal = block,
                    advertencia = RAFAGA_DISCARDS_EXTRAS_WARNING
                        .takeIf { !exigeRazonamiento && (razonamiento.isNotBlank() || fuente.isNotBlank()) },
                )
            } else {
                errores += erroresDelBloque
                bloquesConError += RafagaBloqueConError(
                    numero = numero,
                    bloqueOriginal = block,
                    // Sin título legible se usa la primera línea real del bloque.
                    // No se inventa texto: la fila tiene que poder identificarse
                    // en una lista de 300, y lo único honesto que hay es el PDF.
                    titulo = titulo.orEmpty().ifBlank {
                        block.lineSequence().firstOrNull { line -> line.isNotBlank() }?.trim().orEmpty()
                    },
                    opcionA = fields["A"]?.trim().orEmpty(),
                    opcionB = fields["B"]?.trim().orEmpty(),
                    opcionC = fields["C"]?.trim().orEmpty(),
                    opcionD = fields["D"]?.trim().orEmpty(),
                    respuesta = fields["RESPUESTA"]?.trim()?.uppercase().orEmpty(),
                    razonamiento = fields["RAZONAMIENTO"]?.trim().orEmpty(),
                    fuente = fields["FUENTE"]?.trim().orEmpty(),
                    etiquetas = etiquetas.joinToString(","),
                    detalle = erroresDelBloque.joinToString(" · ") { "${it.campo}: ${it.detalle}" },
                )
            }
        }

    return RafagaParseResult(preguntas, errores, bloquesConError)
}

/**
 * Los campos del bloque y las CLAVES ANTIGUAS que trae (`ID_GLOBAL:`, `ID_AUDIO:`…).
 *
 * Las segundas se devuelven en vez de tragárselas: antes caían en la rama de
 * «continuación» y se pegaban al campo anterior, así que el identificador interno de
 * un documento viejo acababa dentro del enunciado publicado. Una línea con dos
 * puntos que NO casa [LEGACY_KEY_LINE] sigue siendo continuación, porque una
 * explicación puede contener perfectamente `Nota: …`.
 */
private fun extractFields(block: String): Pair<Map<String, String>, List<String>> {
    val result = mutableMapOf<String, StringBuilder>()
    val clavesDesconocidas = linkedSetOf<String>()
    var current: String? = null
    for (line in block.lines()) {
        val trimmed = line.trim()
        if (trimmed.isBlank()) continue
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0) {
            val key = trimmed.substring(0, colonIdx).trim()
            if (key in KNOWN_FIELDS) {
                current = key
                result[key] = StringBuilder(trimmed.substring(colonIdx + 1).trim())
                continue
            }
            val claveAntigua = LEGACY_KEY_LINE.find(trimmed)?.groupValues?.get(1)
            if (claveAntigua != null) {
                clavesDesconocidas += claveAntigua
                // No se acumula como continuación: el objetivo es justamente que no
                // contamine el campo anterior.
                continue
            }
        }
        // Continuation line (e.g. multi-line RAZONAMIENTO)
        current?.let { result[it]?.append('\n')?.append(trimmed) }
    }
    return result.mapValues { it.value.toString() } to clavesDesconocidas.toList()
}
