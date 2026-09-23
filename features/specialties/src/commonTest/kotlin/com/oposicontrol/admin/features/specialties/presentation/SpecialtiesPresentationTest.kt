package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La capa pura de Especialidades.
 *
 * Aquí se mide lo que decide la pantalla sin pintar nada: el reparto de
 * columnas, el recorte del catálogo, el desglose por tipo, las validaciones que
 * corresponden a reglas reales del servidor y lo que dice la barra de guardado.
 * Nada de esto necesita Compose, y por eso está fuera de los composables: un
 * cálculo escrito dentro de un `@Composable` sólo se comprueba mirando la
 * pantalla.
 */
class SpecialtiesPresentationTest {

    // -----------------------------------------------------------------------
    // Columnas
    // -----------------------------------------------------------------------

    @Test
    fun `bajo el regimen compacto las columnas se apilan`() {
        val layout = specialtiesColumns(totalWidth = 1000.dp, isWide = false, supportsMasterDetail = false)
        assertTrue(layout.stacked)
        assertFalse(layout.showRail)
        assertEquals(1000.dp, layout.catalogWidth)
    }

    @Test
    fun `en ventana ancha caben las tres columnas y el editor pasa del minimo`() {
        // 1920 menos la sidebar y los márgenes de DesktopShell deja ~1610dp de
        // contenido; el reparto tiene que dar rail y un editor cómodo.
        val layout = specialtiesColumns(totalWidth = 1610.dp, isWide = true, supportsMasterDetail = true)
        assertFalse(layout.stacked)
        assertTrue(layout.showRail, "A 1610dp de contenido el rail tiene que caber")
        assertEquals(SpecialtiesSizing.catalogWide, layout.catalogWidth)
        assertEquals(SpecialtiesSizing.railWide, layout.railWidth)
        assertTrue(
            layout.editorWidth >= SpecialtiesSizing.editorMinWidth,
            "El editor se ha quedado en ${layout.editorWidth}",
        )
    }

    @Test
    fun `cuando no cabe todo se pliega el rail y nunca el editor`() {
        // 1600 de ventana deja ~1300dp de contenido: con rail el editor bajaría
        // de 700, así que el rail desaparece y el editor se queda con el resto.
        val layout = specialtiesColumns(totalWidth = 1300.dp, isWide = true, supportsMasterDetail = true)
        assertFalse(layout.showRail)
        assertTrue(
            layout.editorWidth >= SpecialtiesSizing.editorMinWidth,
            "Plegar el rail tiene que devolver el ancho al editor: ${layout.editorWidth}",
        )
        assertEquals(1300.dp - SpecialtiesSizing.catalogWide - SpecialtiesSizing.columnGap, layout.editorWidth)
    }

    @Test
    fun `el regimen normal no pinta rail aunque la ventana sea larga`() {
        // STANDARD no es WIDE: el rail es una tercera columna de lectura y en
        // este régimen el ancho se le da al editor.
        val layout = specialtiesColumns(totalWidth = 1400.dp, isWide = false, supportsMasterDetail = true)
        assertFalse(layout.showRail)
        assertEquals(SpecialtiesSizing.catalogStandard, layout.catalogWidth)
    }

    @Test
    fun `una fila repartida a partes iguales comparte los bordes`() {
        val cells = specialtyRowCells(totalWidth = 1000, gap = 16, count = 3)
        assertEquals(3, cells.size)
        assertEquals(0, cells.first().start)
        assertEquals(1000, cells.last().end)
        // Sin deriva de redondeo: el hueco entre celdas es exactamente el pedido.
        assertEquals(16, cells[1].start - cells[0].end)
        assertEquals(16, cells[2].start - cells[1].end)
    }

    // -----------------------------------------------------------------------
    // Catálogo
    // -----------------------------------------------------------------------

    @Test
    fun `el catalogo se ordena por posicion y busca en nombre y descripcion`() {
        val items = listOf(
            specialty(id = "b", name = "Bombero", sortOrder = 2),
            specialty(id = "a", name = "Auxiliar", description = "Administración general", sortOrder = 1),
        )
        val all = applySpecialtyFilters(items, query = "", filter = SpecialtyStatusFilter.ALL)
        assertEquals(listOf("a", "b"), all.map { it.id }, "Ordena por `posicion`, no por orden de llegada")

        val byName = applySpecialtyFilters(items, query = "bomb", filter = SpecialtyStatusFilter.ALL)
        assertEquals(listOf("b"), byName.map { it.id })

        val byDescription = applySpecialtyFilters(items, query = "administración", filter = SpecialtyStatusFilter.ALL)
        assertEquals(listOf("a"), byDescription.map { it.id })
    }

