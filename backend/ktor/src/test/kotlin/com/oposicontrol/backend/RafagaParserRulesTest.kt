package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las reglas de formato del importador de PDF (M10 + endurecimiento del parser): separador único,
 * preámbulo, campos duplicados, `ESTADO`/`MOTIVO_ANULACION`, claves mal escritas y aviso de pie
 * de página. Cada regla tiene su caso positivo y su caso que ANTES pasaba en silencio.
 */
class RafagaParserRulesTest {

    /** Una pregunta bien formada; [extra] se añade tal cual al final del bloque. */
    private fun bloque(titulo: String = "Plazo de alegaciones", respuesta: String = "B", extra: String = ""): String =
        listOf(
            "TITULO: $titulo",
            "A: Diez días",
            "B: Quince días",
            "C: Un mes",
            "D: Dos meses",
            "RESPUESTA: $respuesta",
            extra,
        ).filter { it.isNotEmpty() }.joinToString("\n")

    private fun pdfText(vararg blocks: String, separator: String = "--- PREGUNTA ---"): String =
        blocks.joinToString("\n") { "$separator\n$it" }

    // -----------------------------------------------------------------
    // 1 · Separador único y por líneas
    // -----------------------------------------------------------------

    @Test
    fun theOfficialSeparatorSplitsQuestionsAndNumbersThemFromOne() {
        val parsed = parseQuestionBlocks(pdfText(bloque("Una"), bloque("Dos"), bloque("Tres")), "rafaga")

        assertEquals(listOf(1, 2, 3), parsed.preguntas.map { it.numero })
        assertEquals(listOf("Una", "Dos", "Tres"), parsed.preguntas.map { it.titulo })
        assertTrue(parsed.bloquesConError.isEmpty())
    }

    /** Sólo la línea COMPLETA es separador: `--- PREGUNTA ---` dentro de una frase no corta nada. */
    @Test
    fun theOfficialSeparatorTolerantOnlyToSurroundingWhitespace() {
        val parsed = parseQuestionBlocks("   ---   PREGUNTA   ---   \n${bloque()}", "rafaga")

        assertEquals(1, parsed.preguntas.size, "${parsed.bloquesConError}")
    }

    @Test
    fun aNumberedSeparatorOpensItsOwnErrorBlockInsteadOfMergingQuestions() {
        val text = "--- PREGUNTA ---\n${bloque("Buena")}\n--- PREGUNTA 1 ---\n${bloque("Numerada")}"

        val parsed = parseQuestionBlocks(text, "rafaga")

        assertEquals(listOf("Buena"), parsed.preguntas.map { it.titulo })
        val mala = assertNotNull(parsed.bloquesConError.singleOrNull(), "${parsed.bloquesConError}")
        assertEquals(2, mala.numero)
        assertTrue(mala.detalle.contains("SEPARADOR: Separador no válido: usa exactamente --- PREGUNTA ---"), mala.detalle)
        assertEquals("Numerada", mala.titulo, "lo que sí se leyó viaja con el bloque")
        assertEquals("Quince días", mala.opcionB)
        assertTrue(mala.bloqueOriginal.startsWith("--- PREGUNTA 1 ---"), "el separador dudoso es parte de la evidencia")
        assertTrue(parsed.errores.any { it.campo == "SEPARADOR" && it.numero == 2 })
    }

    @Test
    fun everyLookAlikeSeparatorIsAnErrorBlockNotASilentMerge() {
        val parecidos = listOf(
            "--- PREGUNTA 1 ---",
            "--- pregunta ---",
            "---PREGUNTA:",
            "— PREGUNTA —",
            "—— PREGUNTA ——",
            "-- PREGUNTA --",
            "=== PREGUNTA 3 ===",
            "### Pregunta 4",
            "___ PREGUNTA ___",
            "--- PREGUNTA  ---  (bis)",
        )
        parecidos.forEach { separador ->
            val parsed = parseQuestionBlocks("--- PREGUNTA ---\n${bloque("Ok")}\n$separador\n${bloque("Otra")}", "rafaga")

            assertEquals(1, parsed.preguntas.size, "«$separador» fusionó o perdió preguntas: ${parsed.preguntas}")
            assertEquals(1, parsed.bloquesConError.size, "«$separador» no abrió su bloque de error")
            assertEquals("SEPARADOR", parsed.errores.first().campo, "«$separador»")
        }
    }

