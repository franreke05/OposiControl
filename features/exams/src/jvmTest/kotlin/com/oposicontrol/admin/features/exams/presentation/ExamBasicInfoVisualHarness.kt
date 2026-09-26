package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.DesktopShell
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlinx.datetime.Instant
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Banco de pruebas visual de **Información básica**, en los dos temas.
 *
 * Complementa a [ExamWorkspaceVisualHarness], que cubre las cuatro pestañas a
 * grandes rasgos. Éste se centra en una sola y en sus estados difíciles: el
 * examen que todavía no existe, el que tiene cambios sin guardar, el que abre con
 * el catálogo de especialidades caído, el que tiene una comunidad real que el
 * catálogo no puede resolver, el recuento mientras se vuelve a contar y las tres
 * formas de la portada.
 *
 * **Qué es y qué no es.** Renderiza el workspace real —el mismo
 * [ExamWorkspaceContent] que corre en producción, dentro del mismo
 * `DesktopShell` y del mismo `AdminTheme`— contra un `ExamsState` fabricado, y
 * escribe PNG. Sirve para revisar cristal, fondo, jerarquía, densidad, tema y
 * responsive **sin levantar Ktor y sin arrancar la aplicación**.
 *
 * **NO valida datos.** Ni una línea toca un repositorio, una ruta ni un contrato.
 * Que una captura se vea bien no dice nada sobre si la pantalla lee bien de
 * Supabase.
 *
 * **Por qué el tema se pasa a mano.** `AdminTheme` resuelve por defecto con
 * `isSystemInDarkTheme()`, que aquí dependería de la configuración de la máquina
 * que genere las capturas: el mismo test daría PNG distintos en dos portátiles.
 * El parámetro existe exactamente para esto.
 *
 * Las capturas se escriben en `build/visual-harness/`, o en el directorio que
 * indique la variable de entorno `OPOSICONTROL_HARNESS_OUT` cuando se quieren
 * revisar fuera del árbol de compilación.
 */
@OptIn(ExperimentalTestApi::class)
class ExamBasicInfoVisualHarness {

    // -----------------------------------------------------------------------
    // Las siete capturas obligatorias (§47)
    // -----------------------------------------------------------------------

    @Test
    fun capturaLasSieteObligatorias() {
        render(Capture("exams-basic-1920-light-existing.png", 1920, 1080, dark = false), existenteCompleto())
        render(Capture("exams-basic-1920-light-new.png", 1920, 1080, dark = false), examenNuevoVacio())
        render(Capture("exams-basic-1920-dark-existing.png", 1920, 1080, dark = true), existenteCompleto())
        render(Capture("exams-basic-1920-dark-new.png", 1920, 1080, dark = true), examenNuevoVacio())
        // 1600 pliega el resumen a franja y da al editor el mismo ancho que en un
        // 1920. 1366 pierde además los iconos de las pestañas. 1080 baja del
        // umbral de 1100dp y apila las columnas: ahí el scroll vertical es la
        // respuesta correcta, no un defecto que haya que comprimir — la
        // tipografía, los paddings y las áreas de pulsación no se tocan.
        render(Capture("exams-basic-1600-light.png", 1600, 900, dark = false), existenteCompleto())
        render(Capture("exams-basic-1366-light.png", 1366, 768, dark = false), existenteCompleto())
        render(Capture("exams-basic-1080-light.png", 1080, 900, dark = false), existenteCompleto())
    }

    // -----------------------------------------------------------------------
    // Responsive obligatorias (P0-2, P1-1)
    //
    // La obligatoria de 1366 de arriba sólo cubría un examen existente limpio,
    // y fue justo en «nuevo» y en «sucio» donde el formulario se quedaba a 0 y a
    // 80px. Éstas cubren los tres estados en el portátil pequeño, el nuevo en
    // 1440, nuevo y sucio en 1600 —donde el resumen también se pliega a franja— y
    // el nuevo de 1366 en oscuro. Cada captura deja además sus medidas en
    // `metrics/<nombre>.txt`: alto real de cabecera, franja, formulario y barra.
    // -----------------------------------------------------------------------

    @Test
    fun capturaLasResponsivasObligatorias() {
        render(Capture("exams-basic-1366-light-new.png", 1366, 768, dark = false), examenNuevoVacio())
        render(Capture("exams-basic-1366-light-dirty.png", 1366, 768, dark = false), conCambiosSinGuardar())
        render(Capture("exams-basic-1366-light-clean.png", 1366, 768, dark = false), existenteCompleto())
        render(Capture("exams-basic-1440-light-new.png", 1440, 900, dark = false), examenNuevoVacio())
        render(Capture("exams-basic-1600-light-new.png", 1600, 900, dark = false), examenNuevoVacio())
        render(Capture("exams-basic-1600-light-dirty.png", 1600, 900, dark = false), conCambiosSinGuardar())
        render(Capture("exams-basic-1366-dark-new.png", 1366, 768, dark = true), examenNuevoVacio())
    }

