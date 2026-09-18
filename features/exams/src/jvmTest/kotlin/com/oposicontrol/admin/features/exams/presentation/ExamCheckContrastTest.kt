package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oposicontrol.admin.core.ui.components.glassBackdrops
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contraste de los colores que ELIGEN los componentes del workspace de Exámenes,
 * con la paleta VIVA de los dos temas.
 *
 * **Mide la elección del componente, no un par de tokens.** `ContrastTest`
 * compara pares de tokens y no puede saber de qué color pinta un componente su
 * texto: el detalle de un aviso en `warning` (4,40:1 en claro a 13sp) o el texto
 * de la píldora de filtro activa en `accentInk` (4,21:1) convivían con un
 * `ContrastTest` en verde. Aquí el componente y el test llaman a las mismas
 * funciones, así que devolver otra vez el color que fallaba pone esto en rojo.
 *
 * **Sobre qué se mide.** Lo opaco, contra su superficie. Lo que va sobre el cristal
 * del workspace —la cabecera del examen, el nombre de una columna, el recuento
 * del catálogo, la barra de guardado— contra los DOS extremos reales del cristal
 * (`glassBackdrops`): la parada más profunda del fondo y el pico del halo con el
 * brillo del contenedor encima. Sólo se medía el primero, que es el peor caso para
 * un texto oscuro; en el tema oscuro el texto es claro y manda el segundo. Así se
 * colaban el código de la cabecera a 4,18:1 y «5 exámenes» a 3,82:1.
 *
 * **Por qué los tokens se leen componiendo `AdminTheme`.** `LightGlassTokens` y
 * `DarkGlassTokens` son `internal` de `core/ui` y este módulo no los ve. En vez de
 * abrirlos —y dar a las pantallas una forma de elegir paleta saltándose el
 * tema— se leen por el mismo camino que producción: `GlassTheme.tokens` dentro de
 * `AdminTheme(darkTheme = …)`. Ningún color se replica aquí; sólo la fórmula y la
 * forma de componer un tinte, que es la misma que usa `ContrastTest`.
 */
@OptIn(ExperimentalTestApi::class)
class ExamCheckContrastTest {

    @Test
    fun `el detalle de una comprobacion se lee como texto normal en los dos temas`() {
        // `bodySmall` es 13sp: texto normal, 4,5:1. El panel es `ExamSolidPanel`,
        // que pinta `surfaceSolid`.
        temas.forEach { (nombre, t) ->
            ExamCheckStatus.entries.forEach { status ->
                assertContrast(
                    foreground = examCheckDetailColor(status, t),
                    background = t.surfaceSolid,
                    minimum = 4.5,
                    description = "[$nombre] Detalle de una comprobación '$status' sobre superficie sólida",
                )
            }
        }
    }

    @Test
    fun `el punto de estado de una comprobacion se distingue en los dos temas`() {
        // El punto de 7dp es el que porta el estado en color: elemento de
        // interfaz, 3:1.
        temas.forEach { (nombre, t) ->
            ExamCheckStatus.entries.forEach { status ->
                assertContrast(
                    foreground = examCheckDotColor(status, t),
                    background = t.surfaceSolid,
                    minimum = 3.0,
                    description = "[$nombre] Punto de estado '$status' sobre superficie sólida",
                )
            }
        }
    }

    /**
     * Un valor se lee sea cual sea su procedencia, y sea cual sea dónde se pinta.
     *
     * `examFieldValueColor` pinta la ficha del resumen (sobre `surfaceSolid`), el
     * campo inerte de un selector sin catálogo (sobre `surfaceMuted`) y el par
     * etiqueta/valor de la CABECERA del examen, que va sobre cristal. Sólo se
     * medían los dos primeros. «Cargando…» o «No disponible» se pintan distinto de
     * un dato, pero no pueden pintarse ilegibles: son justo lo que hay que leer
     * cuando algo va mal.
     */
    @Test
    fun `un valor se lee sea cual sea su procedencia`() {
        temas.forEach { (nombre, t) ->
            val fondos = listOf("superficie sólida" to t.surfaceSolid, "campo inerte" to t.surfaceMuted) +
                glassBackdrops(t).glass
            ExamFieldOrigin.entries.forEach { origin ->
                fondos.forEach { (donde, fondo) ->
                    assertContrast(
                        foreground = examFieldValueColor(origin, t),
                        background = fondo,
                        minimum = 4.5,
                        description = "[$nombre] Valor de origen '$origin' sobre $donde",
                    )
                }
            }
        }
    }

