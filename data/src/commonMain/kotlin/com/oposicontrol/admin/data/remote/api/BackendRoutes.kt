package com.oposicontrol.admin.data.remote.api

object BackendRoutes {
    const val HEALTH = "/health"
    const val V1_ESPECIALIDADES = "/v1/especialidades"
    const val V1_EXAMENES = "/v1/examenes"
    const val ADMIN_V1_ESPECIALIDADES = "/api/v1/admin/especialidades"
    const val ADMIN_V1_EXAMENES = "/api/v1/admin/examenes"
    const val ADMIN_V1_PREGUNTAS = "/api/v1/admin/preguntas"
    const val ADMIN_V1_IMPORTACIONES_PREGUNTAS = "/api/v1/admin/importaciones/preguntas"
    const val ADMIN_V1_IMPORTACIONES_PREGUNTAS_PDF = "/api/v1/admin/importaciones/preguntas/pdf"
    const val ADMIN_V1_FEEDBACK_PREGUNTAS = "/api/v1/admin/feedback/preguntas"
    const val ADMIN_V1_AUDITORIA = "/api/v1/admin/auditoria"
    const val ADMIN_V1_USUARIOS = "/api/v1/admin/usuarios"
    const val ADMIN_V1_ROLES = "/api/v1/admin/roles"
    const val ADMIN_V1_ETIQUETAS = "/api/v1/admin/etiquetas"
    const val ADMIN_V1_COMUNIDADES = "/api/v1/admin/comunidades-autonomas"
    const val ADMIN_V1_DASHBOARD = "/api/v1/admin/dashboard"

    /**
     * Contrato agregado del Dashboard V2. Una sola petición por refresco, frente
     * a las cinco de [ADMIN_V1_DASHBOARD] más una por examen que hacía el cliente.
     */
    const val ADMIN_V1_DASHBOARD_OVERVIEW = "$ADMIN_V1_DASHBOARD/overview"
    const val ADMIN_V1_ESTADO_SISTEMA = "/api/v1/admin/estado-sistema"
    const val ADMIN_V1_SESION = "/api/v1/admin/sesion"
    const val ADMIN_V1_REPORTES = "/api/v1/admin/reportes"
    const val ADMIN_V1_TIENDA_ARTICULOS = "/api/v1/admin/tienda/articulos"
    const val ADMIN_V1_TIENDA_OFERTAS = "/api/v1/admin/tienda/ofertas"
    const val ADMIN_V1_TIENDA_RECURSOS_TEMPORALES = "/api/v1/admin/tienda/recursos-temporales"
    const val ADMIN_V1_TIENDA_RAFAGAS = "/api/v1/admin/tienda/rafagas"
    const val ADMIN_V1_FORO_HILOS = "/api/v1/admin/moderacion/hilos"
    fun adminV1ForoHilo(hiloId: String) = "$ADMIN_V1_FORO_HILOS/$hiloId"
    fun adminV1ForoHiloRespuestas(hiloId: String) = "$ADMIN_V1_FORO_HILOS/$hiloId/respuestas"

    fun adminV1Usuario(usuarioId: String) = "$ADMIN_V1_USUARIOS/$usuarioId"
    fun adminV1UsuarioTickets(usuarioId: String) = "$ADMIN_V1_USUARIOS/$usuarioId/tickets"
    fun adminV1UsuarioEstadoCuenta(usuarioId: String) = "$ADMIN_V1_USUARIOS/$usuarioId/estado-cuenta"
    fun adminV1Reporte(reporteId: String) = "$ADMIN_V1_REPORTES/$reporteId"
    fun adminV1ReporteResolver(reporteId: String) = "$ADMIN_V1_REPORTES/$reporteId/resolver"
    fun adminV1FeedbackPregunta(feedbackId: String) = "$ADMIN_V1_FEEDBACK_PREGUNTAS/$feedbackId"
    fun adminV1FeedbackPreguntaResolver(feedbackId: String) = "$ADMIN_V1_FEEDBACK_PREGUNTAS/$feedbackId/resolver"
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

    /**
     * Desvincular una pregunta de UN examen. No confundir con
     * [adminV1Pregunta], cuyo DELETE archiva la pregunta en todos los exámenes.
     */
    fun adminV1ExamenPregunta(examenId: String, preguntaId: String): String =
        "$ADMIN_V1_EXAMENES/$examenId/preguntas/$preguntaId"

    fun adminV1Pregunta(preguntaId: String): String = "$ADMIN_V1_PREGUNTAS/$preguntaId"

    fun adminV1PreguntaRecursos(preguntaId: String): String = "$ADMIN_V1_PREGUNTAS/$preguntaId/recursos"

    fun adminV1PreguntaRecurso(preguntaId: String, recursoId: String): String =
        "$ADMIN_V1_PREGUNTAS/$preguntaId/recursos/$recursoId"

    /** La ÚNICA puerta para ver y crear vínculos pregunta ↔ examen (K3-4, KQ3-1, D-03). */
    fun adminV1PreguntaExamenes(preguntaId: String): String = "$ADMIN_V1_PREGUNTAS/$preguntaId/examenes"

    fun adminV1PreguntaExamen(preguntaId: String, examenId: String): String =
        "$ADMIN_V1_PREGUNTAS/$preguntaId/examenes/$examenId"

    fun adminV1ExamenPublicar(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId/publicar"

    fun adminV1ExamenDespublicar(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId/despublicar"

    /**
     * ARCHIVAR (`activo=false`). Ruta propia desde que
     * `DELETE $ADMIN_V1_EXAMENES/{id}` borra la fila de verdad: antes archivar era
     * lo que hacía ese DELETE, y quien quisiera archivar y quien quisiera borrar
     * llamaban a lo mismo.
     */
    fun adminV1ExamenArchivar(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId/archivar"

    fun adminV1ExamenPortada(examenId: String): String = "$ADMIN_V1_EXAMENES/$examenId/portada"

    fun adminV1Importacion(batchId: String): String = "$ADMIN_V1_IMPORTACIONES_PREGUNTAS/$batchId"

    fun adminV1ImportacionStaging(itemId: String): String = "/api/v1/admin/importaciones/staging/$itemId"

    fun adminV1ImportacionPublish(batchId: String): String = "/api/v1/admin/importaciones/$batchId/importar"
}
