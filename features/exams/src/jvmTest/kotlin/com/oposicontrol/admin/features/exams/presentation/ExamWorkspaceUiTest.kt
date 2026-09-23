package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lo que sólo se puede comprobar RENDERIZANDO.
 *
 * Este fichero existe por una lección concreta: una prueba de mutación cambió
 * el recuento de la tarjeta del catálogo por un `999` literal y los 73 tests del
 * módulo siguieron verdes. Todos eran de función pura, y una función pura no
 * puede saber si alguien la ha dejado de llamar desde el composable. La única
 * defensa contra eso es montar la pantalla y leer lo que pone.
 *
 * Se queda deliberadamente pequeño. No pretende ser una batería de pruebas de
 * UI: cubre los invariantes que ya se rompieron una vez —dos sitios que tienen
 * que decir el mismo número, y una identidad que no puede depender de un filtro—
 * y lo que una función pura no puede ver: que una acción inmediata pida
 * confirmación antes de dispararse.
 */
@OptIn(ExperimentalTestApi::class)
class ExamWorkspaceUiTest {

    /**
     * La tarjeta del catálogo y la cabecera dicen el MISMO número.
     *
     * Es el invariante que la mutación rompió sin que nadie se enterara. Con dos
     * fuentes —`exam.questionCount` en la tarjeta y `linkedQuestionCount` en la
     * cabecera— divergían de forma garantizada en cuanto se desvinculaba una
     * pregunta, porque desvincular recargaba las preguntas y no tocaba la lista.
     *
     * `onAllNodesWithText` con el texto exacto: si los dos sitios coinciden hay
     * al menos dos nodos, y si uno se sale de la fuente común hay uno.
     */
    @Test
    fun elCatalogoYLaCabeceraDicenElMismoRecuentoDePreguntas() {
        renderWorkspace(state = withQuestions(3)) {
            // La cabecera dice la forma larga y la tarjeta del catálogo la corta
            // —«3 preguntas vinculadas» frente a «3 preguntas», porque en 330dp la
            // larga elidía la duración—. Lo que tiene que coincidir es el NÚMERO,
            // así que se comprueba que las dos formas del mismo recuento estén en
            // pantalla. Un `999` en cualquiera de los dos sitios deja una de las
            // dos sin aparecer.
            val header = linkedQuestionsText(3)
            // La línea de meta de la tarjeta se compone entera en un solo nodo, así
            // que se reconstruye igual que la pinta el catálogo. Buscar sólo «3
            // preguntas» como subcadena no serviría: la cabecera dice «3 preguntas
            // vinculadas», que la contiene, y una tarjeta mutada a 999 pasaría.
            val cardMeta = listOfNotNull(
                ExamTypeOption.labelOf("rafaga"),
                linkedQuestionsShortText(3),
                durationLabel(45),
            ).joinToString(" · ")

            assertTrue(
                onAllNodesWithText(header, substring = false).fetchSemanticsNodes().isNotEmpty(),
                "Falta «$header»: la cabecera ha dejado de leer del recuento común.",
            )
            assertTrue(
                onAllNodesWithText(cardMeta, substring = false).fetchSemanticsNodes().isNotEmpty(),
                "Falta «$cardMeta»: la tarjeta del catálogo ha dejado de leer del recuento común.",
            )
        }
    }

    /**
     * Buscar en el catálogo no le quita la identidad al examen abierto.
     *
     * Con la lista filtrada a cero, la cabecera conservaba el título pero perdía
     * el chip de estado, y Publicación pasaba a decirle «Guarda el examen para
     * poder publicarlo» a un examen publicado. Aquí se comprueba lo contrario:
     * con `exams` vacío y `catalogExams` lleno, el estado sigue en pantalla.
     */
    @Test
    fun buscarSinResultadosNoBorraElEstadoDelExamenAbierto() {
        val filtrado = withQuestions(3).copy(
            exams = UiAsync.Empty,
            searchQuery = "oftalmología",
        )
        renderWorkspace(state = filtrado) {
            // `onAllNodesWithText` y no `onNodeWithText`: «Publicado» sale dos
            // veces —el chip de la cabecera y la ficha del resumen— y lo que se
            // comprueba es que el estado NO ha desaparecido, no cuántas veces se
            // dice.
            assertTrue(
                onAllNodesWithText("Publicado").fetchSemanticsNodes().isNotEmpty(),
                "El examen abierto ha perdido su estado porque la búsqueda no lo encuentra.",
            )
        }
    }

