package com.oposicontrol.admin.domain.model

/**
 * La auditoría de recursos de un examen, calculada a partir de sus preguntas ya
 * cargadas. Función pura (K0-5).
 *
 * Vivía como función privada del repositorio y obligaba a pedir
 * `GET /examenes/{id}/preguntas` otra vez cada vez que se abría un examen, sólo
 * para contar recursos de una lista que el editor ya tenía en memoria (EX-13). Aquí
 * la pueden usar el ViewModel —sin petición— y el repositorio —para el camino móvil,
 * que sí la pide al servidor—, con una única definición de «recurso pendiente».
 *
 * Un recurso está PENDIENTE si no es de texto y no tiene todavía una URL que abrir.
 * Los recursos «sin vincular» no se pueden calcular desde las preguntas: salen vacíos.
 */
fun List<ExamQuestion>.toAudit(): ExamResourceAudit {
    val questionAudits = map { question ->
        QuestionResourceAudit(
            questionId = question.id,
            questionTitle = question.title,
            resourceCount = question.resources.size,
            pendingResourceCount = question.resources.count { it.type != QuestionResourceType.TEXT && it.publicUrl.isNullOrBlank() },
            hasLinkedResources = question.resources.isNotEmpty(),
        )
    }
    return ExamResourceAudit(
        summary = ExamResourceSummary(
            totalQuestions = size,
            questionsWithResources = questionAudits.count { it.hasLinkedResources },
            questionsWithoutResources = questionAudits.count { !it.hasLinkedResources },
            linkedResources = sumOf { it.resources.size },
            pendingResources = questionAudits.sumOf { it.pendingResourceCount },
            unlinkedResources = 0,
        ),
        questions = questionAudits,
        unlinkedResources = emptyList(),
    )
}
