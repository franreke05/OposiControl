package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.data.repository.KtorAdminV1ContentRepositoryImpl
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.io.File

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorAdminV1ContentRepositoryImpl.kt"
private const val A = "/api/v1/admin"
private const val ESP = "$A/especialidades"
private const val EXA = "$A/examenes"
private const val PRE = "$A/preguntas"
private const val IMP = "$A/importaciones"

private val NOW = Instant.parse("2026-01-01T00:00:00Z")

/**
 * Respuesta del servidor simulado: este repositorio ENCADENA llamadas (existe la pregunta, nombre de la especialidad, relectura de
 * cachés), asi que el `{"data":[]}` fijo del [Env] cortaria la cadena en el primer mapeo. Aqui cada ruta contesta con la forma
 * que el repositorio espera (ids centinela `ID1` como elemento ya existente); la peticion se graba igual que en el [Env].
 */
private fun body(c: Call): String = when {
    c.method != "GET" -> """{"data":{"id":"ID0","especialidadId":"ID0","tipoExamen":"rafaga"}}"""
    c.template == ESP -> """{"data":[{"id":"ID0","nombre":"E"}]}"""
    c.template == EXA -> """{"data":[{"id":"ID0","especialidadId":"ID0","tipoExamen":"rafaga"}]}"""
    c.template == "$EXA/{}/preguntas" -> """{"data":[{"id":"ID1","posicion":1},{"id":"ID2","posicion":2}]}"""
    c.template == "$PRE/{}/recursos" -> """{"data":[{"id":"ID1"}]}"""
    c.template == "$IMP/preguntas" -> """{"data":[]}"""
    else -> """{"data":{"id":"ID0","especialidadId":"ID0","tipoExamen":"rafaga"}}"""
}