    /** Y con el examen abierto invisible en la lista, Publicación conserva sus acciones. */
    @Test
    fun buscarSinResultadosNoRetiraLasAccionesDePublicacion() {
        val filtrado = withQuestions(3).copy(
            exams = UiAsync.Empty,
            searchQuery = "oftalmología",
        )
        renderWorkspace(state = filtrado, tab = ExamWorkspaceTabId.PUBLICACION) {
            onNodeWithText("Despublicar").assertIsDisplayed()
            assertEquals(
                0,
                onAllNodesWithText("Guarda el examen para poder publicarlo.").fetchSemanticsNodes().size,
                "Le está diciendo a un examen publicado que lo guarde antes de publicarlo.",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Publicación: acciones inmediatas con confirmación
    // -----------------------------------------------------------------------

    /**
     * «Publicar ahora» es inmediato y por eso pide confirmación: el primer clic abre
     * el diálogo y NO publica; sólo confirmar despacha `PublishExam`.
     */
    @Test
    fun publicarPideConfirmacionAntesDePublicar() {
        val borrador = exam.copy(status = ExamStatus.DRAFT, publishedAt = null, questionCount = 3)
        val estado = withQuestions(3).copy(
            exams = UiAsync.Success(listOf(borrador)),
            catalogExams = listOf(borrador),
            editorStatus = "draft",
        )
        val intents = mutableListOf<ExamsIntent>()
        renderWorkspace(state = estado, tab = ExamWorkspaceTabId.PUBLICACION, onIntent = { intents += it }) {
            onNodeWithText("Publicar ahora").performClick()
            waitForIdle()
            assertTrue(ExamsIntent.PublishExam !in intents, "Ha publicado sin confirmar")
            onNodeWithText("Publicar examen").assertIsDisplayed()

            onNodeWithText("Publicar").performClick()
            waitForIdle()
            assertTrue(ExamsIntent.PublishExam in intents, "Confirmar no ha publicado")
        }
    }

    /**
     * K-DEC01: un borrador con la lista cargada y vacía no puede publicarse, y el
     * botón lo dice apagado y con la regla al lado. Una función pura sabe que está
     * vetado; lo que no puede ver es si el composable ha apagado el botón.
     * Mutación: quitar `!questionsMissing` del `enabled` de «Publicar ahora» pone
     * este test en rojo.
     */
    @Test
    fun publicarSinPreguntasApagaElBotonYDiceLaRegla() {
        val borrador = exam.copy(status = ExamStatus.DRAFT, publishedAt = null, questionCount = 0)
        val estado = withQuestions(0).copy(
            exams = UiAsync.Success(listOf(borrador)),
            catalogExams = listOf(borrador),
            editorStatus = "draft",
        )
        val intents = mutableListOf<ExamsIntent>()
        renderWorkspace(state = estado, tab = ExamWorkspaceTabId.PUBLICACION, onIntent = { intents += it }) {
            onNodeWithText("Publicar ahora").assertIsNotEnabled()
            onNodeWithText(EXAM_PUBLISH_REQUIRES_QUESTIONS_EXPLANATION).assertIsDisplayed()

            onNodeWithText("Publicar ahora").performClick()
            waitForIdle()
            assertEquals(
                0,
                onAllNodesWithText("Publicar examen").fetchSemanticsNodes().size,
                "Ha abierto la confirmación de publicar un examen sin preguntas",
            )
            assertTrue(ExamsIntent.PublishExam !in intents, "Ha publicado un examen sin preguntas")
        }
    }

    /** Un archivado no ofrece archivar otra vez; un examen nuevo no ofrece publicar. */
    @Test
    fun cadaEstadoEnsenaSoloSusAccionesReales() {
        val archivado = exam.copy(status = ExamStatus.ARCHIVED, publishedAt = null, questionCount = 3)
        val estadoArchivado = withQuestions(3).copy(
            exams = UiAsync.Success(listOf(archivado)),
            catalogExams = listOf(archivado),
            editorStatus = "archived",
        )
        renderWorkspace(state = estadoArchivado, tab = ExamWorkspaceTabId.PUBLICACION) {
            onNodeWithText("Publicar ahora").assertIsDisplayed()
            assertEquals(0, onAllNodesWithText("Archivar").fetchSemanticsNodes().size, "Ofrece archivar un archivado")
            assertEquals(0, onAllNodesWithText("Despublicar").fetchSemanticsNodes().size)
        }

        val nuevo = ExamsState(
            specialties = UiAsync.Empty,
            exams = UiAsync.Empty,
            communities = UiAsync.Empty,
            questions = UiAsync.Empty,
            specialtyCapabilities = UiAsync.Idle,
            selectedExamId = null,
        )
        renderWorkspace(state = nuevo, tab = ExamWorkspaceTabId.PUBLICACION) {
            onNodeWithText(EXAM_PUBLICATION_NEEDS_SAVE).assertIsDisplayed()
            assertEquals(0, onAllNodesWithText("Publicar ahora").fetchSemanticsNodes().size, "Ofrece publicar lo que no existe")
        }
    }

    /**
     * **Archivar y eliminar son dos botones, y ninguno dispara lo del otro.**
     *
     * Una función pura sabe que las dos acciones existen y qué texto llevan; lo
     * que no puede ver es a cuál de las dos está cableado cada botón. Y ése es
     * justo el defecto que había: el botón que ponía «Eliminar» mandaba la
     * intención que archivaba.
     */
    @Test
    fun archivarYEliminarSonDosBotonesCableadosACosasDistintas() {
        val borrador = exam.copy(status = ExamStatus.DRAFT, publishedAt = null, questionCount = 3)
        val estado = withQuestions(3).copy(
            exams = UiAsync.Success(listOf(borrador)),
            catalogExams = listOf(borrador),
            editorStatus = "draft",
        )
        val intents = mutableListOf<ExamsIntent>()

        renderWorkspace(state = estado, tab = ExamWorkspaceTabId.PUBLICACION, onIntent = { intents += it }) {
            onNodeWithText("Archivar").performClick()
            waitForIdle()
            assertEquals(listOf<ExamsIntent>(ExamsIntent.RequestArchiveSelectedExam), intents)

            onNodeWithText(EXAM_DELETE_ACTION_LABEL).performClick()
            waitForIdle()
            assertEquals(
                listOf<ExamsIntent>(ExamsIntent.RequestArchiveSelectedExam, ExamsIntent.RequestDeleteSelectedExam),
                intents,
                "El botón de eliminar no puede pedir lo mismo que el de archivar",
            )
            // Y ninguno de los dos ejecuta nada sin pasar por la confirmación.
            assertTrue(ExamsIntent.DeleteSelectedExam !in intents, "Ha borrado sin confirmar")
            assertTrue(ExamsIntent.ArchiveSelectedExam !in intents, "Ha archivado sin confirmar")
        }
    }

    /**
     * La confirmación del borrado dice lo que pasa en sus DOS etiquetas.
     *
     * «Aceptar/Cancelar» obliga a deducir cuál de los dos deja las cosas como
     * están, y en la única acción irreversible de la pantalla eso no puede pasar.
     */
    @Test
    fun laConfirmacionDeBorrarNombraLoQueHaceCadaBoton() {
        val estado = withQuestions(3).copy(
            pendingDeleteTarget = PendingDeleteTarget.DeleteExamForever,
            pendingDeleteMessage = EXAM_DELETE_FOREVER_WARNING,
        )
        val intents = mutableListOf<ExamsIntent>()

        renderWorkspace(state = estado, onIntent = { intents += it }) {
            onNodeWithText("Eliminar el examen para siempre").assertIsDisplayed()
            onNodeWithText(EXAM_DELETE_FOREVER_WARNING).assertIsDisplayed()
            onNodeWithText("No eliminar").assertIsDisplayed()
            onNodeWithText("Sí, eliminar").performClick()
            waitForIdle()

            assertEquals(listOf<ExamsIntent>(ExamsIntent.ConfirmPendingDelete), intents)
        }
    }

    /** Y la de archivar es OTRA: otro título, otras etiquetas, otro aviso. */
    @Test
    fun laConfirmacionDeArchivarNoSeParaceALaDeBorrar() {
        val estado = withQuestions(3).copy(
            pendingDeleteTarget = PendingDeleteTarget.ArchiveExam,
            pendingDeleteMessage = EXAM_ARCHIVE_WARNING,
        )

        renderWorkspace(state = estado) {
            onNodeWithText("Archivar examen").assertIsDisplayed()
            onNodeWithText(EXAM_ARCHIVE_WARNING).assertIsDisplayed()
            onNodeWithText("No archivar").assertIsDisplayed()
            assertEquals(
                0,
                onAllNodesWithText("Sí, eliminar").fetchSemanticsNodes().size,
                "La confirmación de archivar ofrece eliminar",
            )
        }
    }

    /**
     * El rechazo por datos de usuario se PINTA, y en la pestaña Publicación.
     *
     * `examDeletionNotice` puede devolver el aviso y el composable no llamarla:
     * entonces el 409 del servidor no se ve en ninguna parte y el administrador
     * pulsa «Eliminar» y no pasa nada visible. Eso es lo que comprueba esto.
     */
    @Test
    fun elRechazoPorDatosDeUsuarioSeVeEnLaPestanaDePublicacion() {
        val mensaje = "No se puede borrar este examen porque los usuarios ya lo han usado: hay intentos de usuarios."
        val estado = withQuestions(3).copy(
            examDeleteState = UiAsync.Error(AppError.Validation(message = mensaje, code = "exam_has_user_data")),
        )

        renderWorkspace(state = estado, tab = ExamWorkspaceTabId.PUBLICACION) {
            onNodeWithText(EXAM_DELETE_BLOCKED_TITLE).assertIsDisplayed()
            onNodeWithText(mensaje).assertIsDisplayed()
        }
    }

    // -----------------------------------------------------------------------
    // El campo culpable de un guardado rechazado
    // -----------------------------------------------------------------------

    /**
     * Un rechazo que señala un campo de OTRA pestaña abre esa pestaña, y el
     * mensaje del backend sale junto al campo además de al pie.
     *
     * Es exactamente lo que una función pura no puede ver: `examFieldErrorTab`
     * puede devolver Configuración y el composable no llamarla —y entonces el
     * campo marcado queda en una pestaña cerrada, que es el defecto de partida—.
     * El formulario se monta en Información básica, la que abre de verdad; si el
     * contenido de Configuración está en el árbol es porque el rechazo la ha
     * abierto él solo.
     */
    @Test
    fun unRechazoQueSenalaOtraPestanaLaAbreYPintaElCampo() {
        // El texto actual del backend (K1-3): la columna exige `>= 0` desde PW-2.
        val mensaje = "El número de preguntas no puede ser negativo."
        val rechazado = withQuestions(3).copy(
            actionState = UiAsync.Error(
                AppError.Validation(message = mensaje, code = EXAM_ERROR_QUESTION_COUNT_INVALID),
            ),
        )
        renderWorkspace(state = rechazado, tab = ExamWorkspaceTabId.BASICA) {
            assertTrue(
                onAllNodesWithText(ExamField.QUESTION_COUNT.label).fetchSemanticsNodes().isNotEmpty(),
                "El rechazo no ha abierto Configuración: el campo señalado sigue en una pestaña cerrada.",
            )
            assertEquals(
                0,
                onAllNodesWithText(examQuestionCountSupport(rechazado, examQuestionCountField(rechazado)))
                    .fetchSemanticsNodes().size,
                "El campo sigue explicando de dónde sale la cifra en vez de decir por qué se ha rechazado.",
            )
            assertTrue(
                onAllNodesWithText(mensaje, substring = false).fetchSemanticsNodes().size >= 2,
                "El mensaje tiene que salir bajo el campo Y al pie: la línea del pie no desaparece.",
            )
        }
    }

    /**
     * Sin código atribuible, nada se mueve: el formulario se queda en la pestaña
     * en la que estaba y el aviso sigue siendo sólo el del pie.
     *
     * Es la conducta de antes, y tiene que conservarse: arrastrar al usuario a otra
     * pestaña por un error que no señala ningún campo sería peor que no moverlo.
     */
    @Test
    fun unRechazoSinCampoAtribuibleNoCambiaDePestana() {
        val rechazado = withQuestions(3).copy(
            actionState = UiAsync.Error(AppError.Validation("La duración debe ser numérica.")),
        )
        renderWorkspace(state = rechazado, tab = ExamWorkspaceTabId.BASICA) {
            assertEquals(
                0,
                onAllNodesWithText(ExamField.QUESTION_COUNT.label).fetchSemanticsNodes().size,
                "Ha cambiado de pestaña por un error que no señala ningún campo.",
            )
            onNodeWithText("La duración debe ser numérica.").assertIsDisplayed()
        }
    }

    // -----------------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------------

    private fun renderWorkspace(
        state: ExamsState,
        tab: ExamWorkspaceTabId = ExamWorkspaceTabId.BASICA,
        onIntent: (ExamsIntent) -> Unit = {},
        assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) {
        runDesktopComposeUiTest(width = 1920, height = 1080) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    // Tema fijo: sin él, `AdminTheme` resuelve con el del sistema
                    // operativo y el mismo test se compondría distinto en dos
                    // máquinas. Lo que se comprueba aquí es texto, no color, pero
                    // un test no debe depender de la configuración de quien lo
                    // ejecuta.
                    AdminTheme(darkTheme = false) {
                        ExamWorkspaceContent(
                            state = state,
                            onIntent = onIntent,
                            onOpenQuestionBank = {},
                            initialTab = tab,
                        )
                    }
                }
            }
            waitForIdle()
            assertions()
        }
    }