    /**
     * El texto que el workspace pinta directamente sobre su cristal.
     *
     * `examGlassText` es lo que usan la cabecera del examen (título, descripción,
     * código, etiquetas del ámbito), la cabecera de cada columna y el recuento del
     * catálogo. Las píldoras —el contador del catálogo y los metadatos de la
     * cabecera— van sobre un relleno translúcido que se compone ENCIMA del cristal.
     */
    @Test
    fun `el texto que el workspace pinta sobre su cristal se lee`() {
        val origenesEnPildora = origenesDeUnaPildoraDeMetadato()
        temas.forEach { (nombre, t) ->
            val texto = examGlassText(t)
            glassBackdrops(t).glass.forEach { (donde, cristal) ->
                assertContrast(texto.primary, cristal, 4.5, "[$nombre] Texto principal del workspace sobre $donde")
                assertContrast(texto.secondary, cristal, 4.5, "[$nombre] Texto secundario del workspace sobre $donde")
                val pildora = examGlassPillFill(t).over(cristal)
                assertContrast(texto.primary, pildora, 4.5, "[$nombre] Contador del catálogo sobre $donde")
                origenesEnPildora.forEach { origin ->
                    assertContrast(
                        foreground = examFieldValueStyle(origin, t).color,
                        background = pildora,
                        minimum = 4.5,
                        description = "[$nombre] Metadato de la cabecera de origen '$origin' sobre $donde",
                    )
                }
            }
        }
    }

