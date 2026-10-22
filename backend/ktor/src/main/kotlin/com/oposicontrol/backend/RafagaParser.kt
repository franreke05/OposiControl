package com.oposicontrol.backend

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.text.Normalizer

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
     * Hoy lo usan dos casos: Ráfaga con explicación o fuente (esas dos columnas existen en
     * la pregunta, pero el modo Ráfaga no las enseña nunca) y un posible pie o número de
     * página colado dentro del texto ([PAGE_MARKER_WARNING]).
     */
    val advertencia: String? = null,
    /**
     * `ESTADO: ANULADA` en el PDF: el tribunal anuló esta pregunta EN ESTE examen.
     *
     * Es una propiedad de la RELACIÓN pregunta↔examen (`preguntas_examen.anulada`), nunca de
     * la pregunta global, y es ortogonal al estado de parseo: una pregunta anulada y bien
     * formada es una fila `ok`.
     */
    val anulada: Boolean = false,
    /** `MOTIVO_ANULACION:` (≤ 500, sólo con [anulada]); `null` si no venía o venía vacío. */
    val motivoAnulacion: String? = null,
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
    /** Lo que el PDF decía de `ESTADO`, conservado aunque el bloque tenga otros errores. */
    val anulada: Boolean = false,
    /** Lo que el PDF decía de `MOTIVO_ANULACION` (recortado; `null` si vacío). Puede pasar de 500: es justo el error. */
    val motivoAnulacion: String? = null,
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

private val TEXT_FIELDS = listOf("TITULO", "A", "B", "C", "D", "RESPUESTA", "RAZONAMIENTO", "FUENTE", "ETIQUETA")
private const val FIELD_ESTADO = "ESTADO"
private const val FIELD_MOTIVO = "MOTIVO_ANULACION"
private val KNOWN_FIELDS = TEXT_FIELDS.toSet() + FIELD_ESTADO + FIELD_MOTIVO

/**
 * El ÚNICO separador oficial: una línea que, recortada, es exactamente `--- PREGUNTA ---`
 * (mayúsculas, nada más en la línea).
 */
private val OFFICIAL_SEPARATOR = Regex("^---\\s*PREGUNTA\\s*---$")

/**
 * Una línea que PARECE un separador pero no es el oficial: `--- PREGUNTA 1 ---`,
 * `--- pregunta ---`, `---PREGUNTA:`, `— PREGUNTA —`… Antes se ignoraba y las dos preguntas
 * quedaban FUSIONADAS en una (o la segunda se perdía en silencio). Ahora abre su propio
 * bloque, marcado como error.
 */
private val SUSPECT_SEPARATOR = Regex(
    // Una racha CONTIGUA de >= 2 caracteres de decoración (sin espacios entre ellos): así
    // `- Pregunta 4 del examen anterior` o `* Pregunta clave` (viñetas de prosa) NO casan.
    // La segunda alternativa es un único guion largo/medio SIMÉTRICO (`— PREGUNTA —`).
    "^(?:[-–—_=*#]{2,}\\s*PREGUNTA\\b.*|[–—]\\s*PREGUNTA\\b.*[–—])$",
    RegexOption.IGNORE_CASE,
)

/**
 * Espacios y marcas invisibles que un PDF/Word mete sin que nadie los vea y que rompen un
 * separador o una clave: U+00A0 se convierte en espacio normal; BOM (U+FEFF) y espacio de
 * ancho cero (U+200B) se eliminan.
 */
private fun normalizeInvisible(text: String): String =
    text.replace(' ', ' ').replace("﻿", "").replace("​", "")

/**
 * Una línea de continuación que empieza por `ESTADO` / `MOTIVO_ANULACION` SIN los dos puntos
 * ASCII delante (`ESTADO ANULADA`, `ESTADO - ANULADA`, `ESTADO： ANULADA` con `：` de ancho
 * completo). Antes caía en «continuación» y una pregunta anulada se importaba como normal en
 * silencio. En MAYÚSCULAS basta; en otra grafía sólo si además dice «anulada».
 */
