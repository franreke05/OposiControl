package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamType
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities

/**
 * Todo lo que la pantalla de Especialidades puede calcular SIN Compose.
 *
 * Etiquetas, recuentos, validaciones, reparto de columnas y elección de color
 * viven aquí por la misma razón que en Exámenes: un `when` de color escrito
 * dentro de un composable no lo puede medir ningún test de contraste, y una
 * regla de negocio escrita dentro de un composable sólo se comprueba mirando la
 * pantalla.
 *
 * **Nada de este fichero inventa producto.** Cada etiqueta que promete algo
 * remite a un comportamiento verificado; donde el comportamiento no existe, la
 * etiqueta lo declara en vez de callarlo.
 */

// ---------------------------------------------------------------------------
// Medidas
// ---------------------------------------------------------------------------

/**
 * Las medidas del workspace de Especialidades, en un solo sitio.
 *
 * Mismos valores que `ExamWorkspaceSizing`, y a propósito: las dos pantallas son
 * el mismo workspace de tres columnas, y que el catálogo mida 330dp en una y 300
 * en la otra sólo consigue que al cambiar de sección todo se mueva un poco.
 */
internal object SpecialtiesSizing {
    /** Catálogo en ventana ancha. Una fila necesita nombre en una línea, descripción y tres metadatos. */
    val catalogWide: Dp = 330.dp

    /** Catálogo en ventana normal. Cede 20dp al editor, que es donde se trabaja. */
    val catalogStandard: Dp = 310.dp

    /** Rail derecho. Columna de lectura: comprobaciones en líneas cortas, no una tabla. */
    val railWide: Dp = 300.dp

    /** Hueco entre columnas. El mismo que Exámenes y que `CrudSplitLayout`. */
    val columnGap: Dp = Spacing.lg

    /**
     * Suelo del editor. Si con las tres columnas el centro no llega aquí se
     * pliega el RAIL —que sólo resume— y nunca el editor, que es donde se
     * escribe.
     */
    val editorMinWidth: Dp = 700.dp

    /**
     * Alto máximo del catálogo cuando las columnas se apilan.
     *
     * El mismo 480 de Exámenes: la cabecera de la columna, el buscador y los
     * filtros de estado se comen ~180dp antes de la primera fila, y cada fila
     * mide ~104dp. Con 480 se ven dos filas completas y el principio de la
     * tercera, lo justo para que se note que la lista sigue.
     */
    val stackedCatalogMaxHeight: Dp = 480.dp

    /** Padding interior de un contenedor de sección de cristal. */
    val sectionPadding: PaddingValues = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)

    /** Contenedor de icono de la cabecera de una sección. */
    val sectionIconChip: Dp = Sizing.iconButton

    /** Hueco para la barra de desplazamiento: sin él el pulgar se pinta sobre el borde redondeado. */
    val scrollbarGutter: Dp = 10.dp

    /**
     * Ancho de editor por debajo del cual las tres tarjetas de tipo se apilan.
     *
     * 640: cada tarjeta necesita ~200dp para sostener su descripción en tres
     * líneas con el interruptor al lado, más los dos huecos de 16dp.
     */
    val typeCardsThreeUpMinWidth: Dp = 640.dp

    /**
     * Ancho de editor por debajo del cual la fila de pestañas pierde los iconos.
     *
     * Tres pestañas cortas —«Identidad · Tipos de examen · Impacto»— con icono
     * miden ~400dp; con los 40dp de padding de la sección piden ~440. El umbral
     * está por encima para que sólo el régimen apilado más estrecho llegue a
     * recortar, y lo que se sacrifica es el icono, nunca la palabra.
     */
    val tabIconsMinWidth: Dp = 520.dp
}

/** El reparto de la pantalla en columnas. */
internal data class SpecialtiesColumns(
    val catalogWidth: Dp,
    val editorWidth: Dp,
    val railWidth: Dp,
    val showRail: Boolean,
    val stacked: Boolean,
)

/**
 * El reparto de columnas, en dp exactos y sin `weight()` horizontal.
 *
 * La regla es la de Exámenes: **el editor manda**. Si con las tres columnas no
 * llega a [SpecialtiesSizing.editorMinWidth], la que desaparece es la del rail,
 * cuyo contenido —vista rápida, pendientes, información— es resumen y no
 * entrada de datos.
 *
 * Función pura y por eso comprobable: que el editor se quede sin sitio en una
 * ventana intermedia es aritmética, no algo que haya que descubrir mirando una
 * captura.
 */
internal fun specialtiesColumns(
    totalWidth: Dp,
    isWide: Boolean,
    supportsMasterDetail: Boolean,
): SpecialtiesColumns {
    if (!supportsMasterDetail) {
        return SpecialtiesColumns(
            catalogWidth = totalWidth,
            editorWidth = totalWidth,
            railWidth = 0.dp,
            showRail = false,
            stacked = true,
        )
    }
    val gap = SpecialtiesSizing.columnGap
    val catalogWidth = if (isWide) SpecialtiesSizing.catalogWide else SpecialtiesSizing.catalogStandard
    val railWidth = SpecialtiesSizing.railWide
    val withRail = totalWidth - catalogWidth - railWidth - gap * 2
    val showRail = isWide && withRail >= SpecialtiesSizing.editorMinWidth
    return SpecialtiesColumns(
        catalogWidth = catalogWidth,
        editorWidth = if (showRail) withRail else totalWidth - catalogWidth - gap,
        railWidth = railWidth,
        showRail = showRail,
        stacked = false,
    )
}

/** Una celda de una fila repartida a partes iguales, en px: de [start] (incl.) a [end] (excl.). */
internal data class SpecialtyGridCell(val start: Int, val end: Int) {
    val width: Int get() = end - start
}

/**
 * Dónde empieza y dónde acaba cada celda de una fila de [count] elementos
 * repartida a partes iguales sobre [totalWidth] px, con [gap] px de hueco.
 *
 * Existe para que las filas de esta pantalla —las tres tarjetas de tipo, los
 * pares de la vista rápida— se repartan **sin `weight()` horizontal**: los
 * bordes se calculan a partir del índice de columna y no acumulando anchos, así
 * que dos filas distintas comparten exactamente el píxel de cada borde común y
 * no hay deriva de redondeo. Es el mismo criterio que `examFieldGridCells`.
 */