    /** Control: una frase normal que contiene la palabra no es un separador. */
    @Test
    fun theWordQuestionInsideProseIsNotASeparator() {
        val parsed = parseQuestionBlocks(
            pdfText(bloque(extra = "RAZONAMIENTO: Esta pregunta trata del plazo.\nPREGUNTA relacionada: la 4.")),
            "razonada",
        )

        assertEquals(1, parsed.preguntas.size, "${parsed.bloquesConError}")
        assertTrue(parsed.preguntas.single().razonamiento.contains("PREGUNTA relacionada: la 4."))
    }

    // -----------------------------------------------------------------
    // 2 · Preámbulo
    // -----------------------------------------------------------------

    @Test
    fun everythingBeforeTheFirstSeparatorIsIgnoredWithoutCreatingARow() {
        val text = "CONVOCATORIA 2025\nEjercicio único\nTITULO: esto no es una pregunta\n" + pdfText(bloque("Real"))

        val parsed = parseQuestionBlocks(text, "rafaga")

        assertEquals(listOf("Real"), parsed.preguntas.map { it.titulo })
        assertEquals(1, parsed.preguntas.single().numero, "la primera pregunta real es la 1")
        assertTrue(parsed.bloquesConError.isEmpty(), "el preámbulo no genera fila de error")
        assertTrue(parsed.errores.isEmpty())
    }

    @Test
    fun aTextWithoutAnySeparatorHasNoBlocksAtAll() {
        val parsed = parseQuestionBlocks("Sólo un título de portada\nTITULO: suelto", "rafaga")

        assertTrue(parsed.preguntas.isEmpty() && parsed.bloquesConError.isEmpty())
    }

    /** Un separador oficial sin contenido (dos seguidos, o al final) no es una pregunta. */
    @Test
    fun emptyOfficialBlocksAreNotQuestions() {
        val parsed = parseQuestionBlocks("--- PREGUNTA ---\n\n--- PREGUNTA ---\n${bloque("Sola")}\n--- PREGUNTA ---\n", "rafaga")

        assertEquals(listOf(1), parsed.preguntas.map { it.numero })
        assertTrue(parsed.bloquesConError.isEmpty())
    }

    // -----------------------------------------------------------------
    // 3 · Campos duplicados
    // -----------------------------------------------------------------

    @Test
    fun aDuplicatedFieldIsAnErrorAndKeepsTheFirstValue() {
        val parsed = parseQuestionBlocks(pdfText(bloque(extra = "RESPUESTA: C")), "rafaga")

        assertTrue(parsed.preguntas.isEmpty(), "el último ya no gana en silencio")
        val bloque = assertNotNull(parsed.bloquesConError.singleOrNull())
        assertTrue(bloque.detalle.contains("RESPUESTA: Campo duplicado: RESPUESTA"), bloque.detalle)
        assertEquals("B", bloque.respuesta, "se conserva el PRIMER valor leído")
    }

    @Test
    fun everyKnownFieldIsCheckedForDuplicates() {
        listOf("TITULO", "A", "B", "C", "D", "RAZONAMIENTO", "FUENTE", "ETIQUETA", "ESTADO", "MOTIVO_ANULACION").forEach { campo ->
            val valor = if (campo == "ESTADO") "ANULADA" else "x"
            val base = bloque(extra = "RAZONAMIENTO: r\nFUENTE: f\nETIQUETA: e\nESTADO: ANULADA\nMOTIVO_ANULACION: m")
            val parsed = parseQuestionBlocks(pdfText("$base\n$campo: $valor"), "razonada")

            val bloqueError = assertNotNull(parsed.bloquesConError.singleOrNull(), "$campo duplicado no invalidó el bloque")
            assertTrue(bloqueError.detalle.contains("Campo duplicado: $campo"), "$campo: ${bloqueError.detalle}")
        }
    }

