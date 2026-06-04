package com.oposicontrol.admin.data.remote.api

object BackendRoutes {
    const val HEALTH = "/health"
    const val V1_ESPECIALIDADES = "/v1/especialidades"
    const val V1_EXAMENES = "/v1/examenes"

    fun url(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path

    fun v1ExamenPreguntas(examenId: String): String = "/v1/examenes/$examenId/preguntas"
}
