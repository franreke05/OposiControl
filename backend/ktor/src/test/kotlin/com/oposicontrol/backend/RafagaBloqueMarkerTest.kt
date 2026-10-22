package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hotfix de producción: el parser no conocía `--- BLOQUE: PRINCIPAL ---` / `--- BLOQUE: RESERVA ---` y lo pegaba como
 * continuación del campo anterior (acababa dentro de la ETIQUETA o del RAZONAMIENTO publicados). Ahora el marcador se
 * reconoce, PRINCIPAL se ignora y todo lo que va detrás de RESERVA sale como bloque con error (no se publica hasta V1).
 */
class RafagaBloqueMarkerTest {

    private fun pregunta(titulo: String, etiqueta: String = "Constitución") = """
        --- PREGUNTA ---
        TITULO: $titulo
        A: uno
        B: dos
        C: tres
        D: cuatro
        RESPUESTA: B
        ETIQUETA: $etiqueta
    """.trimIndent()

    @Test
    fun principalSeIgnoraYNoContaminaElCampoAnterior() {
        val texto = listOf("--- BLOQUE: PRINCIPAL ---", pregunta("P1"), "--- BLOQUE: PRINCIPAL ---", pregunta("P2")).joinToString("\n")
        val r = parseQuestionBlocks(texto, "rafaga")
        assertTrue(r.bloquesConError.isEmpty(), r.bloquesConError.toString())
        assertEquals(listOf("P1", "P2"), r.preguntas.map { it.titulo })
        assertEquals(listOf(listOf("Constitución"), listOf("Constitución")), r.preguntas.map { it.etiquetas })
        assertTrue(r.preguntas.none { it.bloqueOriginal.contains("BLOQUE") }, r.preguntas.toString())
    }

    @Test
    fun lasPreguntasDeReservaSalenComoErrorYLaUltimaPrincipalQuedaLimpia() {
        val texto = listOf(
            "--- BLOQUE: PRINCIPAL ---", pregunta("P1"),
            "--- BLOQUE: RESERVA ---", pregunta("R1"), pregunta("R2"),
        ).joinToString("\n")
        val r = parseQuestionBlocks(texto, "rafaga")

        val p1 = r.preguntas.single()
        assertEquals("P1", p1.titulo)
        assertEquals(listOf("Constitución"), p1.etiquetas, "el marcador no se pega a ETIQUETA")
        assertFalse(p1.bloqueOriginal.contains("BLOQUE"), p1.bloqueOriginal)
        assertEquals(null, p1.advertencia)

        assertEquals(listOf("R1", "R2"), r.bloquesConError.map { it.titulo })
        r.bloquesConError.forEach { b ->
            assertTrue(b.detalle.contains(RESERVA_NOT_SUPPORTED_DETAIL), b.detalle)
            assertEquals("Constitución", b.etiquetas)
        }
        assertEquals(listOf(2, 3), r.bloquesConError.map { it.numero })
    }

    @Test
    fun conEspaciosAlrededorTambienSeReconoce() {
        val texto = listOf(pregunta("P1"), "   ---  BLOQUE :  RESERVA  ---  ", pregunta("R1")).joinToString("\n")
        val r = parseQuestionBlocks(texto, "rafaga")
        assertEquals(listOf("Constitución"), r.preguntas.single().etiquetas)
        assertTrue(r.bloquesConError.single().detalle.contains(RESERVA_NOT_SUPPORTED_DETAIL))
    }

    @Test
    fun unMarcadorMalEscritoEsErrorYLoQueVieneDetrasNoSePublica() {
        val texto = listOf(pregunta("P1"), "-- bloque reserva --", pregunta("R1")).joinToString("\n")
        val r = parseQuestionBlocks(texto, "rafaga")

        val p1 = r.preguntas.single()
        assertEquals(listOf("Constitución"), p1.etiquetas, "el marcador sospechoso tampoco se pega")
        assertFalse(p1.bloqueOriginal.contains("bloque"), p1.bloqueOriginal)

        assertEquals(2, r.bloquesConError.size, r.bloquesConError.toString())
        assertTrue(r.bloquesConError[0].detalle.contains(INVALID_BLOQUE_DETAIL), r.bloquesConError[0].detalle)
        assertEquals("R1", r.bloquesConError[1].titulo)
        assertTrue(r.bloquesConError[1].detalle.contains(BLOQUE_UNCERTAIN_DETAIL), r.bloquesConError[1].detalle)
    }

    @Test
    fun unMarcadorExactoAntesDeLaPrimeraPreguntaNoEsPreambulo() {
        val texto = listOf("Portada", "--- BLOQUE: PRINCIPAL ---", "texto suelto", pregunta("P1")).joinToString("\n")
        val r = parseQuestionBlocks(texto, "rafaga")
        assertEquals(listOf("P1"), r.preguntas.map { it.titulo })
        assertTrue(r.bloquesConError.isEmpty())
    }
}
