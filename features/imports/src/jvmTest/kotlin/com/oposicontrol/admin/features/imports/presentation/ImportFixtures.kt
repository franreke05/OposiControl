package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import kotlinx.datetime.Instant

/**
 * Estados de Importación para el banco de pruebas visual.
 *
 * Datos inventados a propósito y sin tocar ningún repositorio: esto sirve para
 * revisar jerarquía, densidad, tema y responsive, no para validar el contrato.
 */
internal object ImportFixtures {

    private val NOW = Instant.parse("2026-04-15T09:00:00Z")

    val EXAMENES: List<Exam> = listOf(
        exam("ex-1", "EX-2026-0001", "TCAE · Simulacro marzo", "rafaga"),
        exam("ex-2", "EX-2026-0002", "TCAE · Simulacro abril", "rafaga"),
        exam("ex-3", "EX-2026-0004", "TCAE · Higiene hospitalaria", "razonado"),
        exam("ex-4", "EX-2026-0007", "TCAE · Casos clínicos", "inmersivo"),
    )

    private fun exam(id: String, code: String, title: String, type: String) = Exam(
        id = id,
        code = code,
        specialtyId = "esp-tcae",
        specialtyName = "TCAE",
        title = title,
        description = "",
        typeExam = type,
        typeAccess = "gratuito",
        priceTickets = 0,
        status = ExamStatus.PUBLISHED,
        publishFrom = null,
        publishedAt = NOW,
        sortOrder = 0,
        questionCount = 20,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun row(
        id: String,
        position: Int,
        title: String,
        correct: String = "A",
        topic: String = "Tema 28 · Higiene",
        reasoning: String = "",
        source: String = "",
        status: String = "ok",
        error: String? = null,
    ) = ContentImportStagingItem(
        id = id,
        position = position,
        title = title,
        optionA = "Lavado de manos con solución hidroalcohólica antes y después de cada contacto.",
        optionB = "Uso de guantes estériles únicamente en quirófano.",
        optionC = "Cambio de bata al finalizar el turno.",
        optionD = "Desinfección del carro de curas una vez por semana.",
        correctAnswer = correct,
        reasoning = reasoning,
        sourceText = source,
        tagText = topic,
        originalBlock = "",
        parseStatus = status,
        parseError = error,
    )

    private val FILAS_OK = listOf(
        row("s-1", 1, "¿Cuál de las siguientes medidas reduce de forma más eficaz la infección nosocomial?"),
        row("s-2", 2, "Señala el orden correcto de colocación del equipo de protección individual.", correct = "C"),
        row("s-3", 3, "La Constitución Española reconoce el derecho a la protección de la salud en su artículo…", correct = "B"),
        row("s-4", 4, "En una cura estéril, ¿cuál es la secuencia correcta de retirada del material?", correct = "D"),
        row("s-5", 5, "¿Qué residuo corresponde al contenedor de grupo III?"),
    )

    private fun batch(
        rows: List<ContentImportStagingItem>,
        status: String = "parseado",
        type: String = "rafaga",
        imported: Int = 0,
        file: String = "simulacro_tema_28.pdf",
    ) = ContentImportBatch(
        id = "5f2c9a10-3b4d-4e6f-8a9b-0c1d2e3f4a5b",
        examId = "ex-1",
        fileName = file,
        examType = type,
        status = status,
        detectedCount = rows.size,
        importedCount = imported,
        staging = rows,
    )

    private fun base() = ImportState(
        exams = UiAsync.Success(EXAMENES),
        batches = UiAsync.Success(
            listOf(
                batch(FILAS_OK, file = "simulacro_tema_28.pdf"),
                batch(FILAS_OK.take(3), status = "publicado", imported = 3, file = "tema_12_basico.pdf"),
                batch(FILAS_OK.take(2), status = "parcial", imported = 1, type = "razonado", file = "razonadas_abril.pdf"),
            ),
        ),
    )

    // -----------------------------------------------------------------------
    // Entrada
    // -----------------------------------------------------------------------

    /** Pantalla inicial: tipo por defecto, sin examen ni archivo. */
    fun inicio(): ImportState = base()

    fun rafagaElegida(): ImportState = base().copy(
        selectedType = ContentExamType.RAFAGA,
        selectedExamId = "ex-1",
    )

    fun razonadaElegida(): ImportState = base().copy(
        selectedType = ContentExamType.RAZONADO,
        selectedExamId = "ex-3",
    )

    fun inmersivaElegida(): ImportState = base().copy(
        selectedType = ContentExamType.INMERSIVO,
        selectedExamId = "ex-4",
    )

    /** Con el PDF elegido y listo para subir. */
    fun archivoElegido(): ImportState = rafagaElegida().copy(
        pickedPdfName = "simulacro_tema_28.pdf",
        pickedPdfSizeBytes = 486_112,
    )

    /** Subiendo: el servidor está analizando el PDF. */
    fun procesando(): ImportState = archivoElegido().copy(actionState = UiAsync.Loading)

    // -----------------------------------------------------------------------
    // Revisión
    // -----------------------------------------------------------------------

    /** Lote limpio: todo listo para publicar. */
    fun revisionSinErrores(): ImportState = base().copy(
        activeBatch = UiAsync.Success(batch(FILAS_OK)),
    )

    /** Lote con errores reales del servidor: publicar queda bloqueado. */
    fun revisionConErrores(): ImportState = base().copy(
        activeBatch = UiAsync.Success(
            batch(
                listOf(
                    FILAS_OK[0],
                    FILAS_OK[1],
                    row("s-6", 6, "Pregunta con la correcta mal escrita.", correct = "1", error = "CORRECTA debe ser A, B, C o D."),
                    row("s-7", 7, "Pregunta de un examen que no existe.", error = "No existe el examen EX-042."),
                    // Bloque cuyo enunciado el servidor no ha podido leer: la fila
                    // existe, pero sin texto. La UI lo dice («Sin enunciado») en vez
                    // de pintar un hueco.
                    row("s-8", 8, title = "", error = "No se ha podido leer el enunciado de este bloque."),
                ),
            ),
        ),
    )

    /** Fila abierta en el detalle, para revisar el editor. */
    fun revisionConDetalle(): ImportState = revisionConErrores().let { state ->
        val row = (state.activeBatch as UiAsync.Success).value.staging.first { it.parseError != null }
        state.copy(selectedStagingId = row.id, editDraft = row)
    }

    /** Duplicada candidata: se dice, no se fusiona. */
    fun revisionConDuplicado(): ImportState = base().copy(
        activeBatch = UiAsync.Success(
            batch(
                listOf(
                    FILAS_OK[0],
                    row("s-9", 9, "¿Qué residuo corresponde al contenedor de grupo III?", status = "duplicada_candidata"),
                    row("s-10", 10, "Pregunta ya revisada y descartada.", status = "omitida"),
                ),
            ),
        ),
    )

    /** Publicado a medias: el peor final, dicho sin disfrazarlo. */
    fun publicadoParcial(): ImportState = base().copy(
        activeBatch = UiAsync.Success(batch(FILAS_OK, status = "parcial", imported = 2)),
        actionState = UiAsync.Success("Publicación parcial: 2 de 5. Revisa los errores antes de reintentar."),
    )

    // -----------------------------------------------------------------------
    // Estados del historial
    // -----------------------------------------------------------------------

    fun historialVacio(): ImportState = base().copy(batches = UiAsync.Empty)

    fun historialCaido(): ImportState = base().copy(
        batches = UiAsync.Error(AppError.Network("connection refused")),
    )

    /** El PDF no se pudo analizar: el error va donde se sube, no en toda la página. */
    fun subidaFallida(): ImportState = archivoElegido().copy(
        actionState = UiAsync.Error(
            AppError.Validation("El PDF no contiene texto seleccionable: no se han detectado preguntas."),
        ),
    )
}
