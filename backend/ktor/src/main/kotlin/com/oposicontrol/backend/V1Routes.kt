package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID

fun Route.v1Routes(gateway: SupabaseV1Gateway) {
    route("/v1") {
        get("/especialidades") {
            call.respond(V1EspecialidadesResponse(data = gateway.listEspecialidades()))
        }
        get("/examenes") {
            call.respond(V1ExamenesResponse(data = gateway.listExamenes(call.toV1ExamenesFilter())))
        }
        get("/examenes/{examenId}") {
            val examenId = call.requiredPath("examenId").validatedUuid("examenId")
            val examen = gateway.getExamen(examenId)
            if (examen == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorDto(error = "not_found", message = "Examen no encontrado."),
                )
            } else {
                call.respond(V1ExamenResponse(data = examen))
            }
        }
        get("/examenes/{examenId}/preguntas") {
            val examenId = call.requiredPath("examenId").validatedUuid("examenId")
            when (val result = gateway.getPreguntasExamenRafaga(examenId)) {
                V1PreguntasExamenResult.Forbidden -> call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorDto(error = "forbidden", message = "Examen no disponible."),
                )
                V1PreguntasExamenResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    ErrorDto(error = "not_found", message = "Examen no encontrado."),
                )
                is V1PreguntasExamenResult.Success -> call.respond(V1PreguntasExamenResponse(data = result.data))
            }
        }
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

@Serializable
data class V1PreguntasExamenResponse(
    val data: V1PreguntasExamenDto,
)

private fun io.ktor.server.application.ApplicationCall.toV1ExamenesFilter(): V1ExamenesFilter {
    val query = request.queryParameters
    return V1ExamenesFilter(
        especialidadId = query["especialidadId"]?.validatedUuid("especialidadId"),
        comunidadId = query["comunidadId"]?.validatedUuid("comunidadId"),
        tipoExamen = query["tipoExamen"]?.validatedValue("tipoExamen", setOf("rafaga", "inmersivo")),
        tipoAcceso = query["tipoAcceso"]?.validatedValue("tipoAcceso", setOf("gratuito", "premium")),
    )
}

internal fun String.validatedUuid(parameterName: String): String {
    return try {
        UUID.fromString(this).toString()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$parameterName debe ser un UUID valido.")
    }
}

private fun String.validatedValue(parameterName: String, allowed: Set<String>): String {
    val normalized = trim().lowercase()
    require(normalized in allowed) { "$parameterName debe ser uno de: ${allowed.joinToString(", ")}." }
    return normalized
}