    /**
     * El resto de la matriz de medida —1440 sucio y limpio, 1600 limpio— y las dos
     * interacciones que el arreglo promete: bajar con la rueda en 1366 (la
     * cabecera se va, la barra de guardar se queda) y abrir el detalle de la
     * franja (se superpone, no empuja).
     */
    @Test
    fun capturaElRestoDeLaMatrizResponsive() {
        render(Capture("exams-basic-1440-light-dirty.png", 1440, 900, dark = false), conCambiosSinGuardar())
        render(Capture("exams-basic-1440-light-clean.png", 1440, 900, dark = false), existenteCompleto())
        render(Capture("exams-basic-1600-light-clean.png", 1600, 900, dark = false), existenteCompleto())
        render(
            Capture("exams-basic-1366-light-new-desplazado.png", 1366, 768, dark = false, wheelOverTag = EXAM_EDITOR_FORM_TAG),
            examenNuevoVacio(),
        )
        render(
            Capture("exams-basic-1366-light-new-detalle.png", 1366, 768, dark = false, openText = EXAM_STRIP_DETAIL_LABEL),
            examenNuevoVacio(),
        )
    }

    /** Los dos anchos extremos, porque son baratos: un portátil pequeño y un monitor 2K. */
    @Test
    fun capturaLosAnchosExtremos() {
        render(Capture("exams-basic-1440-light.png", 1440, 900, dark = false), existenteCompleto())
        render(Capture("exams-basic-2560-light.png", 2560, 1440, dark = false), existenteCompleto())
    }

    // -----------------------------------------------------------------------
    // BASIC-01..09 (§45), en claro y en oscuro
    // -----------------------------------------------------------------------

    @Test
    fun capturaLosEstadosBasic() {
        val estados = listOf(
            "BASIC-01-nuevo-vacio" to examenNuevoVacio(),
            "BASIC-02-existente-completo" to existenteCompleto(),
            "BASIC-03-cambios-sin-guardar" to conCambiosSinGuardar(),
            "BASIC-04-error-especialidades" to especialidadesCaidas(),
            "BASIC-05-nacional" to nacionalDeVerdad(),
        )
        // Los escenarios de portada se retiran con la sección (2026-09-23): la
        // portada se compone con los componentes de la app y ya no se sube un
        // archivo desde aquí. No se sustituyen por capturas equivalentes porque
        // no hay nada equivalente que capturar.
        estados.forEach { (id, state) ->
            // Las dos de portada bajan hasta el bloque: con el alto de 1080 el
            // editor enseña Identificación y Ámbito, y la portada queda debajo.
            val scroll = EXAM_COVER_SUBTITLE.takeIf { id.contains("portada") }
            render(Capture("exams-$id-light.png", 1920, 1080, dark = false, scrollTo = scroll), state)
            render(Capture("exams-$id-dark.png", 1920, 1080, dark = true, scrollTo = scroll), state)
        }
        render(Capture("exams-BASIC-08-dark-nuevo.png", 1920, 1080, dark = true), examenNuevoVacio())
        render(Capture("exams-BASIC-09-dark-existente.png", 1920, 1080, dark = true), existenteCompleto())
    }

    // -----------------------------------------------------------------------
    // Estados que el rediseño tiene que resolver sin mentir
    // -----------------------------------------------------------------------

