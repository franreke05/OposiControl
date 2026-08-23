package com.oposicontrol.admin.data.remote.api

object BackendRoutes {
    const val HEALTH = "/health"
    const val V1_ESPECIALIDADES = "/v1/especialidades"
    const val V1_EXAMENES = "/v1/examenes"
    const val ADMIN_V1_ESPECIALIDADES = "/api/v1/admin/especialidades"
    const val ADMIN_V1_EXAMENES = "/api/v1/admin/examenes"
    const val ADMIN_V1_PREGUNTAS = "/api/v1/admin/preguntas"
    const val ADMIN_V1_IMPORTACIONES_PREGUNTAS = "/api/v1/admin/importaciones/preguntas"
    const val ADMIN_V1_FEEDBACK_PREGUNTAS = "/api/v1/admin/feedback/preguntas"
    const val ADMIN_V1_AUDITORIA = "/api/v1/admin/auditoria"
    const val ADMIN_V1_USUARIOS = "/api/v1/admin/usuarios"
    const val ADMIN_V1_ROLES = "/api/v1/admin/roles"
    const val ADMIN_V1_ETIQUETAS = "/api/v1/admin/etiquetas"
    const val ADMIN_V1_COMUNIDADES = "/api/v1/admin/comunidades-autonomas"
    const val ADMIN_V1_DASHBOARD = "/api/v1/admin/dashboard"
    const val ADMIN_V1_REPORTES = "/api/v1/admin/reportes"
    const val ADMIN_V1_FORO_HILOS = "/api/v1/admin/moderacion/hilos"
    fun adminV1ForoHilo(hiloId: String) = "$ADMIN_V1_FORO_HILOS/$hiloId"
    fun adminV1ForoHiloRespuestas(hiloId: String) = "$ADMIN_V1_FORO_HILOS/$hiloId/respuestas"

    fun adminV1Usuario(usuarioId: String) = "$ADMIN_V1_USUARIOS/$usuarioId"
    fun adminV1UsuarioTickets(usuarioId: String) = "$ADMIN_V1_USUARIOS/$usuarioId/tickets"
    fun adminV1Reporte(reporteId: String) = "$ADMIN_V1_REPORTES/$reporteId"
    fun adminV1ReporteResolver(reporteId: String) = "$ADMIN_V1_REPORTES/$reporteId/resolver"
    fun adminV1ModerationActions() = "/api/v1/admin/moderacion/actions"

    // App V1 (bearer JWT Supabase del usuario)
    const val APP_V1_HOME = "/api/v1/app/home"
    const val APP_V1_EXAMENES = "/api/v1/app/examenes"
    const val APP_V1_ESTADISTICAS = "/api/v1/app/estadisticas"
    const val APP_V1_RETO_DIARIO = "/api/v1/app/reto-diario"
    fun appV1Examen(id: String) = "/api/v1/app/examenes/$id"
    fun appV1IniciarExamen(id: String) = "/api/v1/app/examenes/$id/iniciar"
    fun appV1ResponderIntento(id: String) = "/api/v1/app/intentos/$id/responder"
    fun appV1FinalizarIntento(id: String) = "/api/v1/app/intentos/$id/finalizar"

    fun url(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path

    fun v1ExamenPreguntas(examenId: String): String = "/v1/examenes/$examenId/preguntas"

    fun adminV1Examen(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId"

    fun adminV1ExamenPreguntas(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId/preguntas"

    fun adminV1ExamenPreguntasOrden(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId/preguntas/orden"

    fun adminV1Pregunta(preguntaId: String): String = "$ADMIN_V1_PREGUNTAS/$preguntaId"

    fun adminV1PreguntaRecursos(preguntaId: String): String = "$ADMIN_V1_PREGUNTAS/$preguntaId/recursos"

    fun adminV1PreguntaRecurso(preguntaId: String, recursoId: String): String =
        "$ADMIN_V1_PREGUNTAS/$preguntaId/recursos/$recursoId"

    fun adminV1ExamenPublicar(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId/publicar"

    fun adminV1ExamenDespublicar(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId/despublicar"

    fun adminV1ExamenPortada(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId/portada"
}
