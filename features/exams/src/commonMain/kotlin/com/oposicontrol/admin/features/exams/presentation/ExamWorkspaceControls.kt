package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.SectionLink
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.FieldLabel
import com.oposicontrol.admin.core.ui.components.InfoHelpButton
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.HelpTopic
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Specialty

/**
 * Las medidas del workspace, en un solo sitio.
 *
 * Mismo criterio que `DashboardV2Sizing`: los anchos de columna se calculan en dp
 * exactos a partir del único `BoxWithConstraints` de la pantalla, no con
 * `weight()`. Con tres columnas y un editor dentro, los pesos reparten el sobrante
 * de una forma distinta en cada régimen de ventana y el catálogo acaba midiendo
 * 280dp en un portátil y 520 en un monitor grande sin que nadie lo haya decidido.
 */
internal object ExamWorkspaceSizing {
    /**
     * Catálogo en ventana ancha.
     *
     * 340 es lo que necesita una tarjeta de examen para llevar título en una
     * línea, especialidad + año debajo y dos chips sin partirse. Por debajo de
     * 320 el título empieza a elidirse en la mayoría de los exámenes reales.
     */
    val catalogWide: Dp = 330.dp

    /** Catálogo en ventana normal. Cede 40dp al editor, que es donde se trabaja. */
    val catalogStandard: Dp = 310.dp

    /**
     * Resumen lateral. Es una columna de lectura: líneas cortas de comprobación,
     * no una tabla. 300 sostiene «Tipo permitido por la especialidad» en dos
     * líneas y su motivo debajo.
     */
    val summaryWide: Dp = 300.dp

    /** Hueco entre columnas. El mismo que usa `CrudSplitLayout`, para que las dos pantallas respiren igual. */
    val columnGap: Dp = Spacing.lg

    /**
     * Suelo del editor. Si con las tres columnas el centro no llega aquí, el
     * resumen se pliega a franja y deja de ser columna.
     *
     * **700 y no 520, medido sobre la captura de 1600.** Con 520 un monitor de
     * 1600 conservaba las tres columnas y dejaba el editor en 612dp: por encima
     * del suelo, sí, pero por debajo de lo que necesita su propia fila de seis
     * pestañas —«Publicación» se salía del borde— y con las tarjetas de tipo
     * apiladas cuando había sitio de sobra para tenerlas en fila.
     *
     * El suelo no lo marca «¿cabe un campo?» sino «¿cabe el cromo del editor?»,
     * y ese cromo es la fila de pestañas. Con 700, un 1600 pliega el resumen a
     * franja y el editor pasa de 612 a 932dp — el mismo ancho que tiene en un
     * 1920, que es justo la coherencia que se busca entre los dos monitores
     * reales del objetivo.
     */
    val editorMinWidth: Dp = 700.dp

    /**
     * Alto máximo del catálogo cuando las columnas se apilan.
     *
     * **480, medido sobre la captura de 1080.** El KDoc anterior decía que 320
     * dejaba ver «tres tarjetas completas y media», y no era verdad: la cabecera
     * de la columna, el buscador, los filtros y el recuento se comen ~200dp antes
     * de la primera tarjeta, y cada tarjeta mide ~116dp más 8 de hueco. Con 320
     * se veía UNA tarjeta, y cortada: el catálogo no se podía usar en ese régimen.
     *
     * Con 480 se ven dos tarjetas completas y el principio de la tercera —lo
     * justo para que se note que la lista sigue— y la cabecera del examen abierto
     * sigue entrando en la primera pantalla de un 1080×900. No convierte la
     * pantalla en un móvil: es el mismo catálogo con su buscador, sus filtros y
     * su paginación, sólo que acotado, y la página desplaza.
     */
    val stackedCatalogMaxHeight: Dp = 480.dp