    /** Las líneas que siguen a un campo REPETIDO no se pegan al primero. */
    @Test
    fun continuationOfADuplicatedFieldDoesNotLeakIntoTheFirstOne() {
        val parsed = parseQuestionBlocks(
            pdfText("TITULO: Uno\nA: a\nB: b\nC: c\nD: d\nRESPUESTA: A\nTITULO: Dos\nresto del segundo título"),
            "rafaga",
        )

        assertEquals("Uno", parsed.bloquesConError.single().titulo)
    }

    // -----------------------------------------------------------------
    // 4 · ESTADO / MOTIVO_ANULACION
    // -----------------------------------------------------------------

    @Test
    fun aQuestionWithoutEstadoIsNormal() {
        val pregunta = parseQuestionBlocks(pdfText(bloque()), "rafaga").preguntas.single()

        assertFalse(pregunta.anulada)
        assertNull(pregunta.motivoAnulacion)
    }

    @Test
    fun estadoAnuladaAnnulsTheQuestionRegardlessOfCaseAndSpacing() {
        listOf("ANULADA", "anulada", "  Anulada  ").forEach { valor ->
            val pregunta = parseQuestionBlocks(pdfText(bloque(extra = "ESTADO: $valor")), "rafaga").preguntas.single()

            assertTrue(pregunta.anulada, "«$valor»")
            assertNull(pregunta.motivoAnulacion)
            assertNull(pregunta.advertencia, "una anulada válida es una fila ok, sin advertencia")
        }
    }

    @Test
    fun anAnnulmentReasonIsKeptTrimmedAndMayBeMultiLine() {
        val pregunta = parseQuestionBlocks(
            pdfText(bloque(extra = "ESTADO: ANULADA\nMOTIVO_ANULACION:   Anulada por el tribunal\nen la resolución de 2025 ")),
            "rafaga",
        ).preguntas.single()

        assertTrue(pregunta.anulada)
        assertEquals("Anulada por el tribunal\nen la resolución de 2025", pregunta.motivoAnulacion)
    }

    @Test
    fun anEmptyReasonIsTreatedAsAbsent() {
        val anulada = parseQuestionBlocks(pdfText(bloque(extra = "ESTADO: ANULADA\nMOTIVO_ANULACION:")), "rafaga")
        assertNull(anulada.preguntas.single().motivoAnulacion)

        // Vacío sin ESTADO tampoco es un error: es como si no estuviera.
        val normal = parseQuestionBlocks(pdfText(bloque(extra = "MOTIVO_ANULACION:")), "rafaga")
        assertFalse(normal.preguntas.single().anulada)
    }

    /**
     * DECISIÓN: `ESTADO: NORMAL` explícito TAMBIÉN es un error. La única forma de decir «pregunta
     * normal» es no decir nada; aceptar `NORMAL` abriría la puerta a `NORMALES`, `OK`, `ACTIVA`…
     * y a que un valor mal escrito se lea como «normal» sin que nadie lo vea.
     */
    @Test
    fun anyEstadoOtherThanAnuladaIsAnErrorEvenNormal() {
        listOf("CANCELADA", "INVALIDA", "BORRADA", "NORMAL", "ANULADAS", "").forEach { valor ->
            val parsed = parseQuestionBlocks(pdfText(bloque(extra = "ESTADO: $valor")), "rafaga")

            assertTrue(parsed.preguntas.isEmpty(), "«$valor» no debería ser una pregunta válida")
            val bloque = assertNotNull(parsed.bloquesConError.singleOrNull(), "«$valor»")
            assertTrue(
                bloque.detalle.contains("ESTADO: solo se admite ANULADA (o ausente para pregunta normal)"),
                "«$valor»: ${bloque.detalle}",
            )
            assertFalse(bloque.anulada, "«$valor» no anula")
        }
    }

