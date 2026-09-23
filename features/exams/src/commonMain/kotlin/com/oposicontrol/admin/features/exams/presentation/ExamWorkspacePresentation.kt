package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities

/**
 * Todo lo que el workspace de Exámenes DECIDE, sin pintar nada.
 *
 * Vive separado de los composables por una razón práctica y no estética: las
 * reglas que este rediseño tiene que respetar —tres estados y no cuatro, tres
 * tipos con las descripciones reales, un solo número de preguntas, recursos
 * derivados de las preguntas— son afirmaciones sobre el dominio, y una
 * afirmación sobre el dominio se prueba con un test de unidad, no mirando una
 * captura de pantalla.
 *
 * Regla que atraviesa el fichero entero: **nada que el contrato no dé**. Si un
 * dato no existe en `Exam`, `ExamQuestion`, `QuestionResource` o
 * `SpecialtyCapabilities`, aquí no se fabrica.
 */

// ---------------------------------------------------------------------------
// Tipo de examen
// ---------------------------------------------------------------------------

/**
 * Los tres tipos reales, con la descripción de lo que CADA UNO hace de verdad.
 *
 * Las descripciones no son literarias: cada una está atada a un comportamiento
 * verificado del backend, y por eso ninguna habla de orden de preguntas ni de
 * bloquear el retroceso — `AppV1Routes.responder` no impone nada de eso, así que
 * decirlo sería inventar una restricción que el producto no tiene.
 *
 *  - Ráfaga: `SupabaseV1Gateway` sirve sus preguntas sin sesión cuando el examen
 *    es gratuito. Es el único tipo con acceso anónimo.
 *  - Razonado: existe `soluciones_pregunta.razonamiento`.
 *  - Inmersivo: existe `recursos_pregunta.tipo_recurso`.
 *
 * `guiado` se acepta al leer porque es el alias legacy que aún vive en la base,
 * pero **nunca se escribe ni se muestra**: el vocabulario de producto es
 * Ráfaga · Razonado · Inmersivo.
 */
enum class ExamTypeOption(
    val wireValue: String,
    val label: String,
    val description: String,
    /**
     * Icono y familia de color de la tarjeta.
     *
     * No son decoración arbitraria: cada uno apunta a lo que ese tipo ES —una
     * ráfaga de preguntas, un razonamiento escrito, un recurso adjunto— y el rol
     * viene del catálogo semántico compartido, no de un color elegido aquí. En la
     * tarjeta deshabilitada el rol se apaga a `NEUTRAL`, y ese apagado forma
     * parte del mensaje.
     */
    val icon: AppIcon,
    val role: SemanticRole,
) {
    RAFAGA(
        wireValue = "rafaga",
        label = "Ráfaga",
        description = "Preguntas tipo test encadenadas. Único tipo que puede servirse gratis sin sesión.",
        icon = AppIcon.QUESTIONS,
        role = SemanticRole.INFO,
    ),
    RAZONADO(
        wireValue = "razonado",
        label = "Razonado",
        description = "Cada pregunta lleva un razonamiento que explica la respuesta correcta.",
        icon = AppIcon.NEWS_TYPES,
        role = SemanticRole.INSIGHT,
    ),
    INMERSIVO(
        wireValue = "inmersivo",
        label = "Inmersivo",
        description = "Preguntas que pueden apoyarse en recursos (imagen, audio, vídeo, documento o texto).",
        icon = AppIcon.RESOURCES,
        role = SemanticRole.COMMERCE,
    ),
    ;

    companion object {
        fun fromWire(value: String): ExamTypeOption? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.wireValue == normalized } ?: when (normalized) {
                // Alias legacy de la base. Se lee, no se escribe.
                "guiado" -> RAZONADO
                else -> null
            }
        }

        /** Etiqueta mostrable de un tipo desconocido, sin inventarle nombre. */
        fun labelOf(value: String): String = fromWire(value)?.label ?: "Tipo sin identificar"
    }
}

/**
 * Si la especialidad elegida permite este tipo, y por qué no cuando no.
 *
 * [allowed] es `false` mientras las capacidades no se hayan podido cargar, y eso
 * es deliberado: `saveExam` rechaza guardar un tipo cuya validez no ha podido
 * comprobar, así que ofrecer el control habilitado prometería algo que el
 * guardado va a rechazar. Debilitar esto reabre el hueco que ese guard cerró.
 */
data class ExamTypeAvailability(
    val option: ExamTypeOption,
    val allowed: Boolean,
    val note: String?,
)

/** Por qué el bloque de tipos está como está. Lo usa la UI para elegir el tono del aviso. */
enum class ExamCapabilitiesNotice {
    /** Capacidades cargadas y decididas. */
    RESOLVED,

    /** La especialidad no tiene fila propia: son los valores por defecto, nadie ha decidido esto. */
    NOT_CONFIGURED,

    /** Cargando o todavía sin especialidad. */
    PENDING,

    /** No se pudieron cargar. Ningún tipo se puede cambiar hasta saberlo. */
    UNAVAILABLE,
}

fun examCapabilitiesNotice(state: UiAsync<SpecialtyCapabilities>): ExamCapabilitiesNotice = when (state) {
    is UiAsync.Success -> if (state.value.configured) {
        ExamCapabilitiesNotice.RESOLVED
    } else {
        ExamCapabilitiesNotice.NOT_CONFIGURED
    }
    is UiAsync.Error -> ExamCapabilitiesNotice.UNAVAILABLE
    UiAsync.Idle, UiAsync.Loading, UiAsync.Empty -> ExamCapabilitiesNotice.PENDING
}

/**
 * Los tres tipos con su disponibilidad resuelta.
 *
 * Nunca `if (especialidad == "X")`: la fuente es `capacidades_especialidad`, y
 * el motivo humano de un tipo bloqueado es `mensaje_limitacion` tal cual lo
 * escribió quien configuró la especialidad — no una frase inventada aquí.
 */