    /** Padding interior de un contenedor de sección de cristal. */
    val sectionPadding: PaddingValues = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)

    /** Contenedor de icono de la cabecera de una sección. */
    val sectionIconChip: Dp = Sizing.iconButton

    /**
     * Alto FIJO de la franja de resumen plegada (P1-1).
     *
     * Una línea y nunca más. La franja crecía de 40 a 130px con la primera tecla
     * —«Cambios sin guardar» le añadía una fila— y ese alto se lo quitaba al
     * formulario: 181 → 80px en 1366×768. 40 es el alto de un input: cabe una
     * línea de 13sp con su punto de estado y «Ver detalle», y la franja entera es
     * el objetivo de clic. El detalle se abre en una capa encima, sin empujar.
     */
    val summaryStripHeight: Dp = 40.dp

    /**
     * Alto mínimo GARANTIZADO del área del formulario (P0-2).
     *
     * **200, medido sobre la captura de 1600×900 con cambios** (`C-1600-light-dirty`):
     * del borde superior de la sección «Identificación» al final del texto de
     * apoyo del código hay 185px —cabecera de sección con icono, título y
     * subtítulo, las etiquetas, los dos inputs y el apoyo de dos líneas—, más los
     * 16 de padding inferior de la sección. Es lo mínimo para trabajar: una
     * sección entera con su primera fila completa.
     *
     * Si la columna no puede dárselo con la cabecera a la vista, la cabecera y la
     * franja pasan a desplazarse con la página y la barra de guardar se queda
     * anclada ([ExamEditorColumnLayout]).
     */
    val editorFormMinHeight: Dp = 200.dp

    /**
     * Lo que el marco del editor gasta FUERA del formulario —pestañas, huecos,
     * barra de guardar y padding—, sólo como estimación del primer pase. **139px
     * medidos en la misma captura** (61 por encima del formulario, 78 por debajo).
     * En cuanto el marco se compone, se usa el que mide de verdad.
     */
    val editorFrameChromeEstimate: Dp = 140.dp

    /**
     * Hueco para la barra de desplazamiento, en el editor y en el resumen: sin
     * él, el pulgar se pinta encima del borde redondeado de las tarjetas.
     */
    val scrollbarGutter: Dp = 10.dp
}

/**
 * Contenedor de SECCIÓN del workspace: nivel 1 de cristal.
 *
 * Es el mismo marco que usa el panel de escritorio —contenedor translúcido con
 * icono semántico y título— y por eso Exámenes se lee como la misma aplicación
 * que Inicio. Lo que va DENTRO es siempre opaco: ni una cifra ni un campo se
 * dibujan directamente sobre este cristal.
 */
@Composable
internal fun ExamWorkspaceSection(
    icon: AppIcon,
    role: SemanticRole,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    help: HelpTopic? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val text = examGlassText(GlassTheme.tokens)
    GlassSectionSurface(
        modifier = modifier,
        shape = Radius.xl,
        contentPadding = ExamWorkspaceSizing.sectionPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SemanticIconContainer(
                    icon = icon,
                    role = role,
                    size = ExamWorkspaceSizing.sectionIconChip,
                    iconSize = Sizing.icon,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // `titleMedium` y no la etiqueta de campo: es el nombre de
                        // una columna del workspace, la misma jerarquía que usa una
                        // franja del panel de escritorio en su modo denso.
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = text.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        help?.let { InfoHelpButton(it) }
                    }
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = text.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            action?.invoke()
        }
        content()
    }
}

/** Los dos colores de texto que el workspace pinta DIRECTAMENTE sobre su cristal de sección. */
internal data class ExamGlassText(val primary: Color, val secondary: Color)

/**
 * El texto que va sobre el cristal del workspace, sin superficie opaca debajo.
 *
 * El nombre de una columna, la cabecera del examen, el recuento del catálogo, la
 * línea de la franja de resumen: todo eso se lee sobre un fondo que cambia de un
 * punto de la ventana a otro. Es una función para que `ExamCheckContrastTest` lo
 * mida contra los dos extremos reales del cristal —`glassBackdrops`— con la misma
 * decisión que toman los composables. Medido en el render oscuro antes de esta
 * corrección: el código de la cabecera daba 4,18:1, la etiqueta «Especialidad»
 * 4,30:1 y el recuento «5 exámenes» 3,82:1, con el test en verde.
 */
internal fun examGlassText(tokens: GlassTokens): ExamGlassText = ExamGlassText(
    primary = tokens.textPrimary,
    secondary = tokens.textSecondary,
)

/**
 * El relleno de las píldoras que van sobre el cristal: el contador del catálogo y
 * los metadatos de la cabecera. Translúcido a propósito —es el mismo que usa
 * `StatusDotChip`—, así que su texto se mide compuesto sobre el cristal.
 */
internal fun examGlassPillFill(tokens: GlassTokens): Color = tokens.glassFillStrong

/**
 * Superficie OPACA: nivel 2. Todo lo que lleva un dato o un campo vive aquí.
 *
 * No se apoya en `GlassSurface` para no arrastrar su sombra y su brillo —que en
 * escritorio se apagan, pero siguen costando una capa de dibujo— y porque este
 * bloque necesita algo que aquél no da: poder marcarse como seleccionado o
 * bloqueado con un borde de color sin repintar el relleno.
 */