    @Test
    fun aReasonWithoutEstadoAnuladaIsAnError() {
        val parsed = parseQuestionBlocks(pdfText(bloque(extra = "MOTIVO_ANULACION: porque sí")), "rafaga")

        assertTrue(parsed.preguntas.isEmpty())
        val bloque = assertNotNull(parsed.bloquesConError.singleOrNull())
        assertTrue(bloque.detalle.contains("MOTIVO_ANULACION: solo se admite junto con ESTADO: ANULADA"), bloque.detalle)
        assertFalse(bloque.anulada)
    }

    @Test
    fun aReasonLongerThan500CharactersIsAnErrorButTheBlockKeepsWhatWasRead() {
        val motivo = "m".repeat(MAX_ANNULMENT_REASON_LENGTH + 1)
        val parsed = parseQuestionBlocks(pdfText(bloque(extra = "ESTADO: ANULADA\nMOTIVO_ANULACION: $motivo")), "rafaga")

        assertTrue(parsed.preguntas.isEmpty())
        val bloque = assertNotNull(parsed.bloquesConError.singleOrNull())
        assertTrue(bloque.detalle.contains("MOTIVO_ANULACION: máximo 500 caracteres (tiene 501)"), bloque.detalle)
        assertTrue(bloque.anulada, "el bloque con error conserva lo leído")
        assertEquals(motivo, bloque.motivoAnulacion)

        // El límite exacto es válido.
        val justo = parseQuestionBlocks(
            pdfText(bloque(extra = "ESTADO: ANULADA\nMOTIVO_ANULACION: ${"m".repeat(MAX_ANNULMENT_REASON_LENGTH)}")),
            "rafaga",
        )
        assertEquals(1, justo.preguntas.size, "${justo.bloquesConError}")
    }

    /** ESTADO/MOTIVO son campos con nombre: no se pegan como continuación del anterior. */
    @Test
    fun estadoAndMotivoAreNeverContinuationOfThePreviousField() {
        val pregunta = parseQuestionBlocks(
            pdfText(bloque(extra = "RAZONAMIENTO: Art. 82.\nESTADO: ANULADA\nMOTIVO_ANULACION: tribunal")),
            "razonada",
        ).preguntas.single()

        assertEquals("Art. 82.", pregunta.razonamiento)
        assertTrue(pregunta.anulada)
        assertEquals("tribunal", pregunta.motivoAnulacion)
    }

    /** Una anulada de Ráfaga con razonamiento conserva el aviso de Ráfaga: la anulación es ortogonal. */
    @Test
    fun annulmentIsOrthogonalToTheRafagaWarningAndToTheReasoningRule() {
        val rafaga = parseQuestionBlocks(pdfText(bloque(extra = "FUENTE: BOE\nESTADO: ANULADA")), "rafaga").preguntas.single()
        assertTrue(rafaga.anulada)
        assertEquals(RAFAGA_DISCARDS_EXTRAS_WARNING, rafaga.advertencia)

        val sinRazonamiento = parseQuestionBlocks(pdfText(bloque(extra = "ESTADO: ANULADA")), "razonada")
        assertTrue(sinRazonamiento.preguntas.isEmpty(), "una anulada razonada sigue exigiendo razonamiento")
        assertTrue(sinRazonamiento.bloquesConError.single().anulada)
    }

    // -----------------------------------------------------------------
    // 5 · Claves mal escritas
    // -----------------------------------------------------------------

