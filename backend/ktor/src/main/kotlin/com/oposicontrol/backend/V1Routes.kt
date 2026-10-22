package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID

fun Route.v1Routes(gateway: SupabaseV1Gateway) = v1Routes(gateway, PublicReadCache())

/** [cache]: estas rutas no piden sesion; sin ella, repetirlas en bucle gastaba lecturas (y trafico facturable) sin limite. */
internal fun Route.v1Routes(gateway: SupabaseV1Gateway, cache: PublicReadCache) {
    route("/v1") {
        get("/especialidades") {
            call.respond(V1EspecialidadesResponse(data = cache.get("especialidades") { gateway.listEspecialidades() }))
        }
        get("/examenes") {
            call.respond(V1ExamenesResponse(data = call.toV1ExamenesFilter().let { filter -> cache.get(filter) { gateway.listExamenes(filter) } }))
        }
        get("/examenes/{examenId}") {
            val examenId = call.requiredPath("examenId").validatedUuid("examenId")
            val examen = cache.get("examen:" + examenId) { gateway.getExamen(examenId) }
            if (examen == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorDto(error = "not_found", message = "Examen no encontrado."),
                )
            } else {
                call.respond(V1ExamenResponse(data = examen))
            }
        }
        // EP-128 RETIRADA (CERT-EXAMS-2026-10-21 B-07, ver [LegacyEndpoints.EP_128]): 410 sin leer nada ni tocar upstream, el mismo
        // cuerpo para cualquier id (no confirma si un examen existe). Las preguntas se leen solo dentro de un intento (D2-08), con
        // sesion, acceso, limite diario y sin reservas no usadas.
        get("/examenes/{examenId}/preguntas") { rejectRetiredPublicExamQuestions() }.legacyRoute(LegacyEndpoints.EP_128)
    }
}

@Serializable
data class V1EspecialidadesResponse(
    val data: List<V1EspecialidadDto>,
)

@Serializable
data class V1ExamenesResponse(
    val data: List<V1ExamenDto>,
)

@Serializable
data class V1ExamenResponse(
    val data: V1ExamenDto,
)

internal const val PUBLIC_EXAM_QUESTIONS_RETIRED = "PUBLIC_EXAM_QUESTIONS_RETIRED"

internal fun rejectRetiredPublicExamQuestions(): Nothing {
    throw BackendDomainException(
        PUBLIC_EXAM_QUESTIONS_RETIRED,
        HttpStatusCode.Gone,
        "Esta ruta publica de preguntas esta retirada. Las preguntas de un examen se leen dentro de un intento: " +
            "POST /api/v1/app/examenes/{examId}/intentos y GET /api/v1/app/intentos/{attemptId}/preguntas.",
    )
}

private fun io.ktor.server.application.ApplicationCall.toV1ExamenesFilter(): V1ExamenesFilter {
    val query = request.queryParameters
    return V1ExamenesFilter(
        especialidadId = query["especialidadId"]?.validatedUuid("especialidadId"),
        comunidadId = query["comunidadId"]?.validatedUuid("comunidadId"),
        // K0-7 (EX-16): los tres tipos canónicos. Sin `razonado`, filtrar por él
        // respondía 400 aunque el catálogo público sirve exámenes razonados.
        tipoExamen = query["tipoExamen"]?.validatedValue("tipoExamen", setOf("rafaga", "razonado", "inmersivo")),
        tipoAcceso = query["tipoAcceso"]?.validatedValue("tipoAcceso", setOf("gratuito", "premium")),
    )
}

internal fun String.validatedUuid(parameterName: String): String {
    // MASTER-API-012: forma canonica 8-4-4-4-12 estricta (UUID.fromString acepta formas cortas como "1-1-1-1-1") y se
    // devuelve el valor normalizado en minusculas.
    require(isUuid()) { "$parameterName debe ser un UUID valido." }
    return lowercase()
}

private fun String.validatedValue(parameterName: String, allowed: Set<String>): String {
    val normalized = trim().lowercase()
    require(normalized in allowed) { "$parameterName debe ser uno de: ${allowed.joinToString(", ")}." }
    return normalized
}