private val ESTADO_WITHOUT_COLON = Regex("^ESTADO(?![\\p{L}\\p{N}_])")
private val MOTIVO_WITHOUT_COLON = Regex("^MOTIVO_ANULACION(?![\\p{L}\\p{N}_])")
private val ESTADO_LOOSE_ANULADA =
    Regex("^estado(?![\\p{L}\\p{N}_]).*\\banulad[ao]\\b", RegexOption.IGNORE_CASE)
private val MOTIVO_LOOSE =
    Regex("^motivo[_ ]anulaci[oó]n(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)

/** Una línea que es, sola, la palabra ANULADA (con puntuación o espacios alrededor). */
private val LONE_ANULADA = Regex("^[\\s\\p{P}]*ANULADA[\\s\\p{P}]*$", RegexOption.IGNORE_CASE)

/** Los textos de error de estos casos, literales, para que el test y la pantalla digan lo mismo. */
internal const val ESTADO_COLON_DETAIL = "escríbelo como `ESTADO: ANULADA` (con dos puntos)"
internal const val MOTIVO_COLON_DETAIL = "escríbelo como `MOTIVO_ANULACION: texto` (con dos puntos)"
internal const val LONE_ANULADA_DETAIL = "«ANULADA» suelto: escríbelo como `ESTADO: ANULADA` (con dos puntos)"

/** El texto del error de separador, literal, para que el test y la pantalla digan lo mismo. */
internal const val INVALID_SEPARATOR_DETAIL = "Separador no válido: usa exactamente --- PREGUNTA ---"

/** El aviso de pie/número de página, literal. */
internal const val PAGE_MARKER_WARNING = "Posible pie o número de página dentro del texto: revisa la fila"

/** Regla de etiquetas: el separador es la coma. */
internal const val ETIQUETA_SEPARATOR_WARNING = "ETIQUETA: separa los temas con comas, no con punto y coma"
internal const val MAX_ETIQUETAS_POR_PREGUNTA = PREGUNTA_ETIQUETAS_MAX

/**
 * `--- BLOQUE: PRINCIPAL ---` / `--- BLOQUE: RESERVA ---` (exacto, en mayúsculas). Esta versión no importa reservas
 * (llegan con V1): el marcador se reconoce para que NUNCA se pegue como continuación del campo anterior, y todo lo que
 * va detrás de RESERVA sale como bloque con error, que no se publica.
 */
private val BLOQUE_MARKER = Regex("^---\\s*BLOQUE\\s*:\\s*(PRINCIPAL|RESERVA)\\s*---$")

/** Parece un marcador de bloque pero no es el exacto (`-- bloque reserva --`, `--- BLOQUE: RESERVAS ---`). */
private val SUSPECT_BLOQUE = Regex("^(?:[-–—_=*#]{2,}\\s*BLOQUE\\b.*|[–—]\\s*BLOQUE\\b.*[–—])$", RegexOption.IGNORE_CASE)

internal const val INVALID_BLOQUE_DETAIL =
    "Marcador de bloque no válido: usa exactamente --- BLOQUE: PRINCIPAL --- o --- BLOQUE: RESERVA ---"
internal const val RESERVA_NOT_SUPPORTED_DETAIL =
    "Las preguntas de reserva todavía no se pueden importar: se activarán con la versión V1. " +
        "Quítalas de este PDF o súbelas más adelante."
internal const val BLOQUE_UNCERTAIN_DETAIL =
    "Hay un marcador de bloque no válido antes de esta pregunta: no se sabe si es principal o reserva"

/** Sólo una línea que es EXACTAMENTE un marcador de página (o 1–4 dígitos). Sin heurística. */
private val PAGE_MARKER_LINE = Regex(
    "^(?:(?:P[aá]gina|P[aá]g\\.?|Page)\\s*\\d+(?:\\s*(?:de|/|of)\\s*\\d+)?|\\d{1,4})$",
    RegexOption.IGNORE_CASE,
)

/** Máximo del motivo de anulación (mismo límite que el CHECK de la base). */
internal const val MAX_ANNULMENT_REASON_LENGTH = 500

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

/** Topes de la importacion de PDF: un fichero pequeno puede declarar miles de paginas o expandirse a gigas de texto. */
internal const val PDF_IMPORT_MAX_PAGES = 400
internal const val PDF_IMPORT_MAX_CHARS = 4_000_000

internal fun extractTextFromPdf(bytes: ByteArray): String =
    Loader.loadPDF(bytes).use { doc ->
        require(doc.numberOfPages <= PDF_IMPORT_MAX_PAGES) { "El PDF tiene mas de $PDF_IMPORT_MAX_PAGES paginas." }
        // Pagina a pagina y con tope: el texto nunca crece en memoria mas alla del limite, lo que haya despues se rechaza.
        val text = StringBuilder()
        val stripper = PDFTextStripper()
        for (page in 1..doc.numberOfPages) {
            stripper.startPage = page
            stripper.endPage = page
            text.append(stripper.getText(doc))
            require(text.length <= PDF_IMPORT_MAX_CHARS) { "El PDF contiene demasiado texto para importarlo de una vez." }
        }
        text.toString()
    }

/** Compatibilidad: el flujo de Ráfaga, que es el que existía antes de que hubiera tipos. */
internal fun parseRafagaText(text: String): RafagaParseResult = parseQuestionBlocks(text, "rafaga")

/** Un bloque ya recortado del texto: sus líneas y si lo abrió un separador sospechoso. */
private class RawBlock(
    val separatorLine: String?,
    val invalidSeparator: Boolean,
    val lines: List<String>,
    /** Va detrás de `--- BLOQUE: RESERVA ---`: en esta versión es un error (no se importan reservas). */
    val reserva: Boolean = false,
    /** Lo abrió un marcador de bloque mal escrito (no un separador de pregunta). */
    val invalidBloqueMarker: Boolean = false,
    /** Va detrás de un marcador de bloque mal escrito: no se sabe si es principal o reserva. */
    val bloqueIncierto: Boolean = false,
)

/**
 * Corta el texto POR LÍNEAS (no con un `split` global):
 *
 * - Lo anterior al primer separador (oficial o sospechoso) es PREÁMBULO y se ignora: portada,
 *   título del documento, instrucciones… no crean fila.
 * - Un separador oficial abre un bloque; uno sospechoso abre un bloque marcado como error.
 * - Un bloque oficial vacío (dos separadores seguidos, o uno al final) no es una pregunta.
 */
/** Los bloques y si el PREÁMBULO ignorado tenía forma de pregunta (una pregunta 1 perdida). */
private class SplitResult(val blocks: List<RawBlock>, val preambleLooksLikeQuestion: Boolean)

private val PREAMBLE_TITLE_LINE = Regex("^TITULO\\s*:", RegexOption.IGNORE_CASE)
private val PREAMBLE_BODY_LINE = Regex("^(?:[A-D]|RESPUESTA)\\s*:", RegexOption.IGNORE_CASE)

/** El aviso cuando el preámbulo ignorado parece una pregunta entera. */
internal const val PREAMBLE_LOOKS_LIKE_QUESTION_WARNING =
    "Antes del primer separador hay texto con forma de pregunta (TITULO y opciones o RESPUESTA) que se ha ignorado: " +
        "comprueba que el separador de la pregunta 1 es exactamente --- PREGUNTA ---."

private fun splitBlocks(text: String): SplitResult {
    val blocks = mutableListOf<RawBlock>()
    val preamble = mutableListOf<String>()
    var separator: String? = null
    var invalid = false
    var lines = mutableListOf<String>()
    var open = false
    var seenAny = false
    var reserva = false
    var invalidMarker = false
    var incierto = false

    fun close() {
        if (!open) return
        if (invalid || lines.any { it.isNotBlank() }) {
            blocks += RawBlock(separator, invalid, lines.toList(), reserva, invalidMarker, incierto && !invalidMarker)
        }
    }

    for (line in text.lines()) {
        val trimmed = line.trim()
        val marker = BLOQUE_MARKER.matchEntire(trimmed)
        val oficial = OFFICIAL_SEPARATOR.matches(trimmed)
        val sospechoso = !oficial && marker == null && SUSPECT_SEPARATOR.matches(trimmed)
        val bloqueSospechoso = marker == null && !oficial && !sospechoso && SUSPECT_BLOQUE.matches(trimmed)
        if (marker != null) {
            // El marcador cierra la pregunta en curso y no abre ninguna: lo que venga hasta el
            // siguiente separador no es de nadie (ni preámbulo ni continuación).
            close()
            open = false
            seenAny = true
            reserva = marker.groupValues[1] == "RESERVA"
            incierto = false
        } else if (oficial || sospechoso || bloqueSospechoso) {
            close()
            open = true
            seenAny = true
            separator = trimmed
            invalid = sospechoso || bloqueSospechoso
            invalidMarker = bloqueSospechoso
            if (bloqueSospechoso) incierto = true
            lines = mutableListOf()
        } else if (open) {
            lines += line
        } else if (!seenAny) {
            preamble += trimmed
        }
    }
    close()
    val looksLikeQuestion = preamble.any { PREAMBLE_TITLE_LINE.containsMatchIn(it) } &&
        preamble.any { PREAMBLE_BODY_LINE.containsMatchIn(it) }
    return SplitResult(blocks, looksLikeQuestion)
}

/**
 * Parsea el texto y devuelve las tres cosas: lo que salió bien, los errores, y
 * **los bloques que fallaron con su contenido parcial**.
 *
 * Un bloque que falla no se tira: se devuelve aparte para que llegue a staging marcado y un
 * humano lo corrija. Se ACUMULAN todos los errores del bloque (título, las cuatro opciones,
 * respuesta, razonamiento, estado…), no sólo el primero.
 *
 * ### Formato (reglas obligatorias)
 *
 * 1. El separador oficial es UNA línea `--- PREGUNTA ---`. Una línea que lo parece pero no lo
 *    es abre un bloque de ERROR ([INVALID_SEPARATOR_DETAIL]); nunca fusiona preguntas.
 * 2. El preámbulo anterior al primer separador se ignora. `numero` es el ordinal del bloque
 *    tras el preámbulo (1 = la primera pregunta real).
 * 3. Un campo repetido dentro del bloque es un ERROR («Campo duplicado: X»), no «gana el
 *    último»; se conserva el PRIMER valor leído.
 * 4. `ESTADO` sólo admite `ANULADA` (insensible a mayúsculas). Su ausencia es lo normal:
 *    `ESTADO: NORMAL` explícito TAMBIÉN es un error, para que la única forma de expresar una
 *    pregunta normal sea no decir nada. `MOTIVO_ANULACION` exige `ESTADO: ANULADA`, ≤ 500;
 *    vacío se trata como ausente. Una línea `ESTADO`/`MOTIVO_ANULACION` SIN los dos puntos ASCII
 *    (`ESTADO ANULADA`, `ESTADO - ANULADA`, `ESTADO： ANULADA`) o una línea suelta `ANULADA` es un
 *    ERROR claro, nunca una continuación: si no, una anulada se importaría como normal en silencio.
 *    Antes de trocear se normalizan U+00A0 (→ espacio), BOM U+FEFF y U+200B (se eliminan).
 * 5. Una clave con grafía casi correcta (`Fuente:`, `TÍTULO:`, `respuesta:`) es un ERROR
 *    («Clave mal escrita: usa X»): antes se pegaba al campo anterior. `Nota:` en prosa sigue
 *    siendo texto. Las claves antiguas con guion bajo (`ID_GLOBAL:`) siguen rechazadas.
 * 6. Sin heurística mágica de cabeceras/pies: sólo un AVISO si una línea de continuación es
 *    exactamente un marcador de página ([PAGE_MARKER_WARNING]).
 *
 * ### Lo que depende de [tipoPregunta]
 *
 * - `razonada`/`inmersiva`: `RAZONAMIENTO` vacío es un ERROR del bloque.
 * - `rafaga`: `RAZONAMIENTO` o `FUENTE` con texto es una ADVERTENCIA
 *   ([RAFAGA_DISCARDS_EXTRAS_WARNING]), no un error.
 */
internal fun parseQuestionBlocks(text: String, tipoPregunta: String): RafagaParseResult {
    val preguntas = mutableListOf<ParsedPregunta>()
    val errores = mutableListOf<RafagaParseError>()
    val bloquesConError = mutableListOf<RafagaBloqueConError>()
    val tipo = tipoPregunta.trim().lowercase()
    val exigeRazonamiento = tipo in TYPES_REQUIRING_REASONING

    val split = splitBlocks(normalizeInvisible(text))
    split.blocks.forEachIndexed { i, raw ->
        val numero = i + 1
        val block = raw.lines.joinToString("\n").trim()
        val bloqueOriginal = if (raw.invalidSeparator) {
            listOfNotNull(raw.separatorLine, block.takeIf { it.isNotEmpty() }).joinToString("\n")
        } else {
            block
        }
        val scan = extractFields(block)
        val fields = scan.fields
        val erroresDelBloque = mutableListOf<RafagaParseError>()
        fun error(campo: String, detalle: String) {
            erroresDelBloque += RafagaParseError(numero, campo, detalle)
        }

        if (raw.reserva) error("BLOQUE", RESERVA_NOT_SUPPORTED_DETAIL)
        if (raw.bloqueIncierto) error("BLOQUE", BLOQUE_UNCERTAIN_DETAIL)
        if (raw.invalidSeparator) error("SEPARADOR", if (raw.invalidBloqueMarker) INVALID_BLOQUE_DETAIL else INVALID_SEPARATOR_DETAIL)
        scan.clavesDesconocidas.forEach { clave -> error(clave, "Campo no admitido en el PDF") }
        scan.clavesMalEscritas.forEach { (escrita, correcta) ->
            error(escrita, "Clave mal escrita: usa $correcta")
        }
        scan.duplicados.forEach { campo -> error(campo, "Campo duplicado: $campo") }
        scan.estadoSinDosPuntos.forEach { (campo, detalle) -> error(campo, detalle) }

        val titulo = fields["TITULO"]?.trim()
        val opciones = mutableListOf<Pair<String, String>>()
        var respuesta: String? = null

        if (titulo.isNullOrBlank()) {
            error("TITULO", "Obligatorio y no puede estar vacío")
        }
        // Es barato validarlo todo: un bloque sin título también dice qué opciones y qué
        // respuesta le faltan, en vez de obligar al revisor a descubrirlo de uno en uno.
        var opcionError = false
        for (letra in listOf("A", "B", "C", "D")) {
            val texto = fields[letra]?.trim()
            if (texto.isNullOrBlank()) {
                error(letra, "Opción $letra obligatoria")
                opcionError = true
            } else {
                opciones += letra to texto
            }
        }
        val valorRespuesta = fields["RESPUESTA"]?.trim()?.uppercase()
        if (valorRespuesta.isNullOrBlank() || valorRespuesta !in setOf("A", "B", "C", "D")) {
            // Sin opciones completas no se acumula: el bloque ya falla por ellas y el
            // revisor arregla primero eso (comportamiento anterior, conservado).
            if (!opcionError) {
                error("RESPUESTA", "Debe ser A, B, C o D — recibido: '${valorRespuesta.orEmpty()}'")
            }
        } else {
            respuesta = valorRespuesta
        }

        val razonamiento = fields["RAZONAMIENTO"]?.trim().orEmpty()
        val fuente = fields["FUENTE"]?.trim().orEmpty()
        if (exigeRazonamiento && razonamiento.isBlank()) {
            error("RAZONAMIENTO", "Obligatorio en preguntas razonadas e inmersivas")
        }

        // ESTADO / MOTIVO_ANULACION. Lo leído se conserva aunque el bloque falle.
        val estado = fields[FIELD_ESTADO]?.trim()
        val anulada = estado != null && estado.uppercase() == "ANULADA"
        if (estado != null && !anulada) {
            error(FIELD_ESTADO, "solo se admite ANULADA (o ausente para pregunta normal)")
        }
        val motivo = fields[FIELD_MOTIVO]?.trim()?.takeIf { it.isNotEmpty() }
        if (motivo != null) {
            if (estado == null) {
                error(FIELD_MOTIVO, "solo se admite junto con ESTADO: ANULADA")
            } else if (motivo.length > MAX_ANNULMENT_REASON_LENGTH) {
                error(FIELD_MOTIVO, "máximo $MAX_ANNULMENT_REASON_LENGTH caracteres (tiene ${motivo.length})")
            }
        }

        // Regla de etiquetas (propietario 2026-10-22): se separan por comas; un «;» se acepta como coma pero se
        // avisa; las repetidas se quitan; más de MAX_ETIQUETAS_POR_PREGUNTA es un aviso (sólo se enlazan las primeras).
        val etiquetaCruda = fields["ETIQUETA"].orEmpty()
        val etiquetas = etiquetaCruda
            .split(",", ";")
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .distinctBy { normalizarNombreEtiqueta(it) }
        val avisoEtiquetas = listOfNotNull(
            ETIQUETA_SEPARATOR_WARNING.takeIf { ';' in etiquetaCruda },
            "ETIQUETA: tiene ${etiquetas.size} temas; solo se enlazan los $MAX_ETIQUETAS_POR_PREGUNTA primeros."
                .takeIf { etiquetas.size > MAX_ETIQUETAS_POR_PREGUNTA },
        )

        val respuestaValida = respuesta
        if (erroresDelBloque.isEmpty() && titulo != null && respuestaValida != null) {
            val avisos = listOfNotNull(
                RAFAGA_DISCARDS_EXTRAS_WARNING
                    .takeIf { !exigeRazonamiento && (razonamiento.isNotBlank() || fuente.isNotBlank()) },
                PAGE_MARKER_WARNING.takeIf { scan.posiblePaginaEnTexto },
                PREAMBLE_LOOKS_LIKE_QUESTION_WARNING.takeIf { i == 0 && split.preambleLooksLikeQuestion },
            ) + avisoEtiquetas
            preguntas += ParsedPregunta(
                numero = numero,
                titulo = titulo,
                opciones = opciones,
                respuesta = respuestaValida,
                razonamiento = razonamiento,
                fuente = fuente,
                etiquetas = etiquetas,
                bloqueOriginal = bloqueOriginal,
                advertencia = avisos.takeIf { it.isNotEmpty() }?.joinToString(" "),
                anulada = anulada,
                motivoAnulacion = motivo.takeIf { anulada },
            )
        } else {
            errores += erroresDelBloque
            bloquesConError += RafagaBloqueConError(
                numero = numero,
                bloqueOriginal = bloqueOriginal,
                // Sin título legible se usa la primera línea real del bloque.
                // No se inventa texto: la fila tiene que poder identificarse
                // en una lista de 300, y lo único honesto que hay es el PDF.
                titulo = titulo.orEmpty().ifBlank {
                    block.lineSequence().firstOrNull { line -> line.isNotBlank() }?.trim()
                        ?: raw.separatorLine.orEmpty()
                },
                opcionA = fields["A"]?.trim().orEmpty(),
                opcionB = fields["B"]?.trim().orEmpty(),
                opcionC = fields["C"]?.trim().orEmpty(),
                opcionD = fields["D"]?.trim().orEmpty(),
                respuesta = fields["RESPUESTA"]?.trim()?.uppercase().orEmpty(),
                razonamiento = fields["RAZONAMIENTO"]?.trim().orEmpty(),
                fuente = fields["FUENTE"]?.trim().orEmpty(),
                etiquetas = etiquetas.joinToString(","),
                detalle = erroresDelBloque.joinToString(" · ") { "${it.campo}: ${it.detalle}" } +
                    (if (i == 0 && split.preambleLooksLikeQuestion) " · $PREAMBLE_LOOKS_LIKE_QUESTION_WARNING" else ""),
                anulada = anulada,
                motivoAnulacion = motivo,
            )
        }
    }

    return RafagaParseResult(preguntas, errores, bloquesConError)
}

/** Todo lo que salió de leer un bloque: los campos (el PRIMER valor de cada uno) y sus anomalías. */
private class FieldScan(
    val fields: Map<String, String>,
    val clavesDesconocidas: List<String>,
    /** Pares (lo escrito, lo correcto): `Fuente` → `FUENTE`. */
    val clavesMalEscritas: List<Pair<String, String>>,
    val duplicados: List<String>,
    val posiblePaginaEnTexto: Boolean,
    /** Pares (campo, detalle): líneas de ESTADO/MOTIVO_ANULACION/ANULADA sin `:` ASCII. */
    val estadoSinDosPuntos: List<Pair<String, String>>,
)

/** Sin acentos y en mayúsculas: `Título` → `TITULO`. */
private fun foldKey(key: String): String =
    Normalizer.normalize(key.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").uppercase()

/**
 * Los campos del bloque y sus anomalías.
 *
 * - **Claves antiguas** (`ID_GLOBAL:`…): se devuelven en vez de tragárselas; antes caían en
 *   «continuación» y el identificador interno acababa dentro del enunciado publicado. Una
 *   línea con dos puntos que NO casa [LEGACY_KEY_LINE] ni es un campo casi-conocido sigue
 *   siendo continuación (`Nota: …` en una explicación).
 * - **Claves casi correctas** (mismo texto salvo mayúsculas/acentos): error, y sus líneas de
 *   continuación NO se pegan al campo anterior.
 * - **Campos repetidos**: se conserva el primero; las líneas de continuación del repetido se
 *   descartan (no se pegan al primero).
 */
private fun extractFields(block: String): FieldScan {
    val result = linkedMapOf<String, StringBuilder>()
    val clavesDesconocidas = linkedSetOf<String>()
    val clavesMalEscritas = linkedMapOf<String, String>()
    val duplicados = linkedSetOf<String>()
    var posiblePagina = false
    val estadoSinDosPuntos = linkedSetOf<Pair<String, String>>()
    var current: String? = null
    for (line in block.lines()) {
        val trimmed = line.trim()
        if (trimmed.isBlank()) continue
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0) {
            val key = trimmed.substring(0, colonIdx).trim()
            if (key in KNOWN_FIELDS) {
                if (key in result) {
                    duplicados += key
                    current = null
                } else {
                    current = key
                    result[key] = StringBuilder(trimmed.substring(colonIdx + 1).trim())
                }
                continue
            }
            val casi = foldKey(key)
            if (casi in KNOWN_FIELDS) {
                clavesMalEscritas.putIfAbsent(key, casi)
                current = null
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
        // Una línea que PARECE ESTADO/MOTIVO_ANULACION/ANULADA pero no es un campo con `:` ASCII
        // jamás se pega como continuación: la anulación se perdería sin que nadie lo viera.
        val estadoDetalle = when {
            ESTADO_WITHOUT_COLON.containsMatchIn(trimmed) || ESTADO_LOOSE_ANULADA.containsMatchIn(trimmed) ->
                FIELD_ESTADO to ESTADO_COLON_DETAIL
            MOTIVO_WITHOUT_COLON.containsMatchIn(trimmed) || MOTIVO_LOOSE.containsMatchIn(trimmed) ->
                FIELD_MOTIVO to MOTIVO_COLON_DETAIL
            LONE_ANULADA.matches(trimmed) -> FIELD_ESTADO to LONE_ANULADA_DETAIL
            else -> null
        }
        if (estadoDetalle != null) {
            estadoSinDosPuntos += estadoDetalle
            current = null
            continue
        }
        // Continuation line (e.g. multi-line RAZONAMIENTO)
        val destino = current?.let { result[it] }
        if (destino != null) {
            if (PAGE_MARKER_LINE.matches(trimmed)) posiblePagina = true
            destino.append('\n').append(trimmed)
        }
    }
    return FieldScan(
        fields = result.mapValues { it.value.toString() },
        clavesDesconocidas = clavesDesconocidas.toList(),
        clavesMalEscritas = clavesMalEscritas.toList(),
        duplicados = duplicados.toList(),
        posiblePaginaEnTexto = posiblePagina,
        estadoSinDosPuntos = estadoSinDosPuntos.toList(),
    )
}