@Composable
internal fun ExamSolidPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Spacing.md),
    borderColor: androidx.compose.ui.graphics.Color? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.sm),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(tokens.surfaceSolid)
            .border(1.dp, borderColor ?: tokens.border, Radius.card)
            .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** Columnas de la rejilla común de los formularios del workspace (P2-2). */
internal const val EXAM_FIELD_GRID_COLUMNS: Int = 6

/**
 * Fila de campos sobre una rejilla COMÚN de [EXAM_FIELD_GRID_COLUMNS] columnas.
 *
 * Cada campo ocupa un número entero de columnas ([spans]; por defecto, las seis
 * repartidas a partes iguales). Lo que importa no es la proporción de cada fila
 * sino que **las filas del mismo formulario compartan bordes**. Con pesos
 * sueltos no los compartían: «Título 2 : Código 1» y «tres tercios» reparten el
 * hueco entre campos de forma distinta, y el borde del código caía 6px a la
 * izquierda del del año (x=1249 frente a 1255 medido en la captura). Sobre seis
 * columnas, Título 4 + Código 2 y Especialidad 2 + Comunidad 2 + Año 2 terminan
 * y empiezan en el mismo píxel: los bordes salen de [examFieldGridCells], que
 * sólo depende del índice de columna.
 *
 * Dos campos a partes iguales (3 + 3) y tres a partes iguales (2 + 2 + 2) miden
 * exactamente lo mismo que con los pesos de antes: las filas de Configuración no
 * cambian.
 *
 * **Los campos que van dentro renuncian a su tope de 420dp**, y hay que pasarles
 * `maxWidth = Dp.Unspecified` al construirlos. El tope existe para un campo
 * suelto —una caja de texto de 900dp de ancho es ilegible—, pero dentro de una
 * fila lo que hace es romper la rejilla.
 */
@Composable
internal fun ExamFieldRow(
    vararg fields: @Composable (Modifier) -> Unit,
    spans: List<Int> = emptyList(),
) {
    val resolved = examFieldSpans(fields.size, spans)
    val gap = with(LocalDensity.current) { Spacing.md.roundToPx() }
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = { fields.forEach { field -> Box { field(Modifier.fillMaxWidth()) } } },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val cells = examFieldGridCells(width, gap, resolved)
        val placeables = measurables.mapIndexed { index, measurable ->
            val cellWidth = cells[index].width
            measurable.measure(Constraints(minWidth = cellWidth, maxWidth = cellWidth))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(cells[index].start, 0) }
        }
    }
}

/** Una celda de la rejilla, en px: desde [start] (incluido) hasta [end] (excluido). */
internal data class ExamGridCell(val start: Int, val end: Int) {
    val width: Int get() = end - start
}

/** Las columnas de cada campo: las pedidas, o las seis a partes iguales. */
internal fun examFieldSpans(count: Int, spans: List<Int>): List<Int> {
    if (spans.isNotEmpty()) {
        require(spans.size == count && spans.all { it > 0 } && spans.sum() == EXAM_FIELD_GRID_COLUMNS) {
            "Una fila de $count campos necesita $count anchos que sumen $EXAM_FIELD_GRID_COLUMNS columnas: $spans"
        }
        return spans
    }
    require(count > 0 && EXAM_FIELD_GRID_COLUMNS % count == 0) {
        "$count campos no se reparten a partes iguales en $EXAM_FIELD_GRID_COLUMNS columnas: indica sus anchos"
    }
    return List(count) { EXAM_FIELD_GRID_COLUMNS / count }
}

/**
 * Dónde empieza y dónde acaba cada celda de una fila de [totalWidth] px.
 *
 * Los bordes se calculan a partir del ÍNDICE de columna —el borde izquierdo de la
 * columna k y el derecho de la k-1, redondeados igual—, nunca acumulando anchos:
 * así dos filas con repartos distintos comparten exactamente el píxel de cada
 * borde que tienen en común, sin deriva de redondeo.
 */
internal fun examFieldGridCells(totalWidth: Int, gap: Int, spans: List<Int>): List<ExamGridCell> {
    val columns = EXAM_FIELD_GRID_COLUMNS
    val column = (totalWidth - gap * (columns - 1)).toFloat() / columns
    fun leftEdgeOf(index: Int): Int = (index * (column + gap)).roundToInt()
    fun rightEdgeBefore(index: Int): Int = (index * (column + gap) - gap).roundToInt()
    var first = 0
    return spans.map { span ->
        val cell = ExamGridCell(start = leftEdgeOf(first), end = rightEdgeBefore(first + span))
        first += span
        cell
    }
}