    @Test
    fun `el filtro de estado separa activas de archivadas`() {
        val items = listOf(
            specialty(id = "a", name = "Activa", isActive = true),
            specialty(id = "z", name = "Archivada", isActive = false),
        )
        assertEquals(
            listOf("a"),
            applySpecialtyFilters(items, "", SpecialtyStatusFilter.ACTIVE).map { it.id },
        )
        // El filtro de archivadas es la ÚNICA forma de encontrar lo archivado:
        // archivar no saca la fila de la lista.
        assertEquals(
            listOf("z"),
            applySpecialtyFilters(items, "", SpecialtyStatusFilter.ARCHIVED).map { it.id },
        )
    }

    @Test
    fun `una descripcion que en realidad es el slug se cuenta como ausencia`() {
        // El backend rellena la descripción vacía con el slug al leer. Pintarlo
        // como descripción real haría creer que alguien escribió «tcae-2026».
        val withSlug = specialty(name = "TCAE 2026", description = "tcae-2026")
        assertNull(specialtyRowDescription(withSlug))
        assertNull(specialtyRowDescription(specialty(description = "   ")))
        assertEquals("Turno libre", specialtyRowDescription(specialty(description = "Turno libre")))
    }

    @Test
    fun `el slug replica el defecto de acentos del servidor`() {
        // No es un descuido: el aviso de renombrado tiene que enseñar el slug que
        // el servidor va a escribir de verdad, y `toSlug()` de AdminV1 no
        // normaliza acentos.
        assertEquals("t-cnico", specialtySlugOf("Técnico"))
        assertEquals("auxiliar-administrativo", specialtySlugOf("Auxiliar Administrativo"))
        assertEquals("tcae-2026", specialtySlugOf("TCAE 2026"))
        // El mismo `ifBlank { "item" }` del servidor: un nombre sin ni una letra
        // latina no produce slug vacío, produce «item».
        assertEquals("item", specialtySlugOf("··"))
    }

    @Test
    fun `un recuento de cero se cuenta como ausencia y nunca como un cero suelto`() {
        assertEquals("Sin exámenes", specialtyExamCountLabel(0))
        assertEquals("1 examen", specialtyExamCountLabel(1))
        assertEquals("4 exámenes", specialtyExamCountLabel(4))
    }

    // -----------------------------------------------------------------------
    // Desglose por tipo
    // -----------------------------------------------------------------------

    @Test
    fun `el desglose agrupa por tipo canonico y no pierde el alias legacy`() {
        val exams = listOf(
            exam(id = "1", specialtyId = "tcae", typeExam = "rafaga"),
            exam(id = "2", specialtyId = "tcae", typeExam = "rafaga"),
            exam(id = "3", specialtyId = "tcae", typeExam = "inmersivo"),
            // `guiado` es el alias legacy de razonado que aún vive en la base: si
            // el desglose no lo reconociera, este examen desaparecería del
            // recuento y nadie sabría que apagar «Razonado» le afecta.
            exam(id = "4", specialtyId = "tcae", typeExam = "guiado"),
            exam(id = "5", specialtyId = "otra", typeExam = "rafaga"),
        )
        val breakdown = specialtyExamBreakdown(exams, "tcae")
        assertEquals(4, breakdown.total, "El examen de otra especialidad no cuenta")
        assertEquals(2, breakdown.countOf(SpecialtyExamType.RAFAGA))
        assertEquals(1, breakdown.countOf(SpecialtyExamType.RAZONADO))
        assertEquals(1, breakdown.countOf(SpecialtyExamType.INMERSIVO))
        assertEquals(0, breakdown.unknown)
    }