    @Test
    fun aMisspelledKeyIsAnErrorInsteadOfGluingToThePreviousField() {
        val casos = mapOf(
            "Fuente: BOE" to "FUENTE",
            "Titulo: otro" to "TITULO",
            "TÍTULO: otro" to "TITULO",
            "respuesta: C" to "RESPUESTA",
            "Razonamiento: x" to "RAZONAMIENTO",
            "Estado: ANULADA" to "ESTADO",
            "motivo_anulacion: x" to "MOTIVO_ANULACION",
        )
        casos.forEach { (linea, correcta) ->
            val parsed = parseQuestionBlocks(pdfText(bloque(extra = "RAZONAMIENTO: r\n$linea")), "razonada")

            assertTrue(parsed.preguntas.isEmpty(), "«$linea» se aceptó")
            val bloque = assertNotNull(parsed.bloquesConError.singleOrNull(), "«$linea»")
            assertTrue(bloque.detalle.contains("Clave mal escrita: usa $correcta"), "«$linea»: ${bloque.detalle}")
            assertEquals("r", bloque.razonamiento, "«$linea» no se pega al razonamiento")
        }
    }

    @Test
    fun aPlainNoteInProseIsStillContinuationAndLegacyKeysAreStillRejected() {
        val nota = parseQuestionBlocks(
            pdfText(bloque(extra = "RAZONAMIENTO: Art. 82.\nNota: cuenta desde la notificación.\nImportante: sin excepciones.")),
            "razonada",
        ).preguntas.single()
        assertTrue(nota.razonamiento.contains("Nota: cuenta desde la notificación."))
        assertTrue(nota.razonamiento.contains("Importante: sin excepciones."))

        val legado = parseQuestionBlocks(pdfText(bloque(extra = "ID_GLOBAL: 7")), "rafaga")
        assertTrue(legado.bloquesConError.single().detalle.contains("ID_GLOBAL: Campo no admitido en el PDF"))
    }

    // -----------------------------------------------------------------
    // 6 · Marcadores de página
    // -----------------------------------------------------------------

    @Test
    fun aPageMarkerInsideAContinuationMakesTheQuestionAWarningNotASilentOk() {
        listOf("Página 3", "Pág. 12", "pagina 4 de 20", "Page 2", "Page 2 of 9", "Página 3/20", "7", "1234").forEach { marcador ->
            val parsed = parseQuestionBlocks(pdfText(bloque(extra = "RAZONAMIENTO: Primera parte.\n$marcador\nSegunda parte.")), "razonada")

            val pregunta = assertNotNull(parsed.preguntas.singleOrNull(), "«$marcador»: ${parsed.bloquesConError}")
            assertEquals(PAGE_MARKER_WARNING, pregunta.advertencia, "«$marcador»")
            assertTrue(pregunta.razonamiento.contains("Segunda parte."), "el texto no se descarta: no hay heurística mágica")
        }
    }

    @Test
    fun linesThatOnlyLookSlightlyLikeAPageMarkerAreNotWarned() {
        listOf("Página de prueba", "12345", "Artículo 3", "Page").forEach { texto ->
            val pregunta = parseQuestionBlocks(pdfText(bloque(extra = "RAZONAMIENTO: Parte.\n$texto")), "razonada").preguntas.single()

            assertNull(pregunta.advertencia, "«$texto»")
        }
    }

    /** Un número de página que no es continuación (no hay campo abierto) no se considera. */
    @Test
    fun aBareNumberThatIsAValueIsNotAWarning() {
        val pregunta = parseQuestionBlocks(pdfText(bloque(titulo = "12")), "rafaga").preguntas.single()

        assertNull(pregunta.advertencia)
    }

    // -----------------------------------------------------------------
    // 7 · Acumulación de errores
    // -----------------------------------------------------------------

    /** Sin título ya se siguen validando las opciones y la respuesta. */
    @Test
    fun aBlockWithoutTitleStillReportsItsMissingOptions() {
        val parsed = parseQuestionBlocks("--- PREGUNTA ---\nA: a\nB: b\nRESPUESTA: A", "rafaga")

        val campos = parsed.errores.map { it.campo }
        assertEquals(listOf("TITULO", "C", "D"), campos)
    }

