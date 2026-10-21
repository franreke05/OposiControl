package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamResourceSummary
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionResourceAudit
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import com.oposicontrol.admin.domain.repository.CommunitiesRepository
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.usecase.communities.ObserveCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.communities.RefreshCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ArchiveExamUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteExamUseCase
import com.oposicontrol.admin.domain.usecase.content.ArchiveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionResourceUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamResourceAuditUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetRafagaQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ImportQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.UnlinkQuestionFromExamUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UnpublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadQuestionResourceUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExamsViewModelTest {

    private fun TestScope.buildViewModel(repository: ExamsTestRepository): ExamsViewModel {
        return ExamsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeSpecialtiesUseCase = ObserveSpecialtiesUseCase(repository),
            observeAllExamsUseCase = ObserveAllExamsUseCase(repository),
            observeRafagasUseCase = ObserveRafagasUseCase(repository),
            refreshSpecialtiesUseCase = RefreshSpecialtiesUseCase(repository),
            upsertSpecialtyUseCase = UpsertSpecialtyUseCase(repository),
            deleteSpecialtyUseCase = DeleteSpecialtyUseCase(repository),
            refreshAllExamsUseCase = RefreshAllExamsUseCase(repository),
            refreshRafagasUseCase = RefreshRafagasUseCase(repository),
            getExamQuestionsUseCase = GetExamQuestionsUseCase(repository),
            getRafagaQuestionsUseCase = GetRafagaQuestionsUseCase(repository),
            getExamResourceAuditUseCase = GetExamResourceAuditUseCase(repository),
            upsertExamUseCase = UpsertExamUseCase(repository),
            deleteExamUseCase = DeleteExamUseCase(repository),
            archiveExamUseCase = ArchiveExamUseCase(repository),
            publishExamUseCase = PublishExamUseCase(repository),
            unpublishExamUseCase = UnpublishExamUseCase(repository),
            getSpecialtyCapabilitiesUseCase = GetSpecialtyCapabilitiesUseCase(repository),
            observeCommunitiesUseCase = ObserveCommunitiesUseCase(repository),
            refreshCommunitiesUseCase = RefreshCommunitiesUseCase(repository),
            upsertRafagaUseCase = UpsertRafagaUseCase(repository),
            deleteRafagaUseCase = DeleteRafagaUseCase(repository),
            upsertQuestionUseCase = UpsertQuestionUseCase(repository),
            importQuestionsUseCase = ImportQuestionsUseCase(repository),
            unlinkQuestionFromExamUseCase = UnlinkQuestionFromExamUseCase(repository),
            archiveQuestionUseCase = ArchiveQuestionUseCase(repository),
            moveQuestionUseCase = MoveQuestionUseCase(repository),
            upsertRafagaQuestionUseCase = UpsertRafagaQuestionUseCase(repository),
            deleteRafagaQuestionUseCase = DeleteRafagaQuestionUseCase(repository),
            moveRafagaQuestionUseCase = MoveRafagaQuestionUseCase(repository),
            uploadQuestionResourceUseCase = UploadQuestionResourceUseCase(repository),
            deleteQuestionResourceUseCase = DeleteQuestionResourceUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
    }

    /**
     * **La emisión semilla no puede decidir el estado de un catálogo.**
     *
     * `KtorCommunitiesRepositoryImpl` siembra su `MutableStateFlow` con
     * `emptyList()` —el repositorio de este test hace exactamente lo mismo— y esa
     * primera emisión se publicaba como `Success(emptyList())`. El efecto no era
     * cosmético: `communities` nunca pasaba por `Loading`, así que desde el
     * arranque un examen con comunidad real se etiquetaba «Nacional» y el
     * desplegable ofrecía «Nacional» como única opción. Dos clics anulaban un
     * `comunidad_id` de verdad, sin backend caído y sin ningún aviso.
     *
     * Lo que se comprueba: que un vacío no confirmado NUNCA es `Success`. Puede
     * ser `Loading` mientras nadie ha preguntado o `Empty` cuando el refresco lo
     * confirma; lo que no puede es tener la forma de un catálogo leído y vacío.
     */
    @Test
    fun `la lista semilla vacia no se publica como catalogo cargado`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        val communities = viewModel.state.value.communities
        assertTrue(
            communities !is UiAsync.Success,
            "Un vacío sembrado por el repositorio se ha publicado como catálogo cargado: $communities",
        )
        // Y, como el refresco sí confirmó el vacío, el estado queda resuelto en
        // vez de girar para siempre.
        assertEquals(UiAsync.Empty, communities)
    }

    /**
     * Con el catálogo sin resolver, la comunidad de un examen no se decide.
     *
     * Es el mismo defecto visto desde la pantalla: lo que la etiqueta no puede
     * hacer es devolver «Nacional» para un examen que tiene `comunidad_id`.
     */
    @Test
    fun `sin catalogo de comunidades no se afirma que un examen sea nacional`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        val etiqueta = communityLabel("com-andalucia", viewModel.state.value.communities)
        assertTrue(
            etiqueta.origin != ExamFieldOrigin.VALUE,
            "Con el catálogo sin resolver, la comunidad no puede presentarse como un dato: $etiqueta",
        )
        assertTrue(etiqueta.text != "Nacional")
    }

    /**
     * Cambiar de examen con el editor sucio pide confirmación en vez de tirarlo.
     *
     * `SelectExam` sobrescribía los catorce campos del editor sin mirar nada:
     * media hora de trabajo desaparecía al pinchar la tarjeta de al lado, sin
     * diálogo y sin forma de volver.
     */
    @Test
    fun `cambiar de examen con cambios sin guardar pide confirmacion`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateTitle("TCAE Marzo revisado"))
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-abril"))
        advanceUntilIdle()

        // Nada ha cambiado todavía: sigue abierto el mismo examen y con el texto
        // escrito intacto.
        assertEquals(PendingDeleteTarget.SwitchExam("exam-tcae-abril"), viewModel.state.value.pendingDeleteTarget)
        assertEquals("exam-tcae-marzo", viewModel.state.value.selectedExamId)
        assertEquals("TCAE Marzo revisado", viewModel.state.value.editorTitle)

        // Y seguir aquí lo deja todo como estaba.
        viewModel.dispatch(ExamsIntent.DismissPendingDelete)
        advanceUntilIdle()
        assertNull(viewModel.state.value.pendingDeleteTarget)
        assertEquals("TCAE Marzo revisado", viewModel.state.value.editorTitle)

        // Confirmar sí abre el otro.
        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-abril"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.ConfirmPendingDelete)
        advanceUntilIdle()
        assertEquals("exam-tcae-abril", viewModel.state.value.selectedExamId)
    }

    /**
     * Sin cambios NO hay diálogo.
     *
     * Confirmar algo que no tiene consecuencias es la forma más rápida de enseñar
     * que confirmar no significa nada, y entonces tampoco protege cuando sí las
     * tiene. (Un borrador nuevo ya no trae especialidad preseleccionada: ver
     * `un borrador nuevo no preselecciona especialidad`.)
     */
    @Test
    fun `cambiar de examen con el editor limpio no pregunta nada`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-marzo"))
        advanceUntilIdle()
        assertNull(viewModel.state.value.pendingDeleteTarget)
        assertEquals("exam-tcae-marzo", viewModel.state.value.selectedExamId)

        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-abril"))
        advanceUntilIdle()
        assertNull(viewModel.state.value.pendingDeleteTarget)
        assertEquals("exam-tcae-abril", viewModel.state.value.selectedExamId)
    }

    /**
     * Un examen NUEVO al que sólo se le ha cambiado el tipo también se protege.
     *
     * El guardián de borrador nuevo miraba título, descripción, código, año,
     * duración, comunidad y portada, pero no el tipo, el acceso, el precio ni
     * «Oficial». Cambiar SÓLO el tipo y pinchar un examen del catálogo tiraba el
     * cambio sin preguntar.
     */
    @Test
    fun `un examen nuevo al que solo se le cambia el tipo pide confirmacion al cambiar de examen`() = runTest {
        // La especialidad permite un segundo tipo, para que elegirlo sea legal.
        val repository = ExamsTestRepository().apply { permiteRazonados = true }
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        // Examen nuevo recién abierto: nada escrito y el tipo por defecto.
        assertNull(viewModel.state.value.selectedExamId)
        assertEquals(NewExamDefaults.TYPE_EXAM, viewModel.state.value.editorTypeExam)

        viewModel.dispatch(ExamsIntent.UpdateTypeExam("razonado"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-marzo"))
        advanceUntilIdle()

        assertEquals(PendingDeleteTarget.SwitchExam("exam-tcae-marzo"), viewModel.state.value.pendingDeleteTarget)
        // Y no se ha tirado nada: sigue el borrador, con su tipo.
        assertNull(viewModel.state.value.selectedExamId)
        assertEquals("razonado", viewModel.state.value.editorTypeExam)
    }

    /** Los otros tres campos que el guardián no miraba: acceso, precio y «Oficial». */
    @Test
    fun `un examen nuevo al que solo se le cambia el acceso el precio u oficial pide confirmacion`() = runTest {
        listOf(
            ExamsIntent.UpdateTypeAccess("premium"),
            ExamsIntent.UpdatePriceTickets("25"),
            ExamsIntent.ToggleIsOfficial(true),
        ).forEach { cambio ->
            val viewModel = buildViewModel(ExamsTestRepository())
            advanceUntilIdle()

            viewModel.dispatch(cambio)
            advanceUntilIdle()
            viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-marzo"))
            advanceUntilIdle()

            assertEquals(
                PendingDeleteTarget.SwitchExam("exam-tcae-marzo"),
                viewModel.state.value.pendingDeleteTarget,
                "Un examen nuevo con sólo este cambio se ha descartado sin preguntar: $cambio",
            )
        }
    }

    /**
     * Un borrador nuevo SIN TOCAR no pide confirmación, tampoco cuando la primera
     * especialidad del catálogo no permitiría ráfaga.
     *
     * Era una trampa real mientras había preselección: el ViewModel aplicaba las
     * capacidades de la especialidad preseleccionada y movía el tipo solo, y
     * comparar contra el literal ponía un diálogo en cada clic del catálogo. Sin
     * preselección no hay capacidades que aplicar: el tipo se queda en el valor
     * por defecto y la compensación sobra.
     */
    @Test
    fun `un borrador nuevo sin tocar no pide confirmacion al cambiar de examen`() = runTest {
        val repository = ExamsTestRepository().apply {
            permiteRafagas = false
            permiteRazonados = true
        }
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        assertEquals(NewExamDefaults.TYPE_EXAM, viewModel.state.value.editorTypeExam)

        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-marzo"))
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingDeleteTarget)
        assertEquals("exam-tcae-marzo", viewModel.state.value.selectedExamId)
    }

    /** Elegir especialidad en un borrador nuevo SÍ es un cambio: ya no la pone el ViewModel. */
    @Test
    fun `elegir especialidad en un borrador nuevo pide confirmacion al cambiar de examen`() = runTest {
        val viewModel = buildViewModel(ExamsTestRepository())
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.UpdateSpecialtyId("specialty-tcae"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-marzo"))
        advanceUntilIdle()

        assertEquals(PendingDeleteTarget.SwitchExam("exam-tcae-marzo"), viewModel.state.value.pendingDeleteTarget)
        assertNull(viewModel.state.value.selectedExamId)
        assertEquals("specialty-tcae", viewModel.state.value.editorSpecialtyId)
    }

    /**
     * Un borrador nuevo NO trae especialidad preseleccionada.
     *
     * El ViewModel rellenaba la primera del catálogo —por orden de posición,
     * inactivas incluidas— al arrancar y en cada «Nuevo examen»: bastaba
     * escribir un título para crear el examen en una especialidad que nadie
     * había elegido.
     */
    @Test
    fun `un borrador nuevo no preselecciona especialidad`() = runTest {
        val viewModel = buildViewModel(ExamsTestRepository())
        advanceUntilIdle()

        assertNull(viewModel.state.value.selectedExamId)
        assertEquals("", viewModel.state.value.editorSpecialtyId)
        assertEquals(UiAsync.Idle, viewModel.state.value.specialtyCapabilities)

        // Tampoco al volver a un borrador nuevo desde un examen abierto.
        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SwitchExam(null))
        advanceUntilIdle()
        assertNull(viewModel.state.value.selectedExamId)
        assertEquals("", viewModel.state.value.editorSpecialtyId)
        assertEquals(UiAsync.Idle, viewModel.state.value.specialtyCapabilities)
    }

    @Test
    fun `un borrador sin especialidad no se guarda y se dice por que`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.UpdateTitle("Simulacro nuevo"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val action = viewModel.state.value.actionState
        assertTrue(action is UiAsync.Error)
        assertEquals("Selecciona una especialidad.", (action.error as AppError.Validation).message)
        assertNull(repository.lastUpsertedExam)
    }

    @Test
    fun `un examen sin comunidad no se guarda y se senala el campo`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateCommunityId(null))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val action = viewModel.state.value.actionState
        assertTrue(action is UiAsync.Error)
        assertEquals(EXAM_ERROR_COMMUNITY_REQUIRED, (action.error as AppError.Validation).code)
        assertEquals(ExamField.COMMUNITY, examFieldError(viewModel.state.value)?.field)
        assertNull(repository.lastUpsertedExam)
    }

    @Test
    fun `selecting exam loads related questions`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()

        val state = viewModel.state.value
        val questions = (state.questions as UiAsync.Success).value
        assertEquals("exam-tcae-marzo", state.selectedExamId)
        assertEquals(2, questions.size)
        assertEquals("question-tcae-1", questions.first().id)
    }

    // Regresión del bug donde saveExam() nunca incluía `code` al construir el
    // Exam a persistir: cualquier edición borraba en silencio el código real
    // que el servidor había autogenerado al crear el examen.
    @Test
    fun `saving exam sends the typed code to the repository`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateCode("EX-2026-001"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        assertEquals("EX-2026-001", repository.lastUpsertedExam?.code)
        // Reabrir el examen guardado ya no devuelve la confirmación a `Idle`: con
        // eso, «Cambios guardados.» no llegaba a pintarse nunca. La retira la
        // siguiente edición (ver `tras guardar se ve la confirmacion...`).
        assertEquals(UiAsync.Success(Unit), viewModel.state.value.actionState)
        assertEquals("exam-tcae-marzo", viewModel.state.value.selectedExamId)
    }

    @Test
    fun `saving exam with a blank code preserves the existing one`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateCode(""))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        assertEquals("TCAE-2026-03", repository.lastUpsertedExam?.code)
    }

    // La especialidad de este examen (ver fake) sólo permite ráfaga: guardar un
    // tipo que no está entre sus capacidades debe rechazarse en el cliente, ya
    // que el backend no valida tipoExamen contra capacidades_especialidad.
    @Test
    fun `saving exam rejects a type not allowed by the specialty`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateTypeExam("inmersivo"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val action = viewModel.state.value.actionState
        assertTrue(action is UiAsync.Error)
        assertTrue((action.error as AppError.Validation).message.contains("no permite"))
        assertNull(repository.lastUpsertedExam)
    }

    // Antes "capabilities != null && !capabilities.allows(typeExam)" se saltaba
    // por completo la comprobación cuando specialtyCapabilities no era Success
    // (fallo de red) y dejaba guardar cualquier tipo sin verificar.
    @Test
    fun `saving exam blocks when specialty capabilities failed to load`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        repository.capabilitiesShouldFail = true
        // Reintentar contra la misma especialidad, ahora fallando.
        viewModel.dispatch(ExamsIntent.UpdateSpecialtyId("specialty-tcae"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.specialtyCapabilities is UiAsync.Error)
        // El tipo ya persistido ("rafaga") no se invalida ni se borra en silencio.
        assertEquals("rafaga", viewModel.state.value.editorTypeExam)

        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val action = viewModel.state.value.actionState
        assertTrue(action is UiAsync.Error)
        assertTrue((action.error as AppError.Validation).message.contains("comprobar"))
        assertNull(repository.lastUpsertedExam)
    }

    // -----------------------------------------------------------------------
    // Quitar del examen vs archivar: dos operaciones, dos alcances
    // -----------------------------------------------------------------------

    @Test
    fun `quitar del examen deja la pregunta en los demas examenes`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.RequestUnlinkQuestion("question-tcae-1"))
        viewModel.dispatch(ExamsIntent.ConfirmPendingDelete)
        advanceUntilIdle()

        val remaining = (viewModel.state.value.questions as UiAsync.Success).value
        assertEquals(listOf("question-tcae-2"), remaining.map { it.id })
        // Y la posición se recompacta: la que quedaba en 1 pasa a 0.
        assertEquals(0, remaining.first().sortOrder)

        // Sigue vinculada al otro examen, que es toda la diferencia.
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-abril"))
        advanceUntilIdle()
        val april = (viewModel.state.value.questions as UiAsync.Success).value
        assertEquals(listOf("question-tcae-1"), april.map { it.id })
    }

    @Test
    fun `archivar saca la pregunta de todos los examenes`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.RequestArchiveQuestion("question-tcae-1"))
        viewModel.dispatch(ExamsIntent.ConfirmPendingDelete)
        advanceUntilIdle()

        assertEquals(
            listOf("question-tcae-2"),
            (viewModel.state.value.questions as UiAsync.Success).value.map { it.id },
        )

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-abril"))
        advanceUntilIdle()
        // El otro examen se queda sin ella: eso es archivar.
        assertTrue(viewModel.state.value.questions is UiAsync.Empty)
    }

    // El aviso no es decoración: sin él, «Archivar» se lee como «quitar de este
    // examen». Y no puede prometer un número porque no existe la consulta
    // inversa pregunta → exámenes.
    @Test
    fun `el aviso de archivar admite que no se sabe en cuantos examenes esta`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.RequestArchiveQuestion("question-tcae-1"))
        advanceUntilIdle()

        val message = viewModel.state.value.pendingDeleteMessage
        assertTrue(message.contains("TODOS los exámenes"), "El aviso debe decir el alcance real: $message")
        assertTrue(message.contains("No podemos decirte"), "El aviso debe admitir que no sabe cuántos: $message")
    }

    @Test
    fun `quitar del examen se anuncia como reversible y no como borrado`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.RequestUnlinkQuestion("question-tcae-1"))
        advanceUntilIdle()

        val message = viewModel.state.value.pendingDeleteMessage
        assertTrue(message.contains("sigue en el banco"), "Debe decir que la pregunta no se pierde: $message")
        assertTrue(!message.contains("TODOS"), "No puede tomar prestado el aviso de archivar: $message")
    }

    // Regresión del bug que el contrato de FASE 2 vino a cerrar: el camino móvil
    // llega aquí por `DeleteSelectedQuestion` y su efecto era archivar pese a
    // recibir un examId. Se conserva el efecto —cambiarlo sería una regresión
    // silenciosa en una pantalla que este encargo no toca— pero ya no pasa por el
    // caso de uso ambiguo: el fake hace fallar `deleteQuestion` a propósito.
    @Test
    fun `el borrado del camino movil archiva sin pasar por el caso de uso ambiguo`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectQuestion("question-tcae-1"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.RequestDeleteSelectedQuestion)
        viewModel.dispatch(ExamsIntent.ConfirmPendingDelete)
        advanceUntilIdle()

        assertTrue(
            viewModel.state.value.actionState is UiAsync.Success,
            "Si hubiera llamado a deleteQuestion, el fake habría devuelto error.",
        )
        assertEquals(
            listOf("question-tcae-2"),
            (viewModel.state.value.questions as UiAsync.Success).value.map { it.id },
        )
    }

    // El catálogo se construye sobre `observeAllExams`, no sumando los cubos de
    // ráfaga e inmersivo: un `razonado` no caía en ninguno de los dos y
    // desaparecía de la lista.
    @Test
    fun `el catalogo incluye los examenes razonados`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        val exams = (viewModel.state.value.exams as UiAsync.Success).value
        assertTrue(exams.any { it.typeExam == "razonado" }, "Falta el razonado: ${exams.map { it.typeExam }}")
    }

    @Test
    fun `publishing an exam uses the dedicated endpoint and updates its status`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.PublishExam)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.lifecycleActionState is UiAsync.Success)
        val exams = (viewModel.state.value.exams as UiAsync.Success).value
        val published = exams.first { it.id == "exam-tcae-marzo" }
        assertEquals(ExamStatus.PUBLISHED, published.status)
        assertNotNull(published.publishedAt)
    }

    @Test
    fun `unpublishing an exam clears its published date`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.PublishExam)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UnpublishExam)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.lifecycleActionState is UiAsync.Success)
        val exams = (viewModel.state.value.exams as UiAsync.Success).value
        val unpublished = exams.first { it.id == "exam-tcae-marzo" }
        assertEquals(ExamStatus.DRAFT, unpublished.status)
        assertNull(unpublished.publishedAt)
    }

    // El botón "Archivar" dispara RequestArchiveSelectedExam -> ConfirmPendingDelete,
    // no un ArchiveSelectedExam directo: es el mismo mecanismo que Rafaga/Pregunta/
    // Especialidad, así que el mensaje de confirmación también debe ser el real.
    @Test
    fun `requesting to archive an exam sets the real confirmation message`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.RequestArchiveSelectedExam)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PendingDeleteTarget.ArchiveExam, state.pendingDeleteTarget)
        assertEquals(EXAM_ARCHIVE_WARNING, state.pendingDeleteMessage)
        assertTrue(state.pendingDeleteMessage.contains("no se eliminan"))

        viewModel.dispatch(ExamsIntent.ConfirmPendingDelete)
        advanceUntilIdle()

        val archivado = viewModel.state.value
        assertNull(archivado.pendingDeleteTarget)
        assertTrue(archivado.lifecycleActionState is UiAsync.Success)
        // Archivar es de CICLO DE VIDA: llama a `archiveExam`, no a `deleteExam`,
        // y deja el examen abierto con su estado nuevo. Cerrarlo sería tratar
        // «retirar del catálogo» como «esto ya no existe».
        assertEquals(listOf("exam-tcae-marzo"), repository.archived)
        assertTrue(repository.deleted.isEmpty(), "archivar no puede borrar: ${repository.deleted}")
        assertEquals("exam-tcae-marzo", archivado.selectedExamId)
        assertEquals("archived", archivado.editorStatus)
    }

    /**
     * **Borrar es otra acción, con otro aviso y otras consecuencias.**
     *
     * El examen desaparece, así que el editor se cierra; y el resultado va a
     * `examDeleteState`, no a la barra de guardado.
     */
    @Test
    fun `deleting an exam for good asks a different confirmation and closes the editor`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.RequestDeleteSelectedExam)
        advanceUntilIdle()

        val pidiendo = viewModel.state.value
        assertEquals(PendingDeleteTarget.DeleteExamForever, pidiendo.pendingDeleteTarget)
        assertEquals(EXAM_DELETE_FOREVER_WARNING, pidiendo.pendingDeleteMessage)

        viewModel.dispatch(ExamsIntent.ConfirmPendingDelete)
        advanceUntilIdle()

        val borrado = viewModel.state.value
        assertEquals(listOf("exam-tcae-marzo"), repository.deleted)
        assertTrue(repository.archived.isEmpty(), "borrar no puede archivar: ${repository.archived}")
        assertTrue(borrado.examDeleteState is UiAsync.Success)
        assertNull(borrado.selectedExamId, "el examen ya no existe: el editor no puede seguir abierto")
    }

    /**
     * **El rechazo por datos de usuario se ve, y NO se disfraza de guardado
     * fallido.**
     *
     * `actionState` es la barra de guardado. Un 409 del borrado puesto ahí decía
     * «no se ha podido guardar» sobre un examen que nadie estaba guardando, y
     * encima obligaba a `examFieldError` a decidir sobre un código que no señala
     * ningún campo. Va a `examDeleteState`, y de ahí a `examDeletionNotice`.
     */
    @Test
    fun `an exam with user data is not deleted and says so next to the action`() = runTest {
        val repository = ExamsTestRepository(
            deleteResult = AppResult.Failure(
                AppError.Validation(
                    message = "No se puede borrar este examen porque los usuarios ya lo han usado: hay intentos de usuarios.",
                    code = "exam_has_user_data",
                ),
            ),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.RequestDeleteSelectedExam)
        viewModel.dispatch(ExamsIntent.ConfirmPendingDelete)
        advanceUntilIdle()

        val estado = viewModel.state.value
        assertTrue(estado.examDeleteState is UiAsync.Error)
        assertTrue(estado.actionState is UiAsync.Idle, "un borrado rechazado no es un guardado fallido")
        // Y el examen sigue abierto: no se ha borrado nada.
        assertEquals("exam-tcae-marzo", estado.selectedExamId)

        val aviso = assertNotNull(examDeletionNotice(estado))
        assertEquals(ExamDeletionNoticeKind.BLOCKED, aviso.kind)
        assertTrue(aviso.detail.orEmpty().contains("intentos de usuarios"), aviso.detail.orEmpty())
        // El código llega del servidor en minúsculas y aun así se reconoce.
        assertNull(examFieldError(estado), "no hay campo culpable que señalar")
    }

    // -----------------------------------------------------------------------
    // Correcciones de dominio de Información básica
    // -----------------------------------------------------------------------

    /**
     * Al abrir otro examen, las preguntas del anterior no se quedan en pantalla.
     *
     * `selectExam` pedía las capacidades de la especialidad ANTES de pedir las
     * preguntas y no tocaba `questions` hasta entonces: durante esa espera la
     * cabecera, la insignia de la pestaña y la tarjeta del catálogo pintaban el
     * recuento del examen anterior como si fuera un dato de éste.
     */
    @Test
    fun `al abrir otro examen las preguntas pasan a cargando antes de pedir las capacidades`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        assertEquals(2, (viewModel.state.value.questions as UiAsync.Success).value.size)

        // Las capacidades del segundo examen quedan colgadas: es la ventana en la
        // que se veía el recuento del primero.
        val gate = CompletableDeferred<Unit>()
        repository.capabilitiesGate = gate
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-abril"))
        advanceUntilIdle()

        assertEquals("exam-tcae-abril", viewModel.state.value.selectedExamId)
        assertEquals(UiAsync.Loading, viewModel.state.value.questions)
        // El recuento del primero sigue guardado como dato de sesión SUYO.
        assertEquals(2, viewModel.state.value.countedQuestions["exam-tcae-marzo"])
        assertNull(viewModel.state.value.countedQuestions["exam-tcae-abril"])

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, (viewModel.state.value.questions as UiAsync.Success).value.size)
        assertEquals(1, viewModel.state.value.countedQuestions["exam-tcae-abril"])
    }

    /** Una lista cargada y vacía también es un recuento: 0. */
    @Test
    fun `el recuento de sesion guarda cero para una lista vacia`() = runTest {
        val viewModel = buildViewModel(ExamsTestRepository())
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-mixto"))
        advanceUntilIdle()

        assertEquals(UiAsync.Empty, viewModel.state.value.questions)
        assertEquals(0, viewModel.state.value.countedQuestions["exam-mixto"])
    }

    /**
     * Abrir un examen NO le cambia el tipo, aunque la especialidad no lo permita.
     *
     * `selectEditorSpecialty` movía el tipo al primero permitido también al abrir:
     * un `razonado` en una especialidad sólo-ráfaga se abría como «Ráfaga», el
     * editor salía sucio sin que nadie hubiera tocado nada y guardar cambiaba el
     * tipo del examen. Un tipo desconocido se disfrazaba igual.
     */
    @Test
    fun `abrir un examen no le cambia el tipo aunque la especialidad no lo permita`() = runTest {
        // La especialidad del fake sólo permite ráfaga.
        val viewModel = buildViewModel(ExamsTestRepository())
        advanceUntilIdle()

        listOf("exam-tcae-abril" to "razonado", "exam-mixto" to "mixto").forEach { (id, tipo) ->
            viewModel.dispatch(ExamsIntent.SelectExam(id))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(tipo, state.editorTypeExam, "El tipo guardado de $id se ha cambiado al abrirlo")
            val guardado = state.catalogExams.first { it.id == id }
            assertFalse(hasUnsavedExamChanges(state, guardado), "Abrir $id no puede dejar el editor sucio")
        }
    }

    /** El tipo no permitido o desconocido se BLOQUEA al guardar; no se corrige a escondidas. */
    @Test
    fun `guardar un tipo no permitido o desconocido se bloquea en vez de corregirse`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-abril"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()
        val notAllowed = viewModel.state.value.actionState
        assertTrue(notAllowed is UiAsync.Error)
        assertTrue((notAllowed.error as AppError.Validation).message.contains("no permite"))

        viewModel.dispatch(ExamsIntent.SelectExam("exam-mixto"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()
        val unknown = viewModel.state.value.actionState
        assertTrue(unknown is UiAsync.Error)
        assertTrue((unknown.error as AppError.Validation).message.contains("no es válido"))

        assertNull(repository.lastUpsertedExam)
    }

    /**
     * Cambiar de especialidad NO cambia el tipo elegido.
     *
     * Antes se ajustaba «al primero que la especialidad permita», y eso es decidir
     * en nombre de quien administra: se elegía una especialidad y el examen
     * cambiaba de tipo sin que nadie lo pidiera ni lo viera. Ahora la elección se
     * conserva y, si la especialidad nueva no la admite, la comprobación lo dice y
     * bloquea el guardado. Cambiarlo es decisión del usuario.
     */
    @Test
    fun `cambiar a otra especialidad no cambia el tipo elegido y lo bloquea si no lo admite`() = runTest {
        val repository = ExamsTestRepository().apply {
            addSpecialty("specialty-otra", "Otra")
            onlyRazonadosIn = "specialty-otra"
        }
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        assertEquals("rafaga", viewModel.state.value.editorTypeExam)

        viewModel.dispatch(ExamsIntent.UpdateSpecialtyId("specialty-otra"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("rafaga", state.editorTypeExam, "El tipo elegido se ha cambiado solo")
        val typeCheck = examChecks(state, state.catalogExams.first { it.id == "exam-tcae-marzo" }, 5)
            .first { it.id == ExamCheckId.TYPE_ALLOWED }
        assertEquals(ExamCheckStatus.BLOCKING, typeCheck.status, "Un tipo que la especialidad no admite debe bloquear")
    }

    /**
     * Publicar no tira los cambios sin guardar.
     *
     * `applyLifecycleResult` reabría el examen con `selectExam`, que copia los
     * catorce campos del examen guardado sobre el editor: un título cambiado y
     * sin guardar desaparecía al pulsar «Publicar», sin diálogo.
     */
    @Test
    fun `publicar con cambios sin guardar los conserva y la barra sigue sucia`() = runTest {
        val viewModel = buildViewModel(ExamsTestRepository())
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateTitle("TCAE Marzo revisado"))
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.PublishExam)
        advanceUntilIdle()

        val state = viewModel.state.value
        val guardado = state.catalogExams.first { it.id == "exam-tcae-marzo" }
        assertEquals("TCAE Marzo revisado", state.editorTitle)
        assertEquals(ExamStatus.PUBLISHED, guardado.status)
        // `saveExam` manda `editorStatus`: sin actualizarlo, guardar después de
        // publicar despublicaría el examen.
        assertEquals("published", state.editorStatus)
        assertTrue(hasUnsavedExamChanges(state, guardado), "El título editado sigue sin guardar y hay que decirlo")
    }

    /**
     * El resultado de publicar es de UN examen y no se arrastra a otro.
     *
     * `lifecycleActionState` no volvía nunca a `Idle`: tras publicar uno, abrir
     * otro —o empezar uno nuevo— enseñaba en su pestaña Publicación «Examen
     * publicado» (o el error) de un examen que ya no estaba en pantalla.
     */
    @Test
    fun `el resultado de publicar no se arrastra al abrir otro examen ni al empezar uno nuevo`() = runTest {
        val viewModel = buildViewModel(ExamsTestRepository())
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.PublishExam)
        advanceUntilIdle()
        assertEquals(UiAsync.Success(Unit), viewModel.state.value.lifecycleActionState)

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-abril"))
        advanceUntilIdle()
        assertEquals(UiAsync.Idle, viewModel.state.value.lifecycleActionState)

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UnpublishExam)
        advanceUntilIdle()
        assertEquals(UiAsync.Success(Unit), viewModel.state.value.lifecycleActionState)
        viewModel.dispatch(ExamsIntent.CreateNew)
        advanceUntilIdle()
        assertEquals(UiAsync.Idle, viewModel.state.value.lifecycleActionState)
    }

    /** «Reintentar» en Preguntas o Recursos vuelve a pedir las preguntas y nada más. */
    @Test
    fun `reintentar las preguntas no toca lo que hay escrito en el editor`() = runTest {
        val viewModel = buildViewModel(ExamsTestRepository())
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateTitle("TCAE Marzo revisado"))
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.RetryLoadQuestions)
        advanceUntilIdle()

        assertEquals("TCAE Marzo revisado", viewModel.state.value.editorTitle)
        assertEquals(2, (viewModel.state.value.questions as UiAsync.Success).value.size)
    }

    /**
     * Tras guardar, el editor enseña lo guardado y no «cambios sin guardar».
     *
     * `saveExam` reabría el examen con `selectExam`, que lee de `allExams`; el
     * `Flow` del repositorio que lo actualiza vive en otra corrutina y todavía no
     * había dado la vuelta. El editor se re-sembraba con los valores de ANTES de
     * guardar.
     */
    @Test
    fun `tras guardar el editor ensena lo guardado y no queda sucio`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.UpdateTitle("TCAE Marzo revisado"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val state = viewModel.state.value
        val guardado = state.catalogExams.first { it.id == "exam-tcae-marzo" }
        assertEquals("TCAE Marzo revisado", repository.lastUpsertedExam?.title)
        assertEquals("TCAE Marzo revisado", state.editorTitle)
        assertFalse(hasUnsavedExamChanges(state, guardado), "Recién guardado no hay nada pendiente")
    }

    /**
     * «Reintentar» recupera el catálogo de comunidades.
     *
     * `refreshAll` no recargaba las comunidades: si fallaban al arrancar, el
     * selector se quedaba sin catálogo hasta reiniciar. Y aunque las recargara,
     * un `StateFlow` no vuelve a emitir una lista igual a la que ya tenía: el
     * estado tiene que salir de la respuesta.
     */
    @Test
    fun `recargar recupera el catalogo de comunidades tras un fallo`() = runTest {
        val andalucia = Community(
            id = "com-andalucia",
            name = "Andalucía",
            code = "AN",
            slug = "andalucia",
            isActive = true,
            sortOrder = 0,
            createdAt = Instant.parse("2026-04-15T09:00:00Z"),
            updatedAt = Instant.parse("2026-04-15T09:00:00Z"),
        )
        val repository = ExamsTestRepository().apply { seedCommunities(listOf(andalucia)) }
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        assertEquals(UiAsync.Success(listOf(andalucia)), viewModel.state.value.communities)

        // Un «Actualizar» que falla deja el catálogo en error...
        repository.communitiesShouldFail = true
        viewModel.dispatch(ExamsIntent.RefreshAll)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.communities is UiAsync.Error)

        // ...y el siguiente, con el servidor ya bien y LOS MISMOS datos, lo
        // recupera aunque el `Flow` no vuelva a emitir.
        repository.communitiesShouldFail = false
        viewModel.dispatch(ExamsIntent.RefreshAll)
        advanceUntilIdle()
        assertEquals(UiAsync.Success(listOf(andalucia)), viewModel.state.value.communities)
    }

    /** Mismo defecto en especialidades: el «Reintentar» del selector no salía de `Error`. */
    @Test
    fun `recargar recupera el catalogo de especialidades tras un fallo`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.specialties is UiAsync.Success)

        repository.specialtiesShouldFail = true
        viewModel.dispatch(ExamsIntent.RefreshAll)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.specialties is UiAsync.Error)

        repository.specialtiesShouldFail = false
        viewModel.dispatch(ExamsIntent.RefreshAll)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.specialties is UiAsync.Success)
    }

    @Test
    fun `con el catalogo de especialidades caido guardar dice que hay que reintentar`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        // El catálogo desaparece y la recarga falla: no se sabe qué especialidades
        // hay, aunque el examen tenga la suya.
        repository.dropSpecialties()
        repository.specialtiesShouldFail = true
        viewModel.dispatch(ExamsIntent.RefreshAll)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.specialties is UiAsync.Error)

        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val action = viewModel.state.value.actionState
        assertTrue(action is UiAsync.Error)
        assertEquals(
            "No se ha podido cargar el catálogo de especialidades. Reintenta antes de guardar.",
            (action.error as AppError.Validation).message,
        )
        assertNull(repository.lastUpsertedExam)
    }

    // -----------------------------------------------------------------------
    // Ronda 3a: peticiones sin duplicar (K0-5) y recuento servido (K2-2)
    // -----------------------------------------------------------------------

    /**
     * Abrir la pantalla pide cada catálogo UNA vez (K0-5, EX-13).
     *
     * Antes: especialidades dos veces (un refresco en el `init` además del de
     * `RefreshAll`) y las ráfagas con su propio `GET /examenes`, que es el mismo
     * catálogo que ya trae `refreshAllExams`. Mutación: devolver al `init` el
     * refresco de especialidades o a `refreshAll` el de ráfagas pone este test en rojo.
     */
    @Test
    fun `abrir la pantalla pide cada catalogo una sola vez`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        assertEquals(1, repository.refreshAllExamsCalls, "catálogo de exámenes")
        assertEquals(1, repository.refreshSpecialtiesCalls, "especialidades")
        assertEquals(1, repository.refreshCommunitiesCalls, "comunidades")
        assertEquals(0, repository.refreshRafagasCalls, "las ráfagas llegan con el catálogo de exámenes")
        // Y sin el refresco duplicado los catálogos siguen resolviéndose.
        assertTrue(viewModel.state.value.specialties is UiAsync.Success)
        assertTrue(viewModel.state.value.exams is UiAsync.Success)
    }

    /** Un catálogo de especialidades vacío tampoco se queda girando sin el refresco del `init`. */
    @Test
    fun `sin especialidades el catalogo queda vacio y no cargando`() = runTest {
        val repository = ExamsTestRepository().apply { dropSpecialties() }
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        assertEquals(UiAsync.Empty, viewModel.state.value.specialties)
        assertEquals(1, repository.refreshSpecialtiesCalls)
    }

    /**
     * Abrir un examen pide sus preguntas UNA vez y calcula la auditoría de recursos
     * con ellas (K0-5). Antes, `refreshResourceAudit` volvía a pedir la misma lista
     * al servidor sólo para contar recursos. El «Actualizar» del camino móvil
     * (`RefreshResourceAudit`) sigue preguntando al servidor.
     */
    @Test
    fun `abrir un examen pide sus preguntas una vez y audita los recursos en memoria`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()

        assertEquals(1, repository.getExamQuestionsCalls)
        assertEquals(0, repository.getExamResourceAuditCalls, "La auditoría no puede volver a pedir las preguntas")
        val auditState = viewModel.state.value.resourceAudit
        assertTrue(auditState is UiAsync.Success, "La auditoría no se ha calculado: $auditState")
        val audit = auditState.value
        assertEquals(2, audit.summary.totalQuestions)
        assertEquals(listOf("question-tcae-1", "question-tcae-2"), audit.questions.map { it.questionId })

        viewModel.dispatch(ExamsIntent.RefreshResourceAudit)
        advanceUntilIdle()
        assertEquals(1, repository.getExamResourceAuditCalls, "El «Actualizar» móvil sigue siendo remoto")
        assertEquals(1, repository.getExamQuestionsCalls)
    }

    /**
     * Publicar y despublicar no vuelven a descargar el catálogo (K0-5): el examen
     * que devuelve el servidor ya es la fila entera. Mutación: devolver el
     * `refreshAllExamsUseCase()` a `applyLifecycleResult` pone este test en rojo.
     */
    @Test
    fun `publicar y despublicar no vuelven a descargar el catalogo`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        val antes = repository.refreshAllExamsCalls

        viewModel.dispatch(ExamsIntent.PublishExam)
        advanceUntilIdle()
        assertEquals(ExamStatus.PUBLISHED, viewModel.state.value.catalogExams.first { it.id == "exam-tcae-marzo" }.status)
        viewModel.dispatch(ExamsIntent.UnpublishExam)
        advanceUntilIdle()

        assertEquals(antes, repository.refreshAllExamsCalls)
        assertEquals(ExamStatus.DRAFT, viewModel.state.value.catalogExams.first { it.id == "exam-tcae-marzo" }.status)
    }

    /**
     * El recuento que devuelve el servidor entra en el catálogo tal cual (K2-2):
     * desde M1 `total_preguntas` lo mantiene la base y ya no se protege con el de
     * memoria. Mutación: volver a `saved.copy(questionCount = it.questionCount)` en
     * `mergeIntoCatalog` pone este test en rojo.
     */
    @Test
    fun `el recuento servido al publicar entra en el catalogo como dato`() = runTest {
        val repository = ExamsTestRepository().apply { servedQuestionCountOnLifecycle = 7 }
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.PublishExam)
        advanceUntilIdle()

        assertEquals(7, viewModel.state.value.catalogExams.first { it.id == "exam-tcae-marzo" }.questionCount)
    }

    // -----------------------------------------------------------------------
    // Ronda 2: código repetido, confirmación de guardado, duración 0, código vacío
    // -----------------------------------------------------------------------

    /**
     * Un código que ya usa otro examen no llega a enviarse (P1-3).
     *
     * La barra deshabilita el botón, pero el ViewModel no puede depender de eso:
     * `codigo_examen` es único y el servidor contestaba con un 500 genérico. La
     * regla es la de `examCodeConflict` —sin espacios ni mayúsculas, el propio
     * examen no cuenta— y el mensaje, el mismo que el del campo.
     */
    @Test
    fun `guardar con el codigo de otro examen se rechaza sin llamar al servidor`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        // Examen existente con el código de otro, con otras mayúsculas y con
        // espacios. Todo lo demás se podría guardar.
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateCode("  tcae-2026-04 "))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val existente = viewModel.state.value.actionState
        assertTrue(existente is UiAsync.Error, "Un código repetido se ha dado por bueno: $existente")
        assertEquals(
            "Ya hay otro examen con el código TCAE-2026-04.",
            (existente.error as AppError.Validation).message,
        )
        assertNull(repository.lastUpsertedExam, "El código repetido ha llegado al servidor")

        // Borrador nuevo: no hay examen propio que excluir.
        viewModel.dispatch(ExamsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateTitle("Simulacro nuevo"))
        viewModel.dispatch(ExamsIntent.UpdateSpecialtyId("specialty-tcae"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateCode("TCAE-2026-03"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val nuevo = viewModel.state.value.actionState
        assertTrue(nuevo is UiAsync.Error, "Un borrador con un código repetido se ha dado por bueno: $nuevo")
        assertEquals(
            "Ya hay otro examen con el código TCAE-2026-03.",
            (nuevo.error as AppError.Validation).message,
        )
        assertNull(repository.lastUpsertedExam, "El código repetido ha llegado al servidor")

        // Control: su propio código no choca consigo mismo.
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateCode("TCAE-2026-03"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        assertEquals(UiAsync.Success(Unit), viewModel.state.value.actionState)
        assertEquals("TCAE-2026-03", repository.lastUpsertedExam?.code)
    }

    /**
     * Tras guardar se ve «Cambios guardados.»; la siguiente edición lo retira (P2-3).
     *
     * `saveExam` ponía `actionState = Success` y, justo después, `selectExam` lo
     * devolvía a `Idle`: la confirmación no llegaba a pintarse nunca, y el test de
     * presentación afirmaba un estado que el ViewModel no producía. Se comprueba
     * con la misma función que pinta la barra.
     */
    @Test
    fun `tras guardar se ve la confirmacion y la siguiente edicion la retira`() = runTest {
        val viewModel = buildViewModel(ExamsTestRepository())
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.UpdateTitle("TCAE Marzo revisado"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        var state = viewModel.state.value
        val guardado = state.catalogExams.first { it.id == "exam-tcae-marzo" }
        assertEquals(
            UiAsync.Success(Unit),
            state.actionState,
            "La confirmación del guardado no ha sobrevivido a reabrir el examen",
        )
        assertEquals("Cambios guardados.", examSaveBarStatus(state, guardado).message?.text)

        // Una edición nueva: vuelve a haber cambios sin guardar y la confirmación
        // anterior se retira.
        viewModel.dispatch(ExamsIntent.UpdateDurationMinutes("15"))
        advanceUntilIdle()
        state = viewModel.state.value
        assertTrue(hasUnsavedExamChanges(state, guardado), "Tras editar tiene que volver a haber cambios sin guardar")
        assertEquals(UiAsync.Idle, state.actionState, "La confirmación del guardado anterior sigue puesta tras editar")
        assertEquals(EXAM_UNSAVED_CHANGES_MESSAGE, examSaveBarStatus(state, guardado).message?.text)

        // Y deshacerla a mano no resucita un «guardado» que nadie ha hecho.
        viewModel.dispatch(ExamsIntent.UpdateDurationMinutes(""))
        advanceUntilIdle()
        state = viewModel.state.value
        assertFalse(hasUnsavedExamChanges(state, guardado))
        assertNull(examSaveBarStatus(state, guardado).message)
    }

    /**
     * Una duración de 0 no se guarda (P2-7).
     *
     * El filtro de entrada sólo deja dígitos, así que «0» —y «00»— llegaban al
     * servidor como un examen de cero minutos. Vacía sí vale: es «sin duración».
     */
    @Test
    fun `guardar una duracion de cero se rechaza y vacia o positiva se guarda`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()

        listOf("0", "00").forEach { cero ->
            viewModel.dispatch(ExamsIntent.UpdateDurationMinutes(cero))
            viewModel.dispatch(ExamsIntent.SaveExam)
            advanceUntilIdle()

            val action = viewModel.state.value.actionState
            assertTrue(action is UiAsync.Error, "«$cero» minutos se ha dado por bueno: $action")
            assertEquals(
                "La duración debe ser mayor que 0 o quedar vacía.",
                (action.error as AppError.Validation).message,
            )
            assertNull(repository.lastUpsertedExam, "«$cero» minutos ha llegado al servidor")
        }

        viewModel.dispatch(ExamsIntent.UpdateDurationMinutes("45"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()
        assertEquals(45, repository.lastUpsertedExam?.durationMinutes)

        viewModel.dispatch(ExamsIntent.UpdateDurationMinutes(""))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.actionState is UiAsync.Success)
        assertNull(repository.lastUpsertedExam?.durationMinutes)
    }

    /**
     * Vaciar el código de un examen existente no es un cambio (D8).
     *
     * En un examen que ya existe, dejar el código en blanco significa «conservar
     * el que tiene»: guardar así no toca nada, así que cambiar de examen no tiene
     * nada que perder y no pregunta. Escribir OTRO código sí es un cambio.
     */
    @Test
    fun `vaciar el codigo de un examen existente no pide confirmacion al cambiar de examen`() = runTest {
        val viewModel = buildViewModel(ExamsTestRepository())
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-marzo"))
        advanceUntilIdle()

        // Control: otro código sí se protege.
        viewModel.dispatch(ExamsIntent.UpdateCode("EX-OTRO"))
        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-abril"))
        advanceUntilIdle()
        assertEquals(PendingDeleteTarget.SwitchExam("exam-tcae-abril"), viewModel.state.value.pendingDeleteTarget)
        viewModel.dispatch(ExamsIntent.DismissPendingDelete)
        advanceUntilIdle()

        // Vaciarlo significa «conservar»: no hay nada que perder.
        viewModel.dispatch(ExamsIntent.UpdateCode(""))
        viewModel.dispatch(ExamsIntent.SwitchExam("exam-tcae-abril"))
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingDeleteTarget, "Vaciar el código ha pedido confirmación")
        assertEquals("exam-tcae-abril", viewModel.state.value.selectedExamId)
    }

    /**
     * Una edición retira el error del guardado; guardar de nuevo lo confirma.
     *
     * «Ya hay otro examen con el código X» describe un intento sobre una versión
     * del formulario que ya no existe: con el código corregido, seguir diciéndolo
     * es un estado falso. Pero mientras nadie toque el formulario el error se
     * queda: recargar los catálogos o reintentar las capacidades de la misma
     * especialidad no son ediciones.
     */
    @Test
    fun `una edicion retira el error del guardado y guardar de nuevo lo confirma`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        val rechazo = "Ya hay otro examen con el código TCAE-2026-04."

        viewModel.dispatch(ExamsIntent.UpdateCode("TCAE-2026-04"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()
        var state = viewModel.state.value
        val guardado = state.catalogExams.first { it.id == "exam-tcae-marzo" }
        assertEquals(rechazo, examSaveBarStatus(state, guardado).message?.text)

        // Sin tocar el formulario, el error sigue a la vista.
        viewModel.dispatch(ExamsIntent.RefreshAll)
        viewModel.dispatch(ExamsIntent.UpdateSpecialtyId("specialty-tcae"))
        advanceUntilIdle()
        state = viewModel.state.value
        assertEquals(
            rechazo,
            examSaveBarStatus(state, guardado).message?.text,
            "El error del guardado se ha retirado sin que nadie editara el formulario",
        )

        // Corregir el código lo retira: la barra dice lo que hay ahora.
        viewModel.dispatch(ExamsIntent.UpdateCode("TCAE-2026-05"))
        advanceUntilIdle()
        state = viewModel.state.value
        assertEquals(UiAsync.Idle, state.actionState, "El error del guardado anterior sigue puesto tras corregir el código")
        assertEquals(EXAM_UNSAVED_CHANGES_MESSAGE, examSaveBarStatus(state, guardado).message?.text)

        // Y guardar de nuevo, con éxito, lo confirma.
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()
        state = viewModel.state.value
        assertEquals("TCAE-2026-05", repository.lastUpsertedExam?.code)
        assertEquals(
            "Cambios guardados.",
            examSaveBarStatus(state, state.catalogExams.first { it.id == "exam-tcae-marzo" }).message?.text,
        )
    }

    /**
     * El `Error` de OTRA acción no lo retira una edición del formulario.
     *
     * `actionState` lo comparten guardar el examen y las acciones sobre sus
     * preguntas. Que no se haya podido quitar una pregunta no se arregla
     * cambiando el título: ese error se queda hasta la siguiente acción.
     */
    @Test
    fun `una edicion no retira el error de otra accion`() = runTest {
        val repository = ExamsTestRepository().apply { unlinkShouldFail = true }
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.RequestUnlinkQuestion("question-tcae-1"))
        viewModel.dispatch(ExamsIntent.ConfirmPendingDelete)
        advanceUntilIdle()
        val fallo = viewModel.state.value.actionState
        assertTrue(fallo is UiAsync.Error, "Quitar la pregunta tenía que fallar: $fallo")

        viewModel.dispatch(ExamsIntent.UpdateTitle("TCAE Marzo revisado"))
        advanceUntilIdle()

        assertEquals(
            fallo,
            viewModel.state.value.actionState,
            "Editar el formulario ha retirado el fallo de quitar una pregunta",
        )
    }
}

private class ExamsTestRepository(
    /**
     * Lo que devuelve `deleteExam`. Por defecto borra; los casos que prueban el
     * rechazo del servidor —«los usuarios ya lo han usado»— inyectan el fallo con
     * su código de dominio.
     */
    private val deleteResult: AppResult<Unit> = AppResult.Success(Unit),
) : ContentRepository, CommunitiesRepository {
    private val now = Instant.parse("2026-04-15T09:00:00Z")
    var lastUpsertedExam: Exam? = null
        private set

    /** Ids borrados y archivados, por separado: las dos acciones no pueden confundirse. */
    val deleted = mutableListOf<String>()
    val archived = mutableListOf<String>()

    private val specialties = MutableStateFlow(
        listOf(
            Specialty(
                id = "specialty-tcae",
                name = "TCAE",
                description = "",
                isActive = true,
                sortOrder = 0,
                linkedExams = 1,
                createdAt = now,
                updatedAt = now,
            ),
        ),
    )
    private val exams = MutableStateFlow(
        listOf(
            Exam(
                id = "exam-tcae-marzo",
                code = "TCAE-2026-03",
                specialtyId = "specialty-tcae",
                communityId = "com-test",
                specialtyName = "TCAE",
                title = "TCAE Marzo",
                description = "",
                typeExam = "rafaga",
                priceTickets = 0,
                status = ExamStatus.DRAFT,
                publishFrom = null,
                publishedAt = null,
                sortOrder = 0,
                questionCount = 2,
                createdAt = now,
                updatedAt = now,
            ),
            // Segundo examen, `razonado` y COMPARTIENDO la primera pregunta con
            // el anterior. Es lo que hace comprobable la diferencia entre quitar
            // del examen y archivar: sin dos exámenes que compartan una pregunta,
            // las dos operaciones se ven idénticas.
            Exam(
                id = "exam-tcae-abril",
                code = "TCAE-2026-04",
                specialtyId = "specialty-tcae",
                communityId = "com-test",
                specialtyName = "TCAE",
                title = "TCAE Abril",
                description = "",
                typeExam = "razonado",
                priceTickets = 0,
                status = ExamStatus.DRAFT,
                publishFrom = null,
                publishedAt = null,
                sortOrder = 1,
                questionCount = 1,
                createdAt = now,
                updatedAt = now,
            ),
            // Tercero, con un tipo que el sistema no reconoce y sin preguntas:
            // abrirlo no puede disfrazarlo de otro tipo, y su lista vacía también
            // es un recuento (0).
            Exam(
                id = "exam-mixto",
                code = "TCAE-MIXTO",
                specialtyId = "specialty-tcae",
                communityId = "com-test",
                specialtyName = "TCAE",
                title = "TCAE Mixto",
                description = "",
                typeExam = "mixto",
                priceTickets = 0,
                status = ExamStatus.DRAFT,
                publishFrom = null,
                publishedAt = null,
                sortOrder = 2,
                questionCount = 0,
                createdAt = now,
                updatedAt = now,
            ),
        ),
    )
    private val rafagas = MutableStateFlow<List<Rafaga>>(emptyList())
    private val communities = MutableStateFlow<List<Community>>(emptyList())
    private val rafagaQuestions = mutableMapOf<String, MutableList<RafagaQuestion>>()
    private val questions = mutableMapOf(
        "exam-tcae-marzo" to mutableListOf(
            question("question-tcae-1", "exam-tcae-marzo", 0),
            question("question-tcae-2", "exam-tcae-marzo", 1),
        ),
        // `question-tcae-1` está en los dos exámenes: una sola pregunta del banco
        // vinculada dos veces, que es lo que `preguntas_examen` permite.
        "exam-tcae-abril" to mutableListOf(
            question("question-tcae-1", "exam-tcae-abril", 0),
        ),
    )

    override fun observeSpecialties(): Flow<List<Specialty>> = specialties
    override fun observeExams(): Flow<List<Exam>> = exams

    /**
     * Explícito, y no por el default de la interfaz (que delega en
     * [observeExams]): es la fuente que observa el ViewModel, y el test de la
     * portada necesita controlar qué llega a ella.
     */
    override fun observeAllExams(): Flow<List<Exam>> = exams
    override fun observeRafagas(): Flow<List<Rafaga>> = rafagas
    override fun observeCommunities(): Flow<List<Community>> = communities

    /**
     * Cuántas veces se ha pedido cada lectura (K0-5, EX-13). Abrir la pantalla o
     * un examen no puede duplicar peticiones: cada una es un catálogo completo o
     * una lista de preguntas entera contra el servidor.
     */
    var refreshAllExamsCalls = 0
        private set
    var refreshSpecialtiesCalls = 0
        private set
    var refreshCommunitiesCalls = 0
        private set
    var refreshRafagasCalls = 0
        private set
    var getExamQuestionsCalls = 0
        private set
    var getExamResourceAuditCalls = 0
        private set

    override suspend fun refreshCommunities(): AppResult<List<Community>> {
        refreshCommunitiesCalls++
        return if (communitiesShouldFail) AppResult.Failure(AppError.Network("sin conexión")) else AppResult.Success(communities.value)
    }
    override suspend fun upsertCommunity(community: Community): AppResult<Community> = AppResult.Success(community)
    override suspend fun deleteCommunity(communityId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun refreshSpecialties(): AppResult<List<Specialty>> {
        refreshSpecialtiesCalls++
        return if (specialtiesShouldFail) AppResult.Failure(AppError.Network("sin conexión")) else AppResult.Success(specialties.value)
    }

    /** Explícito para contarlo: es la lectura del catálogo que hace el ViewModel. */
    override suspend fun refreshAllExams(): AppResult<List<Exam>> {
        refreshAllExamsCalls++
        return AppResult.Success(exams.value)
    }
    override suspend fun refreshExams(): AppResult<List<Exam>> = AppResult.Success(exams.value)
    override suspend fun refreshRafagas(): AppResult<List<Rafaga>> {
        refreshRafagasCalls++
        return AppResult.Success(rafagas.value)
    }
    override suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>> {
        getExamQuestionsCalls++
        return AppResult.Success(questions[examId].orEmpty())
    }
    override suspend fun getRafagaQuestions(rafagaId: String): AppResult<List<RafagaQuestion>> {
        return AppResult.Success(rafagaQuestions[rafagaId].orEmpty())
    }

    /**
     * La auditoría REMOTA. Desde K0-5 sólo la pide el «Actualizar» del camino móvil
     * (`RefreshResourceAudit`); abrir un examen la calcula con sus preguntas.
     */
    override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> {
        getExamResourceAuditCalls++
        val rows = questions[examId].orEmpty()
        val audits = rows.map { question ->
            QuestionResourceAudit(
                questionId = question.id,
                questionTitle = question.title,
                resourceCount = question.resources.size,
                pendingResourceCount = 0,
                hasLinkedResources = question.resources.isNotEmpty(),
            )
        }
        return AppResult.Success(
            ExamResourceAudit(
                summary = ExamResourceSummary(
                    totalQuestions = rows.size,
                    questionsWithResources = audits.count { it.hasLinkedResources },
                    questionsWithoutResources = audits.count { !it.hasLinkedResources },
                    linkedResources = rows.sumOf { it.resources.size },
                    pendingResources = 0,
                    unlinkedResources = 0,
                ),
                questions = audits,
                unlinkedResources = emptyList(),
            ),
        )
    }
    override suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty> = AppResult.Success(specialty)
    override suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit> = AppResult.Success(Unit)

    // Esta especialidad sólo permite ráfaga: es lo que ejercitan los tests de
    // validación de tipo de examen contra capacidades_especialidad.
    var capabilitiesShouldFail = false

    // Los tests del guardián de borrador nuevo necesitan cambiarlo: uno elige un
    // segundo tipo permitido y otro abre con una especialidad que NO permite
    // ráfaga, para que el propio ViewModel mueva el tipo por su cuenta.
    var permiteRafagas = true
    var permiteRazonados = false

    /** Si no es `null`, esa especialidad sólo permite razonado. */
    var onlyRazonadosIn: String? = null

    /**
     * Retiene las capacidades hasta que se complete. Abre la ventana de
     * `selectExam` en la que se pedían las capacidades antes que las preguntas.
     */
    var capabilitiesGate: CompletableDeferred<Unit>? = null

    /**
     * Si un cambio de portada llega a la fuente que observa el ViewModel. `false`
     * reproduce el repositorio real anterior a P1-IB-2, que sólo tocaba
     * `examsFlow`: el ViewModel tiene que alinear el catálogo por su cuenta.
     */
    var coverChangesReachObservedCatalog = true
    var communitiesShouldFail = false
    var specialtiesShouldFail = false

    /** Hace fallar «Quitar del examen»: un `Error` en `actionState` que NO viene de guardar. */
    var unlinkShouldFail = false

    fun seedCommunities(values: List<Community>) {
        communities.value = values
    }

    fun dropSpecialties() {
        specialties.value = emptyList()
    }

    fun addSpecialty(id: String, name: String) {
        specialties.value = specialties.value + Specialty(
            id = id,
            name = name,
            description = "",
            isActive = true,
            sortOrder = specialties.value.size,
            linkedExams = 0,
            createdAt = now,
            updatedAt = now,
        )
    }

    override suspend fun getSpecialtyCapabilities(specialtyId: String): AppResult<SpecialtyCapabilities> {
        capabilitiesGate?.await()
        if (capabilitiesShouldFail) {
            return AppResult.Failure(AppError.Network("sin conexión"))
        }
        val onlyRazonados = specialtyId == onlyRazonadosIn
        return AppResult.Success(
            SpecialtyCapabilities(
                permiteRafagas = permiteRafagas && !onlyRazonados,
                permiteRazonados = permiteRazonados || onlyRazonados,
                permiteInmersivos = false,
                permiteDemos = true,
                permiteOposimarket = true,
                specialtyId = specialtyId,
                configured = false,
            ),
        )
    }

    override suspend fun saveSpecialtyCapabilities(
        capabilities: SpecialtyCapabilities,
    ): AppResult<SpecialtyCapabilities> = AppResult.Success(capabilities)

    override suspend fun upsertExam(exam: Exam): AppResult<Exam> {
        lastUpsertedExam = exam
        exams.value = if (exams.value.any { it.id == exam.id }) {
            exams.value.map { if (it.id == exam.id) exam else it }
        } else {
            exams.value + exam
        }
        return AppResult.Success(exam)
    }
    override suspend fun deleteExam(examId: String): AppResult<Unit> {
        if (deleteResult is AppResult.Failure) return deleteResult
        deleted += examId
        exams.value = exams.value.filterNot { it.id == examId }
        return deleteResult
    }

    override suspend fun archiveExam(examId: String): AppResult<Exam> {
        archived += examId
        val updated = exams.value.first { it.id == examId }
            .copy(status = ExamStatus.ARCHIVED, publishedAt = null)
        exams.value = exams.value.map { if (it.id == examId) updated else it }
        return AppResult.Success(updated)
    }

    /**
     * Si no es `null`, el `total_preguntas` que «sirve» el servidor al publicar o
     * despublicar (K2-2). Entonces el cambio llega SÓLO por la respuesta y no por
     * la fuente observada —como `coverChangesReachObservedCatalog = false`—: así lo
     * único que puede poner esa cifra en el catálogo es el propio ViewModel.
     */
    var servedQuestionCountOnLifecycle: Int? = null

    // Publicar/despublicar reales: no pasan por upsertExam, así que se modelan
    // aparte para que un test que use PublishExam nunca vea tocado lastUpsertedExam.
    override suspend fun publishExam(examId: String): AppResult<Exam> {
        val updated = exams.value.first { it.id == examId }.copy(status = ExamStatus.PUBLISHED, publishedAt = now)
        return lifecycleResult(examId, updated)
    }
    override suspend fun unpublishExam(examId: String): AppResult<Exam> {
        val updated = exams.value.first { it.id == examId }.copy(status = ExamStatus.DRAFT, publishedAt = null)
        return lifecycleResult(examId, updated)
    }

    private fun lifecycleResult(examId: String, updated: Exam): AppResult<Exam> {
        val served = servedQuestionCountOnLifecycle
        if (served != null) return AppResult.Success(updated.copy(questionCount = served))
        exams.value = exams.value.map { exam -> if (exam.id == examId) updated else exam }
        return AppResult.Success(updated)
    }
    override suspend fun upsertRafaga(rafaga: Rafaga): AppResult<Rafaga> = AppResult.Success(rafaga)
    override suspend fun deleteRafaga(rafagaId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun uploadExamCover(examId: String, localPathOrUri: String): AppResult<Exam> {
        val saved = exams.value.first { it.id == examId }.copy(
            coverUrl = "https://storage.local/exam-covers/$examId/cover.png",
            coverStoragePath = "exam-covers/$examId/cover.png",
            coverMimeType = "image/png",
        )
        if (coverChangesReachObservedCatalog) exams.value = exams.value.map { if (it.id == examId) saved else it }
        return AppResult.Success(saved)
    }
    override suspend fun clearExamCover(examId: String): AppResult<Exam> {
        val saved = exams.value.first { it.id == examId }.copy(
            coverUrl = null,
            coverStoragePath = null,
            coverMimeType = null,
        )
        if (coverChangesReachObservedCatalog) exams.value = exams.value.map { if (it.id == examId) saved else it }
        return AppResult.Success(saved)
    }
    override suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion> {
        val bucket = questions.getOrPut(question.examId) { mutableListOf() }
        bucket.removeAll { it.id == question.id }
        bucket += question
        return AppResult.Success(question)
    }
    override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> {
        val bucket = this.questions.getOrPut(examId) { mutableListOf() }
        bucket += questions
        return AppResult.Success(questions)
    }
    /**
     * Desvincular toca UN bucket y recompacta posiciones, igual que el endpoint.
     *
     * La recompactación importa para el test: si el fake dejara huecos, la
     * pantalla podría estar enseñando posiciones inventadas y la prueba no se
     * enteraría.
     */
    override suspend fun unlinkQuestionFromExam(examId: String, questionId: String): AppResult<Unit> {
        if (unlinkShouldFail) return AppResult.Failure(AppError.Network("sin conexión"))
        val bucket = questions[examId] ?: return AppResult.Success(Unit)
        bucket.removeAll { it.id == questionId }
        val recompacted = bucket.sortedBy { it.sortOrder }.mapIndexed { index, q -> q.copy(sortOrder = index) }
        bucket.clear()
        bucket += recompacted
        return AppResult.Success(Unit)
    }

    /** Archivar toca TODOS los buckets. Es la diferencia que el test tiene que poder ver. */
    override suspend fun archiveQuestion(questionId: String): AppResult<Unit> {
        questions.values.forEach { bucket -> bucket.removeAll { it.id == questionId } }
        return AppResult.Success(Unit)
    }

    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<ExamQuestion>> = AppResult.Success(questions[examId].orEmpty())
    override suspend fun upsertRafagaQuestion(question: RafagaQuestion): AppResult<RafagaQuestion> {
        val bucket = rafagaQuestions.getOrPut(question.rafagaId) { mutableListOf() }
        bucket.removeAll { it.id == question.id }
        bucket += question
        return AppResult.Success(question)
    }
    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<RafagaQuestion>> = AppResult.Success(rafagaQuestions[rafagaId].orEmpty())
    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ): AppResult<QuestionResource> {
        return AppResult.Success(
            QuestionResource(
                id = resourceId,
                questionId = questionId,
                type = type,
                title = title,
                textBody = null,
                storagePath = localPathOrUrl,
                publicUrl = localPathOrUrl,
                mimeType = null,
                sortOrder = 0,
            ),
        )
    }
    override suspend fun deleteResource(questionId: String, resourceId: String): AppResult<Unit> = AppResult.Success(Unit)

    private fun question(id: String, examId: String, order: Int): ExamQuestion {
        return ExamQuestion(
            id = id,
            examId = examId,
            title = "Pregunta ${order + 1}",
            statement = "Enunciado ${order + 1}",
            sortOrder = order,
            options = listOf("A", "B", "C", "D").mapIndexed { index, label ->
                QuestionOption(
                    id = "$id-option-$label",
                    questionId = id,
                    label = label,
                    content = "Opcion $label",
                    isCorrect = index == 0,
                    sortOrder = index,
                )
            },
            resources = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
    }
}
