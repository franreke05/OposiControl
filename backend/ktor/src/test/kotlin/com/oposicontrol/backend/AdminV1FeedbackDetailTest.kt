package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun row(
    id: String? = "f-1",
    usuarioId: String? = "u-1",
    preguntaId: String = "p-1",
    intentoId: String? = "i-1",
    tipoFeedback: String = "pregunta_ambigua",
    descripcion: String? = "El enunciado es confuso.",
    estado: String = "pendiente",
    creadoEn: String? = "2026-08-23T10:00:00Z",
) = FeedbackRow(
    id = id,
    usuarioId = usuarioId,
    preguntaId = preguntaId,
    intentoId = intentoId,
    tipoFeedback = tipoFeedback,
    descripcion = descripcion,
    estado = estado,
    creadoEn = creadoEn,
)

class AdminV1FeedbackDetailTest {
    @Test
    fun buildFeedbackDetailDto_mapsAllRealFields_andCarriesWhateverSpecialtyNameItIsGiven() {
        val pregunta = AdminPreguntaDto(
            id = "p-1",
            codigoPregunta = "RAF-0042",
            titulo = "¿Cuál es el plazo de alegaciones?",
        )

        val dto = buildFeedbackDetailDto(
            row = row(),
            autorNombre = "Arturo G",
            pregunta = pregunta,
            especialidadNombre = "Auxiliar Administrativo",
        )

        assertEquals("f-1", dto.id)
        assertEquals("u-1", dto.usuarioId)
        assertEquals("Arturo G", dto.autorNombre)
        assertEquals("p-1", dto.preguntaId)
        assertEquals("i-1", dto.intentoId)
        assertEquals("pregunta_ambigua", dto.tipoFeedback)
        assertEquals("El enunciado es confuso.", dto.descripcion)
        assertEquals("pendiente", dto.estado)
        assertEquals("2026-08-23T10:00:00Z", dto.creadoEn)
        assertEquals("RAF-0042", dto.pregunta?.codigoPregunta)
        assertEquals("¿Cuál es el plazo de alegaciones?", dto.pregunta?.titulo)
        assertEquals("Auxiliar Administrativo", dto.pregunta?.especialidadNombre)
    }

    @Test
    fun buildFeedbackDetailDto_omitsQuestionContext_whenThePreguntaNoLongerExists() {
        val dto = buildFeedbackDetailDto(row = row(), autorNombre = "", pregunta = null, especialidadNombre = null)

        assertNull(dto.pregunta)
    }

    @Test
    fun buildFeedbackDetailDto_neverFabricatesASpecialtyName_whenItCouldNotBeResolved() {
        val pregunta = AdminPreguntaDto(id = "p-1", codigoPregunta = "RAF-0042", titulo = "Título")

        val dto = buildFeedbackDetailDto(row = row(), autorNombre = "", pregunta = pregunta, especialidadNombre = null)

        assertEquals("RAF-0042", dto.pregunta?.codigoPregunta)
        assertNull(dto.pregunta?.especialidadNombre)
    }

    @Test
    fun buildFeedbackDetailDto_defaultsBlankDescriptionToEmptyString_neverNull() {
        val dto = buildFeedbackDetailDto(row = row(descripcion = null), autorNombre = "", pregunta = null, especialidadNombre = null)

        assertEquals("", dto.descripcion)
    }

    @Test
    fun buildFeedbackDetailDto_preservesTheRealStatus_resuelto() {
        val dto = buildFeedbackDetailDto(row = row(estado = "resuelto"), autorNombre = "", pregunta = null, especialidadNombre = null)

        assertEquals("resuelto", dto.estado)
    }
}