    @Test
    fun theAnswerIsUppercasedAndOptionsKeepTheirOrder() {
        val pregunta = parseQuestionBlocks(pdfText(bloque(respuesta = "c")), "rafaga").preguntas.single()

        assertEquals("C", pregunta.respuesta)
        assertEquals(listOf("A", "B", "C", "D"), pregunta.opciones.map { it.first })
    }

    // -----------------------------------------------------------------
    // 7 · ESTADO / MOTIVO_ANULACION sin dos puntos: nunca una anulada silenciosa
    // -----------------------------------------------------------------

    @Test
    fun anEstadoLineWithoutAsciiColonIsAClearErrorNotAContinuation() {
        listOf(
            "ESTADO ANULADA",
            "ESTADO - ANULADA",
            "ESTADO： ANULADA",
            "ESTADO：ANULADA",
            "estado anulada",
            "Estado - anulada",
        ).forEach { linea ->
            // Tras un campo con continuación (RAZONAMIENTO) y también tras la RESPUESTA.
            listOf(bloque(extra = linea), bloque(extra = "RAZONAMIENTO: Art. 82.\n$linea")).forEach { texto ->
                val parsed = parseQuestionBlocks(pdfText(texto), "rafaga")

                assertTrue(parsed.preguntas.isEmpty(), "«$linea» se importó como normal")
                val bloque = assertNotNull(parsed.bloquesConError.singleOrNull(), "«$linea»")
                assertTrue(bloque.detalle.contains("ESTADO: $ESTADO_COLON_DETAIL"), "«$linea»: ${bloque.detalle}")
                assertFalse(bloque.razonamiento.contains("ANULADA", ignoreCase = true), "«$linea» se pegó al razonamiento")
            }
        }
    }

    @Test
    fun aLoneAnuladaLineIsAnError() {
        listOf("ANULADA", "anulada", "  Anulada  ", "ANULADA.", "- ANULADA").forEach { linea ->
            val parsed = parseQuestionBlocks(pdfText(bloque(extra = "RAZONAMIENTO: Art. 82.\n$linea")), "razonada")

            assertTrue(parsed.preguntas.isEmpty(), "«$linea» se importó")
            val bloque = assertNotNull(parsed.bloquesConError.singleOrNull(), "«$linea»")
            assertTrue(bloque.detalle.contains(LONE_ANULADA_DETAIL), "«$linea»: ${bloque.detalle}")
            assertEquals("Art. 82.", bloque.razonamiento)
        }
    }

    @Test
    fun aMotivoLineWithoutAsciiColonIsAClearError() {
        listOf(
            "MOTIVO_ANULACION porque sí",
            "MOTIVO_ANULACION： porque sí",
            "MOTIVO_ANULACION - porque sí",
        ).forEach { linea ->
            val parsed = parseQuestionBlocks(pdfText(bloque(extra = "ESTADO: ANULADA\n$linea")), "rafaga")

            assertTrue(parsed.preguntas.isEmpty(), "«$linea» se aceptó")
            val bloque = assertNotNull(parsed.bloquesConError.singleOrNull(), "«$linea»")
            assertTrue(bloque.detalle.contains("MOTIVO_ANULACION: $MOTIVO_COLON_DETAIL"), "«$linea»: ${bloque.detalle}")
            assertTrue(bloque.anulada, "lo leído de ESTADO se conserva")
        }
    }

    /** Control: prosa que sólo se parece de lejos sigue siendo texto normal. */
    @Test
    fun proseAroundTheWordsEstadoAndAnuladaIsStillText() {
        val pregunta = parseQuestionBlocks(
            pdfText(bloque(extra = "RAZONAMIENTO: El Estado de derecho.\nEstado de la cuestión: resuelta.\nLa norma anulada fue derogada.\nEl estado anulada")),
            "razonada",
        ).preguntas.singleOrNull()

        assertNotNull(pregunta)
        assertFalse(pregunta.anulada)
        assertTrue(pregunta.razonamiento.contains("Estado de la cuestión: resuelta."))
        assertTrue(pregunta.razonamiento.contains("La norma anulada fue derogada."))
    }