    @Test
    fun `un tipo que no se reconoce se cuenta aparte en vez de repartirse`() {
        val exams = listOf(exam(id = "1", specialtyId = "tcae", typeExam = "sorpresa"))
        val breakdown = specialtyExamBreakdown(exams, "tcae")
        assertEquals(1, breakdown.total)
        assertEquals(1, breakdown.unknown)
        SpecialtyExamType.entries.forEach { type ->
            assertEquals(0, breakdown.countOf(type), "$type no puede absorber un tipo desconocido")
        }
    }

    @Test
    fun `sin especialidad abierta el desglose es cero en los tres tipos`() {
        val breakdown = specialtyExamBreakdown(listOf(exam(specialtyId = "tcae")), specialtyId = null)
        assertEquals(0, breakdown.total)
        assertEquals(SpecialtyExamType.entries.size, breakdown.byType.size)
    }

    @Test
    fun `avisa de los tipos apagados que tienen examenes`() {
        val exams = listOf(
            exam(id = "1", specialtyId = "tcae", typeExam = "rafaga"),
            exam(id = "2", specialtyId = "tcae", typeExam = "inmersivo"),
        )
        val breakdown = specialtyExamBreakdown(exams, "tcae")
        val draft = capabilities(rafagas = true, razonados = false, inmersivos = false)
        val off = specialtyTypesOffWithExams(breakdown, draft)
        assertEquals(listOf(SpecialtyExamType.INMERSIVO), off.map { it.type })
        assertEquals(1, off.single().count)
        // Un tipo apagado SIN exámenes no es un aviso: no hay nada en juego.
        assertTrue(off.none { it.type == SpecialtyExamType.RAZONADO })
    }

    // -----------------------------------------------------------------------
    // Validación
    // -----------------------------------------------------------------------

    @Test
    fun `el nombre en blanco bloquea el guardado`() {
        val state = openState().copy(editorName = "   ")
        assertTrue(specialtySaveBlockers(state).any { it.message.contains("nombre") })
    }

    @Test
    fun `un orden que no es un entero bloquea el guardado`() {
        val state = openState().copy(editorSortOrder = "primero")
        assertTrue(specialtySaveBlockers(state).any { it.message.contains("número entero") })
    }

    @Test
    fun `apagar los tres tipos sin mensaje bloquea el guardado con la razon del servidor`() {
        // Regla real: el backend responde 422 SPECIALTY_NO_MODES_WITHOUT_MESSAGE.
        val state = openState().copy(
            capabilities = UiAsync.Success(capabilities()),
            capabilitiesDraft = capabilities(rafagas = false, razonados = false, inmersivos = false),
        )
        val blockers = specialtySaveBlockers(state)
        assertTrue(blockers.any { it.message.contains("422") }, "El bloqueo tiene que decir por qué: $blockers")
        // Y tiene que apuntar a la mitad del formulario que lo provoca, para que
        // el aviso salga junto a los interruptores y no junto al nombre.
        assertEquals(SpecialtyBlockerTarget.CAPABILITIES, blockers.single().target)
    }

    @Test
    fun `con mensaje de limitacion los tres tipos apagados dejan de bloquear`() {
        val state = openState().copy(
            capabilities = UiAsync.Success(capabilities()),
            capabilitiesDraft = capabilities(
                rafagas = false,
                razonados = false,
                inmersivos = false,
                limitationMessage = "Contenido en preparación.",
            ),
        )
        assertTrue(specialtySaveBlockers(state).isEmpty())
    }

    @Test
    fun `sin especialidad abierta no hay nada que validar`() {
        assertTrue(specialtySaveBlockers(SpecialtiesState()).isEmpty())
    }

    // -----------------------------------------------------------------------
    // Escrituras y barra de guardado
    // -----------------------------------------------------------------------

    @Test
    fun `las dos escrituras se enumeran con su endpoint`() {
        val state = openState().copy(
            editorName = "TCAE renombrada",
            capabilities = UiAsync.Success(capabilities()),
            capabilitiesDraft = capabilities(inmersivos = true),
        )
        val writes = specialtyPendingWrites(state)
        assertEquals(listOf(SpecialtyWrite.IDENTITY, SpecialtyWrite.CAPABILITIES), writes)
        val label = assertNotNull(specialtyWritesLabel(writes))
        assertTrue(label.contains("Dos escrituras"), label)
        assertTrue(label.contains("Identidad"), label)
        assertTrue(label.contains("Tipos de examen"), label)
    }