fun examTypeAvailability(state: UiAsync<SpecialtyCapabilities>): List<ExamTypeAvailability> {
    val capabilities = (state as? UiAsync.Success)?.value
    val fallbackNote = when (examCapabilitiesNotice(state)) {
        ExamCapabilitiesNotice.PENDING -> pendingCapabilitiesNote(state)
        ExamCapabilitiesNotice.UNAVAILABLE ->
            "No se ha podido comprobar qué permite esta especialidad, así que el tipo no se puede cambiar."
        else -> null
    }
    return ExamTypeOption.entries.map { option ->
        if (capabilities == null) {
            ExamTypeAvailability(option = option, allowed = false, note = fallbackNote)
        } else {
            val allowed = capabilities.allows(option.wireValue)
            ExamTypeAvailability(
                option = option,
                allowed = allowed,
                note = if (allowed) null else capabilities.limitationMessage?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Aviso de nivel de BLOQUE para el selector de tipo. `null` cuando no hay nada que advertir.
 *
 * El `mensaje_limitacion` sale por aquí, y no por cada tarjeta bloqueada, porque
 * es un dato de la ESPECIALIDAD: el esquema guarda uno solo, no uno por tipo.
 * Repetirlo palabra por palabra debajo de cada tarjeta era pintar un dato de
 * nivel bloque a nivel elemento — el mismo párrafo dos veces seguidas cuando dos
 * tipos están bloqueados, que es lo normal.
 *
 * Sacarlo del elemento arregla además el contraste: el motivo ya no compite con
 * el título de la tarjeta y puede ir en `textSecondary` sobre el fondo del
 * bloque, en vez de en ámbar sobre `surfaceMuted` —donde medía 4,10:1 y no
 * llegaba al 4,5:1 que WCAG pide a un texto de cuerpo—.
 */
fun examCapabilitiesMessage(state: UiAsync<SpecialtyCapabilities>): String? =
    when (examCapabilitiesNotice(state)) {
        ExamCapabilitiesNotice.RESOLVED -> {
            val capabilities = (state as? UiAsync.Success)?.value
            val hasBlocked = ExamTypeOption.entries.any { capabilities?.allows(it.wireValue) != true }
            capabilities?.limitationMessage?.takeIf { it.isNotBlank() && hasBlocked }
        }
        ExamCapabilitiesNotice.NOT_CONFIGURED ->
            "Nadie ha decidido todavía qué modos permite esta especialidad: se están usando los valores por defecto."
        ExamCapabilitiesNotice.PENDING -> pendingCapabilitiesNote(state)
        ExamCapabilitiesNotice.UNAVAILABLE ->
            "No se han podido comprobar los modos de esta especialidad. Reintenta antes de cambiar el tipo."
    }

// ---------------------------------------------------------------------------
// Estado del examen
// ---------------------------------------------------------------------------

/**
 * Los TRES estados que existen, derivados de `activo` + `publicado_en`.
 *
 * No hay «En revisión», y no puede haberla: no existe ninguna columna ni ninguna
 * transición que la respalde. Una cuarta palabra aquí sería una promesa de
 * flujo de trabajo que el backend no cumple.
 */
fun examStatusLabel(status: ExamStatus): String = when (status) {
    ExamStatus.DRAFT -> "Borrador"
    ExamStatus.PUBLISHED -> "Publicado"
    ExamStatus.ARCHIVED -> "Archivado"
}

/** Las tres etiquetas, en el orden en que se ofrecen como filtro. */
val examStatusLabels: List<String> = ExamStatus.entries.map(::examStatusLabel)

// ---------------------------------------------------------------------------
// Catálogo: filtros y paginación, los dos en cliente
// ---------------------------------------------------------------------------

/**
 * Los filtros que resuelve el propio panel.
 *
 * Búsqueda y Estado los resuelve el ViewModel (también en cliente, sobre la
 * lista ya descargada); Especialidad, Tipo y Año se resuelven aquí. La división
 * es histórica, no conceptual: `GET /examenes` no acepta ni un solo query param,
 * así que **todo** el filtrado de esta pantalla es de cliente y ninguna parte de
 * la interfaz debe insinuar lo contrario.
 */
data class ExamCatalogFilter(
    val specialtyId: String? = null,
    val typeWire: String? = null,
    val year: Int? = null,
) {
    val isActive: Boolean get() = specialtyId != null || typeWire != null || year != null
}

fun applyCatalogFilters(exams: List<Exam>, filter: ExamCatalogFilter): List<Exam> = exams.filter { exam ->
    (filter.specialtyId == null || exam.specialtyId == filter.specialtyId) &&
        (filter.typeWire == null || ExamTypeOption.fromWire(exam.typeExam)?.wireValue == filter.typeWire) &&
        (filter.year == null || exam.year == filter.year)
}

/**
 * Una página de la lista COMPLETA que ya está en memoria.
 *
 * No hay paginación de servidor que imitar: el endpoint devuelve todo y el panel
 * lo trocea para no pintar doscientas tarjetas de golpe. Por eso la barra dice
 * siempre cuántos elementos hay en total — un paginador que sólo enseñe «1 2 3 …»
 * se leería como si hubiera páginas que pedir al servidor, y no las hay.
 */
data class ExamCatalogPage<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageCount: Int,
    val totalItems: Int,
    val firstItemNumber: Int,
    val lastItemNumber: Int,
) {
    val hasPrevious: Boolean get() = pageIndex > 0
    val hasNext: Boolean get() = pageIndex < pageCount - 1
    val isPaginated: Boolean get() = pageCount > 1
}

/** Cuántas tarjetas caben en la columna del catálogo sin convertirla en un scroll infinito. */
const val ExamCatalogPageSize: Int = 12

fun <T> examCatalogPage(
    items: List<T>,
    pageIndex: Int,
    pageSize: Int = ExamCatalogPageSize,
): ExamCatalogPage<T> {
    val safePageSize = pageSize.coerceAtLeast(1)
    val pageCount = if (items.isEmpty()) 1 else (items.size + safePageSize - 1) / safePageSize
    val safeIndex = pageIndex.coerceIn(0, pageCount - 1)
    val from = safeIndex * safePageSize
    val to = minOf(from + safePageSize, items.size)
    return ExamCatalogPage(
        items = if (from >= items.size) emptyList() else items.subList(from, to),
        pageIndex = safeIndex,
        pageCount = pageCount,
        totalItems = items.size,
        firstItemNumber = if (items.isEmpty()) 0 else from + 1,
        lastItemNumber = to,
    )
}

/** «Mostrando 1–12 de 47 exámenes». Dice el total porque el total está aquí, en memoria. */
fun examCatalogRangeText(page: ExamCatalogPage<*>): String = when {
    page.totalItems == 0 -> "Sin exámenes que mostrar"
    page.totalItems == 1 -> "1 examen"
    !page.isPaginated -> "${page.totalItems} exámenes"
    else -> "${page.firstItemNumber}–${page.lastItemNumber} de ${page.totalItems} exámenes"
}

// ---------------------------------------------------------------------------
// Preguntas vinculadas: UN número, no dos
// ---------------------------------------------------------------------------

/**
 * Cuántas preguntas tiene el examen. Un número, nunca dos.
 *
 * `total_preguntas` es el RECUENTO de `preguntas_examen`, no un objetivo: no
 * existe ninguna columna que diga cuántas debería tener. Enseñar «118 de 120»
 * exigiría inventarse ese 120 y, con él, un estado «incompleto» que el producto
 * no tiene.
 */
fun linkedQuestionsText(count: Int): String = when (count) {
    0 -> "Sin preguntas vinculadas"
    1 -> "1 pregunta vinculada"
    else -> "$count preguntas vinculadas"
}

/**
 * El mismo recuento, sin la palabra «vinculada».
 *
 * Para la línea de meta de una tarjeta de catálogo, donde compite con el tipo y
 * la duración en 330dp: con la forma larga la duración se elidía. Ahí «vinculada»
 * no aporta —no hay ningún otro recuento de preguntas con el que confundirlo— y
 * en la cabecera y el resumen, donde sí hay sitio, se sigue diciendo entera.
 */
fun linkedQuestionsShortText(count: Int): String = when (count) {
    0 -> "Sin preguntas"
    1 -> "1 pregunta"
    else -> "$count preguntas"
}

/**
 * Cuántas preguntas hay realmente. **Una sola fuente de verdad, y ninguna
 * estimación.**
 *
 * **`Empty` es CERO, no «todavía no lo sé».** Es la corrección de un defecto que
 * se vio en la captura antes de que lo viera nadie más: con la lista cargada y
 * vacía, el recuento caía al `total_preguntas` que traía la fila del catálogo y
 * la pantalla se contradecía consigo misma —«12 preguntas vinculadas» en la
 * cabecera, en la insignia de la pestaña y en el resumen, encima de una lista
 * que decía «Este examen todavía no tiene preguntas asociadas»—.
 *
 * **`Idle`/`Loading` es `null`.** Este número alimenta la insignia de la pestaña y
 * las comprobaciones, que hablan de la lista que se va a ver en la pestaña: sólo
 * vale la lista cargada. Desde M1 `total_preguntas` sí es un recuento mantenido
 * por la base (al vincular, quitar o archivar), y por eso las ETIQUETAS lo
 * enseñan como dato mientras la lista carga ([linkedQuestionCountLabel], K2-2);
 * pero una insignia o un «sin preguntas» se leen como hechos sobre la lista, y la
 * lista todavía no ha llegado.
 *
 * Un fallo de carga también devuelve `null`. Los dos casos se distinguen por
 * [linkedQuestionCountLabel], que es quien sabe si estamos contando o si no se ha
 * podido contar — un `null` a secas no puede decir cuál de las dos cosas es.
 */
fun linkedQuestionCount(questions: UiAsync<List<ExamQuestion>>, exam: Exam?): Int? = when (questions) {
    is UiAsync.Success -> questions.value.size
    UiAsync.Empty -> 0
    UiAsync.Idle, UiAsync.Loading -> null
    is UiAsync.Error -> null
}

/**
 * El recuento de preguntas tal y como se ENSEÑA, con su procedencia.
 *
 * Es la pieza que impide que «contando» y «no se ha podido contar» se pinten
 * igual. Las dos son un `null` para [linkedQuestionCount] y significan cosas
 * opuestas: una se resuelve sola en un segundo y la otra necesita que alguien
 * reintente.
 *
 * Toda la pantalla —cabecera, ficha lateral, parámetros de Configuración y la
 * tarjeta del examen abierto en el catálogo— lee de aquí. Es la regla única de
 * fuente de verdad, y existe porque ya hubo un defecto real de catálogo y
 * workspace diciendo cifras distintas del mismo examen.
 */
fun linkedQuestionCountLabel(
    questions: UiAsync<List<ExamQuestion>>,
    provisional: Int?,
): ExamFieldLabel = when (questions) {
    is UiAsync.Success -> ExamFieldLabel(questions.value.size.toString(), ExamFieldOrigin.VALUE)
    UiAsync.Empty -> ExamFieldLabel("0", ExamFieldOrigin.VALUE)
    UiAsync.Idle, UiAsync.Loading -> pendingQuestionsLabel(provisional) { it.toString() }
    is UiAsync.Error -> uncountedQuestions()
}

/** La misma procedencia, en la forma larga que usan la cabecera y las comprobaciones. */
fun linkedQuestionsLabel(
    questions: UiAsync<List<ExamQuestion>>,
    provisional: Int?,
): ExamFieldLabel = when (questions) {
    is UiAsync.Success -> ExamFieldLabel(linkedQuestionsText(questions.value.size), ExamFieldOrigin.VALUE)
    UiAsync.Empty -> ExamFieldLabel(linkedQuestionsText(0), ExamFieldOrigin.VALUE)
    UiAsync.Idle, UiAsync.Loading -> pendingQuestionsLabel(provisional, ::linkedQuestionsText)
    is UiAsync.Error -> uncountedQuestions()
}

/**
 * La forma CORTA, para la línea de meta de la tarjeta del catálogo.
 *
 * Sólo la usa el examen abierto, que es el único cuyo recuento real conocemos:
 * de los demás no se cargan las preguntas y lo único disponible es su
 * `total_preguntas`. La tarjeta del abierto tiene que decir lo mismo que la
 * cabecera —las dos están en pantalla a la vez— y ya hubo un defecto real de
 * catálogo y workspace discrepando del mismo examen. Por eso usa la misma cifra
 * mientras carga que la cabecera ([provisionalQuestionCount]).
 */
fun linkedQuestionsShortLabel(questions: UiAsync<List<ExamQuestion>>, provisional: Int?): String =
    when (questions) {
        is UiAsync.Success -> linkedQuestionsShortText(questions.value.size)
        UiAsync.Empty -> linkedQuestionsShortText(0)
        UiAsync.Idle, UiAsync.Loading -> pendingQuestionsLabel(provisional, ::linkedQuestionsShortText).text
        is UiAsync.Error -> uncountedQuestions().text
    }

/**
 * La cifra de preguntas de un examen guardado mientras su lista todavía se está
 * cargando.
 *
 * Primero, el último recuento CARGADO en esta sesión
 * (`ExamsState.countedQuestions`): evita que la cabecera parpadee cada vez que se
 * guarda o se publica. Si no lo hay, el `total_preguntas` servido con la fila del
 * catálogo, que es lo único que existe en la primera apertura.
 *
 * **Las dos son un dato (K2-2).** Desde M1 `total_preguntas` lo mantiene la base
 * al vincular, quitar o archivar preguntas —con la misma definición que la lista:
 * preguntas activas vinculadas—, así que ya no se marca «sin confirmar». Antes
 * ningún escritor la mantenía y enseñarla como valor era afirmar una cifra vieja.
 *
 * `null` para un examen que todavía no existe: no hay nada que contar.
 *
 * El badge de la pestaña Preguntas y las comprobaciones NO la usan: ahí sólo
 * vale la lista cargada ([linkedQuestionCount]), porque hablan de la lista que se
 * va a ver.
 */
fun provisionalQuestionCount(state: ExamsState, exam: Exam?): Int? =
    exam?.let { state.countedQuestions[it.id] ?: it.questionCount }

private fun pendingQuestionsLabel(provisional: Int?, format: (Int) -> String): ExamFieldLabel =
    if (provisional == null) {
        ExamFieldLabel("Contando preguntas…", ExamFieldOrigin.PENDING)
    } else {
        // La cifra servida es un dato (K2-2): se pinta como tal, sin sufijo.
        ExamFieldLabel(format(provisional), ExamFieldOrigin.VALUE)
    }

/** Un fallo al contar no enseña NINGUNA cifra, tampoco la provisional: no saber no es saber doce. */
private fun uncountedQuestions() = ExamFieldLabel(
    text = "Sin contar",
    origin = ExamFieldOrigin.UNAVAILABLE,
    detail = "No se ha podido contar las preguntas de este examen.",
)

// ---------------------------------------------------------------------------
// Recursos: vista agregada de las preguntas, de sólo lectura
// ---------------------------------------------------------------------------

/**
 * Un recurso, tal y como existe: colgado de una PREGUNTA.
 *
 * No hay relación `examen → recurso`. `recursos_pregunta` tiene `pregunta_id` y
 * no tiene `examen_id`, y todas las rutas de recurso cuelgan de
 * `/preguntas/{id}/recursos`. Lo único que un examen tiene directamente es la
 * portada, que es una columna de `examenes` y vive en Información básica.
 *
 * No lleva estado. El único que la base guarda es `activo: Boolean`, y ni
 * siquiera llega al cliente: `QuestionResource` no lo mapea. «Válido»,
 * «pendiente» y «revisado» no existen en ninguna parte del sistema.
 */
data class ExamResourceEntry(
    val id: String,
    val type: QuestionResourceType,
    val typeLabel: String,
    val title: String,
    val preview: String,
    /**
     * El fichero NO está servido: lo que se guardó como URL es una ruta del
     * ordenador de quien lo subió (P0-4 / EXAM-008).
     *
     * Es un hecho derivable del dato, no una suposición: el cliente nunca sube
     * bytes y deja la ruta local en `url_externa`, así que ese recurso no existe
     * para nadie más. Se dice; la ruta **no se enseña**.
     */
    val isUnavailable: Boolean = false,
)

/** Los recursos de una pregunta concreta del examen, con su posición en él. */
data class ExamResourceGroup(
    val questionId: String,
    val position: Int,
    val questionTitle: String,
    val resources: List<ExamResourceEntry>,
    /** El tipo de la pregunta, o `null` si el servidor mandó uno que no se reconoce. */
    val questionType: QuestionType? = null,
    /**
     * Si esta pregunta puede llevar recursos.
     *
     * Sólo las inmersivas pueden. La regla es de producto: el backend no la hace
     * cumplir, así que en los datos pueden existir recursos colgados de otra
     * cosa, y esta pestaña los enseña como anomalía en vez de esconderlos.
     *
     * **Cuidado con la certeza.** Un `tipo_pregunta` ausente llega como
     * «rafaga» —el DTO del backend lo declara con ese valor por defecto y el JSON
     * del servidor no fija `encodeDefaults`—, así que de una pregunta que no sea
     * inmersiva sólo se puede afirmar que **no consta** como tal.
     */
    val resourcesAllowed: Boolean = true,
)

/** Lo que se dice de un recurso cuya subida nunca ocurrió. */
const val EXAM_RESOURCE_UNAVAILABLE: String =
    "Archivo no disponible: se guardó una ruta local en lugar de subirlo."

/** Lo que se dice de un recurso colgado de una pregunta que no consta inmersiva. */
const val EXAM_RESOURCE_NOT_IMMERSIVE: String =
    "Recurso en una pregunta que no consta como inmersiva."

/**
 * Si lo guardado como URL es una ruta del ordenador de quien la escribió.
 *
 * Se comprueba lo que de verdad aparece en ese campo: unidades de Windows
 * (`C:\` y `C:/`), rutas UNC, `file:` y las raíces habituales de macOS y Linux.
 * Una URL servida (`https://…`) **no** se marca.
 */
internal fun isLocalResourcePath(value: String): Boolean {
    val path = value.trim()
    if (path.isEmpty()) return false
    if (path.startsWith("file:", ignoreCase = true)) return true
    if (path.startsWith("\\\\")) return true
    if (path.length >= 3 && path[0].isLetter() && path[1] == ':' && (path[2] == '\\' || path[2] == '/')) return true
    return path.startsWith("/Users/") || path.startsWith("/home/") || path.startsWith("/var/") || path.startsWith("/tmp/")
}

/** Recuento honesto: cuántos recursos, en cuántas preguntas, sobre cuántas hay. */
data class ExamResourceTotals(
    val resourceCount: Int,
    val questionsWithResources: Int,
    val totalQuestions: Int,
)

fun resourceTypeLabel(type: QuestionResourceType): String = when (type) {
    QuestionResourceType.IMAGE -> "Imagen"
    QuestionResourceType.AUDIO -> "Audio"
    QuestionResourceType.VIDEO -> "Vídeo"
    QuestionResourceType.DOCUMENT -> "Documento"
    QuestionResourceType.TEXT -> "Texto"
}

/**
 * La cadena real: examen → preguntas_examen → preguntas → recursos_pregunta.
 *
 * Sólo devuelve las preguntas que TIENEN recursos: una lista con cuarenta filas
 * vacías para encontrar las tres que llevan algo no es una vista, es un ruido.
 */
fun examResourceGroups(questions: List<ExamQuestion>): List<ExamResourceGroup> = questions
    .sortedBy { it.sortOrder }
    .mapIndexedNotNull { index, question ->
        val resources = question.resources.sortedBy { it.sortOrder }
        if (resources.isEmpty()) return@mapIndexedNotNull null
        ExamResourceGroup(
            questionId = question.id,
            position = index + 1,
            questionTitle = question.title.ifBlank { question.statement }.ifBlank { "Pregunta sin título" },
            questionType = question.type,
            // Sólo las inmersivas pueden llevar recursos. Se compara contra el
            // tipo tal y como llega: de una pregunta que no sea inmersiva sólo
            // se puede afirmar que NO CONSTA como tal.
            resourcesAllowed = question.type == QuestionType.INMERSIVA,
            resources = resources.map { resource ->
                ExamResourceEntry(
                    id = resource.id,
                    type = resource.type,
                    typeLabel = resourceTypeLabel(resource.type),
                    title = resource.title.ifBlank { resourceTypeLabel(resource.type) },
                    // Un recurso de texto vive en `textBody`: no tiene fichero que
                    // servir, así que su URL no dice nada de si está disponible.
                    isUnavailable = resource.type != QuestionResourceType.TEXT &&
                        isLocalResourcePath(resource.publicUrl.orEmpty()),
                    preview = when (resource.type) {
                        QuestionResourceType.TEXT -> resource.textBody.orEmpty()
                        // El NOMBRE del fichero, no su ruta.
                        //
                        // Misma regla que hizo desaparecer los UUID de esta
                        // pantalla: una ruta de almacenamiento es implementación,
                        // no un dato de producto, y aquí además suele ser la ruta
                        // local del ordenador de quien subió el recurso —el campo
                        // acaba conteniendo eso—, así que enseñarla entera filtra
                        // el escritorio de un administrador dentro del panel.
                        else -> resource.publicUrl.orEmpty().resourceFileName()
                    },
                )
            },
        )
    }

/**
 * El último segmento de una ruta o URL. Vacío si no queda nada legible.
 *
 * Corta por los dos separadores porque las dos formas conviven en el mismo campo:
 * `url_externa` guarda tanto URLs del almacenamiento como rutas locales de
 * Windows de quien subió el fichero.
 */
internal fun String.resourceFileName(): String =
    trim().trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').substringBefore('?')

fun examResourceTotals(questions: List<ExamQuestion>): ExamResourceTotals {
    val groups = examResourceGroups(questions)
    return ExamResourceTotals(
        resourceCount = groups.sumOf { it.resources.size },
        questionsWithResources = groups.size,
        totalQuestions = questions.size,
    )
}

fun examResourceTotalsText(totals: ExamResourceTotals): String = when {
    totals.totalQuestions == 0 -> "Este examen todavía no tiene preguntas asociadas."
    totals.resourceCount == 0 && totals.totalQuestions == 1 ->
        "La única pregunta de este examen no tiene recursos."
    totals.resourceCount == 0 ->
        "Ninguna de las ${totals.totalQuestions} preguntas de este examen tiene recursos."
    totals.resourceCount == 1 ->
        "1 recurso, en 1 de las ${totals.totalQuestions} preguntas."
    else ->
        "${totals.resourceCount} recursos en ${totals.questionsWithResources} de las ${totals.totalQuestions} preguntas."
}

// ---------------------------------------------------------------------------
// Resumen: para detectar problemas, no para repetir el título
// ---------------------------------------------------------------------------

enum class ExamCheckStatus {
    /** Correcto. */
    OK,

    /** Impide guardar. */
    BLOCKING,

    /** No impide nada, pero conviene mirarlo antes de publicar. */
    WARNING,

    /** No se ha podido comprobar. No es un OK por descarte. */
    UNKNOWN,
}

/**
 * Qué comprobación es ésta.
 *
 * Existe para que el resumen pueda separar la compatibilidad del tipo del resto
 * sin buscarla por su texto: una comprobación se identifica por lo que
 * comprueba, no por cómo está redactada hoy.
 */
enum class ExamCheckId {
    TITLE,

    /**
     * Código repetido (P1-3).
     *
     * `codigo_examen` es único en la tabla, y ahora que el código se puede
     * escribir, repetir uno es un flujo normal y no un accidente. Guardarlo sólo
     * podía acabar en un 500 que la pantalla contaba como «El servidor no está
     * respondiendo bien»; aquí se dice qué pasa de verdad y con qué examen choca.
     */
    CODE,
    SPECIALTY,
    TYPE_ALLOWED,
    LINKED_QUESTIONS,

    /**
     * Duración de cero minutos (P2-7).
     *
     * Aviso y no bloqueo: la validación al guardar es cosa del ViewModel. Aquí se
     * dice que un examen de 0 minutos no se puede hacer, en vez de pintarlo como
     * «Sin duración», que es justo lo contrario de lo que se guardaría.
     */
    DURATION,
    UNSAVED_CHANGES,

    /**
     * Precio puesto en un examen gratuito.
     *
     * `precio_tickets` y `tipo_acceso` son dos columnas independientes y nada
     * impide que convivan un `gratuito` y un precio de 1300. El servidor no lo
     * cobra —el acceso manda—, así que el número queda ahí sin efecto: el tipo de
     * dato inerte que hace que alguien crea haber puesto un precio y no lo haya
     * puesto. Es aviso y no bloqueo porque guardar así es perfectamente legal.
     */
    ACCESS_PRICE,
}

// ---------------------------------------------------------------------------
// Portada
// ---------------------------------------------------------------------------

/**
 * En qué punto está la portada del examen.
 *
 * Siete situaciones y no dos. El editor anterior distinguía «hay» y «no hay», y
 * con eso ofrecía «Guardar portada» también en un examen que todavía no existe —
 * un botón que sólo podía producir «Guarda el examen antes de subir portada».
 *
 * El camino de examen nuevo **ya funciona y no hay que inventarlo** (D3, opción
 * B): `saveExam` captura la selección local, crea el examen y encadena la subida
 * con el id recién creado. Lo que faltaba no era almacenamiento temporal, era
 * **decirlo**: en un examen nuevo la imagen se sube al crearlo, y la interfaz
 * tiene que prometer eso y no otra cosa.
 */
enum class ExamCoverStage {
    /** Ni portada guardada ni fichero elegido. */
    EMPTY,

    /** Examen nuevo con fichero elegido: se subirá al crear el examen. */
    PENDING_ON_CREATE,

    /** Examen existente sin portada, con fichero elegido: se sube ahora. */
    PENDING_UPLOAD,

    /** Portada guardada en el servidor, sin cambios pendientes. */
    STORED,

    /** Portada guardada y un fichero nuevo elegido que la sustituirá. */
    REPLACING,

    /** Subida en curso. */
    UPLOADING,

    /**
     * Retirada en curso.
     *
     * Existe aparte de [UPLOADING] porque las dos comparten el mismo estado de
     * carga (`coverUploadState`) y antes se pintaban igual: quitar una portada
     * decía «Subiendo…».
     */
    REMOVING,
}

/**
 * Subtítulo del bloque de portada (D3).
 *
 * El anterior —«Imagen con la que el examen se presenta en la app»— era falso:
 * `AppExamenDto` no incluye la portada, así que hoy la app no la recibe. La
 * imagen se guarda con el examen y eso es todo lo que se puede afirmar.
 */
const val EXAM_COVER_SUBTITLE: String = "Imagen guardada con el examen. La API de la app todavía no la envía."

/**
 * Formatos y tamaño (D3). Son EXACTAMENTE los que filtra el selector de fichero
 * y los que cortan cliente y servidor.
 */
const val EXAM_COVER_FORMATS: String = "JPG, PNG o WEBP · Máximo 10 MB"

/**
 * El aviso antes de quitar la portada (K5-3).
 *
 * Desde PW-8 quitarla pone a null sus columnas y DESPUÉS borra el objeto del
 * bucket (`clearExamenPortada`). El aviso anterior decía que el archivo no se
 * borraba, que era verdad entonces y ya no lo es: sin subirla otra vez, no hay
 * vuelta atrás.
 */
const val EXAM_COVER_CLEAR_WARNING: String =
    "La portada deja de estar asociada a este examen y su archivo se borra del almacenamiento."

/**
 * Lo que el bloque de portada tiene que pintar, decidido fuera del composable.
 *
 * [fileName] es el nombre del fichero y nunca un identificador. Sólo lo tiene una
 * portada ELEGIDA: el nombre que dio el selector o el último segmento de su ruta
 * local — nunca la ruta entera, que es el escritorio de quien administra. Una
 * portada GUARDADA no tiene nombre que enseñar (K5-3): desde PW-8 el servidor la
 * guarda como `exam-covers/<examen_id>/<uuid>.<ext>` y su último segmento es un
 * uuid, así que el marcador dice el formato ([storedCoverLabel]) y nada más.
 *
 * [previewPath] es la **vista previa**, y sólo existe para el fichero LOCAL
 * recién elegido: la plataforma de escritorio sí sabe decodificarlo
 * (`NewsBackgroundImage.jvm.kt`, Skia, fuera del hilo de interfaz). La portada ya
 * guardada NO se previsualiza: cargar la remota pediría red desde un Composable y
 * un caché de imágenes que no existe. En su sitio va un marcador honesto,
 * «Portada guardada (PNG)».
 *
 * Lo que NO lleva, y no por olvido: «Cómo se mostrará». Ninguna parte de la app
 * pinta hoy portadas y ningún contrato fija su proporción (D3).
 */
data class ExamCoverPresentation(
    val stage: ExamCoverStage,
    val fileName: String?,
    /**
     * La línea de estado de la zona de acciones, o `null` si no hay nada que el
     * marcador no diga ya (P2-4).
     *
     * Sin portada, guardada o quitándose, la zona de imagen ya lo dice —«Sin
     * portada», «Portada guardada», «Quitando…»— y repetirlo al lado era leer lo
     * mismo dos veces: «Sin portada» dos veces seguidas, o «Portada guardada» y
     * «Portada guardada con el examen.».
     */
    val statusText: String?,
    /** Qué dice la zona de imagen cuando no hay vista previa que enseñar. */
    val placeholderText: String,
    /** Ruta LOCAL del fichero elegido, para la vista previa. Nunca una URL. */
    val previewPath: String?,
    val canPick: Boolean,
    val canUploadNow: Boolean,
    val canCancelSelection: Boolean,
    val canRemove: Boolean,
    val pickLabel: String,
    val errorMessage: String?,
)

fun examCoverPresentation(state: ExamsState): ExamCoverPresentation {
    val isNew = state.selectedExamId == null
    // Guardada = hay RUTA de objeto (K5-3). La URL ya no vale como señal: es una
    // firma efímera que el servidor genera en cada lectura, y puede faltar aunque
    // la portada exista si la firma falló en ese momento.
    val stored = state.editorCoverStoragePath.isNotBlank()
    val picked = state.editorCoverLocalUri.isNotBlank()
    val busy = state.coverUploadState is UiAsync.Loading
    val pickedName = state.editorCoverFileName.ifBlank { state.editorCoverLocalUri.resourceFileName() }

    val stage = when {
        // Con fichero elegido, lo que está en curso es su subida; sin él, sólo
        // puede ser la retirada de la guardada.
        busy && picked -> ExamCoverStage.UPLOADING
        busy -> ExamCoverStage.REMOVING
        picked && stored -> ExamCoverStage.REPLACING
        picked && isNew -> ExamCoverStage.PENDING_ON_CREATE
        picked -> ExamCoverStage.PENDING_UPLOAD
        stored -> ExamCoverStage.STORED
        else -> ExamCoverStage.EMPTY
    }

    return ExamCoverPresentation(
        stage = stage,
        fileName = when (stage) {
            // La guardada no tiene nombre legible: su fichero es un uuid (K5-3).
            ExamCoverStage.EMPTY, ExamCoverStage.REMOVING, ExamCoverStage.STORED -> null
            else -> pickedName.ifBlank { null }
        },
        statusText = when (stage) {
            // Lo dice el marcador. Al lado quedan la acción y los formatos.
            ExamCoverStage.EMPTY, ExamCoverStage.STORED, ExamCoverStage.REMOVING -> null
            // La frase que faltaba. El botón de subir no aparece aquí porque no
            // hay a qué examen subirla todavía, y eso hay que decirlo en vez de
            // ofrecer una acción que devolvería un error de validación.
            ExamCoverStage.PENDING_ON_CREATE -> "Se subirá al crear el examen."
            ExamCoverStage.PENDING_UPLOAD -> "Seleccionada. Todavía no está en el servidor."
            ExamCoverStage.REPLACING -> "Sustituirá a la portada actual al subirla."
            ExamCoverStage.UPLOADING -> "Subiendo…"
        },
        placeholderText = when (stage) {
            ExamCoverStage.EMPTY -> "Sin portada"
            ExamCoverStage.STORED -> storedCoverLabel(state.editorCoverMimeType)
            ExamCoverStage.REMOVING -> "Quitando…"
            // Sólo se ve si la vista previa no ha podido decodificar el fichero.
            else -> "Imagen seleccionada"
        },
        previewPath = state.editorCoverLocalUri.takeIf { picked && it.isLocalPath() },
        canPick = !busy,
        // «Subir ahora» y no «Guardar portada»: el botón primario de la pantalla
        // se llama «Guardar cambios» y dos «guardar» que hacen cosas distintas en
        // el mismo formulario es una forma segura de que se pulse el que no era.
        canUploadNow = stage == ExamCoverStage.PENDING_UPLOAD || stage == ExamCoverStage.REPLACING,
        // En TODA selección pendiente, no sólo al sustituir (D3). Antes, con una
        // imagen elegida en un examen nuevo o sin portada, la única salida era
        // elegir otra: no había forma de volver a «sin portada».
        canCancelSelection = stage == ExamCoverStage.PENDING_ON_CREATE ||
            stage == ExamCoverStage.PENDING_UPLOAD ||
            stage == ExamCoverStage.REPLACING,
        canRemove = stage == ExamCoverStage.STORED,
        pickLabel = when (stage) {
            ExamCoverStage.EMPTY -> "Elegir imagen"
            ExamCoverStage.STORED -> "Cambiar imagen"
            else -> "Elegir otra"
        },
        errorMessage = (state.coverUploadState as? UiAsync.Error)?.error?.humanMessage(),
    )
}

/**
 * El marcador de una portada guardada: «Portada guardada (PNG)» (K5-3).
 *
 * Sustituye al nombre de fichero sacado de la ruta. Desde PW-8 el servidor guarda
 * la portada como `exam-covers/<examen_id>/<uuid>.<ext>` —el CHECK de M6 lo exige—
 * y la URL es una firma efímera, así que de ninguna de las dos sale un nombre que
 * oriente a nadie: sólo un uuid. Lo que sí es un dato es el formato, que viene de
 * `portada_mime_type`. Un tipo que no se reconoce no se adivina: sin paréntesis.
 */
internal fun storedCoverLabel(mimeType: String): String {
    val format = when (mimeType.trim().lowercase()) {
        "image/jpeg", "image/jpg" -> "JPG"
        "image/png" -> "PNG"
        "image/webp" -> "WEBP"
        else -> null
    }
    return if (format == null) "Portada guardada" else "Portada guardada ($format)"
}

/**
 * Si una ruta es LOCAL y por tanto se puede leer sin red.
 *
 * Una URL `http(s)` nunca lo es: la vista previa no descarga nada desde un
 * Composable.
 */
internal fun String.isLocalPath(): Boolean {
    val normalized = trim().lowercase()
    return normalized.isNotEmpty() && !normalized.startsWith("http://") && !normalized.startsWith("https://")
}

data class ExamCheck(
    val id: ExamCheckId,
    val label: String,
    val status: ExamCheckStatus,
    val detail: String? = null,
    /**
     * `true` si la comprobación está en UNKNOWN sólo porque algo se está cargando
     * AHORA —las preguntas se están contando, las capacidades se están
     * consultando— y se va a resolver sola en un momento (P2-6).
     *
     * No es un problema del examen, y por eso no hace aparecer «Antes de publicar»
     * por sí sola: esa tarjeta aparecía y desaparecía en cada apertura y en cada
     * guardado, ~190dp de rail saltando para decir «estoy contando». Un FALLO de
     * carga no es transitorio: ése sí hay que verlo, y no lleva esta marca.
     */
    val transient: Boolean = false,
)

/**
 * Si el editor tiene cambios que todavía no están en el servidor.
 *
 * Compara SÓLO campos que el editor carga desde el propio examen, así que un
 * examen recién seleccionado nunca sale sucio. `sortOrder` y `deletedAt` no
 * entran: los fabrica el mapper y no representan nada que el usuario haya
 * tocado.
 */
fun hasUnsavedExamChanges(state: ExamsState, exam: Exam?): Boolean {
    if (exam == null) return false
    return state.editorTitle.trim() != exam.title.trim() ||
        state.editorDescription.trim() != exam.description.trim() ||
        // Un código en blanco en un examen existente NO es un cambio (D8):
        // guardar así conserva el que ya tenía, así que no hay nada que perder.
        state.editorCode.trim().ifBlank { exam.code.trim() } != exam.code.trim() ||
        state.editorSpecialtyId != exam.specialtyId ||
        state.editorCommunityId != exam.communityId ||
        state.editorTypeExam != exam.typeExam ||
        state.editorTypeAccess != exam.typeAccess ||
        // «00» y «0» son el mismo precio (D8). Un campo vaciado sí cuenta: no es 0.
        state.editorPriceTickets.trim().toIntOrNull() != exam.priceTickets ||
        state.editorYear != exam.year?.toString().orEmpty() ||
        state.editorDurationMinutes != exam.durationMinutes?.toString().orEmpty() ||
        state.editorIsOfficial != exam.isOfficial ||
        // La RUTA y no la URL (K5-3): la URL es una firma que cambia en cada
        // lectura del servidor, y compararla anunciaba «cambios sin guardar» en
        // un examen que nadie había tocado.
        state.editorCoverStoragePath != exam.coverStoragePath.orEmpty() ||
        state.editorCoverLocalUri.isNotBlank()
}

/**
 * Lo que hay que mirar antes de dar por bueno este examen (D9).
 *
 * Cada línea corresponde a algo que el sistema comprueba de verdad:
 *
 *  - Título y especialidad: `saveExam` los exige para guardar.
 *  - Código: sólo si ya lo usa otro examen ([examCodeConflict]). `codigo_examen`
 *    es único y guardar así sólo puede fallar.
 *  - Duración: sólo si es 0 (P2-7). Aviso: un examen de 0 minutos no se puede
 *    hacer, pero la regla al guardar la pone el ViewModel.
 *  - Tipo: `saveExam` lo rechaza si la especialidad no lo permite, si no ha
 *    podido comprobarlo o si no lo reconoce. La línea sale de
 *    [examTypeCompatibility], la misma función que pinta su tarjeta.
 *  - Preguntas vinculadas: publicar las EXIGE (DEC-EX-01 = A, K-DEC01; el
 *    servidor responde 409 `EXAM_PUBLISH_REQUIRES_QUESTIONS`), así que en un
 *    examen guardado sin preguntas es bloqueo; guardar no las exige, y en un
 *    examen nuevo es aviso. **No usa la cifra provisional**: un «sin preguntas»
 *    se lee como un hecho, así que sólo lo da un recuento cargado.
 *
 * No hay ninguna línea de «configuración válida» genérica: no existe ninguna
 * regla que dependa del tipo más allá de si la especialidad lo permite, así que
 * un semáforo global sólo podría estar siempre en verde.
 */
fun examChecks(
    state: ExamsState,
    exam: Exam?,
    questionCount: Int?,
): List<ExamCheck> = buildList {
    add(
        ExamCheck(
            id = ExamCheckId.TITLE,
            label = "Título",
            status = if (state.editorTitle.isBlank()) ExamCheckStatus.BLOCKING else ExamCheckStatus.OK,
            detail = "Sin título no se puede guardar.".takeIf { state.editorTitle.isBlank() },
        ),
    )
    // Código repetido: justo después del título, que es el orden del bloque
    // Identificación. Sólo aparece si hay choque — un «código correcto» en verde
    // no le diría nada a nadie.
    examCodeConflict(state, exam)?.let { other ->
        add(
            ExamCheck(
                id = ExamCheckId.CODE,
                label = "Código",
                status = ExamCheckStatus.BLOCKING,
                detail = examCodeConflictMessage(other),
            ),
        )
    }
    val specialtyMissing = state.editorSpecialtyId.isBlank()
    add(
        ExamCheck(
            id = ExamCheckId.SPECIALTY,
            label = "Especialidad",
            status = if (specialtyMissing) ExamCheckStatus.BLOCKING else ExamCheckStatus.OK,
            // Con el catálogo caído, «Selecciona una especialidad» es una orden
            // imposible (P2-15): la frase dice qué falta para poder elegirla.
            detail = if (specialtyMissing) examSpecialtyMissingDetail(state.specialties) else null,
        ),
    )
    val compatibility = examTypeCompatibility(state)
    add(
        ExamCheck(
            id = ExamCheckId.TYPE_ALLOWED,
            label = compatibility.label,
            status = compatibility.status,
            detail = compatibility.detail,
            transient = compatibility.case == ExamTypeCompatibilityCase.CHECKING,
        ),
    )
    add(linkedQuestionsCheck(state, questionCount))
    if (examDurationIsZero(state)) {
        add(
            ExamCheck(
                id = ExamCheckId.DURATION,
                label = "Duración",
                status = ExamCheckStatus.WARNING,
                detail = EXAM_ZERO_DURATION_WARNING,
            ),
        )
    }
    // Precio inerte: dos columnas independientes que se contradicen. No bloquea
    // —guardar así es legal— pero es un dato que no hace nada, y quien lo escribió
    // cree que sí.
    val priceTickets = state.editorPriceTickets.toIntOrNull() ?: 0
    if (state.editorTypeAccess == "gratuito" && priceTickets > 0) {
        add(
            ExamCheck(
                id = ExamCheckId.ACCESS_PRICE,
                label = "Precio sin efecto",
                status = ExamCheckStatus.WARNING,
                detail = "El examen es gratuito, así que su precio en tickets no se cobra nunca.",
            ),
        )
    }
    if (exam != null && hasUnsavedExamChanges(state, exam)) {
        add(
            ExamCheck(
                id = ExamCheckId.UNSAVED_CHANGES,
                label = "Cambios sin guardar",
                status = ExamCheckStatus.WARNING,
                // La portada se separa porque **no viaja con «Guardar cambios»**:
                // tiene su propia ruta y su propio botón. Decir sólo «lo que se ve
                // en el editor no está en el servidor» dejaba creer que el botón
                // primario también la subía.
                detail = if (state.editorCoverLocalUri.isNotBlank()) {
                    "Hay una portada elegida que todavía no se ha subido."
                } else {
                    "Lo que se ve en el editor todavía no está en el servidor."
                },
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Etiquetas sueltas
// ---------------------------------------------------------------------------

/**
 * De dónde sale lo que se está enseñando en un campo de sólo lectura.
 *
 * Existe porque **un `String` no puede transportar «esto no es un valor»**, y
 * mientras lo intentó produjo el peor defecto de esta pantalla: con el catálogo
 * de comunidades sin cargar, un examen con comunidad real se etiquetaba
 * «Nacional» —un valor perfectamente plausible, indistinguible del verdadero— y
 * el desplegable ofrecía «Nacional» como única opción. Confirmar ahí anulaba un
 * `comunidad_id` real sin que nada avisara.
 *
 * Y no hacía falta que el backend estuviera caído: el repositorio siembra su
 * flujo con `emptyList()`, así que el estado pasaba por `Success(emptyList())` en
 * cada arranque. Bastaba con abrir la pantalla rápido.
 *
 * Con el origen explícito, la UI puede pintar un valor como valor y una ausencia
 * de información como lo que es, que es la única forma de que un fallo de lectura
 * no se convierta en un dato.
 */
enum class ExamFieldOrigin {
    /** Un dato real, del examen o del catálogo. Se pinta con el peso de un valor. */
    VALUE,

    /** El campo está legítimamente vacío. No es un fallo: nadie ha elegido todavía. */
    ABSENT,

    /** Hay un id guardado y el catálogo cargado NO lo contiene. El dato apunta a algo que ya no está. */
    UNRESOLVED,

    /** El catálogo no se ha podido leer. No se sabe, y no se puede fingir que sí. */
    UNAVAILABLE,

    /** Todavía se está cargando. */
    PENDING,
}

/** Lo que se pinta en un campo derivado, con su procedencia y su explicación. */
data class ExamFieldLabel(
    val text: String,
    val origin: ExamFieldOrigin,
    val detail: String? = null,
) {
    /**
     * Si esto es un dato del examen y no un estado del catálogo.
     *
     * Lo usa la UI para decidir el peso tipográfico: un «Cargando…» en SemiBold
     * junto a un nombre de especialidad en SemiBold hace que los dos se lean como
     * la misma clase de cosa, y no lo son.
     */
    val isValue: Boolean get() = origin == ExamFieldOrigin.VALUE
}

/**
 * Nombre de la comunidad, o «Nacional», o la verdad sobre por qué no se sabe.
 *
 * `comunidad_id` nunca se enseña: lo que orienta a quien administra es el
 * nombre, y un UUID en pantalla no es un dato, es una fuga de implementación.
 *
 * **El orden de las ramas es la corrección.** «Sin comunidad» se resuelve ANTES
 * de mirar el catálogo, porque no depende de él: un examen sin `comunidad_id` es
 * nacional aunque la tabla de comunidades no exista en este entorno. Sólo cuando
 * hay un id que resolver importa si el catálogo se pudo leer, y ahí un fallo se
 * dice, no se sustituye por el valor por defecto.
 */
fun communityLabel(communityId: String?, communities: UiAsync<List<Community>>): ExamFieldLabel {
    if (communityId == null) {
        return ExamFieldLabel("Nacional", ExamFieldOrigin.VALUE)
    }
    return when (communities) {
        is UiAsync.Success -> communities.value.firstOrNull { it.id == communityId }
            ?.let { ExamFieldLabel(it.name, ExamFieldOrigin.VALUE) }
            ?: unresolvedCommunity()
        // `Empty` es un catálogo leído y vacío: el id apunta a algo que no está.
        UiAsync.Empty -> unresolvedCommunity()
        // «No disponible», simétrico con la especialidad (D5): «Comunidad /
        // Comunidad no disponible» repetía la etiqueta dentro del valor y no
        // cabía en la celda del resumen. El porqué va en el detalle.
        is UiAsync.Error -> ExamFieldLabel(
            text = "No disponible",
            origin = ExamFieldOrigin.UNAVAILABLE,
            // Corto y simétrico con el de especialidades: vive en media columna de
            // la ficha, donde la versión larga se cortaba a media palabra.
            detail = "No se ha podido cargar el catálogo de comunidades.",
        )
        UiAsync.Loading, UiAsync.Idle -> ExamFieldLabel("Cargando…", ExamFieldOrigin.PENDING)
    }
}

private fun unresolvedCommunity() = ExamFieldLabel(
    text = "Comunidad no encontrada",
    origin = ExamFieldOrigin.UNRESOLVED,
    detail = "Este examen apunta a una comunidad que ya no está en el catálogo.",
)

/**
 * Nombre de la especialidad, de la primera fuente que lo sepa.
 *
 * `especialidad_id` es `NOT NULL` en la tabla: un examen GUARDADO siempre tiene
 * una. Lo que puede pasar es que el catálogo no diga cuál —no ha cargado, ha
 * fallado o no la contiene—, y por eso las fuentes van en este orden:
 *
 *  1. El catálogo de especialidades, si la resuelve.
 *  2. **El nombre que llega con el propio examen**, si el catálogo no la
 *     resuelve y el editor sigue en la especialidad guardada. `Exam.specialtyName`
 *     lo resuelve el repositorio al cargar la lista de exámenes, y es lo que ya
 *     pinta la tarjeta del catálogo. Sin esta rama, con el catálogo de
 *     especialidades caído la cabecera decía «No disponible» mientras la tarjeta
 *     seleccionada del MISMO examen decía «TCAE · 2026»: la pantalla se
 *     contradecía consigo misma. Si el repositorio no lo pudo resolver, llega en
 *     blanco y no cuenta.
 *  3. Sólo cuando ninguna de las dos tiene nombre se dice por qué: especialidad
 *     no encontrada (`UNRESOLVED`), catálogo caído (`UNAVAILABLE`) o cargando.
 *
 * «Sin especialidad» sólo es verdad en un borrador NUEVO al que nadie ha elegido
 * una: es el único caso en que la columna todavía no existe (D4, rama 1). En un
 * examen guardado sería falso por definición: un editor en blanco sobre un examen
 * guardado —el selector no lo permite, pero esta función no lo supone— apunta a
 * algo que no se puede resolver, y se dice así (D4, rama 3).
 *
 * [savedExam] no tiene valor por defecto a propósito: con uno, cualquier llamada
 * nueva que se olvidara de pasarlo volvería a producir la contradicción.
 */
fun specialtyLabel(
    specialtyId: String,
    specialties: UiAsync<List<Specialty>>,
    savedExam: Exam?,
): ExamFieldLabel {
    if (specialtyId.isBlank()) {
        return if (savedExam == null) {
            ExamFieldLabel("Sin especialidad", ExamFieldOrigin.ABSENT)
        } else {
            unresolvedSpecialty()
        }
    }
    (specialties as? UiAsync.Success)?.value
        ?.firstOrNull { it.id == specialtyId }
        ?.let { return ExamFieldLabel(it.name, ExamFieldOrigin.VALUE) }
    savedExam
        ?.takeIf { it.specialtyId == specialtyId }
        ?.specialtyName
        ?.takeIf { it.isNotBlank() }
        ?.let { return ExamFieldLabel(it, ExamFieldOrigin.VALUE) }
    return when (specialties) {
        is UiAsync.Success, UiAsync.Empty -> unresolvedSpecialty()
        is UiAsync.Error -> ExamFieldLabel(
            text = "No disponible",
            origin = ExamFieldOrigin.UNAVAILABLE,
            detail = "No se ha podido cargar el catálogo de especialidades.",
        )
        UiAsync.Loading, UiAsync.Idle -> ExamFieldLabel("Cargando…", ExamFieldOrigin.PENDING)
    }
}

private fun unresolvedSpecialty() = ExamFieldLabel(
    text = "Especialidad no encontrada",
    origin = ExamFieldOrigin.UNRESOLVED,
    detail = "Este examen apunta a una especialidad que ya no está en el catálogo.",
)

/**
 * «45 min», «0 min», o nada cuando el examen no tiene duración.
 *
 * `null` es «sin duración» y 0 es un valor (P2-7). Antes el 0 caía en la rama del
 * vacío y la pantalla decía «Sin duración» encima de un editor que iba a guardar
 * 0 minutos: `null != 0`. Ahora el 0 se enseña tal cual y la comprobación de
 * duración ([ExamCheckId.DURATION]) avisa de que no se puede cumplir.
 */
fun durationLabel(minutes: Int?): String? = minutes?.let { "$it min" }

/**
 * Instante en formato legible y honesto.
 *
 * Se recorta a minutos y se marca UTC porque es exactamente lo que el backend
 * devuelve: convertirlo a la zona del administrador sin que él lo haya pedido
 * daría una hora que no coincide con la del registro de auditoría.
 */
fun instantLabel(value: kotlin.time.Instant?): String? =
    value?.toString()?.take(16)?.replace('T', ' ')?.let { "$it UTC" }