    @Test
    fun capturaLosEstadosDelRediseno() {
        // Comunidad real con el catálogo caído: el campo queda inerte y dice que
        // conserva la guardada. Es el caso que antes anulaba el `comunidad_id`
        // con dos clics.
        render(
            Capture("exams-basic-1920-light-comunidad-no-disponible.png", 1920, 1080, dark = false),
            comunidadNoDisponible(),
        )
        // Especialidades caídas en un examen NUEVO: sin catálogo no se puede
        // elegir, y sin especialidad no se puede crear. Se dice en la celda.
        render(
            Capture("exams-basic-1920-light-error-especialidades-nuevo.png", 1920, 1080, dark = false),
            nuevoConEspecialidadesCaidas(),
        )
        // Recuento mientras se vuelven a contar las preguntas (D1): desde K2-2 la
        // cifra es un dato. Sin «Antes de publicar»: contar no es un problema (P2-6).
        render(
            Capture("exams-basic-1920-light-contando-preguntas.png", 1920, 1080, dark = false),
            contandoConProvisional(),
        )
        // Código que ya usa otro examen (P1-3): comprobación que bloquea, apoyo
        // del campo que dice con quién choca y «Guardar cambios» deshabilitado.
        render(
            Capture("exams-basic-1920-light-codigo-repetido.png", 1920, 1080, dark = false),
            conCodigoRepetido(),
        )
        // Duración de 0 minutos (P2-7): «0 min», no «Sin duración», y el aviso.
        render(
            Capture("exams-basic-1920-light-duracion-cero.png", 1920, 1080, dark = false),
            conDuracionCero(),
        )
        // El guardado rechazado por `examenes_total_preguntas_check`.
        //
        // La captura prueba el mecanismo COMPLETO, no sólo el color: el estado se
        // monta con la pestaña inicial en Información básica —la que abre el
        // formulario—, así que si en el PNG se ve Configuración es porque el
        // rechazo ha abierto la pestaña del campo culpable él solo. Y en ella,
        // «Número de preguntas» con borde y apoyo rojos, y el mensaje del backend
        // debajo del campo además de al pie.
        listOf(false, true).forEach { dark ->
            render(
                Capture(
                    "exams-basic-1920-${if (dark) "dark" else "light"}-recuento-rechazado.png",
                    1920,
                    1080,
                    dark = dark,
                ),
                creacionRechazadaPorElRecuento(),
            )
        }
        // Examen nuevo Inmersivo sin comunidad: la portada provisional y su explicación.
        listOf(false, true).forEach { dark ->
            render(
                Capture(
                    "exams-basic-1920-${if (dark) "dark" else "light"}-portada-nueva.png",
                    1920,
                    1080,
                    dark = dark,
                    scrollTo = EXAM_COVER_SUBTITLE,
                ),
                examenNuevoConPortadaElegida(),
            )
        }
    }

    /**
     * **Las dos confirmaciones y el rechazo del borrado.**
     *
     * Es lo único que separa «retirar del catálogo» de «no existe nunca más» en
     * el momento en que se decide, así que las dos confirmaciones se miran juntas:
     * el título, el mensaje y —sobre todo— las dos etiquetas de los botones, que
     * aquí no dicen «Aceptar/Cancelar» sino lo que pasa con cada una.
     *
     * La tercera captura es el 409 del servidor —«los usuarios ya lo han
     * usado»— presentado como aviso de la ACCIÓN, en la pestaña Publicación y
     * pegado al botón que lo provocó, no como error de campo al pie del
     * formulario. Ver `examDeletionNotice`.
     */
    @Test
    fun capturaElBorradoYSusConfirmaciones() {
        listOf(false, true).forEach { dark ->
            val tema = if (dark) "dark" else "light"
            render(
                Capture("exams-borrado-1920-$tema-confirmar-archivar.png", 1920, 1080, dark = dark, overlay = true),
                confirmandoArchivar(),
            )
            render(
                Capture("exams-borrado-1920-$tema-confirmar-eliminar.png", 1920, 1080, dark = dark, overlay = true),
                confirmandoEliminar(),
            )
            render(
                Capture(
                    "exams-borrado-1920-$tema-rechazado-datos-usuario.png",
                    1920,
                    1080,
                    dark = dark,
                    // El aviso queda bajo el pliegue en 1080: la pestaña abre por
                    // Estado actual y las dos secciones de acciones van antes. Se
                    // desplaza hasta él, que es lo que hace quien acaba de pulsar.
                    //
                    // Y se desplaza hasta el DETALLE, no hasta el título:
                    // `performScrollTo` deja el nodo justo dentro del recorte, así
                    // que apuntando al título el mensaje —que es lo que hay que
                    // leer— se quedaba fuera por debajo.
                    scrollTo = RECHAZO_POR_DATOS_DE_USUARIO,
                    initialTab = ExamWorkspaceTabId.PUBLICACION,
                ),
                borradoRechazadoPorDatosDeUsuario(),
            )
        }
    }