internal fun specialtyRowCells(totalWidth: Int, gap: Int, count: Int): List<SpecialtyGridCell> {
    require(count > 0) { "Una fila necesita al menos un elemento" }
    val column = (totalWidth - gap * (count - 1)).toFloat() / count
    return List(count) { index ->
        val start = (index * (column + gap)).toInt()
        val end = ((index + 1) * (column + gap) - gap).toInt()
        SpecialtyGridCell(start = start, end = end.coerceAtLeast(start))
    }
}

// ---------------------------------------------------------------------------
// Vocabulario
// ---------------------------------------------------------------------------

/**
 * Los tres tipos de examen, en el vocabulario canónico de producto.
 *
 * **Singular masculino y la palabra «tipo»: Ráfaga · Razonado · Inmersivo.** El
 * sustantivo que gobierna es *examen* y el dato vivo es `examenes.tipo_examen`.
 * El plural que usaba esta pantalla («Ráfagas», «Razonados») describía los
 * nombres de columna de `capacidades_especialidad`, no el producto. El femenino
 * (razonada, inmersiva) queda reservado a las PREGUNTAS y no se toca aquí.
 *
 * **Las etiquetas y descripciones están REPLICADAS de
 * `features/exams/.../ExamWorkspacePresentation.kt` (`ExamTypeOption`), no
 * importadas.** Ese enum es `internal` de `features:exams` y este módulo no
 * depende de él —ni debe: una vertical de taxonomía que dependiera del editor de
 * exámenes invertiría la relación—. La consecuencia hay que decirla: si allí se
 * reescribe una descripción, aquí no cambia sola. El sitio donde dejarían de
 * estar duplicadas es `domain`, junto a [ExamType], y eso queda fuera del
 * alcance de esta pantalla.
 *
 * [canonical] ata cada entrada al enum de dominio, que es quien sabe leer el
 * alias legacy `guiado`. Así el desglose por tipo no pierde un examen por no
 * reconocer su cadena, que es justo el defecto que `observeAllExams` existe para
 * evitar.
 */
internal enum class SpecialtyExamType(
    val canonical: ExamType,
    val toggle: SpecialtyCapabilityToggle,
    val label: String,
    val description: String,
    val icon: AppIcon,
    val role: SemanticRole,
) {
    RAFAGA(
        canonical = ExamType.RAFAGA,
        toggle = SpecialtyCapabilityToggle.RAFAGAS,
        label = "Ráfaga",
        description = "Preguntas tipo test encadenadas. Único tipo que puede servirse gratis sin sesión.",
        icon = AppIcon.QUESTIONS,
        role = SemanticRole.INFO,
    ),
    RAZONADO(
        canonical = ExamType.RAZONADO,
        toggle = SpecialtyCapabilityToggle.RAZONADOS,
        label = "Razonado",
        description = "Cada pregunta lleva un razonamiento que explica la respuesta correcta.",
        icon = AppIcon.NEWS_TYPES,
        role = SemanticRole.INSIGHT,
    ),
    INMERSIVO(
        canonical = ExamType.INMERSIVO,
        toggle = SpecialtyCapabilityToggle.INMERSIVOS,
        label = "Inmersivo",
        description = "Preguntas que pueden apoyarse en recursos (imagen, audio, vídeo, documento o texto).",
        icon = AppIcon.RESOURCES,
        role = SemanticRole.COMMERCE,
    ),
    ;

    /** Si este tipo está abierto en un borrador de capacidades. */
    fun isEnabled(capabilities: SpecialtyCapabilities): Boolean = when (this) {
        RAFAGA -> capabilities.permiteRafagas
        RAZONADO -> capabilities.permiteRazonados
        INMERSIVO -> capabilities.permiteInmersivos
    }
}

/** Hasta dónde llega de verdad un ajuste que se puede guardar. */
internal enum class SpecialtyDeclaration(val label: String, val icon: AppIcon) {
    /** Se guarda y algo lo lee, pero nada lo aplica. */
    PARTIAL("Parcial", AppIcon.WARNING),

    /** Se guarda y no lo lee nadie. */
    NOT_APPLIED("No aplicado", AppIcon.BLOCKED),
}

/**
 * Las dos capacidades que NO son un tipo de examen, cada una con su estado
 * declarado.
 *
 * No se esconden —esconderlas perdería función administrable— pero tampoco se
 * pintan como si hicieran algo. Lo que dice cada una está verificado contra el
 * repositorio entero:
 *
 *  - `permiteDemos` se guarda y se lee en UN sitio, como chip informativo de
 *    sólo lectura del editor de exámenes. Ninguna ruta del backend lo aplica.
 *  - `permiteOposimarket` no tiene NI UN lector en todo el repositorio.
 */
internal enum class SpecialtyCommercialCapability(
    val toggle: SpecialtyCapabilityToggle,
    val label: String,
    val description: String,
    val declaration: SpecialtyDeclaration,
    val declarationDetail: String,
    val icon: AppIcon,
) {
    DEMOS(
        toggle = SpecialtyCapabilityToggle.DEMOS,
        label = "Demos",
        description = "Probar contenido de la especialidad sin tenerlo comprado.",
        declaration = SpecialtyDeclaration.PARTIAL,
        declarationDetail = "Se guarda y se enseña como dato en el editor de exámenes. " +
            "Ninguna ruta del backend lo aplica.",
        icon = AppIcon.VIEW,
    ),
    OPOSIMARKET(
        toggle = SpecialtyCapabilityToggle.OPOSIMARKET,
        label = "OposiMarket",
        description = "Acceso a packs, potenciadores y protectores desde esta especialidad.",
        declaration = SpecialtyDeclaration.NOT_APPLIED,
        declarationDetail = "Se guarda, y nada lo lee: ni el backend ni la app consultan este campo.",
        icon = AppIcon.MARKET,
    ),
}

/** Los tres filtros de estado del catálogo. Son locales: no hay parámetro de consulta. */
enum class SpecialtyStatusFilter(val id: String, val label: String) {
    ALL("all", "Todas"),
    ACTIVE("active", "Activas"),
    ARCHIVED("archived", "Archivadas"),
    ;