// El chip de opción única vivía aquí y lo usaba SÓLO Acceso, para Gratuito y
// Premium. Esa decisión pasó a dos tarjetas con el mismo sistema que las de tipo
// de Configuración (`ExamAccessCard`), que es lo que pedía su peso en la pantalla:
// una píldora pequeña no se lee como la decisión principal de una pestaña. Sus
// colores se fueron con él a `examAccessCardColors`, que `ExamCheckContrastTest`
// sigue midiendo con la misma regla (texto nunca en coral, DEBT-024).

/** Un par etiqueta/valor de una línea. Para meta que se lee, no se edita. */
// ---------------------------------------------------------------------------
// Selectores de catálogo
// ---------------------------------------------------------------------------

/**
 * Selector de especialidad.
 *
 * Con el catálogo cargado es un desplegable. Sin él, la celda NO se convierte en
 * un párrafo: queda un **campo inerte** con la misma etiqueta y el mismo alto que
 * los de al lado —la rejilla de tres no se desmonta— y debajo una línea compacta
 * con su porqué y «Reintentar» (D4). Un error de catálogo a todo lo ancho del
 * formulario hace que parezca un problema del examen y no del entorno; y
 * **ocultarlo** tampoco: si no se puede elegir, hay que verlo donde se iba a
 * elegir.
 *
 * El campo inerte enseña lo que se sabe: el nombre de la especialidad guardada si
 * el examen lo trae ([specialtyPickerValue]); nunca «Sin especialidad» como
 * consecuencia de un fallo.
 */
@Composable
internal fun ExamSpecialtyPicker(
    state: ExamsState,
    exam: Exam?,
    onSelected: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val specialties = state.specialties
    if (specialties is UiAsync.Success) {
        val label = specialtyLabel(state.editorSpecialtyId, specialties, exam)
        val unresolved = label.origin == ExamFieldOrigin.UNRESOLVED
        DropdownField(
            label = "Especialidad",
            required = true,
            // Vacío deja ver el placeholder; un nombre que el catálogo ya no
            // contiene se enseña igualmente, marcado como vínculo roto, en vez de
            // dejar el control en «Selecciona» sobre un examen que sí tiene una.
            selectedLabel = if (label.origin == ExamFieldOrigin.ABSENT) "" else label.text,
            placeholder = "Selecciona una especialidad",
            options = specialties.value.map { it.id to it.name },
            onSelect = { value -> value?.let(onSelected) },
            modifier = modifier,
            maxWidth = Dp.Unspecified,
            supportingText = label.detail.takeIf { unresolved },
            isError = unresolved,
        )
    } else {
        ExamPickerCell(
            label = "Especialidad",
            required = true,
            value = specialtyPickerValue(state, exam),
            notice = specialtyPickerNotice(specialties, isNew = state.selectedExamId == null),
            onRetry = onRetry,
            modifier = modifier,
        )
    }
}

/**
 * Selector de comunidad autónoma.
 *
 * Tolerante a fallo por diseño —`comunidad_autonoma` es una migración reciente
 * que puede no estar aplicada en este entorno—, pero **tolerante no es
 * silencioso**, y ahí estaba el peor defecto de esta pestaña.
 *
 * Lo que hacía antes: con el catálogo en `Error` decía «El examen queda como
 * nacional» **fuera cual fuera su comunidad guardada**, y con la lista vacía de
 * la semilla del repositorio se pintaba un desplegable normal cuya única opción
 * era «Nacional». Un examen andaluz se presentaba como nacional, con aspecto de
 * dato correcto, y bastaba con desplegar y confirmar para anular un
 * `comunidad_id` real. Un fallo de lectura no puede convertirse en una escritura.
 *
 * Lo que hace ahora depende de si hay algo que perder:
 *
 *  - **Sin comunidad guardada** y catálogo caído: se conserva el mensaje de
 *    antes, porque ahí sí es verdad —el examen es nacional y seguirá siéndolo—.
 *  - **Con comunidad guardada** y catálogo caído: el control queda **inerte, no
 *    desplegable**. Un desplegable cuya única opción es «Nacional» con un id real
 *    detrás no puede hacer nada bueno; sólo puede destruir el dato.
 *  - **Con id que el catálogo cargado no contiene**: se puede elegir otra —el
 *    catálogo es bueno, el vínculo es el que está roto— y se avisa de qué cuesta.
 *    Lo que NO se ofrece es «Nacional» (D5): elegirla escribiría un nulo encima
 *    de un `comunidad_id` real, y un aviso no puede venir acompañado de la
 *    herramienta que borra el dato del que avisa.
 *
 * Los textos de los casos sin desplegable los decide [communityPickerNotice],
 * fuera del composable, para que la regla «catálogo caído no es Nacional» se
 * pueda comprobar con un test.
 */