    @Test
    fun `una sola escritura se nombra con su ruta`() {
        val state = openState().copy(editorName = "Otro nombre")
        val writes = specialtyPendingWrites(state)
        assertEquals(listOf(SpecialtyWrite.IDENTITY), writes)
        val label = assertNotNull(specialtyWritesLabel(writes))
        assertTrue(label.contains("PATCH /especialidades/{id}"), label)
    }

    @Test
    fun `un alta se escribe con POST y no arrastra capacidades`() {
        val state = SpecialtiesState(creatingNew = true, editorName = "Nueva")
        assertEquals(listOf(SpecialtyWrite.CREATE), specialtyPendingWrites(state))
    }

    @Test
    fun `sin cambios no hay escrituras y la barra no se pone prominente`() {
        val status = specialtySaveBarStatus(openState())
        assertFalse(status.prominent)
        assertFalse(status.canSave)
        assertFalse(status.canDiscard)
        assertTrue(status.writes.isEmpty())
        assertEquals(SpecialtySaveBarTone.SUCCESS, assertNotNull(status.message).tone)
    }

    @Test
    fun `con cambios la barra se pone prominente y ofrece descartar`() {
        val status = specialtySaveBarStatus(openState().copy(editorName = "Otro nombre"))
        assertTrue(status.prominent)
        assertTrue(status.canSave)
        assertTrue(status.canDiscard)
        assertEquals(SpecialtySaveBarTone.WARNING, assertNotNull(status.message).tone)
        assertNotNull(status.detail, "La barra tiene que decir qué se va a escribir")
    }

    @Test
    fun `un bloqueo deshabilita el guardado y lo explica`() {
        val status = specialtySaveBarStatus(openState().copy(editorName = ""))
        assertFalse(status.canSave)
        assertEquals(SpecialtySaveBarTone.DANGER, assertNotNull(status.message).tone)
    }

    @Test
    fun `sin especialidad abierta la barra dice que abrir una`() {
        val status = specialtySaveBarStatus(SpecialtiesState())
        assertFalse(status.canSave)
        assertFalse(status.prominent)
        assertNull(status.detail)
        assertEquals(SpecialtySaveBarTone.INFO, assertNotNull(status.message).tone)
    }

    @Test
    fun `un alta llama al primario Crear especialidad`() {
        val status = specialtySaveBarStatus(SpecialtiesState(creatingNew = true, editorName = "Nueva"))
        assertEquals("Crear especialidad", status.primaryLabel)
        assertTrue(status.canSave)
    }

    @Test
    fun `mientras guarda la barra no deja guardar otra vez ni descartar`() {
        val status = specialtySaveBarStatus(
            openState().copy(editorName = "Otro", actionState = UiAsync.Loading),
        )
        assertTrue(status.saving)
        assertFalse(status.canSave)
        assertFalse(status.canDiscard)
    }

    // -----------------------------------------------------------------------
    // Estado sucio
    // -----------------------------------------------------------------------

    @Test
    fun `la identidad esta sucia campo a campo`() {
        val clean = openState()
        assertFalse(clean.identityDirty)
        assertFalse(clean.anyDirty)
        assertTrue(clean.copy(editorName = "Otro").identityDirty)
        assertTrue(clean.copy(editorDescription = "Otra").identityDirty)
        assertTrue(clean.copy(editorSortOrder = "9").identityDirty)
        assertTrue(clean.copy(editorIsActive = false).identityDirty)
    }

    @Test
    fun `las capacidades estan sucias solo frente a lo cargado`() {
        val loaded = capabilities()
        val state = openState().copy(capabilities = UiAsync.Success(loaded), capabilitiesDraft = loaded)
        assertFalse(state.capabilitiesDirty)
        assertTrue(state.copy(capabilitiesDraft = loaded.copy(permiteInmersivos = true)).capabilitiesDirty)
        // Un borrador sin nada cargado no puede estar sucio: no hay con qué
        // comparar, y marcarlo así habilitaría un guardado a ciegas.
        assertFalse(
            state.copy(capabilities = UiAsync.Loading, capabilitiesDraft = loaded).capabilitiesDirty,
        )
    }

