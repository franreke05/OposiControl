package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorQuestionsRepositoryImpl
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionListQuery
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.QuestionResourceDraft
import com.oposicontrol.admin.domain.repository.QuestionResourceFile
import com.oposicontrol.admin.domain.repository.QuestionResourceKind

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorQuestionsRepositoryImpl.kt"
private const val Q = "/api/v1/admin/preguntas"

private fun Env.repo() = KtorQuestionsRepositoryImpl(client, appConfig, session, adapter)

private val DRAFT = QuestionDraft(statement = "s", type = QuestionType.RAFAGA, options = emptyList(), explanation = "e", source = "", active = true, difficulty = "")
private val RESOURCE = QuestionResourceDraft(kind = QuestionResourceKind.TEXTO, title = "t", textBody = "b")

/** Filas del registrador (R4-REC-B): KtorQuestionsRepositoryImpl (13 sitios, una fila por metodo). */
internal val ROWS_B_QUESTIONS_REPO: List<CallRow> = listOf(
    CallRow(FILE, "listQuestions", get(Q)) { repo().listQuestions(QuestionListQuery()) },
    CallRow(FILE, "getQuestionDetail", get("$Q/{}")) { repo().getQuestionDetail("ID0") },
    CallRow(FILE, "createQuestion", post(Q)) { repo().createQuestion("ID0", DRAFT, null) },
    CallRow(FILE, "updateQuestion", patch("$Q/{}")) { repo().updateQuestion("ID0", DRAFT, optionsChanged = false) },
    CallRow(FILE, "archiveQuestion", delete("$Q/{}")) { repo().archiveQuestion("ID0") },
    CallRow(FILE, "listQuestionExams", get("$Q/{}/examenes")) { repo().listQuestionExams("ID0") },
    CallRow(FILE, "linkQuestionToExam", post("$Q/{}/examenes")) { repo().linkQuestionToExam("ID0", "ID1", null) },
    CallRow(FILE, "unlinkQuestionFromExam", delete("$Q/{}/examenes/{}")) { repo().unlinkQuestionFromExam("ID0", "ID1") },
    CallRow(FILE, "listQuestionResources", get("$Q/{}/recursos")) { repo().listQuestionResources("ID0") },
    CallRow(FILE, "createQuestionResource", post("$Q/{}/recursos")) { repo().createQuestionResource("ID0", RESOURCE) },
    CallRow(FILE, "uploadQuestionResourceFile", post("$Q/{}/recursos/archivo")) {
        repo().uploadQuestionResourceFile("ID0", "ID1", QuestionResourceDraft(kind = QuestionResourceKind.PDF, title = "t"), QuestionResourceFile("a.pdf", "application/pdf", byteArrayOf(1)))
    },
    CallRow(FILE, "updateQuestionResource", patch("$Q/{}/recursos/{}")) { repo().updateQuestionResource("ID0", "ID1", RESOURCE) },
    CallRow(FILE, "deleteQuestionResource", delete("$Q/{}/recursos/{}")) { repo().deleteQuestionResource("ID0", "ID1") },
)
