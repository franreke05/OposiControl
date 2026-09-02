package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlinx.coroutines.flow.Flow

interface ContentRepository {
    fun observeSpecialties(): Flow<List<Specialty>>
    fun observeExams(): Flow<List<Exam>>
    fun observeRafagas(): Flow<List<Rafaga>>

    suspend fun refreshSpecialties(): AppResult<List<Specialty>>
    suspend fun refreshExams(): AppResult<List<Exam>>
    suspend fun refreshRafagas(): AppResult<List<Rafaga>>
    suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>>
    suspend fun getRafagaQuestions(rafagaId: String): AppResult<List<RafagaQuestion>>
    suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit>

    suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty>
    suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit>

    /** Modos disponibles por especialidad. Ver [SpecialtyCapabilities]. */
    suspend fun getSpecialtyCapabilities(specialtyId: String): AppResult<SpecialtyCapabilities>
    suspend fun saveSpecialtyCapabilities(capabilities: SpecialtyCapabilities): AppResult<SpecialtyCapabilities>

    suspend fun upsertExam(exam: Exam): AppResult<Exam>
    suspend fun deleteExam(examId: String): AppResult<Unit>
    suspend fun upsertRafaga(rafaga: Rafaga): AppResult<Rafaga>
    suspend fun deleteRafaga(rafagaId: String): AppResult<Unit>

    suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion>
    suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>>
    suspend fun deleteQuestion(examId: String, questionId: String): AppResult<Unit>
    suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<ExamQuestion>>

    suspend fun upsertRafagaQuestion(question: RafagaQuestion): AppResult<RafagaQuestion>
    suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String): AppResult<Unit>
    suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<RafagaQuestion>>

    suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ): AppResult<QuestionResource>
    suspend fun deleteResource(questionId: String, resourceId: String): AppResult<Unit>

    suspend fun uploadExamCover(
        examId: String,
        localPathOrUri: String,
    ): AppResult<Exam>
    suspend fun clearExamCover(examId: String): AppResult<Exam>
}