    private val now = Instant.parse("2026-04-15T09:00:00Z")

    private val exam = Exam(
        id = "ex-1",
        code = "EX-2026-0001",
        specialtyId = "esp-1",
        specialtyName = "TCAE",
        communityId = null,
        title = "TCAE · Simulacro marzo",
        description = "Simulacro completo.",
        typeExam = "rafaga",
        typeAccess = "gratuito",
        priceTickets = 0,
        status = ExamStatus.PUBLISHED,
        publishFrom = null,
        publishedAt = now,
        sortOrder = 0,
        questionCount = 3,
        durationMinutes = 45,
        year = 2026,
        isOfficial = false,
        coverUrl = null,
        coverStoragePath = null,
        coverMimeType = null,
        createdAt = now,
        updatedAt = now,
    )

    /** Un examen abierto con [count] preguntas, coherente en las dos fuentes. */
    private fun withQuestions(count: Int): ExamsState {
        val current = exam.copy(questionCount = count)
        val questions = (0 until count).map { index ->
            ExamQuestion(
                id = "q-$index",
                examId = current.id,
                title = "Pregunta ${index + 1}",
                statement = "Enunciado ${index + 1}",
                sortOrder = index,
                options = listOf("A", "B").mapIndexed { optionIndex, label ->
                    QuestionOption(
                        id = "q-$index-$label",
                        questionId = "q-$index",
                        label = label,
                        content = "Opción $label",
                        isCorrect = optionIndex == 0,
                        sortOrder = optionIndex,
                    )
                },
                resources = emptyList(),
                createdAt = now,
                updatedAt = now,
            )
        }
        return ExamsState(
            specialties = UiAsync.Empty,
            exams = UiAsync.Success(listOf(current)),
            catalogExams = listOf(current),
            questions = if (questions.isEmpty()) UiAsync.Empty else UiAsync.Success(questions),
            communities = UiAsync.Empty,
            specialtyCapabilities = UiAsync.Success(
                SpecialtyCapabilities(
                    permiteRafagas = true,
                    permiteRazonados = true,
                    permiteInmersivos = true,
                    permiteDemos = false,
                    permiteOposimarket = false,
                    specialtyId = "esp-1",
                ),
            ),
            statusFilter = "all",
            selectedExamId = current.id,
            editorTitle = current.title,
            editorDescription = current.description,
            editorCode = current.code,
            editorSpecialtyId = current.specialtyId,
            editorTypeExam = current.typeExam,
            editorStatus = current.status.name.lowercase(),
            editorTypeAccess = current.typeAccess,
            editorPriceTickets = current.priceTickets.toString(),
            editorYear = current.year?.toString().orEmpty(),
            editorDurationMinutes = current.durationMinutes?.toString().orEmpty(),
        )
    }
}