private suspend fun Env.withRepo(block: suspend (KtorAdminV1ContentRepositoryImpl) -> Unit) {
    val scripted = HttpClient(
        MockEngine { request ->
            val call = recordedCall(request)
            recorded += call
            respond(
                content = body(call),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    try {
        block(KtorAdminV1ContentRepositoryImpl(scripted, appConfig, session, adapter, PlatformFileReader(AppPlatformContext())))
    } finally {
        scripted.close()
    }
}

private fun tempFile(suffix: String): File = File.createTempFile("oc-inventory", suffix).apply {
    writeBytes(byteArrayOf(1, 2, 3))
    deleteOnExit()
}

private val SPECIALTY_NEW = Specialty(id = "", name = "n", description = "", isActive = true, sortOrder = 0, linkedExams = 0, createdAt = NOW, updatedAt = NOW)
private val EXAM_NEW = Exam(
    id = "", specialtyId = "ID0", specialtyName = "", title = "t", description = "", typeExam = "rafaga", priceTickets = 0,
    status = ExamStatus.DRAFT, publishFrom = null, publishedAt = null, sortOrder = 0, questionCount = 0, createdAt = NOW, updatedAt = NOW,
)
private val RAFAGA_ARCHIVED = Rafaga(
    id = "", specialtyId = "ID0", specialtyName = "", title = "t", priceTickets = 0, status = ExamStatus.ARCHIVED,
    publishFrom = null, publishedAt = null, questionCount = 0, createdAt = NOW, updatedAt = NOW,
)

private fun examQuestion(id: String) = ExamQuestion(
    id = id, examId = "ID0", title = "t", statement = "s", sortOrder = 0, options = emptyList(), resources = emptyList(),
    createdAt = NOW, updatedAt = NOW,
)

private fun rafagaQuestion(id: String) = RafagaQuestion(id = id, rafagaId = "ID0", statement = "s", sortOrder = 0, createdAt = NOW, updatedAt = NOW)

private val STAGING_ITEM = ContentImportStagingItem(
    id = "ID0", position = 1, title = "t", optionA = "a", optionB = "b", optionC = "c", optionD = "d", correctAnswer = "A",
    reasoning = "", sourceText = "", tagText = "", originalBlock = "", parseStatus = "ok",
)

// Secuencias compartidas por los sitios que se alcanzan desde el mismo metodo publico (cada sitio tiene su fila).
private val REFRESH_SPECIALTIES = listOf(get(ESP), get(EXA))
private val IMPORT_QUESTIONS = listOf(get("$EXA/{}"), post("$IMP/preguntas"), post("$IMP/{}/importar"), get("$EXA/{}/preguntas"))
private val UPSERT_RESOURCE_UPDATE = listOf(get("$PRE/{}/recursos"), patch("$PRE/{}/recursos/{}"))
private val UPSERT_QUESTION_CREATE = listOf(get("$EXA/{}"), post(PRE))
private val MOVE = listOf(get("$EXA/{}/preguntas"), patch("$EXA/{}/preguntas/orden"))

/** Filas del registrador (R5-REC-C1): KtorAdminV1ContentRepositoryImpl, 36 sitios de llamada (una fila por sitio, en orden de fichero). */
internal val ROWS_C_ADMIN_V1_CONTENT_REPO: List<CallRow> = listOf(
    CallRow(FILE, "upsertSpecialty(create)", listOf(post(ESP)) + REFRESH_SPECIALTIES) {
        withRepo { it.upsertSpecialty(SPECIALTY_NEW) }
    },
    CallRow(FILE, "upsertSpecialty(update)", listOf(patch("$ESP/{}")) + REFRESH_SPECIALTIES) {
        withRepo { it.upsertSpecialty(SPECIALTY_NEW.copy(id = "ID0")) }
    },
    CallRow(FILE, "deleteSpecialty", listOf(delete("$ESP/{}")) + REFRESH_SPECIALTIES) {
        withRepo { it.deleteSpecialty("ID0") }
    },
    CallRow(FILE, "getSpecialtyCapabilities", get("$ESP/{}/capacidades")) {
        withRepo { it.getSpecialtyCapabilities("ID0") }
    },
    CallRow(FILE, "saveSpecialtyCapabilities", put("$ESP/{}/capacidades")) {
        withRepo { it.saveSpecialtyCapabilities(SpecialtyCapabilities(true, true, true, true, true, specialtyId = "ID0")) }
    },
    CallRow(FILE, "deleteExam", delete("$EXA/{}")) {
        withRepo { it.deleteExam("ID0") }
    },
    // deleteRafaga: DELETE y la relectura de cachés (sin especialidades en memoria: las pide antes del catalogo)
    CallRow(FILE, "deleteRafaga", listOf(delete("$EXA/{}"), get(ESP), get(EXA))) {
        withRepo { it.deleteRafaga("ID0") }
    },
    CallRow(FILE, "upsertQuestion(create)", UPSERT_QUESTION_CREATE) {
        withRepo { it.upsertQuestion(examQuestion("")) }
    },
    // id existente: comprueba la lista del examen, lee el examen (tipo de pregunta) y guarda con PATCH
    CallRow(FILE, "upsertQuestion(update)", listOf(get("$EXA/{}/preguntas"), get("$EXA/{}"), patch("$PRE/{}"))) {
        withRepo { it.upsertQuestion(examQuestion("ID1")) }
    },
    CallRow(FILE, "importQuestions(create)", IMPORT_QUESTIONS) {
        withRepo { it.importQuestions("ID0", emptyList()) }
    },
    CallRow(FILE, "importQuestions(import)", IMPORT_QUESTIONS) {
        withRepo { it.importQuestions("ID0", emptyList()) }
    },
    CallRow(FILE, "listImportBatches", get("$IMP/preguntas")) {
        withRepo { it.listImportBatches() }
    },
    CallRow(FILE, "getImportBatch", get("$IMP/preguntas/{}")) {
        withRepo { it.getImportBatch("ID0") }
    },
    CallRow(FILE, "uploadPdfToStaging", post("$IMP/preguntas/pdf")) {
        withRepo { it.uploadPdfToStaging("ID0", "rafaga", tempFile(".pdf").absolutePath, "a.pdf") }
    },
    CallRow(FILE, "updateStaging", patch("$IMP/staging/{}")) {
        withRepo { it.updateStaging(STAGING_ITEM) }
    },
    CallRow(FILE, "publishImportBatch", post("$IMP/{}/importar")) {
        withRepo { it.publishImportBatch("ID0", confirmExamPublished = true) }
    },
    CallRow(FILE, "unlinkQuestionFromExam", listOf(delete("$EXA/{}/preguntas/{}"), get("$EXA/{}/preguntas"))) {
        withRepo { it.unlinkQuestionFromExam("ID0", "ID1") }
    },
    CallRow(FILE, "archiveQuestion", delete("$PRE/{}")) {
        withRepo { it.archiveQuestion("ID0") }
    },
    CallRow(FILE, "upsertRafagaQuestion(create)", listOf(post(PRE), get("$EXA/{}/preguntas"))) {
        withRepo { it.upsertRafagaQuestion(rafagaQuestion("")) }
    },
    CallRow(FILE, "upsertRafagaQuestion(update)", listOf(get("$EXA/{}/preguntas"), patch("$PRE/{}"), get("$EXA/{}/preguntas"))) {
        withRepo { it.upsertRafagaQuestion(rafagaQuestion("ID1")) }
    },
    CallRow(FILE, "deleteRafagaQuestion", listOf(delete("$PRE/{}"), get("$EXA/{}/preguntas"))) {
        withRepo { it.deleteRafagaQuestion("ID0", "ID1") }
    },
    CallRow(FILE, "uploadResource(create)", post("$PRE/{}/recursos")) {
        withRepo { it.uploadResource("ID0", "", QuestionResourceType.TEXT, "texto", "t") }
    },
    CallRow(FILE, "uploadResource(update)", UPSERT_RESOURCE_UPDATE) {
        withRepo { it.uploadResource("ID0", "ID1", QuestionResourceType.TEXT, "texto", "t") }
    },
    CallRow(FILE, "deleteResource", delete("$PRE/{}/recursos/{}")) {
        withRepo { it.deleteResource("ID0", "ID1") }
    },
    CallRow(FILE, "uploadExamCover", post("$EXA/{}/portada")) {
        withRepo { it.uploadExamCover("ID0", tempFile(".png").absolutePath) }
    },
    CallRow(FILE, "clearExamCover", delete("$EXA/{}/portada")) {
        withRepo { it.clearExamCover("ID0") }
    },

    // ---- ayudantes privados (un sitio cada uno): su fila ejecuta los metodos publicos que pasan por el --------------------
    CallRow(FILE, "getEspecialidades", REFRESH_SPECIALTIES, covers = setOf("refreshSpecialties")) {
        withRepo { it.refreshSpecialties() }
    },
    // sin especialidades en cache la primera relectura las pide; las dos siguientes solo piden el catalogo
    CallRow(FILE, "getExamenes", listOf(get(ESP), get(EXA), get(EXA), get(EXA)), covers = setOf("refreshAllExams", "refreshExams", "refreshRafagas")) {
        withRepo { it.refreshAllExams(); it.refreshExams(); it.refreshRafagas() }
    },
    CallRow(
        FILE, "getAdminQuestions", List(3) { get("$EXA/{}/preguntas") },
        covers = setOf("getExamQuestions", "getRafagaQuestions", "getExamResourceAudit"),
    ) {
        withRepo { it.getExamQuestions("ID0"); it.getRafagaQuestions("ID0"); it.getExamResourceAudit("ID0") }
    },
    CallRow(FILE, "upsertExamDto(create)", listOf(post(EXA), get(ESP)), covers = setOf("upsertExam")) {
        withRepo { it.upsertExam(EXAM_NEW) }
    },
    CallRow(FILE, "upsertExamDto(update)", listOf(patch("$EXA/{}"), get(ESP)), covers = setOf("upsertExam")) {
        withRepo { it.upsertExam(EXAM_NEW.copy(id = "ID0")) }
    },
    // rafaga archivada: crea (POST), encadena la ruta DEDICADA de archivar (nunca DELETE), nombre de especialidad y relectura
    CallRow(
        FILE, "upsertExamDto(archive)", listOf(post(EXA), post("$EXA/{}/archivar"), get(ESP), get(ESP), get(EXA)),
        covers = setOf("upsertRafaga"),
    ) {
        withRepo { it.upsertRafaga(RAFAGA_ARCHIVED) }
    },
    CallRow(
        FILE, "postExamState",
        listOf(post("$EXA/{}/publicar"), get(ESP), post("$EXA/{}/despublicar"), get(ESP), post("$EXA/{}/archivar"), get(ESP)),
        covers = setOf("publishExam", "unpublishExam", "archiveExam"),
    ) {
        withRepo { it.publishExam("ID0"); it.unpublishExam("ID0"); it.archiveExam("ID0") }
    },
    CallRow(FILE, "postQuestionOrder", MOVE + MOVE, covers = setOf("moveQuestion", "moveRafagaQuestion")) {
        withRepo {
            it.moveQuestion("ID0", "ID1", QuestionMoveDirection.DOWN)
            it.moveRafagaQuestion("ID0", "ID1", QuestionMoveDirection.DOWN)
        }
    },
    CallRow(FILE, "isExistingResource", UPSERT_RESOURCE_UPDATE, covers = setOf("uploadResource")) {
        withRepo { it.uploadResource("ID0", "ID1", QuestionResourceType.TEXT, "texto", "t") }
    },
    CallRow(FILE, "getExamen", UPSERT_QUESTION_CREATE, covers = setOf("upsertQuestion")) {
        withRepo { it.upsertQuestion(examQuestion("")) }
    },
)