    companion object {
        fun fromId(id: String): SpecialtyStatusFilter = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/**
 * Activa o archivada, y nada más.
 *
 * `activa` es un boolean en `public.especialidades`: no hay tercer estado, y
 * «archivada» es el nombre honesto de `activa = false` porque es exactamente lo
 * que hace el botón de la zona de peligro.
 */
internal fun specialtyStatusLabel(isActive: Boolean): String = if (isActive) "Activa" else "Archivada"

/** El tono del chip de estado. Dos estados, dos tonos, sin tercero por descarte. */
internal fun specialtyStatusTone(isActive: Boolean): StatusTone =
    if (isActive) StatusTone.POSITIVE else StatusTone.NEGATIVE

/**
 * La salvedad del recuento de exámenes, escrita una sola vez.
 *
 * El recuento agrupa TODOS los exámenes de la especialidad, borradores y
 * archivados incluidos. Etiquetar la cifra «Exámenes» a secas miente por
 * omisión: se lee como exámenes servidos.
 */
internal const val SPECIALTY_EXAM_COUNT_CAVEAT: String = "Incluye borradores y archivados."

/**
 * La misma salvedad, en una línea corta.
 *
 * `SummaryCard` pinta su texto de apoyo con `maxLines = 1`, y en una tarjeta de
 * ~200dp la frase larga se cortaba en «Incluye borradores y archiva…» —medido en
 * la captura de 1920—. No se recorta la verdad para que quepa: la frase completa
 * va en la descripción de la sección, que está justo encima, y la tarjeta dice
 * lo mismo en corto.
 */
internal const val SPECIALTY_EXAM_COUNT_CAVEAT_SHORT: String = "Todos los estados"

/** «4 exámenes» / «1 examen» / «Sin exámenes». Nunca un cero suelto. */
internal fun specialtyExamCountLabel(count: Int): String = when (count) {
    0 -> "Sin exámenes"
    1 -> "1 examen"
    else -> "$count exámenes"
}

/** «2 de 3» tipos abiertos. Los tres tipos son el universo; demos y market no cuentan. */
internal fun specialtyOpenTypesLabel(capabilities: SpecialtyCapabilities?): String =
    if (capabilities == null) {
        "Sin cargar"
    } else {
        "${capabilities.enabledExamModes.size} de ${SpecialtyExamType.entries.size}"
    }

// ---------------------------------------------------------------------------
// Catálogo
// ---------------------------------------------------------------------------

/**
 * El recorte del catálogo, en cliente y sobre la lista ya descargada.
 *
 * `GET /especialidades` no acepta ni búsqueda ni filtro de estado, así que esto
 * no es una consulta: es un recorte de lo que ya hay en memoria. Se ordena por
 * `posicion` y, a igualdad, por nombre, que es lo que hace que la columna
 * «Orden» signifique algo al leerla.
 */
internal fun applySpecialtyFilters(
    items: List<Specialty>,
    query: String,
    filter: SpecialtyStatusFilter,
): List<Specialty> {
    val needle = query.trim().lowercase()
    return items
        .filter { item ->
            val matchesQuery = needle.isBlank() ||
                item.name.lowercase().contains(needle) ||
                item.description.lowercase().contains(needle)
            val matchesStatus = when (filter) {
                SpecialtyStatusFilter.ALL -> true
                SpecialtyStatusFilter.ACTIVE -> item.isActive
                SpecialtyStatusFilter.ARCHIVED -> !item.isActive
            }
            matchesQuery && matchesStatus
        }
        .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
}

/** Si el catálogo está recortado por algo que haya puesto quien lo mira. */
internal fun specialtyCatalogHasFilters(query: String, filter: SpecialtyStatusFilter): Boolean =
    query.isNotBlank() || filter != SpecialtyStatusFilter.ALL

/**
 * El slug que el backend genera a partir del nombre.
 *
 * Réplica de `toSlug()` de `AdminV1Routes`, **defecto incluido**: no normaliza
 * acentos, así que «Técnico» se convierte en «t-cnico». Se replica tal cual y no
 * arreglado, porque su único uso aquí es enseñar el slug que el servidor va a
 * escribir de verdad al renombrar; un slug «bien hecho» en pantalla sería un
 * aviso falso.
 */
internal fun specialtySlugOf(name: String): String {
    // Literalmente `lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')`: sólo
    // sobreviven a-z y 0-9, así que una letra acentuada se convierte en guion.
    // `isLetterOrDigit()` NO sirve aquí —conservaría la «é»— y produciría un slug
    // distinto del que el servidor escribe.
    val collapsed = name.lowercase()
        .map { character -> if (character in 'a'..'z' || character in '0'..'9') character else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
    return collapsed.ifBlank { "item" }
}

/**
 * La descripción que se pinta en una fila del catálogo, o `null` si no hay.
 *
 * Una descripción en blanco se rellena con el SLUG al leer del backend
 * (`KtorAdminV1ContentRepositoryImpl`), así que una fila puede traer
 * «tcae-2026» como si fuera una descripción que escribió alguien. Arreglarlo
 * exige tocar `data` y queda fuera de esta pantalla; lo que sí se puede es no
 * empeorarlo: si la descripción es exactamente el slug del nombre, se enseña la
 * ausencia en vez del slug.
 */
internal fun specialtyRowDescription(specialty: Specialty): String? {
    val description = specialty.description.trim()
    if (description.isEmpty()) return null
    return if (description.equals(specialtySlugOf(specialty.name), ignoreCase = true)) null else description
}

// ---------------------------------------------------------------------------
// Desglose de exámenes por tipo
// ---------------------------------------------------------------------------

/** Cuántos exámenes de un tipo tiene la especialidad abierta. */
internal data class SpecialtyTypeCount(val type: SpecialtyExamType, val count: Int)

/**
 * El desglose por tipo de los exámenes de una especialidad.
 *
 * [unknown] son los exámenes cuyo `tipo_examen` no corresponde a ninguno de los
 * tres tipos canónicos ni al alias legacy. No se reparten entre los demás ni se
 * descartan: si existen hay que verlos, porque un examen cuyo tipo no se
 * reconoce no lo cubre ningún interruptor de esta pantalla.
 */
internal data class SpecialtyExamBreakdown(
    val total: Int,
    val byType: List<SpecialtyTypeCount>,
    val unknown: Int,
) {
    fun countOf(type: SpecialtyExamType): Int = byType.firstOrNull { it.type == type }?.count ?: 0
}

/**
 * El desglose, calculado en cliente sobre la lista completa de exámenes.
 *
 * Es el mismo cálculo que hace `refreshSpecialties` para `linkedExams` —agrupar
 * todos los exámenes por especialidad— pero abierto por tipo, que es el dato que
 * hace peligroso apagar un interruptor. No hay endpoint que lo devuelva.
 */
internal fun specialtyExamBreakdown(exams: List<Exam>, specialtyId: String?): SpecialtyExamBreakdown {
    if (specialtyId.isNullOrBlank()) {
        return SpecialtyExamBreakdown(
            total = 0,
            byType = SpecialtyExamType.entries.map { SpecialtyTypeCount(it, 0) },
            unknown = 0,
        )
    }
    val own = exams.filter { it.specialtyId == specialtyId }
    val byCanonical = own.groupingBy { ExamType.fromExternal(it.typeExam) }.eachCount()
    return SpecialtyExamBreakdown(
        total = own.size,
        byType = SpecialtyExamType.entries.map { type -> SpecialtyTypeCount(type, byCanonical[type.canonical] ?: 0) },
        unknown = byCanonical[null] ?: 0,
    )
}

/**
 * Los tipos que tienen exámenes y el interruptor apagado.
 *
 * Es lo único que hace peligroso apagar un tipo: el guardado del editor de
 * Exámenes deja de aceptarlo, así que esos exámenes se quedan sin poder
 * guardarse en su propio tipo.
 */
internal fun specialtyTypesOffWithExams(
    breakdown: SpecialtyExamBreakdown,
    draft: SpecialtyCapabilities?,
): List<SpecialtyTypeCount> {
    if (draft == null) return emptyList()
    return breakdown.byType.filter { it.count > 0 && !it.type.isEnabled(draft) }
}

// ---------------------------------------------------------------------------
// Validación y barra de guardado
// ---------------------------------------------------------------------------

/** Las escrituras que esta pantalla puede lanzar. DOS endpoints, nunca uno disfrazado. */
internal enum class SpecialtyWrite(val label: String, val endpoint: String) {
    CREATE("Alta de la especialidad", "POST /especialidades"),
    IDENTITY("Identidad", "PATCH /especialidades/{id}"),
    CAPABILITIES("Tipos de examen", "PUT /especialidades/{id}/capacidades"),
}

/**
 * Qué va a escribir el botón de guardar, en orden.
 *
 * Identidad y capacidades son dos tablas, dos endpoints y dos resultados
 * posibles. La barra lo dice; fingir una sola operación es lo que hace que, si
 * la segunda falla, nadie sepa que la primera sí se guardó.
 */
internal fun specialtyPendingWrites(state: SpecialtiesState): List<SpecialtyWrite> = buildList {
    when {
        state.creatingNew -> add(SpecialtyWrite.CREATE)
        state.selectedId != null && state.identityDirty -> add(SpecialtyWrite.IDENTITY)
    }
    if (state.selectedId != null && state.capabilitiesDirty) add(SpecialtyWrite.CAPABILITIES)
}

/** La línea que enumera las escrituras pendientes, o `null` si no hay ninguna. */
internal fun specialtyWritesLabel(writes: List<SpecialtyWrite>): String? = when (writes.size) {
    0 -> null
    1 -> "Una escritura: ${writes.single().label} · ${writes.single().endpoint}"
    else -> "Dos escrituras independientes: " +
        writes.joinToString(" · ") { it.label } +
        ". Si la segunda falla, la primera ya estará guardada."
}

/** A cuál de las dos escrituras pertenece un bloqueo. */
internal enum class SpecialtyBlockerTarget { IDENTITY, CAPABILITIES }

/**
 * Un bloqueo del guardado, con la escritura a la que pertenece.
 *
 * El objetivo no es decoración: con dos endpoints, un error hay que enseñarlo
 * junto a los campos que lo provocan. Un «falta el mensaje de limitación»
 * escrito bajo el nombre manda a buscarlo donde no está.
 */
internal data class SpecialtySaveBlocker(val message: String, val target: SpecialtyBlockerTarget)

/**
 * Lo que impide guardar, en el orden en que hay que arreglarlo.
 *
 * Los tres son reglas reales, no preferencias: el nombre es lo único obligatorio
 * de `POST /especialidades`, `posicion` es un entero en la tabla, y el backend
 * devuelve **422 SPECIALTY_NO_MODES_WITHOUT_MESSAGE** si se apagan los tres
 * tipos sin mensaje de limitación. Avisar aquí, antes de pulsar, es la
 * diferencia entre corregir un campo y comerse un error del servidor.
 */
internal fun specialtySaveBlockers(state: SpecialtiesState): List<SpecialtySaveBlocker> = buildList {
    if (state.selectedId == null && !state.creatingNew) return@buildList
    if (state.editorName.isBlank()) {
        add(SpecialtySaveBlocker("El nombre es obligatorio.", SpecialtyBlockerTarget.IDENTITY))
    }
    if (state.editorSortOrder.trim().toIntOrNull() == null) {
        add(SpecialtySaveBlocker("El orden tiene que ser un número entero.", SpecialtyBlockerTarget.IDENTITY))
    }
    val draft = state.capabilitiesDraft
    if (draft != null && draft.hasNoExamModes && draft.limitationMessage.isNullOrBlank()) {
        add(
            SpecialtySaveBlocker(
                message = "Sin ningún tipo de examen abierto, el mensaje de limitación es obligatorio: " +
                    "el servidor lo rechaza con un 422.",
                target = SpecialtyBlockerTarget.CAPABILITIES,
            ),
        )
    }
}

/** El tono de la barra de guardado. */
internal enum class SpecialtySaveBarTone { INFO, WARNING, DANGER, SUCCESS }

/** Lo que dice la barra y con qué tono. */
internal data class SpecialtySaveBarMessage(val text: String, val tone: SpecialtySaveBarTone)

internal data class SpecialtySaveBarStatus(
    val message: SpecialtySaveBarMessage?,
    val detail: String?,
    val prominent: Boolean,
    val canDiscard: Boolean,
    val canSave: Boolean,
    val saving: Boolean,
    val primaryLabel: String,
    val writes: List<SpecialtyWrite>,
)

/**
 * Qué dice la barra de guardado y qué ofrece.
 *
 * Cinco casos, y ninguno se solapa:
 *
 *  - **Sin especialidad abierta**: una línea que dice qué hacer. Nada que guardar.
 *  - **Guardando**: el primario en curso, y el detalle sigue enumerando las
 *    escrituras para que se vea cuántas van.
 *  - **Algo bloquea**: tono de peligro, primario deshabilitado y el primer
 *    bloqueo como mensaje. La barra se vuelve prominente igual, porque hay algo
 *    sin guardar que además no se puede guardar.
 *  - **Cambios sin guardar**: prominente, «Descartar» disponible y el detalle
 *    dice a cuántos endpoints va.
 *  - **Sin cambios**: la barra NO se oculta. Mide y ocupa su hueco en reposo
 *    —con relleno y borde transparentes— porque ocultarla cambiaría el alto del
 *    formulario en la primera tecla.
 */
internal fun specialtySaveBarStatus(state: SpecialtiesState): SpecialtySaveBarStatus {
    val writes = specialtyPendingWrites(state)
    val detail = specialtyWritesLabel(writes)
    if (state.selectedId == null && !state.creatingNew) {
        return SpecialtySaveBarStatus(
            message = SpecialtySaveBarMessage(
                text = "Abre una especialidad del catálogo o crea una nueva.",
                tone = SpecialtySaveBarTone.INFO,
            ),
            detail = null,
            prominent = false,
            canDiscard = false,
            canSave = false,
            saving = false,
            primaryLabel = "Guardar",
            writes = emptyList(),
        )
    }
    val primaryLabel = if (state.creatingNew) "Crear especialidad" else "Guardar"
    val saving = state.actionState is UiAsync.Loading || state.capabilitiesActionState is UiAsync.Loading
    if (saving) {
        return SpecialtySaveBarStatus(
            message = SpecialtySaveBarMessage("Guardando…", SpecialtySaveBarTone.INFO),
            detail = detail,
            prominent = true,
            canDiscard = false,
            canSave = false,
            saving = true,
            primaryLabel = primaryLabel,
            writes = writes,
        )
    }
    val blockers = specialtySaveBlockers(state)
    if (blockers.isNotEmpty()) {
        return SpecialtySaveBarStatus(
            message = SpecialtySaveBarMessage(blockers.first().message, SpecialtySaveBarTone.DANGER),
            detail = detail,
            prominent = true,
            canDiscard = state.anyDirty,
            canSave = false,
            saving = false,
            primaryLabel = primaryLabel,
            writes = writes,
        )
    }
    if (state.creatingNew) {
        return SpecialtySaveBarStatus(
            message = SpecialtySaveBarMessage(
                text = "Especialidad nueva: el servidor genera su código y su slug al crearla.",
                tone = SpecialtySaveBarTone.INFO,
            ),
            detail = detail,
            prominent = true,
            canDiscard = true,
            canSave = true,
            saving = false,
            primaryLabel = primaryLabel,
            writes = writes,
        )
    }
    if (state.anyDirty) {
        return SpecialtySaveBarStatus(
            message = SpecialtySaveBarMessage("Cambios sin guardar.", SpecialtySaveBarTone.WARNING),
            detail = detail,
            prominent = true,
            canDiscard = true,
            canSave = true,
            saving = false,
            primaryLabel = primaryLabel,
            writes = writes,
        )
    }
    return SpecialtySaveBarStatus(
        message = SpecialtySaveBarMessage("Sin cambios pendientes.", SpecialtySaveBarTone.SUCCESS),
        detail = null,
        prominent = false,
        canDiscard = false,
        canSave = false,
        saving = false,
        primaryLabel = primaryLabel,
        writes = emptyList(),
    )
}

// ---------------------------------------------------------------------------
// «Antes de guardar»
// ---------------------------------------------------------------------------

internal enum class SpecialtyCheckStatus { BLOCKING, WARNING, PENDING }

internal enum class SpecialtyCheckId {
    NAME_REQUIRED,
    SORT_ORDER_NUMERIC,
    LIMITATION_REQUIRED,
    SLUG_REWRITE,
    TYPE_OFF_WITH_EXAMS,
    UNKNOWN_TYPE_EXAMS,
    CAPABILITIES_UNCONFIGURED,
    ARCHIVED_EXAMS_STILL_SERVED,
    UNSAVED_WRITES,
}

internal data class SpecialtyCheck(
    val id: SpecialtyCheckId,
    val label: String,
    val detail: String,
    val status: SpecialtyCheckStatus,
)

/** Título del bloque del rail. Sólo se pinta si la lista no está vacía. */
internal const val SPECIALTY_PENDING_TITLE: String = "Antes de guardar"

/**
 * Lo que hay pendiente, y sólo lo que hay pendiente.
 *
 * Sin filas verdes. Un bloque permanente lleno de marcas de correcto entrena a
 * no mirarlo, y entonces el día que aparece un aviso tampoco se mira.
 */
internal fun specialtyPendingChecks(
    state: SpecialtiesState,
    breakdown: SpecialtyExamBreakdown?,
): List<SpecialtyCheck> = buildList {
    if (state.selectedId == null && !state.creatingNew) return@buildList
    if (state.editorName.isBlank()) {
        add(
            SpecialtyCheck(
                id = SpecialtyCheckId.NAME_REQUIRED,
                label = "Falta el nombre",
                detail = "Es el único campo obligatorio del alta.",
                status = SpecialtyCheckStatus.BLOCKING,
            ),
        )
    }
    if (state.editorSortOrder.trim().toIntOrNull() == null) {
        add(
            SpecialtyCheck(
                id = SpecialtyCheckId.SORT_ORDER_NUMERIC,
                label = "El orden no es un número",
                detail = "«${state.editorSortOrder}» no se puede escribir en `posicion`, que es un entero.",
                status = SpecialtyCheckStatus.BLOCKING,
            ),
        )
    }
    val draft = state.capabilitiesDraft
    if (draft != null && draft.hasNoExamModes && draft.limitationMessage.isNullOrBlank()) {
        add(
            SpecialtyCheck(
                id = SpecialtyCheckId.LIMITATION_REQUIRED,
                label = "Falta el mensaje de limitación",
                detail = "Con los tres tipos apagados el servidor responde 422 si no se explica por qué.",
                status = SpecialtyCheckStatus.BLOCKING,
            ),
        )
    }
    val loaded = state.selectedSpecialty()
    if (loaded != null && state.editorName.isNotBlank() && state.editorName.trim() != loaded.name) {
        add(
            SpecialtyCheck(
                id = SpecialtyCheckId.SLUG_REWRITE,
                label = "Cambiar el nombre reescribe el slug",
                detail = "El slug es el identificador público que consume la app: pasará de " +
                    "«${specialtySlugOf(loaded.name)}» a «${specialtySlugOf(state.editorName)}».",
                status = SpecialtyCheckStatus.WARNING,
            ),
        )
    }
    if (breakdown != null) {
        specialtyTypesOffWithExams(breakdown, draft).forEach { off ->
            add(
                SpecialtyCheck(
                    id = SpecialtyCheckId.TYPE_OFF_WITH_EXAMS,
                    label = "${off.type.label}: apagado con ${specialtyExamCountLabel(off.count).lowercase()}",
                    detail = "El editor de Exámenes dejará de aceptar ese tipo al guardar. " +
                        "Los exámenes ya publicados se siguen sirviendo.",
                    status = SpecialtyCheckStatus.WARNING,
                ),
            )
        }
        if (breakdown.unknown > 0) {
            add(
                SpecialtyCheck(
                    id = SpecialtyCheckId.UNKNOWN_TYPE_EXAMS,
                    label = "${breakdown.unknown} sin tipo reconocido",
                    detail = "Su `tipo_examen` no es ráfaga, razonado ni inmersivo, " +
                        "así que ningún interruptor de aquí los cubre.",
                    status = SpecialtyCheckStatus.WARNING,
                ),
            )
        }
    }
    if (draft != null && !draft.configured) {
        add(
            SpecialtyCheck(
                id = SpecialtyCheckId.CAPABILITIES_UNCONFIGURED,
                label = "Tipos sin configurar",
                detail = "Esta especialidad todavía no tiene fila propia: lo que se ve son los valores " +
                    "por defecto. Guardar los fija.",
                status = SpecialtyCheckStatus.PENDING,
            ),
        )
    }
    if (!state.editorIsActive) {
        add(
            SpecialtyCheck(
                id = SpecialtyCheckId.ARCHIVED_EXAMS_STILL_SERVED,
                label = "Archivada, con exámenes servidos",
                detail = "`activa = false` la saca del catálogo público, pero sus exámenes publicados " +
                    "se siguen sirviendo.",
                status = SpecialtyCheckStatus.WARNING,
            ),
        )
    }
    val writes = specialtyPendingWrites(state)
    if (writes.size > 1) {
        add(
            SpecialtyCheck(
                id = SpecialtyCheckId.UNSAVED_WRITES,
                label = "Dos escrituras pendientes",
                detail = writes.joinToString(" · ") { "${it.label} (${it.endpoint})" },
                status = SpecialtyCheckStatus.PENDING,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Vista rápida del rail
// ---------------------------------------------------------------------------

/** De dónde sale un valor del rail, para no pintar una ausencia como un dato. */
internal enum class SpecialtyFactOrigin { VALUE, ABSENT, PENDING, UNRESOLVED }

internal data class SpecialtyFact(
    val label: String,
    val value: String,
    val origin: SpecialtyFactOrigin = SpecialtyFactOrigin.VALUE,
    val detail: String? = null,
)

/**
 * Los cuatro datos de la vista rápida.
 *
 * Sin fechas: el DTO de especialidad no las lleva y el mapeo las FABRICA con
 * `nowInstant()`, así que cualquier «creada el …» de esta pantalla sería la hora
 * de la última lectura disfrazada de metadato.
 */
internal fun specialtyQuickFacts(
    state: SpecialtiesState,
    breakdown: SpecialtyExamBreakdown?,
): List<SpecialtyFact> {
    // Sin nada abierto, los campos del editor son sus VALORES POR DEFECTO, no
    // datos: el rail decía «Estado: Activa · Orden: 0» de una especialidad que no
    // existía. Con el catálogo caído eso es exactamente lo peor que puede hacer,
    // porque parece información que ha llegado.
    if (state.selectedId == null && !state.creatingNew) {
        return listOf("Estado", "Orden", "Exámenes", "Tipos abiertos").map { label ->
            SpecialtyFact(label = label, value = "Sin especialidad", origin = SpecialtyFactOrigin.ABSENT)
        }
    }
    val examFact = when {
        state.exams is UiAsync.Loading || state.exams is UiAsync.Idle -> SpecialtyFact(
            label = "Exámenes",
            value = "Contando…",
            origin = SpecialtyFactOrigin.PENDING,
            detail = "El desglose se calcula sobre la lista completa de exámenes.",
        )
        state.exams is UiAsync.Error -> SpecialtyFact(
            label = "Exámenes",
            value = "No disponible",
            origin = SpecialtyFactOrigin.UNRESOLVED,
            detail = "No se ha podido leer la lista de exámenes.",
        )
        breakdown == null -> SpecialtyFact(
            label = "Exámenes",
            value = "Sin especialidad",
            origin = SpecialtyFactOrigin.ABSENT,
        )
        else -> SpecialtyFact(
            label = "Exámenes",
            value = specialtyExamCountLabel(breakdown.total),
            origin = if (breakdown.total == 0) SpecialtyFactOrigin.ABSENT else SpecialtyFactOrigin.VALUE,
            detail = SPECIALTY_EXAM_COUNT_CAVEAT,
        )
    }
    val typesFact = when {
        state.capabilities is UiAsync.Loading || state.capabilities is UiAsync.Idle -> SpecialtyFact(
            label = "Tipos abiertos",
            value = "Cargando…",
            origin = SpecialtyFactOrigin.PENDING,
        )
        state.capabilities is UiAsync.Error -> SpecialtyFact(
            label = "Tipos abiertos",
            value = "No disponible",
            origin = SpecialtyFactOrigin.UNRESOLVED,
            detail = "No se han podido leer las capacidades de esta especialidad.",
        )
        else -> SpecialtyFact(
            label = "Tipos abiertos",
            value = specialtyOpenTypesLabel(state.capabilitiesDraft),
            origin = SpecialtyFactOrigin.VALUE,
        )
    }
    return listOf(
        SpecialtyFact(label = "Estado", value = specialtyStatusLabel(state.editorIsActive)),
        SpecialtyFact(
            label = "Orden",
            value = state.editorSortOrder.trim().ifBlank { "Sin orden" },
            origin = if (state.editorSortOrder.trim().toIntOrNull() == null) {
                SpecialtyFactOrigin.UNRESOLVED
            } else {
                SpecialtyFactOrigin.VALUE
            },
        ),
        examFact,
        typesFact,
    )
}

/**
 * Lo que esta pantalla NO decide, dicho una vez.
 *
 * Cada línea existe porque su ausencia se leería como una promesa: que apagar
 * una especialidad apaga sus exámenes, que hay fechas, que se puede borrar.
 */
internal val specialtyInformationLines: List<String> = listOf(
    "Archivar escribe `activa = false`: la fila se queda en esta lista y no hay borrado.",
    "Una especialidad archivada desaparece del catálogo público; sus exámenes publicados se siguen sirviendo.",
    "El código y el slug los genera el servidor. El slug se reescribe cada vez que cambia el nombre.",
    "Los tipos de examen restringen el guardado del editor de Exámenes, no las rutas de la app.",
    "Esta pantalla no lleva fechas: el contrato de especialidad no las devuelve.",
)

// ---------------------------------------------------------------------------
// Colores
//
// Funciones `internal` PURAS, no un `when` dentro de un composable: así
// `SpecialtiesContrastTest` mide exactamente el color que se pinta, con la
// paleta viva de los dos temas.
// ---------------------------------------------------------------------------

/** Los dos colores de texto que esta pantalla pinta DIRECTAMENTE sobre cristal. */
internal data class SpecialtyGlassText(val primary: Color, val secondary: Color)

/**
 * El texto que va sobre el cristal de una sección, sin superficie opaca debajo:
 * el nombre de una columna, el recuento del catálogo, el nombre de la
 * especialidad abierta.
 */
internal fun specialtyGlassText(tokens: GlassTokens): SpecialtyGlassText = SpecialtyGlassText(
    primary = tokens.textPrimary,
    secondary = tokens.textSecondary,
)

/** Relleno de las píldoras que van sobre cristal. El mismo translúcido que `StatusDotChip`. */
internal fun specialtyGlassPillFill(tokens: GlassTokens): Color = tokens.glassFillStrong

/** Relleno de la barra de guardado con cambios sin guardar: el ámbar del aviso, muy bajo. */
internal fun specialtySaveBarProminentFill(tokens: GlassTokens): Color = tokens.warning.copy(alpha = 0.10f)

/**
 * El color que PORTA el tono en la barra: el icono de aviso o el punto de 7dp.
 * Elemento de interfaz, 3:1.
 *
 * **`successInk` y no `success`**, y es una medición, no una preferencia: la
 * barra en reposo es transparente, así que su punto se compone sobre el cristal
 * del editor, y ahí el verde de éxito del tema claro da **2,98:1** contra la
 * parada más oscura del fondo —por debajo del 3:1 de un elemento de interfaz—.
 * Es el mismo caso y el mismo remedio que `semanticIconTint`, que ya cambia el
 * verde por su tinta en ese tema; en el oscuro la tinta de éxito ES el verde, así
 * que no cambia nada. Los otros tres tonos pasan de 3:1 sobre los dos extremos
 * del cristal y conservan su color.
 */
internal fun specialtySaveBarToneColor(tone: SpecialtySaveBarTone, tokens: GlassTokens): Color = when (tone) {
    SpecialtySaveBarTone.INFO -> tokens.info
    SpecialtySaveBarTone.WARNING -> tokens.warning
    SpecialtySaveBarTone.DANGER -> tokens.danger
    SpecialtySaveBarTone.SUCCESS -> tokens.successInk
}

/**
 * El color del TEXTO de la barra.
 *
 * Sobre el relleno ámbar de la forma prominente va siempre en `textPrimary`: el
 * rojo de peligro encima de ese relleno no llega al 4,5:1 de un texto normal en
 * el tema claro. Fuera de la forma prominente, un error sí va en `danger` —sobre
 * el cristal del editor llega— y el resto en `textSecondary`, porque el verde de
 * éxito no sostiene texto en claro.
 */
internal fun specialtySaveBarTextColor(
    tone: SpecialtySaveBarTone,
    prominent: Boolean,
    tokens: GlassTokens,
): Color = when {
    prominent -> tokens.textPrimary
    tone == SpecialtySaveBarTone.DANGER -> tokens.danger
    else -> tokens.textSecondary
}

/** Relleno, borde y textos de una tarjeta. */
internal data class SpecialtyCardColors(
    val background: Color,
    val border: Color,
    val title: Color,
    val description: Color,
    val mark: Color,
)

/**
 * Una fila del catálogo.
 *
 * La seleccionada se marca con una barra coral a la izquierda, un fondo muy
 * tenue y un borde coral al 45 %; el texto se queda en `textPrimary` porque el
 * coral como texto no llega a 4,5:1 en el tema claro.
 */
internal fun specialtyCatalogRowColors(
    selected: Boolean,
    hovered: Boolean,
    tokens: GlassTokens,
): SpecialtyCardColors = SpecialtyCardColors(
    background = when {
        selected -> tokens.surfaceSelected
        hovered -> tokens.surfaceHover
        else -> tokens.surfaceSolid
    },
    border = if (selected) tokens.accent.copy(alpha = 0.45f) else tokens.border,
    title = tokens.textPrimary,
    description = tokens.textSecondary,
    mark = tokens.accentInk,
)

/**
 * La barra coral de 3dp que marca la fila abierta.
 *
 * Está fuera del composable por la misma razón que el resto: es el elemento que
 * PORTA la selección en color y hay que poder medirlo (3:1 contra el relleno de
 * la fila). Sin seleccionar es transparente, así que la fila no gasta una
 * sangría distinta según su estado.
 *
 * **`accentInk` y no `accent`**, medido: el coral de relleno sobre
 * `surfaceSelected` —que es un tinte del propio coral— da **2,10:1** en el tema
 * claro. La barra y el tinte de la fila son la misma familia de color, así que
 * uno encima del otro no marca nada; con la tinta, que es el coral oscurecido
 * para portar significado, la barra se separa de su propio fondo.
 */
internal fun specialtyCatalogRowBar(selected: Boolean, tokens: GlassTokens): Color =
    if (selected) tokens.accentInk else Color.Transparent

/**
 * Una tarjeta de tipo de examen.
 *
 * Abierto y cerrado se distinguen por el relleno y por el interruptor, no por el
 * color del texto: un título en gris claro dentro de una tarjeta apagada se lee
 * peor sin decir nada que el interruptor no diga ya. El caso que sí cambia el
 * borde es el peligroso —cerrado y con exámenes—, que se tiñe de aviso.
 */
internal fun specialtyTypeCardColors(
    enabled: Boolean,
    hasExams: Boolean,
    tokens: GlassTokens,
): SpecialtyCardColors = SpecialtyCardColors(
    background = if (enabled) tokens.surfaceSolid else tokens.surfaceMuted,
    border = when {
        enabled -> tokens.border
        hasExams -> tokens.warning.copy(alpha = 0.45f)
        else -> tokens.border
    },
    title = tokens.textPrimary,
    description = tokens.textSecondary,
    mark = if (enabled) tokens.success else tokens.textSecondary,
)

/** El punto de estado de una comprobación: elemento de interfaz, 3:1. */
internal fun specialtyCheckDotColor(status: SpecialtyCheckStatus, tokens: GlassTokens): Color = when (status) {
    SpecialtyCheckStatus.BLOCKING -> tokens.danger
    SpecialtyCheckStatus.WARNING -> tokens.warning
    SpecialtyCheckStatus.PENDING -> tokens.info
}

/** El detalle de una comprobación: `textSecondary` sea cual sea el estado. Es texto que se lee. */
internal fun specialtyCheckDetailColor(status: SpecialtyCheckStatus, tokens: GlassTokens): Color = when (status) {
    SpecialtyCheckStatus.BLOCKING,
    SpecialtyCheckStatus.WARNING,
    SpecialtyCheckStatus.PENDING,
    -> tokens.textSecondary
}

/** Relleno, borde, texto e icono de una píldora. */
internal data class SpecialtyPillColors(
    val background: Color,
    val border: Color,
    val content: Color,
    val icon: Color,
)

/** La píldora que declara hasta dónde llega una capacidad comercial. */
internal fun specialtyDeclarationPillColors(
    declaration: SpecialtyDeclaration,
    tokens: GlassTokens,
): SpecialtyPillColors = when (declaration) {
    SpecialtyDeclaration.PARTIAL -> SpecialtyPillColors(
        background = tokens.surfaceSolid,
        border = tokens.warning.copy(alpha = 0.45f),
        content = tokens.textPrimary,
        icon = tokens.warning,
    )
    SpecialtyDeclaration.NOT_APPLIED -> SpecialtyPillColors(
        background = tokens.surfaceMuted,
        border = tokens.border,
        content = tokens.textSecondary,
        icon = tokens.textSecondary,
    )
}

/** El filtro de estado del catálogo: píldora opaca; activa, el coral sólo en el borde y el icono. */
internal fun specialtyStatusFilterColors(active: Boolean, tokens: GlassTokens): SpecialtyPillColors =
    SpecialtyPillColors(
        background = if (active) tokens.surfaceSelected else tokens.surfaceSolid,
        border = if (active) tokens.accent.copy(alpha = 0.55f) else tokens.border,
        content = if (active) tokens.textPrimary else tokens.textSecondary,
        icon = if (active) tokens.accentInk else tokens.textSecondary,
    )

/** El peso de un aviso dentro del formulario. */
internal enum class SpecialtyNoticeTone { INFO, WARNING, DANGER }

/**
 * Relleno, borde, icono y texto de un aviso del formulario.
 *
 * El TEXTO va siempre en `textPrimary` y el detalle en `textSecondary`: el color
 * lo lleva el icono y el borde, que son elementos de interfaz (3:1). Un aviso
 * entero escrito en ámbar se queda en 4,40:1 a 13sp en el tema claro, que es el
 * defecto que esta separación evita.
 */
internal fun specialtyNoticeColors(tone: SpecialtyNoticeTone, tokens: GlassTokens): SpecialtyPillColors =
    SpecialtyPillColors(
        background = tokens.surfaceSolid,
        border = when (tone) {
            SpecialtyNoticeTone.INFO -> tokens.info.copy(alpha = 0.45f)
            SpecialtyNoticeTone.WARNING -> tokens.warning.copy(alpha = 0.45f)
            SpecialtyNoticeTone.DANGER -> tokens.danger.copy(alpha = 0.45f)
        },
        content = tokens.textPrimary,
        icon = when (tone) {
            SpecialtyNoticeTone.INFO -> tokens.info
            SpecialtyNoticeTone.WARNING -> tokens.warning
            SpecialtyNoticeTone.DANGER -> tokens.danger
        },
    )

/** El icono de un aviso. Marca Y palabra: el tono nunca es el único portador. */
internal fun specialtyNoticeIcon(tone: SpecialtyNoticeTone): AppIcon = when (tone) {
    SpecialtyNoticeTone.INFO -> AppIcon.INFO
    SpecialtyNoticeTone.WARNING -> AppIcon.WARNING
    SpecialtyNoticeTone.DANGER -> AppIcon.BLOCKED
}

/** Color y peso de un valor de la vista rápida, según de dónde salga. */
internal data class SpecialtyFactStyle(val color: Color, val emphasised: Boolean)

/**
 * Un valor que no es un dato no se pinta como un dato.
 *
 * `UNRESOLVED` en `danger` —sobre superficie opaca llega a 4,5:1—, lo demás en
 * secundario y sin peso. Un cero real sí es un dato, y va en primario.
 */
internal fun specialtyFactStyle(origin: SpecialtyFactOrigin, tokens: GlassTokens): SpecialtyFactStyle =
    when (origin) {
        SpecialtyFactOrigin.VALUE -> SpecialtyFactStyle(tokens.textPrimary, emphasised = true)
        SpecialtyFactOrigin.ABSENT -> SpecialtyFactStyle(tokens.textSecondary, emphasised = false)
        SpecialtyFactOrigin.PENDING -> SpecialtyFactStyle(tokens.textSecondary, emphasised = false)
        SpecialtyFactOrigin.UNRESOLVED -> SpecialtyFactStyle(tokens.danger, emphasised = false)
    }

/** El icono que acompaña a un valor que no es un dato, o `null` si lo es. */
internal fun specialtyFactOriginIcon(origin: SpecialtyFactOrigin): AppIcon? = when (origin) {
    SpecialtyFactOrigin.VALUE -> null
    SpecialtyFactOrigin.ABSENT -> AppIcon.INFO
    SpecialtyFactOrigin.PENDING -> AppIcon.CLOCK
    SpecialtyFactOrigin.UNRESOLVED -> AppIcon.WARNING
}