    @Test
    fun `la especialidad abierta se resuelve sobre la lista SIN filtrar`() {
        // Es el defecto que Exámenes ya pagó: con la lista filtrada como fuente,
        // buscar algo que no la incluya hacía desaparecer la especialidad abierta.
        val state = openState().copy(
            searchQuery = "zzz",
            items = UiAsync.Empty,
        )
        assertNotNull(state.selectedSpecialty())
    }

    // -----------------------------------------------------------------------
    // «Antes de guardar»
    // -----------------------------------------------------------------------

    @Test
    fun `sin nada pendiente el bloque de comprobaciones esta vacio`() {
        val state = openState().copy(
            capabilities = UiAsync.Success(capabilities()),
            capabilitiesDraft = capabilities(),
        )
        assertTrue(
            specialtyPendingChecks(state, specialtyExamBreakdown(emptyList(), "tcae")).isEmpty(),
            "Un bloque lleno de filas verdes entrena a no mirarlo",
        )
    }

    @Test
    fun `renombrar avisa de que el slug publico se reescribe`() {
        val state = openState().copy(editorName = "TCAE turno libre")
        val checks = specialtyPendingChecks(state, null)
        val slug = assertNotNull(checks.firstOrNull { it.id == SpecialtyCheckId.SLUG_REWRITE })
        assertEquals(SpecialtyCheckStatus.WARNING, slug.status)
        assertTrue(slug.detail.contains("tcae-turno-libre"), slug.detail)
    }

    @Test
    fun `archivar avisa de que los examenes publicados se siguen sirviendo`() {
        val state = openState().copy(editorIsActive = false)
        val checks = specialtyPendingChecks(state, null)
        val archived = assertNotNull(
            checks.firstOrNull { it.id == SpecialtyCheckId.ARCHIVED_EXAMS_STILL_SERVED },
        )
        assertTrue(archived.detail.contains("publicados"), archived.detail)
    }

    @Test
    fun `un tipo apagado con examenes aparece como pendiente`() {
        val exams = listOf(exam(id = "1", specialtyId = "tcae", typeExam = "rafaga"))
        val breakdown = specialtyExamBreakdown(exams, "tcae")
        val loaded = capabilities()
        val state = openState().copy(
            capabilities = UiAsync.Success(loaded),
            capabilitiesDraft = loaded.copy(permiteRafagas = false, limitationMessage = "Sin ráfagas por ahora."),
        )
        val checks = specialtyPendingChecks(state, breakdown)
        assertTrue(checks.any { it.id == SpecialtyCheckId.TYPE_OFF_WITH_EXAMS })
    }

    @Test
    fun `unas capacidades sin configurar se declaran pendientes`() {
        val unconfigured = capabilities(configured = false)
        val state = openState().copy(
            capabilities = UiAsync.Success(unconfigured),
            capabilitiesDraft = unconfigured,
        )
        val checks = specialtyPendingChecks(state, null)
        assertTrue(checks.any { it.id == SpecialtyCheckId.CAPABILITIES_UNCONFIGURED })
    }

    // -----------------------------------------------------------------------
    // Vista rápida
    // -----------------------------------------------------------------------

    @Test
    fun `un recuento que no se ha podido leer no se pinta como un cero`() {
        val state = openState().copy(exams = UiAsync.Error(AppError.Network("sin red")))
        val fact = assertNotNull(specialtyQuickFacts(state, null).firstOrNull { it.label == "Exámenes" })
        assertEquals(SpecialtyFactOrigin.UNRESOLVED, fact.origin)
        assertEquals("No disponible", fact.value)
    }

    @Test
    fun `un recuento en curso se pinta como pendiente`() {
        val state = openState().copy(exams = UiAsync.Loading)
        val fact = assertNotNull(specialtyQuickFacts(state, null).firstOrNull { it.label == "Exámenes" })
        assertEquals(SpecialtyFactOrigin.PENDING, fact.origin)
    }

    @Test
    fun `el recuento real lleva escrita su salvedad`() {
        val breakdown = specialtyExamBreakdown(
            listOf(exam(id = "1", specialtyId = "tcae", typeExam = "rafaga")),
            "tcae",
        )
        val state = openState().copy(exams = UiAsync.Success(emptyList()))
        val fact = assertNotNull(specialtyQuickFacts(state, breakdown).firstOrNull { it.label == "Exámenes" })
        assertEquals("1 examen", fact.value)
        assertEquals(SPECIALTY_EXAM_COUNT_CAVEAT, fact.detail)
    }

