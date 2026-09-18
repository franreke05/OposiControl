package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlin.time.Instant

/**
 * Los estados con los que se renderiza Especialidades en el banco de pruebas
 * visual.
 *
 * **Son estados FABRICADOS.** Ni una línea de este fichero toca un repositorio,
 * una ruta ni un contrato: que una captura se vea bien no dice nada sobre si la
 * pantalla lee bien de Supabase.
 *
 * Lo que sí se ha copiado del entorno vivo son las cifras, para que las capturas
 * enseñen la forma que la pantalla tiene con datos reales y no con tres ceros:
 * TCAE tiene 4 exámenes (3 ráfaga, 1 inmersivo, 0 razonado), «prueba» tiene 2 y
 * «e» ninguno. El estado archivado de «e» y las descripciones sí están
 * inventados: hacen falta para poder capturar el chip de archivada y una fila con
 * descripción larga, y no se afirma que sean los de producción.
 */
internal object SpecialtiesFixtures {

    const val TCAE_ID: String = "esp-tcae"
    private const val PRUEBA_ID: String = "esp-prueba"
    private const val VACIA_ID: String = "esp-e"

    private val NOW: Instant = Instant.parse("2026-09-14T09:00:00Z")

    private val tcae = Specialty(
        id = TCAE_ID,
        name = "TCAE",
        description = "Técnico en cuidados auxiliares de enfermería · turno libre",
        isActive = true,
        sortOrder = 1,
        linkedExams = 4,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private val prueba = Specialty(
        id = PRUEBA_ID,
        name = "Prueba",
        description = "Especialidad de trabajo para validar importaciones",
        isActive = true,
        sortOrder = 2,
        linkedExams = 2,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private val vacia = Specialty(
        id = VACIA_ID,
        name = "e",
        // Descripción en blanco a propósito: es el caso en el que la lectura la
        // rellena con el slug, y la fila tiene que enseñar la ausencia.
        description = "",
        isActive = false,
        sortOrder = 3,
        linkedExams = 0,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private val catalog = listOf(tcae, prueba, vacia)

    private val exams: List<Exam> = buildList {
        repeat(3) { index -> add(exam("tcae-rafaga-$index", TCAE_ID, "TCAE", "rafaga")) }
        add(exam("tcae-inmersivo-0", TCAE_ID, "TCAE", "inmersivo"))
        repeat(2) { index -> add(exam("prueba-rafaga-$index", PRUEBA_ID, "Prueba", "rafaga")) }
    }

    private fun exam(id: String, specialtyId: String, specialtyName: String, typeExam: String) = Exam(
        id = id,
        code = "EX-${id.uppercase()}",
        specialtyId = specialtyId,
        specialtyName = specialtyName,
        title = "Simulacro $id",
        description = "",
        typeExam = typeExam,
        priceTickets = 0,
        status = ExamStatus.PUBLISHED,
        publishFrom = null,
        publishedAt = NOW,
        sortOrder = 0,
        questionCount = 20,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun capabilities(
        rafagas: Boolean = true,
        razonados: Boolean = false,
        inmersivos: Boolean = true,
        demos: Boolean = true,
        oposimarket: Boolean = false,
        limitationMessage: String? = null,
        configured: Boolean = true,
    ) = SpecialtyCapabilities(
        permiteRafagas = rafagas,
        permiteRazonados = razonados,
        permiteInmersivos = inmersivos,
        permiteDemos = demos,
        permiteOposimarket = oposimarket,
        specialtyId = TCAE_ID,
        limitationMessage = limitationMessage,
        configured = configured,
    )

    /** El caso normal: TCAE abierta, limpia, con sus tipos cargados. */
    fun especialidadAbierta(): SpecialtiesState = base()

    /**
     * Cambios sin guardar en LAS DOS mitades.
     *
     * Es el estado que justifica que la barra enumere dos escrituras: el nombre
     * ha cambiado (y por tanto el slug público) y además se ha apagado un tipo.
     */
    fun cambiosSinGuardar(): SpecialtiesState = base().copy(
        editorName = "TCAE turno libre",
        capabilitiesDraft = capabilities(inmersivos = false),
    )

    /** Nada seleccionado: el catálogo está, el editor no tiene qué enseñar. */
    fun sinSeleccion(): SpecialtiesState = base().copy(
        selectedId = null,
        creatingNew = false,
        editorName = "",
        editorDescription = "",
        editorSortOrder = "0",
        editorIsActive = true,
        capabilities = UiAsync.Idle,
        capabilitiesDraft = null,
    )

    /** Un alta en blanco: no hay id, así que todavía no hay tipos que configurar. */
    fun nuevaEspecialidad(): SpecialtiesState = base().copy(
        selectedId = null,
        creatingNew = true,
        editorName = "",
        editorDescription = "",
        editorSortOrder = "3",
        editorIsActive = true,
        capabilities = UiAsync.Idle,
        capabilitiesDraft = null,
    )

    /** Cargando: ni catálogo, ni tipos, ni recuentos. */
    fun cargando(): SpecialtiesState = SpecialtiesState(
        items = UiAsync.Loading,
        capabilities = UiAsync.Loading,
        exams = UiAsync.Loading,
    )

    /** Vacío confirmado: se ha hablado con el servidor y no hay ninguna especialidad. */
    fun vacio(): SpecialtiesState = SpecialtiesState(
        items = UiAsync.Empty,
        catalog = emptyList(),
        exams = UiAsync.Success(emptyList()),
    )

    /** El catálogo no se ha podido leer. Con `Network` la tarjeta de error ofrece reintento. */
    fun error(): SpecialtiesState = SpecialtiesState(
        items = UiAsync.Error(AppError.Network("No se ha podido conectar con el panel.", code = 503)),
        exams = UiAsync.Error(AppError.Network("No se ha podido conectar con el panel.", code = 503)),
    )

    /** Los tres tipos apagados sin mensaje: el 422 del servidor, avisado antes de pulsar. */
    fun sinTiposAbiertos(): SpecialtiesState = base().copy(
        capabilitiesDraft = capabilities(rafagas = false, razonados = false, inmersivos = false),
    )

    /** Un tipo apagado que tiene exámenes detrás: el aviso que da sentido al desglose. */
    fun tipoApagadoConExamenes(): SpecialtiesState = base().copy(
        capabilitiesDraft = capabilities(
            rafagas = false,
            inmersivos = true,
            limitationMessage = "Las ráfagas de esta especialidad están en revisión.",
        ),
    )

    /** Las capacidades no se han podido leer; el resto de la pantalla sigue usable. */
    fun capacidadesCaidas(): SpecialtiesState = base().copy(
        capabilities = UiAsync.Error(AppError.Network("capacidades_especialidad no responde.", code = 503)),
        capabilitiesDraft = null,
    )

    /** La lista de exámenes no se ha podido leer: el desglose se declara, no se inventa. */
    fun examenesCaidos(): SpecialtiesState = base().copy(
        exams = UiAsync.Error(AppError.Network("No se ha podido leer la lista de exámenes.", code = 503)),
    )

    /** Tipos que nadie ha configurado: lo que se ve son los valores por defecto. */
    fun sinConfigurar(): SpecialtiesState {
        val defaults = capabilities(razonados = true, configured = false)
        return base().copy(
            capabilities = UiAsync.Success(defaults),
            capabilitiesDraft = defaults,
        )
    }

    /** Una especialidad ya archivada y abierta. */
    fun archivada(): SpecialtiesState = base().copy(
        selectedId = VACIA_ID,
        editorName = vacia.name,
        editorDescription = vacia.description,
        editorSortOrder = vacia.sortOrder.toString(),
        editorIsActive = false,
    )

    /** El diálogo de archivado, con el texto que de verdad describe lo que pasa. */
    fun dialogoArchivar(): SpecialtiesState = base().copy(
        pendingAction = PendingSpecialtyAction.Archive,
        pendingMessage = "«TCAE» pasará a archivada: desaparecerá del catálogo público y seguirá en esta " +
            "lista. No se borra nada, y sus exámenes publicados se siguen sirviendo.",
    )

    /** El diálogo de cambios sin guardar al cambiar de fila. */
    fun dialogoCambiarDeFila(): SpecialtiesState = cambiosSinGuardar().copy(
        pendingAction = PendingSpecialtyAction.Switch(PRUEBA_ID),
        pendingMessage = "Hay cambios sin guardar en «TCAE turno libre». Dos escrituras independientes: " +
            "Identidad · Tipos de examen. Si la segunda falla, la primera ya estará guardada.",
    )

    /** El catálogo filtrado a cero: hay datos detrás, pero ninguno coincide. */
    fun catalogoSinResultados(): SpecialtiesState = base().copy(
        searchQuery = "bombero",
        items = UiAsync.Empty,
    )

    /** Sólo archivadas: el filtro que existe porque archivar no saca la fila de la lista. */
    fun soloArchivadas(): SpecialtiesState = base().copy(
        statusFilter = SpecialtyStatusFilter.ARCHIVED,
        items = UiAsync.Success(
            applySpecialtyFilters(catalog, "", SpecialtyStatusFilter.ARCHIVED),
        ),
        selectedId = VACIA_ID,
        editorName = vacia.name,
        editorDescription = vacia.description,
        editorSortOrder = vacia.sortOrder.toString(),
        editorIsActive = false,
    )

    private fun base(): SpecialtiesState {
        val loaded = capabilities()
        return SpecialtiesState(
            items = UiAsync.Success(applySpecialtyFilters(catalog, "", SpecialtyStatusFilter.ALL)),
            catalog = catalog,
            selectedId = TCAE_ID,
            editorName = tcae.name,
            editorDescription = tcae.description,
            editorSortOrder = tcae.sortOrder.toString(),
            editorIsActive = tcae.isActive,
            capabilities = UiAsync.Success(loaded),
            capabilitiesDraft = loaded,
            exams = UiAsync.Success(exams),
        )
    }
}