    /**
     * Un desplegable ABIERTO, en los dos temas.
     *
     * Los selectores de Especialidad y Comunidad abren un `DropdownMenu` de
     * Material, y ése toma su fondo del esquema de Material (`surfaceContainer`),
     * no de los tokens: si el esquema no lo mapea, el menú sale con el gris lila
     * por defecto de Material, en claro y en oscuro. Se abre el de Especialidad de
     * un examen nuevo —el que se usa sí o sí para crear uno— y se captura la
     * ventana con el menú encima; el menú también se guarda solo, recortado.
     */
    @Test
    fun capturaUnDesplegableAbierto() {
        listOf(false, true).forEach { dark ->
            render(
                Capture(
                    "exams-basic-1920-${if (dark) "dark" else "light"}-desplegable-especialidad.png",
                    1920,
                    1080,
                    dark = dark,
                    openText = "Selecciona una especialidad",
                ),
                examenNuevoVacio(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Motor de captura
    // -----------------------------------------------------------------------

    private data class Capture(
        val fileName: String,
        val width: Int,
        val height: Int,
        val dark: Boolean,
        /**
         * Texto al que desplazar el editor antes de disparar la captura.
         *
         * El contenido de la pestaña desplaza por dentro de su propio panel, así
         * que los bloques de abajo —Portada— quedan fuera del recorte en una
         * ventana de 1080. Sin esto, las capturas «con portada» y «sin portada»
         * enseñaban las dos exactamente lo mismo: el bloque de Identificación.
         */
        val scrollTo: String? = null,
        /**
         * Texto EXACTO de un control al que hacer clic antes de capturar, para
         * abrir lo que despliegue (un menú). Con un menú abierto la escena tiene
         * dos raíces —la ventana y el popup—, así que la captura se toma de la
         * primera y el menú se guarda además solo, con el sufijo `-menu`.
         */
        val openText: String? = null,
        /**
         * Etiqueta de prueba sobre la que girar la rueda del ratón antes de
         * capturar. Es el gesto real —pasa por el mismo camino de desplazamiento
         * anidado que en la aplicación—, no un salto de scroll programado.
         */
        val wheelOverTag: String? = null,
        /**
         * La escena ya trae una capa encima SIN tocar nada: un diálogo que el
         * propio estado abre (`pendingDeleteTarget`).
         *
         * Hace falta su propia bandera y no vale [openText]: aquél CLICA para
         * abrir un menú, y estas confirmaciones no se abren clicando —el estado
         * llega con ellas puestas, que es como se fabrica un estado en este
         * banco—. Lo que sí comparte con él es el problema que resuelve:
         * `onRoot()` exige una sola raíz y con un diálogo hay dos, así que se
         * captura la ventana y, aparte, la capa.
         */
        val overlay: Boolean = false,
        /**
         * Con qué pestaña abre el workspace. Por defecto Información básica, que
         * es de lo que va este banco; el aviso del borrado vive en Publicación.
         */
        val initialTab: ExamWorkspaceTabId = ExamWorkspaceTabId.BASICA,
    )

    private fun render(capture: Capture, state: ExamsState) {
        runDesktopComposeUiTest(width = capture.width, height = capture.height) {
            setContent {
                // Densidad 1: así 1 dp es 1 px y el PNG mide exactamente lo que
                // dice su nombre. Sin esto la captura dependería del escalado de
                // la pantalla de quien la genere.
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    ExamBasicInfoHarnessShell(
                        state = state,
                        dark = capture.dark,
                        initialTab = capture.initialTab,
                    )
                }
            }
            waitForIdle()
            // Las fuentes de `compose-resources` —y la vista previa de portada,
            // que se decodifica en `Dispatchers.IO`— terminan en corrutinas que el
            // reloj de test no rastrea. Sin esta espera, la primera captura sale
            // con la tipografía del sistema y sin la imagen.
            Thread.sleep(SETTLE_MILLIS)
            waitForIdle()

            capture.scrollTo?.let { texto ->
                onNodeWithText(texto, substring = false).performScrollTo()
                waitForIdle()
            }
            capture.wheelOverTag?.let { tag ->
                onNodeWithTag(tag).performMouseInput {
                    moveTo(center)
                    scroll(HARNESS_WHEEL_DELTA)
                }
                waitForIdle()
            }

            // Las medidas de la columna del editor sobre el render, en px (densidad
            // 1): son las que se comparan con la especificación. `y=` y `alto=` son
            // lo VISIBLE (recortado); entre corchetes, dónde está y cuánto mide de
            // verdad cuando no coincide: una cabecera que se ha ido por arriba o un
            // bloque que queda bajo el pliegue.
            fun medidas(): String = listOf(
                "cabecera" to EXAM_HEADER_TAG,
                "franja" to EXAM_SUMMARY_STRIP_TAG,
                "formulario" to EXAM_EDITOR_FORM_TAG,
                "barra" to EXAM_SAVE_BAR_TAG,
            ).joinToString(" | ") { (nombre, tag) ->
                onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.let { nodo ->
                    val r = nodo.boundsInRoot
                    val visible = "$nombre y=${r.top.roundToInt()}..${r.bottom.roundToInt()} alto=${r.height.roundToInt()}"
                    val arriba = nodo.positionInRoot.y.roundToInt()
                    if (arriba == r.top.roundToInt() && nodo.size.height == r.height.roundToInt()) {
                        visible
                    } else {
                        "$visible [real y=$arriba alto=${nodo.size.height}]"
                    }
                } ?: "$nombre —"
            }
            val metrics = File(outputDir, "metrics").also { it.mkdirs() }
            val lineas = mutableListOf("${capture.fileName} ${capture.width}x${capture.height} | ${medidas()}")

            val file = File(outputDir, capture.fileName)
            if (capture.overlay) {
                // Con un diálogo en escena hay dos raíces y `onRoot()` exige una,
                // así que se toma la primera explícitamente. **No se guarda además
                // el diálogo recortado**, como sí hace el camino de
                // [Capture.openText] con los menús: en escritorio el diálogo se
                // pinta DENTRO de la ventana —se ve en la captura, sobre su velo—
                // y las dos raíces dan el mismo píxel. Se comprobó: los dos PNG
                // salían byte a byte iguales.
                val raices = onAllNodes(isRoot())
                val cuantas = raices.fetchSemanticsNodes().size
                assertTrue(cuantas >= 2, "El estado no ha abierto ningún diálogo ($cuantas raíz)")
                File(metrics, capture.fileName.removeSuffix(".png") + ".txt").writeText(lineas.joinToString("\n") + "\n")
                ImageIO.write(raices[0].captureToImage().toAwtImage(), "png", file)
            } else if (capture.openText == null) {
                val bitmap = onRoot().captureToImage()
                ImageIO.write(bitmap.toAwtImage(), "png", file)
                File(metrics, capture.fileName.removeSuffix(".png") + ".txt").writeText(lineas.joinToString("\n") + "\n")
            } else {
                onNodeWithText(capture.openText, substring = false).performClick()
                waitForIdle()
                lineas += "  tras abrir «${capture.openText}» | ${medidas()}"
                File(metrics, capture.fileName.removeSuffix(".png") + ".txt").writeText(lineas.joinToString("\n") + "\n")
                val raices = onAllNodes(isRoot())
                val cuantas = raices.fetchSemanticsNodes().size
                assertTrue(cuantas >= 2, "El clic en «${capture.openText}» no ha abierto ningún menú ($cuantas raíz)")
                ImageIO.write(raices[0].captureToImage().toAwtImage(), "png", file)
                val menu = File(outputDir, capture.fileName.removeSuffix(".png") + "-menu.png")
                ImageIO.write(raices[cuantas - 1].captureToImage().toAwtImage(), "png", menu)
                assertTrue(menu.exists() && menu.length() > 0L, "No se escribió ${menu.absolutePath}")
            }

            assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
        }
    }

    private val outputDir: File by lazy {
        val override = System.getenv("OPOSICONTROL_HARNESS_OUT")?.takeIf { it.isNotBlank() }
        File(override ?: "build/visual-harness").also { it.mkdirs() }
    }

}

/**
 * Cuánto gira la rueda en las capturas y pruebas que desplazan. Medido: 10 unidades
 * desplazan ~91px; 30 sobran para que la cabecera y la franja (245px en 1366) se
 * vayan enteras y la captura enseñe el estado final, no uno a medias.
 */
internal const val HARNESS_WHEEL_DELTA: Float = 30f

/**
 * El workspace dentro de su marco real, con el tema FIJADO.
 *
 * `DesktopShell` de verdad —no una imitación— porque el ancho de contenido es lo
 * que decide el reparto de columnas y el alto de la columna del editor: la
 * ventana menos la sidebar, la cabecera de página y los márgenes. Lo comparten el
 * banco visual y `ExamWorkspaceResponsiveTest`, que mide sobre el mismo marco.
 */
@Composable
internal fun ExamBasicInfoHarnessShell(
    state: ExamsState,
    dark: Boolean,
    initialTab: ExamWorkspaceTabId = ExamWorkspaceTabId.BASICA,
) {
    AdminTheme(darkTheme = dark) {
        DesktopShell(
            currentRouteId = "content.exams",
            groups = HARNESS_SIDEBAR,
            onRouteSelected = {},
            environmentLabel = "Entorno dev | vista frontend",
            title = "Exámenes",
            description = "Catálogo, preguntas y publicación de exámenes.",
            breadcrumbs = listOf("Contenido", "Exámenes"),
            // La etiqueta real de la acción de página (`AppRoot`): abre un
            // borrador y no crea nada, así que no se llama «Crear examen».
            primaryActionLabel = "Nuevo examen",
            onPrimaryAction = {},
            globalSearch = {},
        ) {
            ExamWorkspaceContent(
                state = state,
                onIntent = {},
                onOpenQuestionBank = {},
                initialTab = initialTab,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Estados de demostración
//
// Se declaran aparte de los de `ExamWorkspaceVisualHarness` porque prueban otra
// cosa: allí el fixture busca un catálogo lleno y realista, aquí lo que importa
// son los estados de carga y de fallo de los catálogos que esta pestaña consulta.
// ---------------------------------------------------------------------------

private const val SETTLE_MILLIS = 2_000L
private val AHORA = Instant.parse("2026-04-15T09:00:00Z")

private fun especialidad(id: String, name: String, order: Int) = Specialty(
    id = id,
    name = name,
    description = "",
    isActive = true,
    sortOrder = order,
    linkedExams = 4,
    createdAt = AHORA,
    updatedAt = AHORA,
)

private val ESPECIALIDADES = listOf(
    especialidad("esp-tcae", "TCAE", 0),
    especialidad("esp-enfermeria", "Enfermería", 1),
    especialidad("esp-celador", "Celador", 2),
)

private val COMUNIDADES = listOf(
    Community("com-an", "Andalucía", "AN", "andalucia", true, 0, AHORA, AHORA),
    Community("com-md", "Madrid", "MD", "madrid", true, 1, AHORA, AHORA),
    Community("com-ct", "Cataluña", "CT", "cataluna", true, 2, AHORA, AHORA),
)

private val CAPACIDADES = SpecialtyCapabilities(
    permiteRafagas = true,
    permiteRazonados = true,
    permiteInmersivos = true,
    permiteDemos = false,
    permiteOposimarket = false,
    specialtyId = "esp-tcae",
    limitationMessage = null,
    configured = true,
)

private fun examen(
    id: String,
    code: String,
    title: String,
    communityId: String? = null,
    status: ExamStatus = ExamStatus.DRAFT,
    questionCount: Int = 5,
    coverUrl: String? = null,
) = Exam(
    id = id,
    code = code,
    specialtyId = "esp-tcae",
    specialtyName = "TCAE",
    communityId = communityId,
    title = title,
    description = "Simulacro completo con el temario de la última convocatoria.",
    typeExam = "rafaga",
    typeAccess = "gratuito",
    priceTickets = 0,
    status = status,
    publishFrom = null,
    publishedAt = if (status == ExamStatus.PUBLISHED) AHORA else null,
    sortOrder = 0,
    questionCount = questionCount,
    durationMinutes = 45,
    year = 2026,
    isOfficial = false,
    coverUrl = coverUrl,
    // Una ruta derivada de la URL del fixture. La forma real desde PW-8 es
    // `exam-covers/<examen_id>/<uuid>.<ext>`; para la captura basta con que exista.
    coverStoragePath = coverUrl?.let { "exam-covers/$id/${it.substringAfterLast('/')}" },
    coverMimeType = coverUrl?.let { "image/png" },
    createdAt = AHORA,
    updatedAt = AHORA,
)

/**
 * Catálogo corto pero suficiente: dos comunidades reales, un nacional y un
 * publicado, para que la lista de la izquierda no sea una columna de gemelos.
 */
private val CATALOGO: List<Exam> = listOf(
    examen("ex-1", "EX-2026-0001", "TCAE · Simulacro marzo", communityId = "com-an"),
    examen("ex-2", "EX-2026-0002", "TCAE · Simulacro abril", status = ExamStatus.PUBLISHED),
    examen("ex-3", "EX-2026-0003", "TCAE · Madrid 2026", communityId = "com-md"),
    examen("ex-4", "EX-2026-0004", "TCAE · Repaso exprés", questionCount = 0),
    examen("ex-5", "EX-2026-0005", "TCAE · Higiene hospitalaria", status = ExamStatus.ARCHIVED),
)

/** Cinco preguntas de verdad, para que el recuento de la cabecera sea un número. */
private val PREGUNTAS: List<ExamQuestion> = (1..5).map { index ->
    ExamQuestion(
        id = "q-$index",
        examId = "ex-1",
        title = "Pregunta $index del simulacro",
        statement = "Pregunta $index del simulacro",
        sortOrder = index - 1,
        options = listOf("A", "B", "C", "D").mapIndexed { position, label ->
            QuestionOption(
                id = "q-$index-$label",
                questionId = "q-$index",
                label = label,
                content = "Opción $label",
                isCorrect = position == 0,
                sortOrder = position,
            )
        },
        resources = emptyList(),
        createdAt = AHORA,
        updatedAt = AHORA,
    )
}

/**
 * El editor tal y como lo deja `selectExam`: los campos copiados del examen, así
 * que la barra inferior no anuncia cambios que nadie ha hecho.
 */
private fun abierto(
    exam: Exam,
    specialties: UiAsync<List<Specialty>> = UiAsync.Success(ESPECIALIDADES),
    communities: UiAsync<List<Community>> = UiAsync.Success(COMUNIDADES),
    questions: UiAsync<List<ExamQuestion>> = UiAsync.Success(PREGUNTAS),
) = ExamsState(
    specialties = specialties,
    exams = UiAsync.Success(CATALOGO),
    catalogExams = CATALOGO,
    questions = questions,
    communities = communities,
    specialtyCapabilities = UiAsync.Success(CAPACIDADES),
    statusFilter = "all",
    selectedExamId = exam.id,
    editorTitle = exam.title,
    editorDescription = exam.description,
    editorCode = exam.code,
    editorSpecialtyId = exam.specialtyId,
    editorCommunityId = exam.communityId,
    editorTypeExam = exam.typeExam,
    editorStatus = exam.status.name.lowercase(),
    editorTypeAccess = exam.typeAccess,
    editorPriceTickets = exam.priceTickets.toString(),
    editorYear = exam.year?.toString().orEmpty(),
    editorDurationMinutes = exam.durationMinutes?.toString().orEmpty(),
    editorIsOfficial = exam.isOfficial,
)

/** BASIC-02 · examen guardado, con comunidad real, preguntas y sin nada pendiente. */
internal fun existenteCompleto(): ExamsState = abierto(CATALOGO[0])

/**
 * BASIC-01 · examen nuevo, con el editor como lo deja `resetExamEditor`.
 *
 * La especialidad viene EN BLANCO y las capacidades en `Idle` (D15): el ViewModel
 * ya no preselecciona la primera del catálogo en un borrador nuevo. Lo hacía en
 * silencio, y un examen podía crearse en una especialidad que nadie había
 * elegido.
 */
internal fun examenNuevoVacio(): ExamsState = ExamsState(
    specialties = UiAsync.Success(ESPECIALIDADES),
    exams = UiAsync.Success(CATALOGO),
    catalogExams = CATALOGO,
    questions = UiAsync.Empty,
    communities = UiAsync.Success(COMUNIDADES),
    specialtyCapabilities = UiAsync.Idle,
    statusFilter = "all",
    selectedExamId = null,
    editorSpecialtyId = "",
    editorTypeExam = "rafaga",
    editorStatus = "draft",
    editorTypeAccess = "gratuito",
    editorPriceTickets = "0",
)

/** BASIC-03 · título y duración tocados: la barra se vuelve prominente y ofrece Descartar. */
internal fun conCambiosSinGuardar(): ExamsState = abierto(CATALOGO[0]).copy(
    editorTitle = "TCAE · Simulacro marzo (revisado)",
    editorDurationMinutes = "90",
)

/**
 * El código de otro examen escrito en éste (P1-3): «ex-2026-0002», en minúsculas,
 * contra el «EX-2026-0002» de `ex-2`. Cambia el código, así que la barra está
 * sucia, y aun así «Guardar cambios» queda deshabilitado.
 */
private fun conCodigoRepetido(): ExamsState = abierto(CATALOGO[0]).copy(editorCode = "ex-2026-0002")

/** Duración de 0 minutos escrita en un examen guardado (P2-7). */
private fun conDuracionCero(): ExamsState = abierto(CATALOGO[0]).copy(editorDurationMinutes = "0")

/**
 * Un guardado rechazado por `examenes_total_preguntas_check`.
 *
 * Desde PW-2 la columna exige `>= 0` y, desde M1, la API no la escribe: sólo un
 * negativo llegado por otra vía dispara el 422 `EXAM_QUESTION_COUNT_INVALID`. La
 * captura se conserva porque el campo señalado sigue existiendo. El mensaje es el
 * del backend, LITERAL (K1-3): aquí no se redacta nada, y si alguien lo cambia
 * allí esta captura deja de corresponder — que es lo que se quiere.
 */
private fun creacionRechazadaPorElRecuento(): ExamsState = examenNuevoVacio().copy(
    editorTitle = "TCAE · Simulacro mayo",
    editorSpecialtyId = "esp-tcae",
    specialtyCapabilities = UiAsync.Success(CAPACIDADES),
    actionState = UiAsync.Error(
        AppError.Validation(
            message = "El número de preguntas no puede ser negativo.",
            code = EXAM_ERROR_QUESTION_COUNT_INVALID,
        ),
    ),
)

// ---------------------------------------------------------------------------
// Borrar ≠ archivar: los tres estados que lo deciden
//
// Los dos diálogos se fabrican poniendo el `pendingDeleteTarget` y su mensaje, que
// es exactamente lo que hace el ViewModel al recibir la petición: el texto sale de
// las mismas constantes que usa la aplicación, no de literales del banco, así que
// una captura no puede enseñar una redacción que ya no exista.
// ---------------------------------------------------------------------------

/** Confirmación de ARCHIVAR: reversible, y las etiquetas lo dicen. */
private fun confirmandoArchivar(): ExamsState = abierto(exam = CATALOGO[0]).copy(
    pendingDeleteTarget = PendingDeleteTarget.ArchiveExam,
    pendingDeleteMessage = EXAM_ARCHIVE_WARNING,
)

/** Confirmación de ELIMINAR: irreversible, más seria, y con «Sí, eliminar / No eliminar». */
private fun confirmandoEliminar(): ExamsState = abierto(exam = CATALOGO[0]).copy(
    pendingDeleteTarget = PendingDeleteTarget.DeleteExamForever,
    pendingDeleteMessage = EXAM_DELETE_FOREVER_WARNING,
)

/**
 * El 409 del servidor: el examen tiene datos de usuarios y NO se ha borrado.
 *
 * El mensaje es el que redacta el backend, con las categorías que encontró. Se
 * pinta en la pestaña Publicación, bajo el botón que lo provocó, y no al pie del
 * formulario: no hay ningún campo que corregir.
 */
private fun borradoRechazadoPorDatosDeUsuario(): ExamsState = abierto(exam = CATALOGO[0]).copy(
    examDeleteState = UiAsync.Error(
        AppError.Validation(
            message = RECHAZO_POR_DATOS_DE_USUARIO,
            // En minúsculas, como lo publica el backend.
            code = "exam_has_user_data",
        ),
    ),
)

/**
 * El mensaje tal y como lo compone `examHasUserDataException` con tres categorías
 * colgando. Copiado del backend a propósito: esta captura sirve para ver si ese
 * texto CABE y se lee, y para eso tiene que ser el texto real y no uno de muestra.
 */
private const val RECHAZO_POR_DATOS_DE_USUARIO: String =
    "No se puede borrar este examen porque los usuarios ya lo han usado: hay intentos de usuarios, " +
        "respuestas contestadas y compras o desbloqueos de contenido. Archívalo en su lugar: deja de " +
        "aparecer en la app y el historial de los usuarios se conserva."

/**
 * BASIC-04 · el catálogo de especialidades no ha cargado.
 *
 * El examen sigue teniendo la suya —`especialidad_id` es `NOT NULL`— y su nombre
 * llega con el propio examen, así que la cabecera, la ficha y la tarjeta del
 * catálogo dicen lo mismo: «TCAE». El campo enseña el fallo en su propio hueco,
 * con «Reintentar».
 */
private fun especialidadesCaidas(): ExamsState = abierto(
    exam = CATALOGO[0],
    specialties = UiAsync.Error(AppError.Network("No se ha podido conectar con el servidor.")),
)

/** BASIC-05 · nacional de verdad: sin `comunidad_id` y con el catálogo cargado. */
private fun nacionalDeVerdad(): ExamsState = abierto(CATALOGO[1])

/**
 * Comunidad real con el catálogo caído: el caso que antes borraba el dato.
 *
 * Se etiquetaba «Nacional» y el desplegable ofrecía «Nacional» como única
 * opción; dos clics anulaban un `comunidad_id` de verdad.
 */
private fun comunidadNoDisponible(): ExamsState = abierto(
    exam = CATALOGO[0],
    communities = UiAsync.Error(AppError.Network("La tabla de comunidades no responde.")),
)

/**
 * BASIC-06 · portada automática de un examen Inmersivo (vector de su comunidad si la tiene).
 *
 * El examen modificado se REEMPLAZA dentro del catalogo, no solo se abre: un `copy()` suelto
 * deja el catalogo con el tipo anterior y el editor con el nuevo, así que `hasUnsavedExamChanges`
 * lo declara sucio y la captura sale con la barra avisando de cambios que nadie ha hecho.
 */
private fun conPortadaGuardada(): ExamsState {
    val conPortada = CATALOGO[0].copy(typeExam = "inmersivo")
    val catalogo = CATALOGO.map { if (it.id == conPortada.id) conPortada else it }
    return abierto(conPortada).copy(exams = UiAsync.Success(catalogo), catalogExams = catalogo)
}

/** Examen nuevo de tipo Inmersivo: la previsualización de portada avisa de que falta la comunidad. */
private fun examenNuevoConPortadaElegida(): ExamsState = examenNuevoVacio().copy(
    editorTitle = "TCAE · Simulacro mayo",
    editorSpecialtyId = "esp-tcae",
    specialtyCapabilities = UiAsync.Success(CAPACIDADES),
    editorTypeExam = "inmersivo",
)

/** Especialidades caídas en un examen NUEVO: sin catálogo no hay forma de elegir una. */
private fun nuevoConEspecialidadesCaidas(): ExamsState = examenNuevoVacio().copy(
    specialties = UiAsync.Error(AppError.Network("No se ha podido conectar con el servidor.")),
)

/**
 * Las preguntas se están volviendo a contar (D1).
 *
 * La cifra que se ve es la del último recuento de la sesión y, desde K2-2, se
 * pinta como un dato: la base mantiene `total_preguntas` con la misma definición
 * que la lista. Sale en la cabecera y en el resumen, que son los dos sitios que la
 * cuentan desde que las pestañas no llevan insignia.
 */
private fun contandoConProvisional(): ExamsState = abierto(
    exam = CATALOGO[0],
    questions = UiAsync.Loading,
).copy(countedQuestions = mapOf("ex-1" to 5))
