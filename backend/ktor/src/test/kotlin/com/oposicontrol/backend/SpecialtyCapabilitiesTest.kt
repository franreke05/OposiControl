package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecialtyCapabilitiesTest {

    private val especialidadId = "11111111-2222-3333-4444-555555555555"

    // --- modos habilitados: la traducción flag -> vocabulario canónico ---

    @Test
    fun enabledModesUseTheCanonicalVocabulary() {
        val all = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = true,
            permiteRazonados = true,
            permiteInmersivos = true,
        )

        // rafaga / razonado / inmersivo. Nunca "guiado", que es solo alias legacy.
        assertEquals(listOf("rafaga", "razonado", "inmersivo"), all.modosHabilitados())
    }

    @Test
    fun aSpecialtyWithOnlyBurstsReportsOnlyThatMode() {
        val onlyBursts = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = true,
            permiteRazonados = false,
            permiteInmersivos = false,
        )

        assertEquals(listOf("rafaga"), onlyBursts.modosHabilitados())
    }

    @Test
    fun demosAndMarketAreNotExamModes() {
        // permiteDemos / permiteOposimarket habilitan otras superficies, no un tipo
        // de examen: colarlos aqui haria que OposiBot pintase un modo inexistente.
        val noExamModes = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = false,
            permiteRazonados = false,
            permiteInmersivos = false,
            permiteDemos = true,
            permiteOposimarket = true,
        )

        assertTrue(noExamModes.modosHabilitados().isEmpty())
    }

    // --- validación: no dejar al usuario en una pantalla muda ---

    @Test
    fun aSpecialtyWithNoModesRequiresAnExplanatoryMessage() {
        val blocked = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = false,
            permiteRazonados = false,
            permiteInmersivos = false,
            mensajeLimitacion = null,
        )

        val failure = assertFailsWith<BackendDomainException> { blocked.validated() }
        assertEquals("SPECIALTY_NO_MODES_WITHOUT_MESSAGE", failure.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, failure.status)
    }

    @Test
    fun aBlankMessageDoesNotCountAsAnExplanation() {
        val blocked = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = false,
            permiteRazonados = false,
            permiteInmersivos = false,
            mensajeLimitacion = "   ",
        )

        assertFailsWith<BackendDomainException> { blocked.validated() }
    }

    @Test
    fun aSpecialtyWithNoModesIsAllowedWhenItExplainsWhy() {
        val blocked = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = false,
            permiteRazonados = false,
            permiteInmersivos = false,
            mensajeLimitacion = "Contenido en preparación para esta especialidad.",
        )

        assertEquals(blocked, blocked.validated())
    }

    @Test
    fun aSpecialtyWithAtLeastOneModeNeedsNoMessage() {
        val open = AdminEspecialidadCapacidadesDto(especialidadId = especialidadId, permiteRafagas = true)

        assertEquals(open, open.validated())
    }

    // --- defaults: "sin configurar" no es "todo apagado" ---

    @Test
    fun defaultsMatchTheSchemaSoOpeningAndSavingChangesNothing() {
        val defaults = defaultCapacidades(especialidadId)

        // Iguales a los DEFAULT de capacidades_especialidad.
        assertTrue(defaults.permiteRafagas)
        assertFalse(defaults.permiteRazonados)
        assertFalse(defaults.permiteInmersivos)
        assertTrue(defaults.permiteDemos)
        assertTrue(defaults.permiteOposimarket)
        assertNull(defaults.mensajeLimitacion)
    }

    @Test
    fun defaultsAreFlaggedAsNotConfiguredYet() {
        // Distinguir "nadie lo ha decidido" de "alguien lo dejó así" es la razón de
        // que exista el flag: sin él, el panel no puede avisar de lo primero.
        assertFalse(defaultCapacidades(especialidadId).configurado)
    }

    @Test
    fun defaultsAreValidOnTheirOwn() {
        // Los defaults dejan rafagas activas, asi que no disparan la regla del
        // mensaje obligatorio: guardar sin tocar nada nunca puede fallar.
        assertEquals(listOf("rafaga"), defaultCapacidades(especialidadId).modosHabilitados())
        defaultCapacidades(especialidadId).validated()
    }

    // --- ida y vuelta fila <-> DTO ---

    @Test
    fun rowRoundTripPreservesEveryFlag() {
        val row = CapacidadesEspecialidadRow(
            especialidadId = especialidadId,
            permiteRafagas = true,
            permiteRazonados = true,
            permiteInmersivos = false,
            permiteDemos = false,
            permiteOposimarket = true,
            mensajeLimitacion = "Sin inmersivos todavía.",
            actualizadoEn = "2026-09-01T10:00:00Z",
        )

        val dto = row.toDto()
        assertTrue(dto.configurado, "Una fila existente siempre está configurada.")
        assertEquals("2026-09-01T10:00:00Z", dto.actualizadoEn)

        val back = dto.toRow(especialidadId)
        assertEquals(row.permiteRafagas, back.permiteRafagas)
        assertEquals(row.permiteRazonados, back.permiteRazonados)
        assertEquals(row.permiteInmersivos, back.permiteInmersivos)
        assertEquals(row.permiteDemos, back.permiteDemos)
        assertEquals(row.permiteOposimarket, back.permiteOposimarket)
        assertEquals(row.mensajeLimitacion, back.mensajeLimitacion)
    }

    @Test
    fun aBlankMessageIsStoredAsNullNotAsEmptyText() {
        // Evita que la app muestre un aviso de limitación vacío.
        val row = AdminEspecialidadCapacidadesDto(
            especialidadId = especialidadId,
            permiteRafagas = true,
            mensajeLimitacion = "  ",
        ).toRow(especialidadId)

        assertNull(row.mensajeLimitacion)
    }

    @Test
    fun theTargetSpecialtyIdWinsOverWhateverTheBodyCarried() {
        // El id viene de la ruta; si el cuerpo trae otro, escribir ese permitiría
        // reconfigurar una especialidad distinta de la que se está editando.
        val row = AdminEspecialidadCapacidadesDto(especialidadId = "otra-cosa", permiteRafagas = true)
            .toRow(especialidadId)

        assertEquals(especialidadId, row.especialidadId)
    }
}