    /**
     * El mismo dato, el mismo tratamiento, en la cabecera y en el resumen (P2-9).
     *
     * «Sin duración» salía en `textPrimary` en la píldora de la cabecera y en
     * `textSecondary` en la ficha del resumen. Las dos piezas pintan ahora con
     * `examFieldValueStyle`; aquí se comprueba que las dos parten de la MISMA
     * procedencia —la cabecera y la ficha leen la duración de la misma función— y
     * que esa procedencia se pinta como una ausencia, no como un dato.
     */
    @Test
    fun `sin duracion se pinta igual en la cabecera y en el resumen`() {
        val nuevo = ExamsState(editorTypeExam = "rafaga", editorDurationMinutes = "")
        val enCabecera = examHeaderChips(nuevo, exam = null).single { it.text == EXAM_NO_DURATION }
        val enResumen = examSummaryFacts(nuevo, exam = null, questionCount = null, communities = nuevo.communities)
            .single { it.label == "Duración" }.value
        kotlin.test.assertEquals(enResumen.origin, enCabecera.origin, "La cabecera y el resumen dan a «Sin duración» procedencias distintas")
        kotlin.test.assertEquals(ExamFieldOrigin.ABSENT, enCabecera.origin)
        temas.forEach { (nombre, t) ->
            val estilo = examFieldValueStyle(enCabecera.origin, t)
            kotlin.test.assertEquals(examFieldValueStyle(enResumen.origin, t), estilo, "[$nombre] Tratamientos distintos")
            kotlin.test.assertEquals(t.textSecondary, estilo.color, "[$nombre] Una ausencia se está pintando como un dato")
            kotlin.test.assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, estilo.weight, "[$nombre] Una ausencia lleva peso de dato")
        }
    }

    /**
     * La píldora de filtro activa del catálogo: texto legible, coral en el chevron.
     *
     * Su texto iba en `accentInk` sobre `surfaceSelected`: 4,21:1 en claro para un
     * texto de 13sp. El chevron conserva el coral y sólo necesita 3:1.
     */
    @Test
    fun `la pildora de filtro se lee activa o no`() {
        temas.forEach { (nombre, t) ->
            listOf(true, false).forEach { active ->
                val fondo = examFilterPillBackground(t, active)
                val estado = if (active) "activa" else "inactiva"
                assertContrast(
                    foreground = examFilterPillLabelColor(t, active),
                    background = fondo,
                    minimum = 4.5,
                    description = "[$nombre] Texto de la píldora de filtro $estado",
                )
                assertContrast(
                    foreground = examFilterPillChevronTint(t, active),
                    background = fondo,
                    minimum = 3.0,
                    description = "[$nombre] Chevron de la píldora de filtro $estado",
                )
            }
        }
    }

    /**
     * El texto de la barra de guardado se lee en todos sus tonos.
     *
     * La barra va dentro del contenedor de cristal del editor. Con cambios sin
     * guardar lleva además el relleno ámbar de la forma prominente, compuesto
     * sobre ese cristal. Se mide sobre los dos extremos del cristal.
     */
    @Test
    fun `el texto de la barra de guardado se lee en todos sus tonos`() {
        temas.forEach { (nombre, t) ->
            glassBackdrops(t).glass.forEach { (donde, cristal) ->
                listOf(true, false).forEach { prominent ->
                    val fondo = if (prominent) examSaveBarProminentFill(t).over(cristal) else cristal
                    ExamSaveBarTone.entries.forEach { tone ->
                        assertContrast(
                            foreground = examSaveBarTextColor(tone, prominent, t),
                            background = fondo,
                            minimum = 4.5,
                            description = "[$nombre] Texto de la barra de guardado ($tone, " +
                                (if (prominent) "prominente" else "en reposo") + ") sobre $donde",
                        )
                    }
                }
            }
        }
    }

    /**
     * La capa del detalle de la franja de resumen (P0-2): las mismas filas que la
     * tarjeta del resumen, sobre el fondo que el componente elige para la capa.
     */
    @Test
    fun `el detalle de la franja se lee sobre su capa`() {
        temas.forEach { (nombre, t) ->
            val fondo = examSummaryDetailContainer(t)
            assertContrast(t.textPrimary, fondo, 4.5, "[$nombre] Etiqueta de una comprobación en la capa de la franja")
            ExamCheckStatus.entries.forEach { status ->
                assertContrast(examCheckDetailColor(status, t), fondo, 4.5, "[$nombre] Detalle '$status' en la capa de la franja")
                assertContrast(examCheckDotColor(status, t), fondo, 3.0, "[$nombre] Punto '$status' en la capa de la franja")
            }
        }
    }

    /**
     * Las tarjetas de acceso (Gratuito / Premium) — DEBT-024.
     *
     * El chip que había antes pintaba su texto en `accentInk` sobre
     * `surfaceSelected`: 4,21:1 en claro para un texto de 14sp. La tarjeta que lo
     * sustituye mantiene la regla: título y frase en texto normal (4,5:1) sobre el
     * relleno que ella misma elige, y el coral sólo en el check, que como elemento
     * de interfaz pide 3:1. Si alguien devuelve el texto a coral, esto cae.
     */
    @Test
    fun `las tarjetas de acceso se leen elegidas o no`() {
        temas.forEach { (nombre, t) ->
            listOf(true, false).forEach { selected ->
                listOf(false, true).forEach hover@{ hovered ->
                    // El hover sólo existe en la tarjeta que se puede pulsar.
                    if (hovered && selected) return@hover
                    val c = examAccessCardColors(selected, hovered, t)
                    val estado = "elegida=$selected, hover=$hovered"
                    assertFalse(c.title == t.accentInk, "[$nombre] El título de la tarjeta de acceso va en coral")
                    assertContrast(c.title, c.background, 4.5, "[$nombre] Título de tarjeta de acceso ($estado)")
                    assertContrast(c.summary, c.background, 4.5, "[$nombre] Frase de tarjeta de acceso ($estado)")
                    if (selected) {
                        assertContrast(c.selectedMark, c.background, 3.0, "[$nombre] Check de la tarjeta elegida ($estado)")
                    }
                }
            }
        }
    }

    /**
     * Las tarjetas de tipo de Configuración y su píldora de permiso, en todos los
     * estados que pueden tener: elegida o no, permitida, prohibida o sin comprobar,
     * y con el ratón encima cuando se puede pulsar.
     *
     * El título y la descripción son texto (4,5:1) sobre el relleno que la tarjeta
     * elige; el check coral del tipo elegido es un elemento de interfaz (3:1). La
     * píldora lleva su propio relleno, así que se mide contra él.
     */
    @Test
    fun `las tarjetas de tipo y su permiso se leen en todos sus estados`() {
        temas.forEach { (nombre, t) ->
            listOf(true, false).forEach { selected ->
                ExamTypePermission.entries.forEach { permission ->
                    listOf(false, true).forEach hover@{ hovered ->
                        // El hover sólo existe en una tarjeta que se puede pulsar.
                        if (hovered && (selected || permission != ExamTypePermission.ALLOWED)) return@hover
                        val c = examTypeCardColors(selected, permission, hovered, t)
                        val estado = "elegida=$selected, $permission, hover=$hovered"
                        assertContrast(c.title, c.background, 4.5, "[$nombre] Título de tarjeta de tipo ($estado)")
                        assertContrast(c.description, c.background, 4.5, "[$nombre] Descripción de tarjeta de tipo ($estado)")
                        if (selected) {
                            assertContrast(c.selectedMark, c.background, 3.0, "[$nombre] Check del tipo elegido ($estado)")
                        }
                    }
                }
            }
            ExamTypePermission.entries.forEach { permission ->
                val p = examTypePermissionPillColors(permission, t)
                assertContrast(p.content, p.background, 4.5, "[$nombre] Texto de la píldora de permiso '$permission'")
                assertContrast(p.icon, p.background, 3.0, "[$nombre] Marca de la píldora de permiso '$permission'")
            }
        }
    }

    /**
     * El campo inerte de Configuración —«Número de preguntas»— señalado en rojo.
     *
     * Es el campo culpable del rechazo de `examenes_total_preguntas_check`, y el
     * único que el administrador NO puede corregir escribiendo, así que la marca
     * es toda la información que tiene. Se mide lo que la marca puede cambiar:
     *
     *  - el **borde**, que es la única frontera que le queda —el relleno hundido se
     *    queda como está, porque es lo que dice «esto no se escribe»—: elemento de
     *    interfaz, 3:1 contra su propio relleno y contra la sección de al lado;
     *  - el **apoyo** de debajo, que es texto de 13sp: 4,5:1 sobre la sección
     *    sólida en la que cae y sobre el relleno del campo.
     *
     * Y que el estado de error se DISTINGA del de reposo: si alguien devolviera los
     * dos al mismo color, todo lo de arriba seguiría cumpliendo y el campo no diría
     * nada.
     */
    @Test
    fun `el campo de solo lectura señalado se lee en los dos temas`() {
        temas.forEach { (nombre, t) ->
            val error = examReadOnlyFieldColors(isError = true, tokens = t)
            val reposo = examReadOnlyFieldColors(isError = false, tokens = t)
            assertFalse(
                error.border == reposo.border && error.support == reposo.support,
                "[$nombre] El campo señalado se pinta igual que uno correcto: la marca no dice nada",
            )
            kotlin.test.assertEquals(
                reposo.fill,
                error.fill,
                "[$nombre] El relleno del campo inerte no puede cambiar con el error: es lo que dice que no se escribe",
            )
            listOf(
                "su propio relleno hundido" to error.fill,
                "la sección sólida de al lado" to t.surfaceSolid,
            ).forEach { (donde, fondo) ->
                assertContrast(error.border, fondo, 3.0, "[$nombre] Borde del campo señalado sobre $donde")
                assertContrast(error.support, fondo, 4.5, "[$nombre] Apoyo del campo señalado sobre $donde")
            }
        }
    }

    /**
     * Las procedencias que DE VERDAD llegan a una píldora de metadato de la
     * cabecera, sacadas de `examHeaderChips` en los estados que la pantalla puede
     * tener: examen nuevo, recuento cargando (con la cifra servida), recuento
     * fallido, examen vacío, examen con preguntas y examen sin duración.
     *
     * Hoy son TRES y se exigen las tres: `VALUE` (tipo, recuento, duración, año,
     * «Oficial»), `ABSENT` («Sin duración») y `UNAVAILABLE` («Sin contar», el
     * recuento que falló).
     *
     * **`PENDING` se retiró del conjunto esperado con K2-2.** La píldora del recuento
     * lo producía como «12 · sin confirmar» mientras la lista cargaba. Desde M1 la
     * base mantiene `total_preguntas` y esa cifra se pinta como `VALUE`; lo único
     * que sigue siendo `PENDING` es «Contando preguntas…», que exige una lista
     * cargando SIN examen guardado detrás, y la pantalla no llega a ese estado: las
     * preguntas sólo pasan a `Loading` en `selectExam` y `loadQuestions`, con el
     * examen ya en el catálogo (`catalogExams`), y un examen nuevo arranca con la
     * lista en `Empty`. Si alguna píldora vuelve a poder ser `PENDING`, aparecerá
     * aquí sola y se medirá: el bucle recorre lo que salga, no una lista fija.
     *
     * No se miden todas las de [ExamFieldOrigin] porque no todas pueden pintarse
     * ahí: tipo, recuento, duración, año y «Oficial» no se resuelven contra un
     * catálogo, así que ninguna píldora es nunca `UNRESOLVED` (el rojo de un
     * vínculo roto va en los pares etiqueta/valor, que se miden sobre cristal en
     * `un valor se lee sea cual sea su procedencia`, junto con `PENDING`).
     */
    private fun origenesDeUnaPildoraDeMetadato(): Set<ExamFieldOrigin> {
        val ahora = kotlinx.datetime.Instant.parse("2026-04-15T09:00:00Z")
        val examen = com.oposicontrol.admin.domain.model.Exam(
            id = "ex-1", code = "EX-2026-0001", specialtyId = "esp-1", specialtyName = "TCAE", communityId = null,
            title = "TCAE · Simulacro", description = "", typeExam = "rafaga", typeAccess = "gratuito", priceTickets = 0,
            status = com.oposicontrol.admin.domain.model.ExamStatus.DRAFT, publishFrom = null, publishedAt = null,
            sortOrder = 0, questionCount = 3, durationMinutes = 45, year = 2026, isOfficial = true,
            coverUrl = null, coverStoragePath = null, coverMimeType = null, createdAt = ahora, updatedAt = ahora,
        )
        val abierto = ExamsState(
            selectedExamId = examen.id,
            editorTypeExam = "rafaga",
            editorDurationMinutes = "45",
            editorYear = "2026",
            editorIsOfficial = true,
        )
        val estados = listOf(
            ExamsState(editorTypeExam = "rafaga") to null,
            abierto.copy(questions = com.oposicontrol.admin.core.common.presentation.UiAsync.Loading) to examen,
            abierto.copy(
                questions = com.oposicontrol.admin.core.common.presentation.UiAsync.Error(
                    com.oposicontrol.admin.core.common.result.AppError.Network("sin red"),
                ),
            ) to examen,
            abierto.copy(questions = com.oposicontrol.admin.core.common.presentation.UiAsync.Empty) to examen,
            abierto.copy(questions = com.oposicontrol.admin.core.common.presentation.UiAsync.Success(emptyList())) to examen,
            // Un guardado sin duración: la ausencia en un examen EXISTENTE, no sólo en uno nuevo.
            abierto.copy(
                editorDurationMinutes = "",
                questions = com.oposicontrol.admin.core.common.presentation.UiAsync.Empty,
            ) to examen,
        )
        val origenes = estados.flatMap { (estado, abiertoONulo) -> examHeaderChips(estado, abiertoONulo).map { it.origin } }.toSet()
        // Sin esto, un cambio que vaciara la lista dejaría el bucle sin medir nada.
        // Se exige CADA procedencia que la cabecera puede pintar hoy, no «alguna».
        val esperadas = setOf(ExamFieldOrigin.VALUE, ExamFieldOrigin.ABSENT, ExamFieldOrigin.UNAVAILABLE)
        assertTrue(
            origenes.containsAll(esperadas),
            "Los estados de prueba ya no producen las procedencias esperadas en la cabecera: " +
                "faltan ${esperadas - origenes} (salen $origenes)",
        )
        return origenes
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Double, description: String) {
        val ratio = contrast(foreground, background)
        assertTrue(ratio >= minimum, "$description: ${round2(ratio)}:1, se requiere $minimum:1")
    }

    private fun Color.relativeLuminance(): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.relativeLuminance()
        val b = background.relativeLuminance()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** Compone [this] sobre [background] con su propio alpha. Lo que hace el renderer. */
    private fun Color.over(background: Color): Color = Color(
        red = red * alpha + background.red * (1f - alpha),
        green = green * alpha + background.green * (1f - alpha),
        blue = blue * alpha + background.blue * (1f - alpha),
    )

    private fun round2(value: Double): Double = (value * 100).toInt() / 100.0

    private companion object {
        /**
         * Las dos paletas vivas, leídas una sola vez por clase.
         *
         * Se comprueba que de verdad son dos: si `AdminTheme` ignorara el
         * parámetro, se mediría dos veces la misma paleta y la mitad de las
         * comprobaciones pasarían sin haberse hecho.
         */
        val temas: List<Pair<String, GlassTokens>> by lazy {
            val claro = tokensDelTema(dark = false)
            val oscuro = tokensDelTema(dark = true)
            assertFalse(claro.isDark, "AdminTheme(darkTheme = false) ha entregado la paleta oscura")
            assertTrue(oscuro.isDark, "AdminTheme(darkTheme = true) ha entregado la paleta clara")
            listOf("tema claro" to claro, "tema oscuro" to oscuro)
        }

        fun tokensDelTema(dark: Boolean): GlassTokens {
            var leidos: GlassTokens? = null
            runDesktopComposeUiTest(width = 320, height = 240) {
                setContent {
                    AdminTheme(darkTheme = dark) {
                        val tokens = GlassTheme.tokens
                        SideEffect { leidos = tokens }
                    }
                }
                waitForIdle()
            }
            return checkNotNull(leidos) { "AdminTheme no llegó a componer su contenido" }
        }
    }
}