    // -----------------------------------------------------------------
    // 8 · Separadores sospechosos más estrictos + caracteres invisibles
    // -----------------------------------------------------------------

    @Test
    fun bulletsOfProseAreNotSuspectSeparators() {
        listOf("- Pregunta 4 del examen anterior", "* Pregunta clave", "– Pregunta abierta", "# Pregunta").forEach { linea ->
            val parsed = parseQuestionBlocks(
                pdfText(bloque(extra = "RAZONAMIENTO: Ver notas.\n$linea")),
                "razonada",
            )

            assertEquals(1, parsed.preguntas.size, "«$linea» abrió un bloque: ${parsed.bloquesConError}")
            assertTrue(parsed.preguntas.single().razonamiento.contains(linea), "«$linea» debe seguir siendo texto")
        }
    }

    @Test
    fun nonBreakingSpacesBomAndZeroWidthSpacesAreNormalised() {
        val nbsp = parseQuestionBlocks("--- PREGUNTA ---\n${bloque("Nbsp")}", "rafaga")
        assertEquals("Nbsp", nbsp.preguntas.single().titulo, "${nbsp.bloquesConError}")

        val bom = parseQuestionBlocks("﻿--- PREGUNTA ---\n${bloque("Bom")}", "rafaga")
        assertEquals("Bom", bom.preguntas.single().titulo, "${bom.bloquesConError}")

        val zw = parseQuestionBlocks("--- PREGUNTA ---​\nTITULO:​ Cero\nA: a\nB: b\nC: c\nD: d\nRESPUESTA: A", "rafaga")
        val cero = assertNotNull(zw.preguntas.singleOrNull(), "${zw.bloquesConError}")
        assertEquals("Cero", cero.titulo)
        assertEquals("c", cero.opciones[2].second)
    }

    // -----------------------------------------------------------------
    // 9 · Una pregunta 1 perdida en el preámbulo NO pasa en silencio
    // -----------------------------------------------------------------

    @Test
    fun aQuestionShapedPreambleWarnsInsteadOfSilentlyDroppingQuestionOne() {
        // El primer separador está mal escrito (`--- PREGUNTAS ---`): la pregunta 1 quedaría como preámbulo.
        val text = "--- PREGUNTAS ---\n${bloque("Perdida")}\n--- PREGUNTA ---\n${bloque("Segunda")}"

        val parsed = parseQuestionBlocks(text, "rafaga")

        val segunda = assertNotNull(parsed.preguntas.singleOrNull(), "${parsed.bloquesConError}")
        assertEquals("Segunda", segunda.titulo)
        assertTrue(
            segunda.advertencia.orEmpty().contains(PREAMBLE_LOOKS_LIKE_QUESTION_WARNING),
            "la primera pregunta leída debe avisar de que se ignoró algo con forma de pregunta: ${segunda.advertencia}",
        )
    }

    @Test
    fun theWarningAlsoReachesAnErrorBlockWhenTheFirstQuestionIsTheBrokenOne() {
        val roto = "TITULO: Sin opciones\nRESPUESTA: A"
        val text = "--- PREGUNTAS ---\n${bloque("Perdida")}\n--- PREGUNTA ---\n$roto"

        val parsed = parseQuestionBlocks(text, "rafaga")

        assertTrue(parsed.preguntas.isEmpty())
        assertTrue(parsed.bloquesConError.first().detalle.contains(PREAMBLE_LOOKS_LIKE_QUESTION_WARNING))
    }

    @Test
    fun aPlainCoverPageDoesNotTriggerThePreambleWarning() {
        val text = "CONVOCATORIA 2025\nTITULO: Ejercicio único\n${pdfText(bloque("Real"))}"

        val parsed = parseQuestionBlocks(text, "rafaga")

        assertNull(parsed.preguntas.single().advertencia, "una portada con un TITULO suelto no es una pregunta perdida")
    }
}
