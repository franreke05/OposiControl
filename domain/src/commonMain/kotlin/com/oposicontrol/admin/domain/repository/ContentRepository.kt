package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Specialty
import kotlinx.coroutines.flow.Flow

interface ContentRepository {
    fun observeSpecialties(): Flow<List<Specialty>>
    fun observeExams(): Flow<List<Exam>>

    suspend fun refreshSpecialties(): AppResult<List<Specialty>>
    suspend fun refreshExams(): AppResult<List<Exam>>
    suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>>
    suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit>

    suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty>
    suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit>

    suspend fun upsertExam(exam: Exam): AppResult<Exam>
    suspend fun deleteExam(examId: String): AppResult<Unit>

    suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion>
    suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>>
    suspend fun deleteQuestion(examId: String, questionId: String): AppResult<Unit>
    suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<ExamQuestion>>

    suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ): AppResult<QuestionResource>
}