@Composable
internal fun ExamCommunityPicker(
    communities: UiAsync<List<Community>>,
    selectedId: String?,
    isNew: Boolean,
    onSelected: (String?) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = communityLabel(selectedId, communities)
    if (communities is UiAsync.Success) {
        val unresolved = label.origin == ExamFieldOrigin.UNRESOLVED
        DropdownField(
            label = "Comunidad autónoma",
            help = Help.comunidadAutonoma,
            selectedLabel = label.text,
            options = (if (unresolved) emptyList() else listOf<Pair<String?, String>>(null to "Nacional")) +
                communities.value.map { it.id to it.name },
            onSelect = onSelected,
            modifier = modifier,
            maxWidth = Dp.Unspecified,
            // Sólo cuando el vínculo está roto. En el caso normal no hay nada que
            // advertir y una línea de apoyo permanente debajo de un desplegable
            // que funciona es ruido que se aprende a no leer.
            supportingText = "Si eliges otra, se perderá la referencia a la que tenía.".takeIf { unresolved },
            isError = unresolved,
        )
    } else {
        ExamPickerCell(
            label = "Comunidad autónoma",
            help = Help.comunidadAutonoma,
            value = label,
            notice = communityPickerNotice(selectedId, communities, isNew),
            onRetry = onRetry,
            modifier = modifier,
        )
    }
}

/**
 * La celda de un selector que no puede desplegarse.
 *
 * Dos piezas, las mismas que un campo de al lado, para que la celda **no sea más
 * alta que sus vecinas** (P2-1): la misma etiqueta y un **campo inerte** con el
 * alto de un input —superficie hundida, sin cursor— que enseña lo que se sabe del
 * valor y lleva «Reintentar» dentro, a la derecha.
 *
 * El porqué NO va dentro de la celda: lo pinta [ExamPickerNotices] debajo de la
 * fila entera. Dentro medía dos líneas en un tercio de ancho —el texto de D4 no
 * cabe en una a 1920— y, con un segundo «Reintentar» debajo, la celda de
 * Especialidad bajaba 61px por debajo de Comunidad y Año (C-1920-b04, y=664 y
 * y=744: dos «Reintentar» para una sola acción).
 */
@Composable
private fun ExamPickerCell(
    label: String,
    value: ExamFieldLabel,
    notice: ExamPickerNoticeText?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    help: HelpTopic? = null,
    required: Boolean = false,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        FieldLabel(label = label, required = required, help = help)
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = Radius.md,
            tint = tokens.surfaceMuted,
        ) {
            // «Reintentar» va DENTRO del campo, a la derecha, y no en una línea
            // propia debajo: medido en la captura de especialidades caídas, como
            // tercera línea de la celda quedaba bajo el pliegue del editor en
            // 1920×1080 y hacía esa celda más alta que sus vecinas. Aquí está
            // donde está el problema y la celda mide lo que un campo con su
            // texto de apoyo.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Sizing.inputHeight)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = examFieldValueColor(value.origin, tokens),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (notice?.canRetry == true) {
                    SectionLink(text = "Reintentar", onClick = onRetry)
                }
            }
        }
    }
}

/**
 * Los avisos de los selectores de una fila, DEBAJO de la fila y a su ancho (P2-1).
 *
 * Un aviso por selector que no puede desplegarse, en el orden de la fila, con su
 * icono —ámbar si algo impide elegir, neutro si sólo se explica— y el texto de D4
 * y D5, que dice de qué catálogo habla. Así las celdas conservan el alto de un
 * campo y la rejilla de tres no se descuadra; el aviso sigue pegado al campo que
 * explica, no en un párrafo rojo a lo ancho del formulario.
 *
 * Mientras carga no hay línea: el propio campo ya dice «Cargando…».
 *
 * El aviso va en `textSecondary` con un icono ámbar, no en rojo: un catálogo que
 * no carga es un problema del entorno, no un error del examen, y pintarlo del
 * color de los errores del formulario lo haría pasar por uno.
 */
@Composable
internal fun ExamPickerNotices(notices: List<ExamPickerNoticeText>, modifier: Modifier = Modifier) {
    val visible = notices.filter { it.kind != ExamPickerNoticeKind.LOADING }
    if (visible.isEmpty()) return
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        visible.forEach { notice ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val problem = notice.kind == ExamPickerNoticeKind.PROBLEM
                AppIconImage(
                    icon = if (problem) AppIcon.WARNING else AppIcon.INFO,
                    size = 14.dp,
                    tint = if (problem) tokens.warning else tokens.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = notice.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}
