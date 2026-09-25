package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import kotlinx.datetime.Instant

internal val EPOCH: Instant = Instant.fromEpochSeconds(1_700_000_000)

internal fun exam(
    id: String,
    title: String = "Examen $id",
    specialty: String = "TCAE",
    type: String = "inmersivo",
    status: ExamStatus = ExamStatus.PUBLISHED,
    access: String = "premium",
    tickets: Int = 0,
    sortOrder: Int = 0,
    questions: Int = 100,
    duration: Int? = null,
    year: Int? = null,
    official: Boolean = false,
    code: String = "",
    deleted: Boolean = false,
) = Exam(
    id = id,
    code = code,
    specialtyId = "esp-$specialty",
    specialtyName = specialty,
    title = title,
    description = "",
    typeExam = type,
    typeAccess = access,
    priceTickets = tickets,
    status = status,
    publishFrom = null,
    publishedAt = null,
    sortOrder = sortOrder,
    questionCount = questions,
    durationMinutes = duration,
    year = year,
    isOfficial = official,
    createdAt = EPOCH,
    updatedAt = EPOCH,
    deletedAt = if (deleted) EPOCH else null,
)
