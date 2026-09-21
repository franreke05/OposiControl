package com.oposicontrol.backend

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

/**
 * Genera los PDF BINARIOS reales de `src/test/resources/pdf/` con PDFBox.
 *
 * No son mocks: son PDF de verdad (fuente Helvetica Standard14, codificación WinAnsi, así que
 * acentos, ñ, ¿ y ¡ sobreviven al ciclo texto → PDF → `PDFTextStripper`). Los tests LEEN los
 * ficheros del classpath; este generador sólo se ejecuta con
 * `-Dgenerate.pdf.fixtures=true` (ver [GeneratePdfFixtures]).
 *
 * Cada documento es una lista de PÁGINAS y cada página una lista de LÍNEAS, tal cual saldrían
 * de un editor. Una pregunta puede empezar en una página y acabar en la siguiente: es el caso
 * real que más rompe a los importadores y hay un fixture que lo fuerza.
 */
internal object PdfFixtureFactory {

    /** Nombre de fichero → páginas. El nombre es el contrato con los tests. */
    val documents: Map<String, List<List<String>>> = linkedMapOf(
        "rafaga_valid.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: ¿Cuál es el plazo para interponer un recurso de alzada?",
                "A: Diez días",
                "B: Un mes",
                "C: Tres meses",
                "D: Un año",
                "RESPUESTA: B",
                "--- PREGUNTA ---",
                "TITULO: ¡Atención! ¿Qué órgano resuelve el recurso de reposición?",
                "A: El superior jerárquico",
                "B: El Consejo de Estado",
                "C: El mismo órgano que dictó el acto",
                "D: El Defensor del Pueblo",
                "RESPUESTA: c",
                "--- PREGUNTA ---",
                "TITULO: ¿Qué principio rige la actuación de la Administración en España?",
                "A: Eficacia",
                "B: Oportunidad política",
                "C: Discrecionalidad absoluta",
                "D: Ninguno de los anteriores",
                "RESPUESTA: A",
            ),
        ),
        "razonada_valid.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: ¿Qué artículo de la Constitución regula la reforma constitucional?",
                "A: El 166",
                "B: El 168",
                "C: El 169",
                "D: El 171",
                "RESPUESTA: B",
                "RAZONAMIENTO: El Título X, que empieza en el artículo 166, regula la reforma.",
                "El procedimiento agravado del artículo 168 exige mayoría de dos tercios.",
                "FUENTE: Constitución Española, arts. 166 a 169",
                "ETIQUETA: constitucion, reforma",
                "--- PREGUNTA ---",
                "TITULO: ¿Cuántos días hábiles tiene la Administración para resolver un procedimiento?",
                "A: Tres meses como máximo, salvo norma con rango de ley",
                "B: Seis meses siempre",
                "C: Un mes improrrogable",
                "D: No hay plazo",
                "RESPUESTA: A",
                "RAZONAMIENTO: Según el artículo 21 de la Ley 39/2015 el plazo máximo es de tres meses",
                "cuando la norma reguladora no fije uno distinto.",
                "--- PREGUNTA ---",
                "TITULO: El silencio administrativo en procedimientos iniciados a solicitud del interesado",
                "A: Es siempre positivo",
                "B: Es siempre negativo",
                "C: Es positivo por regla general",
                "D: No produce efectos",
                "RESPUESTA: C",
                "RAZONAMIENTO: La regla general es el silencio positivo (art. 24 Ley 39/2015).",
            ),
        ),
        "inmersiva_valid.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: Lee el supuesto práctico sobre el paciente en urgencias. ¿Cuál es la primera actuación?",
                "A: Avisar al familiar",
                "B: Valorar la vía aérea",
                "C: Administrar analgesia",
                "D: Trasladar a planta",
                "RESPUESTA: B",
                "RAZONAMIENTO: En la valoración inicial ABCDE lo primero es la permeabilidad de la vía aérea.",
                "FUENTE: Protocolo de triaje del hospital",
                "--- PREGUNTA ---",
                "TITULO: Tras la valoración inicial, ¿qué se hace a continuación?",
                "A: Evaluar la ventilación",
                "B: Dar el alta",
                "C: Cambiar el turno",
                "D: Nada",
                "RESPUESTA: A",
                "RAZONAMIENTO: Después de la vía aérea se evalúa la ventilación (B de ABCDE).",
            ),
        ),
        // El ejemplo de la anulación: una anulada con motivo y otra normal en el MISMO pdf.
        "razonada_annulled.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: ¿Quién sanciona las leyes en España?",
                "A: El Presidente del Gobierno",
                "B: El Rey",
                "C: El Presidente del Congreso",
                "D: El Tribunal Constitucional",
                "RESPUESTA: B",
                "RAZONAMIENTO: El artículo 91 de la Constitución atribuye la sanción al Rey.",
                "ESTADO: ANULADA",
                "MOTIVO_ANULACION: Anulada por el tribunal",
                "--- PREGUNTA ---",
                "TITULO: ¿Qué mayoría exige una ley orgánica?",
                "A: Mayoría absoluta del Congreso en votación final sobre el conjunto",
                "B: Mayoría simple",
                "C: Dos tercios",
                "D: Unanimidad",
                "RESPUESTA: A",
                "RAZONAMIENTO: Artículo 81.2 de la Constitución.",
            ),
        ),
        // Ráfaga con una normal y una anulada (sin RAZONAMIENTO ni FUENTE: Ráfaga no los publica).
        "rafaga_annulled.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: ¿Cuántos miembros componen el Tribunal Constitucional?",
                "A: Nueve",
                "B: Diez",
                "C: Doce",
                "D: Quince",
                "RESPUESTA: C",
                "--- PREGUNTA ---",
                "TITULO: ¿Cuál es la lengua oficial del Estado español?",
                "A: El castellano",
                "B: El catalán",
                "C: El gallego",
                "D: El euskera",
                "RESPUESTA: A",
                "ESTADO: ANULADA",
                "MOTIVO_ANULACION: Anulada por el tribunal",
            ),
        ),
        // Cinco preguntas Ráfaga, la 3 anulada: el examen de la prueba de puntuación.
        "rafaga_five_one_annulled.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: Puntuación uno: ¿cuál es la capital de Francia?",
                "A: París",
                "B: Roma",
                "C: Madrid",
                "D: Berlín",
                "RESPUESTA: A",
                "--- PREGUNTA ---",
                "TITULO: Puntuación dos: ¿cuántos días tiene una semana?",
                "A: Cinco",
                "B: Seis",
                "C: Siete",
                "D: Ocho",
                "RESPUESTA: C",
                "--- PREGUNTA ---",
                "TITULO: Puntuación tres: ¿qué planeta es el más cercano al Sol?",
                "A: Mercurio",
                "B: Venus",
                "C: Marte",
                "D: Júpiter",
                "RESPUESTA: A",
                "ESTADO: ANULADA",
                "MOTIVO_ANULACION: Anulada por el tribunal",
                "--- PREGUNTA ---",
                "TITULO: Puntuación cuatro: ¿cuántos lados tiene un triángulo?",
                "A: Dos",
                "B: Tres",
                "C: Cuatro",
                "D: Cinco",
                "RESPUESTA: B",
                "--- PREGUNTA ---",
                "TITULO: Puntuación cinco: ¿cuál es el resultado de dos más dos?",
                "A: Tres",
                "B: Cuatro",
                "C: Cinco",
                "D: Seis",
                "RESPUESTA: B",
            ),
        ),
        // Inmersiva con una normal y una anulada. Sin recursos: la importación no los soporta.
        "inmersiva_annulled.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: Tras escuchar el aviso, ¿cuál es la primera actuación del técnico?",
                "A: Alertar al equipo",
                "B: Ignorar el aviso",
                "C: Esperar instrucciones",
                "D: Abandonar el puesto",
                "RESPUESTA: A",
                "RAZONAMIENTO: El protocolo exige alertar al equipo de forma inmediata.",
                "--- PREGUNTA ---",
                "TITULO: Según el vídeo, ¿qué documento debe cumplimentarse al finalizar?",
                "A: El parte de incidencias",
                "B: La factura",
                "C: El albarán",
                "D: Ninguno",
                "RESPUESTA: A",
                "RAZONAMIENTO: Todo incidente se documenta en el parte de incidencias.",
                "ESTADO: ANULADA",
                "MOTIVO_ANULACION: Enunciado ambiguo según el tribunal",
            ),
        ),
        // Varias normales y anuladas, tres páginas, y la pregunta 3 cruza de la página 1 a la 2.
        "mixed_normal_annulled.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: Pregunta uno normal",
                "A: Opción uno A",
                "B: Opción uno B",
                "C: Opción uno C",
                "D: Opción uno D",
                "RESPUESTA: A",
                "RAZONAMIENTO: Razonamiento de la primera pregunta.",
                "--- PREGUNTA ---",
                "TITULO: Pregunta dos anulada con motivo",
                "A: Opción dos A",
                "B: Opción dos B",
                "C: Opción dos C",
                "D: Opción dos D",
                "RESPUESTA: B",
                "RAZONAMIENTO: Razonamiento de la segunda pregunta.",
                "ESTADO: ANULADA",
                "MOTIVO_ANULACION: Enunciado ambiguo según el tribunal",
                "--- PREGUNTA ---",
                "TITULO: Pregunta tres normal que cruza de página",
                "A: Opción tres A",
                "B: Opción tres B",
                "C: Opción tres C",
                "D: Opción tres D",
                "RESPUESTA: C",
                "RAZONAMIENTO: Este razonamiento empieza al final de la primera página",
            ),
            listOf(
                "y termina al principio de la segunda, sin ningún marcador de página en medio.",
                "--- PREGUNTA ---",
                "TITULO: Pregunta cuatro anulada sin motivo",
                "A: Opción cuatro A",
                "B: Opción cuatro B",
                "C: Opción cuatro C",
                "D: Opción cuatro D",
                "RESPUESTA: D",
                "RAZONAMIENTO: Razonamiento de la cuarta pregunta.",
                "ESTADO: ANULADA",
                "--- PREGUNTA ---",
                "TITULO: Pregunta cinco normal",
                "A: Opción cinco A",
                "B: Opción cinco B",
                "C: Opción cinco C",
                "D: Opción cinco D",
                "RESPUESTA: A",
                "RAZONAMIENTO: Razonamiento de la quinta pregunta.",
                "--- PREGUNTA ---",
                "TITULO: Pregunta seis anulada con motivo",
                "A: Opción seis A",
                "B: Opción seis B",
                "C: Opción seis C",
                "D: Opción seis D",
                "RESPUESTA: B",
                "RAZONAMIENTO: Razonamiento de la sexta pregunta.",
                "ESTADO: ANULADA",
                "MOTIVO_ANULACION: Dos respuestas correctas",
            ),
            listOf(
                "--- PREGUNTA ---",
                "TITULO: Pregunta siete normal",
                "A: Opción siete A",
                "B: Opción siete B",
                "C: Opción siete C",
                "D: Opción siete D",
                "RESPUESTA: C",
                "RAZONAMIENTO: Razonamiento de la séptima pregunta.",
                "--- PREGUNTA ---",
                "TITULO: Pregunta ocho normal",
                "A: Opción ocho A",
                "B: Opción ocho B",
                "C: Opción ocho C",
                "D: Opción ocho D",
                "RESPUESTA: D",
                "RAZONAMIENTO: Razonamiento de la octava pregunta.",
            ),
        ),
        "invalid_separator.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: Primera pregunta correcta",
                "A: a",
                "B: b",
                "C: c",
                "D: d",
                "RESPUESTA: A",
                "--- PREGUNTA 1 ---",
                "TITULO: Pregunta con el separador numerado",
                "A: a",
                "B: b",
                "C: c",
                "D: d",
                "RESPUESTA: B",
                "--- PREGUNTA ---",
                "TITULO: Tercera pregunta correcta",
                "A: a",
                "B: b",
                "C: c",
                "D: d",
                "RESPUESTA: C",
            ),
        ),
        "with_preamble.pdf" to listOf(
            listOf(
                "EXAMEN OFICIAL 2025",
                "Cuerpo General Administrativo",
                "Instrucciones: lea atentamente cada pregunta antes de contestar.",
                "TITULO: esto es parte de la portada y no una pregunta",
                "Página 1",
                "--- PREGUNTA ---",
                "TITULO: ¿Cuál es la capital de España?",
                "A: Madrid",
                "B: Sevilla",
                "C: Valencia",
                "D: Bilbao",
                "RESPUESTA: A",
                "--- PREGUNTA ---",
                "TITULO: ¿En qué año se aprobó la Constitución?",
                "A: 1975",
                "B: 1978",
                "C: 1981",
                "D: 1986",
                "RESPUESTA: B",
            ),
        ),
        "duplicated_field.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: Pregunta correcta",
                "A: a",
                "B: b",
                "C: c",
                "D: d",
                "RESPUESTA: A",
                "--- PREGUNTA ---",
                "TITULO: Pregunta con la respuesta repetida",
                "A: a",
                "B: b",
                "C: c",
                "D: d",
                "RESPUESTA: B",
                "RESPUESTA: C",
            ),
        ),
        "malformed_question.pdf" to listOf(
            listOf(
                "--- PREGUNTA ---",
                "TITULO: Falta la opción C",
                "A: a",
                "B: b",
                "D: d",
                "RESPUESTA: A",
                "--- PREGUNTA ---",
                "TITULO: Respuesta fuera de A-D",
                "A: a",
                "B: b",
                "C: c",
                "D: d",
                "RESPUESTA: E",
                "--- PREGUNTA ---",
                "TITULO: Pregunta correcta",
                "A: a",
                "B: b",
                "C: c",
                "D: d",
                "RESPUESTA: D",
            ),
        ),
    )

    /** Un PDF de una página sin ninguna operación de texto: lo que da un escaneado sin OCR. */
    const val NO_TEXT_FIXTURE = "scanned_like_no_text.pdf"

    /** Los bytes de un fixture; el de [NO_TEXT_FIXTURE] es una página con sólo un recuadro gráfico. */
    fun build(name: String): ByteArray =
        if (name == NO_TEXT_FIXTURE) renderWithoutText() else render(documents.getValue(name))

    /** Todos los nombres, los del generador y el sin texto. */
    fun allNames(): List<String> = documents.keys.toList() + NO_TEXT_FIXTURE

    private fun render(pages: List<List<String>>): ByteArray = PDDocument().use { doc ->
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        pages.forEach { lines ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(font, 11f)
                content.setLeading(15f)
                content.newLineAtOffset(50f, 790f)
                lines.forEach { line ->
                    content.showText(line)
                    content.newLine()
                }
                content.endText()
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun renderWithoutText(): ByteArray = PDDocument().use { doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { content ->
            // Un recuadro relleno: hay "algo" en la página, pero ningún texto seleccionable.
            content.addRect(60f, 500f, 400f, 250f)
            content.fill()
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
}