    @Test
    fun `sin especialidad abierta la vista rapida no pinta los valores por defecto`() {
        // El editor arranca con `activa = true` y `orden = 0`: son defaults, no
        // datos. El rail los pintaba como si fueran de una especialidad real.
        val facts = specialtyQuickFacts(SpecialtiesState(), breakdown = null)
        assertEquals(4, facts.size)
        assertTrue(
            facts.all { it.origin == SpecialtyFactOrigin.ABSENT },
            "Ningún valor puede leerse como un dato: $facts",
        )
        assertTrue(facts.none { it.value == "Activa" || it.value == "0" })
    }

    @Test
    fun `los tipos abiertos se cuentan sobre tres y no sobre cinco`() {
        // Demos y OposiMarket no son tipos de examen: contarlos aquí inflaría el
        // dato que se usa para decidir si una especialidad tiene contenido.
        assertEquals("1 de 3", specialtyOpenTypesLabel(capabilities()))
        assertEquals(
            "3 de 3",
            specialtyOpenTypesLabel(capabilities(rafagas = true, razonados = true, inmersivos = true)),
        )
        assertEquals("Sin cargar", specialtyOpenTypesLabel(null))
    }

    @Test
    fun `el estado solo tiene dos etiquetas`() {
        assertEquals("Activa", specialtyStatusLabel(true))
        assertEquals("Archivada", specialtyStatusLabel(false))
    }

    // -----------------------------------------------------------------------
    // Vocabulario
    // -----------------------------------------------------------------------

    @Test
    fun `el vocabulario de tipos es singular masculino`() {
        assertEquals(
            listOf("Ráfaga", "Razonado", "Inmersivo"),
            SpecialtyExamType.entries.map { it.label },
        )
        assertEquals(
            listOf("rafaga", "razonado", "inmersivo"),
            SpecialtyExamType.entries.map { it.canonical.wireValue },
        )
    }

    @Test
    fun `las dos capacidades comerciales van con su alcance declarado`() {
        assertEquals(
            SpecialtyDeclaration.PARTIAL,
            SpecialtyCommercialCapability.DEMOS.declaration,
        )
        assertEquals(
            SpecialtyDeclaration.NOT_APPLIED,
            SpecialtyCommercialCapability.OPOSIMARKET.declaration,
        )
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private fun specialty(
        id: String = "tcae",
        name: String = "TCAE",
        description: String = "Técnico en cuidados auxiliares de enfermería",
        isActive: Boolean = true,
        sortOrder: Int = 1,
        linkedExams: Int = 4,
    ) = Specialty(
        id = id,
        name = name,
        description = description,
        isActive = isActive,
        sortOrder = sortOrder,
        linkedExams = linkedExams,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun exam(
        id: String = "ex-1",
        specialtyId: String = "tcae",
        typeExam: String = "rafaga",
    ) = Exam(
        id = id,
        code = "EX-$id",
        specialtyId = specialtyId,
        specialtyName = "TCAE",
        title = "Simulacro $id",
        description = "",
        typeExam = typeExam,
        priceTickets = 0,
        status = ExamStatus.DRAFT,
        publishFrom = null,
        publishedAt = null,
        sortOrder = 0,
        questionCount = 0,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun capabilities(
        rafagas: Boolean = true,
        razonados: Boolean = false,
        inmersivos: Boolean = false,
        limitationMessage: String? = null,
        configured: Boolean = true,
    ) = SpecialtyCapabilities(
        permiteRafagas = rafagas,
        permiteRazonados = razonados,
        permiteInmersivos = inmersivos,
        permiteDemos = true,
        permiteOposimarket = false,
        specialtyId = "tcae",
        limitationMessage = limitationMessage,
        configured = configured,
    )

    /** Una especialidad abierta y limpia: el editor coincide con lo guardado. */
    private fun openState(): SpecialtiesState {
        val item = specialty()
        return SpecialtiesState(
            items = UiAsync.Success(listOf(item)),
            catalog = listOf(item),
            selectedId = item.id,
            editorName = item.name,
            editorDescription = item.description,
            editorSortOrder = item.sortOrder.toString(),
            editorIsActive = item.isActive,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-01T09:00:00Z")
    }
}
