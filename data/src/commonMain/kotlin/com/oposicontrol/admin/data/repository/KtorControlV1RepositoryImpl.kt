package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.ConfigEntry
import com.oposicontrol.admin.domain.model.ExamEconomy
import com.oposicontrol.admin.domain.model.ExamEconomyDraft
import com.oposicontrol.admin.domain.model.ExamEconomyPage
import com.oposicontrol.admin.domain.model.FastWindow
import com.oposicontrol.admin.domain.model.FastWindowPage
import com.oposicontrol.admin.domain.model.ForumAction
import com.oposicontrol.admin.domain.model.ForumChannel
import com.oposicontrol.admin.domain.model.ForumChannelDraft
import com.oposicontrol.admin.domain.model.ForumContentType
import com.oposicontrol.admin.domain.model.ForumCounterDrift
import com.oposicontrol.admin.domain.model.ForumImagePurge
import com.oposicontrol.admin.domain.model.ForumQueueItem
import com.oposicontrol.admin.domain.model.ForumQueuePage
import com.oposicontrol.admin.domain.model.ForumRestriction
import com.oposicontrol.admin.domain.model.GradingAuditEntry
import com.oposicontrol.admin.domain.model.GradingCheck
import com.oposicontrol.admin.domain.model.GradingRules
import com.oposicontrol.admin.domain.model.GradingSnapshot
import com.oposicontrol.admin.domain.model.OrderDecision
import com.oposicontrol.admin.domain.model.OrderItem
import com.oposicontrol.admin.domain.model.OrderResolution
import com.oposicontrol.admin.domain.model.OfficialQuestion
import com.oposicontrol.admin.domain.model.OfficialQuestionDraft
import com.oposicontrol.admin.domain.model.OfficialQuestions
import com.oposicontrol.admin.domain.model.OrderPage
import com.oposicontrol.admin.domain.model.RefundCase
import com.oposicontrol.admin.domain.model.ReferralItem
import com.oposicontrol.admin.domain.model.ReferralPage
import com.oposicontrol.admin.domain.model.ReferralRevokeResult
import com.oposicontrol.admin.domain.repository.ControlV1Repository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * Control V1 a través del límite canónico de Ktor. Ninguna validación de este cliente es la guarda: son las del
 * formulario, y el servidor repite todas.
 *
 * Los textos que vienen de usuarios (foro, handles, motivos) se copian TAL CUAL al modelo de dominio: el panel los pinta
 * como texto plano y no interpreta marcado.
 */
class KtorControlV1RepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : ControlV1Repository {

    // ---------------------------------------------------------------- reglas de puntuación

    override suspend fun getGradingRules(examId: String): AppResult<GradingRules> = adapter.safeCall {
        client.get(url("/examenes/$examId/reglas-puntuacion")) { auth() }
            .checkedBody<GradingRulesDto>("/api/v1/admin/examenes/$examId/reglas-puntuacion").toDomain()
    }

    override suspend fun saveGradingRules(
        examId: String,
        annulledRule: String?,
        rationalPenalty: Boolean,
        numerator: Int,
        denominator: Int,
        reason: String?,
    ): AppResult<GradingRules> = adapter.safeCall {
        client.put(url("/examenes/$examId/reglas-puntuacion")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(GradingRulesPutDto(annulledRule, rationalPenalty, numerator, denominator, reason?.trim()?.ifEmpty { null }))
        }.checkedBody<GradingRulesDto>("/api/v1/admin/examenes/$examId/reglas-puntuacion").toDomain()
    }

    override suspend fun getGradingAudit(examId: String): AppResult<List<GradingAuditEntry>> = adapter.safeCall {
        client.get(url("/examenes/$examId/reglas-puntuacion/auditoria")) { auth() }
            .checkedBody<GradingAuditResponseDto>("/api/v1/admin/examenes/$examId/reglas-puntuacion/auditoria")
            .data.map { it.toDomain() }
    }

    override suspend fun officialQuestions(examId: String): AppResult<OfficialQuestions> = adapter.safeCall {
        client.get(url("/examenes/$examId/preguntas-oficiales")) { auth() }
            .checkedBody<OfficialQuestionsDto>("/api/v1/admin/examenes/$examId/preguntas-oficiales").toDomain()
    }

    override suspend fun saveOfficialQuestion(examId: String, questionId: String, draft: OfficialQuestionDraft): AppResult<OfficialQuestions> =
        adapter.safeCall {
            client.put(url("/examenes/$examId/preguntas-oficiales/$questionId")) {
                auth()
                contentType(ContentType.Application.Json)
                setBody(
                    OfficialQuestionPutDto(
                        anulada = draft.annulled,
                        motivoAnulacion = draft.reason.trim().takeIf { draft.annulled && it.isNotEmpty() },
                        esReserva = draft.isReserve,
                        ordenReserva = draft.order.trim().toIntOrNull()?.takeIf { draft.isReserve },
                    ),
                )
            }.checkedBody<OfficialQuestionsDto>("/api/v1/admin/examenes/$examId/preguntas-oficiales/$questionId").toDomain()
        }

    // ---------------------------------------------------------------- configuración

    override suspend fun getConfig(group: String): AppResult<List<ConfigEntry>> = adapter.safeCall {
        client.get(url("/config-v1")) {
            auth()
            parameter("grupo", group)
        }.checkedBody<ConfigResponseDto>("/api/v1/admin/config-v1").data.map { it.toDomain() }
    }

    override suspend fun setConfig(key: String, value: Int): AppResult<ConfigEntry> = adapter.safeCall {
        client.put(url("/config-v1/${key.encodeURLPathPart()}")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ConfigPutDto(value))
        }.checkedBody<ConfigEntryDto>("/api/v1/admin/config-v1/$key").toDomain()
    }

    // ---------------------------------------------------------------- invitaciones

    override suspend fun listReferrals(state: String?, cursor: String?): AppResult<ReferralPage> = adapter.safeCall {
        client.get(url("/referidos/invitaciones")) {
            auth()
            if (state != null) parameter("estado", state)
            if (cursor != null) parameter("cursor", cursor)
        }.checkedBody<ReferralPageDto>("/api/v1/admin/referidos/invitaciones").toDomain()
    }

    override suspend fun revokeReferral(id: String, reason: String): AppResult<ReferralRevokeResult> = adapter.safeCall {
        client.post(url("/referidos/invitaciones/$id/revocar")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ReasonBodyDto(reason.trim()))
        }.checkedBody<ReferralRevokeDto>("/api/v1/admin/referidos/invitaciones/$id/revocar")
            .let { ReferralRevokeResult(it.revertido, it.noRevertible) }
    }

    // ---------------------------------------------------------------- foro

    override suspend fun forumQueue(type: ForumContentType, state: String, cursor: String?): AppResult<ForumQueuePage> =
        adapter.safeCall {
            client.get(url("/foro/cola")) {
                auth()
                parameter("tipo", type.wire)
                parameter("estado", state)
                if (cursor != null) parameter("cursor", cursor)
            }.checkedBody<ForumQueueDto>("/api/v1/admin/foro/cola").toDomain()
        }

    override suspend fun moderateForum(
        type: ForumContentType,
        id: String,
        action: ForumAction,
        reason: String?,
        days: Int?,
    ): AppResult<Unit> = adapter.safeCall {
        client.post(url("/foro/moderar")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ForumModerateDto(type.wire, id, action.wire, days, reason?.trim()?.ifEmpty { null }))
        }.requireSuccess("/api/v1/admin/foro/moderar")
    }

    override suspend fun forumChannels(): AppResult<List<ForumChannel>> = adapter.safeCall {
        client.get(url("/foro/canales")) { auth() }
            .checkedBody<ForumChannelsDto>("/api/v1/admin/foro/canales").data.map { it.toDomain() }
    }

    override suspend fun createForumChannel(draft: ForumChannelDraft): AppResult<ForumChannel> = adapter.safeCall {
        client.post(url("/foro/canales")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(draft.toDto())
        }.checkedBody<ForumChannelDto>("/api/v1/admin/foro/canales").toDomain()
    }

    override suspend fun updateForumChannel(id: String, draft: ForumChannelDraft): AppResult<ForumChannel> = adapter.safeCall {
        client.patch(url("/foro/canales/$id")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(draft.toDto())
        }.checkedBody<ForumChannelDto>("/api/v1/admin/foro/canales/$id").toDomain()
    }

    override suspend fun forumBlockList(): AppResult<List<String>> = adapter.safeCall {
        client.get(url("/foro/bloqueos")) { auth() }
            .checkedBody<ForumBlocksDto>("/api/v1/admin/foro/bloqueos").data.map { it.patron }
    }

    override suspend fun addForumBlock(pattern: String): AppResult<Unit> = adapter.safeCall {
        client.post(url("/foro/bloqueos")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ForumBlockBodyDto(pattern))
        }.requireSuccess("/api/v1/admin/foro/bloqueos")
    }

    override suspend fun removeForumBlock(pattern: String): AppResult<Unit> = adapter.safeCall {
        client.delete(url("/foro/bloqueos/${pattern.encodeURLPathPart()}")) { auth() }
            .requireSuccess("/api/v1/admin/foro/bloqueos/$pattern")
    }

    override suspend fun forumRestrictions(): AppResult<List<ForumRestriction>> = adapter.safeCall {
        client.get(url("/foro/restricciones")) { auth() }
            .checkedBody<ForumRestrictionsDto>("/api/v1/admin/foro/restricciones").data.map { it.toDomain() }
    }

    override suspend fun forumImagePurge(): AppResult<ForumImagePurge> = adapter.safeCall {
        client.get(url("/foro/imagenes-purga")) { auth() }
            .checkedBody<ImagePurgeDto>("/api/v1/admin/foro/imagenes-purga").let { ForumImagePurge(it.pendientes, it.atascadas, it.masAntiguaEn) }
    }

    override suspend fun liftForumRestriction(userId: String): AppResult<Unit> = adapter.safeCall {
        client.delete(url("/foro/restricciones/$userId")) { auth() }.requireSuccess("/api/v1/admin/foro/restricciones/$userId")
    }

    // ---------------------------------------------------------------- economía por examen

    override suspend fun examEconomy(offset: Int, onlyWithoutCommunity: Boolean): AppResult<ExamEconomyPage> = adapter.safeCall {
        client.get(url("/examenes-economia")) {
            auth()
            parameter("limit", ECONOMY_PAGE)
            parameter("offset", offset)
            if (onlyWithoutCommunity) parameter("sinComunidad", true)
        }.checkedBody<ExamEconomyPageDto>("/api/v1/admin/examenes-economia").toDomain()
    }

    override suspend fun saveExamEconomy(examId: String, draft: ExamEconomyDraft): AppResult<ExamEconomy> = adapter.safeCall {
        client.patch(url("/examenes-economia/$examId")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(
                ExamEconomyPatchDto(
                    comprableConTickets = draft.sellable,
                    precioTickets = draft.price.trim().toIntOrNull(),
                    diasAccesoCompra = draft.days.trim().toInt(),
                    comunidadId = draft.communityId,
                ),
            )
        }.checkedBody<ExamEconomyDto>("/api/v1/admin/examenes-economia/$examId").toDomain()
    }

    // ---------------------------------------------------------------- pedidos

    override suspend fun orders(state: String?, offset: Int): AppResult<OrderPage> = adapter.safeCall {
        client.get(url("/pedidos")) {
            auth()
            if (state != null) parameter("estado", state)
            parameter("limit", ORDERS_PAGE)
            parameter("offset", offset)
        }.checkedBody<OrderPageDto>("/api/v1/admin/pedidos").toDomain()
    }

    override suspend fun ordersInReviewCount(): AppResult<Int> = adapter.safeCall {
        client.get(url("/pedidos/en-revision/total")) { auth() }
            .checkedBody<CountDto>("/api/v1/admin/pedidos/en-revision/total").total
    }

    override suspend fun order(id: String): AppResult<OrderItem> = adapter.safeCall {
        client.get(url("/pedidos/$id")) { auth() }.checkedBody<OrderDto>("/api/v1/admin/pedidos/$id").toDomain()
    }

    override suspend fun addOrderNote(id: String, note: String): AppResult<Unit> = adapter.safeCall {
        client.post(url("/pedidos/$id/nota")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(NoteBodyDto(note.trim()))
        }.requireSuccess("/api/v1/admin/pedidos/$id/nota")
    }

    override suspend fun resolveOrder(id: String, decision: OrderDecision, note: String): AppResult<OrderResolution> = adapter.safeCall {
        client.post(url("/pedidos/$id/resolver")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ResolveBodyDto(decision.wire, note.trim()))
        }.checkedBody<OrderResolutionDto>("/api/v1/admin/pedidos/$id/resolver").toDomain()
    }

    // ---------------------------------------------------------------- alertas

    override suspend fun fastWindows(offset: Int): AppResult<FastWindowPage> = adapter.safeCall {
        client.get(url("/alertas/ranking-ventanas-rapidas")) {
            auth()
            parameter("limit", ALERTS_PAGE)
            parameter("offset", offset)
        }.checkedBody<FastWindowPageDto>("/api/v1/admin/alertas/ranking-ventanas-rapidas").toDomain()
    }

    override suspend fun forumCounterDrift(): AppResult<List<ForumCounterDrift>> = adapter.safeCall {
        client.get(url("/alertas/foro-contadores")) { auth() }
            .checkedBody<ForumDriftDto>("/api/v1/admin/alertas/foro-contadores").data.map { ForumCounterDrift(it.id, it.creadoEn, it.filas) }
    }

    // ---------------------------------------------------------------- infraestructura

    private fun url(path: String): String = "${appConfig.baseUrl.trimEnd('/')}/api/v1/admin$path"

    private fun HttpRequestBuilder.auth() = adminAuthHeaders(appConfig, sessionLocalDataSource)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (status.isSuccess()) return
        // 501 = la función de servidor que necesita la operación aún no existe en el entorno conectado. Es un mensaje
        // para el operador (no un 5xx opaco): `IllegalArgumentException` llega a la pantalla como validación legible.
        if (status.value == 501) throw IllegalArgumentException(NOT_PROVISIONED_MESSAGE)
        error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
    }

    private suspend inline fun <reified T> HttpResponse.checkedBody(endpoint: String): T {
        requireSuccess(endpoint)
        return body()
    }

    companion object {
        /** Lo que se le dice al operador cuando el servidor contesta 501 `CONTROL_RPC_NOT_PROVISIONED`. */
        const val NOT_PROVISIONED_MESSAGE =
            "Esta función todavía no está disponible en el servidor conectado. No se ha cambiado nada."

        private const val ECONOMY_PAGE = 25
        const val ORDERS_PAGE = 25
        const val ALERTS_PAGE = 25
    }
}

// =====================================================================================================================
// DTOs de transporte (espejo de los de backend/ktor/.../AdminControl*.kt). Privados: el dominio no los ve.
// =====================================================================================================================

@Serializable
private data class GradingRulesDto(
    val examenId: String,
    val reglaAnuladas: String? = null,
    val penalizacionRacional: Boolean = false,
    val penalizacionNum: Int = 0,
    val penalizacionDen: Int = 1,
    val esOficial: Boolean = false,
    val publicado: Boolean = false,
    val reservas: Int = 0,
    val anuladasPrincipales: Int = 0,
    val chequeo: GradingCheckDto? = null,
) {
    fun toDomain() = GradingRules(
        examId = examenId,
        annulledRule = reglaAnuladas,
        rationalPenalty = penalizacionRacional,
        fixedNumerator = penalizacionNum,
        fixedDenominator = penalizacionDen,
        isOfficial = esOficial,
        isPublished = publicado,
        reserves = reservas,
        annulledMain = anuladasPrincipales,
        check = chequeo?.let { GradingCheck(it.problema, it.avisos, it.regla) },
    )
}

@Serializable
private data class GradingCheckDto(val problema: String? = null, val avisos: List<String> = emptyList(), val regla: String? = null)

@Serializable
private data class GradingRulesPutDto(
    val reglaAnuladas: String?,
    val penalizacionRacional: Boolean,
    val penalizacionNum: Int,
    val penalizacionDen: Int,
    val motivo: String? = null,
)

@Serializable
private data class GradingSnapshotDto(
    val reglaAnuladas: String? = null,
    val penalizacionRacional: Boolean = false,
    val penalizacionNum: Int = 0,
    val penalizacionDen: Int = 1,
) {
    fun toDomain() = GradingSnapshot(reglaAnuladas, penalizacionRacional, penalizacionNum, penalizacionDen)
}

@Serializable
private data class GradingAuditEntryDto(
    val id: String,
    val creadoEn: String? = null,
    val actorId: String? = null,
    val motivo: String? = null,
    val antes: GradingSnapshotDto? = null,
    val despues: GradingSnapshotDto? = null,
) {
    fun toDomain() = GradingAuditEntry(id, creadoEn, actorId, motivo, antes?.toDomain(), despues?.toDomain())
}

@Serializable
private data class GradingAuditResponseDto(val data: List<GradingAuditEntryDto> = emptyList())

@Serializable
private data class ConfigEntryDto(
    val clave: String,
    val grupo: String,
    val etiqueta: String,
    val ayuda: String? = null,
    val min: Int,
    val max: Int,
    val valor: Int? = null,
    val actualizadoEn: String? = null,
) {
    fun toDomain() = ConfigEntry(clave, grupo, etiqueta, ayuda, min, max, valor, actualizadoEn)
}

@Serializable
private data class ConfigResponseDto(val data: List<ConfigEntryDto> = emptyList())

@Serializable
private data class ConfigPutDto(val valor: Int)

@Serializable
private data class ReasonBodyDto(val motivo: String)

@Serializable
private data class NoteBodyDto(val nota: String)

@Serializable
private data class ResolveBodyDto(val decision: String, val nota: String)

@Serializable
private data class OrderResolutionDto(val outcome: String = "", val estado: String = "", val casoReembolso: Boolean = false) {
    fun toDomain() = OrderResolution(outcome, estado, casoReembolso)
}

@Serializable
private data class ReferralItemDto(
    val id: String,
    val estado: String,
    val creadoEn: String? = null,
    val invitadorHandle: String? = null,
    val invitadoHandle: String? = null,
    val pagoInvitador: String? = null,
    val pagoInvitado: String? = null,
    val intentosPago: Int = 0,
    val ultimoError: String? = null,
    val diferidaHasta: String? = null,
    val premiadaEn: String? = null,
    val motivo: String? = null,
    val marcas: List<String> = emptyList(),
) {
    fun toDomain() = ReferralItem(
        id, estado, creadoEn, invitadorHandle, invitadoHandle, pagoInvitador, pagoInvitado, intentosPago,
        ultimoError, diferidaHasta, premiadaEn, motivo, marcas,
    )
}

@Serializable
private data class ReferralPageDto(
    val items: List<ReferralItemDto> = emptyList(),
    val nextCursor: String? = null,
    val resumen: Map<String, Int> = emptyMap(),
) {
    fun toDomain() = ReferralPage(items.map { it.toDomain() }, nextCursor, resumen)
}

@Serializable
private data class ReferralRevokeDto(val estado: String = "revocada", val revertido: Int = 0, val noRevertible: Int = 0)

@Serializable
private data class ForumQueueItemDto(
    val tipo: String,
    val id: String,
    val publicacionId: String? = null,
    val canal: String? = null,
    val titulo: String? = null,
    val fragmento: String = "",
    val estado: String = "",
    val autorHandle: String? = null,
    val creadoEn: String? = null,
    val tieneImagen: Boolean = false,
    val reportesPendientes: Int = 0,
    val motivosReporte: List<String> = emptyList(),
) {
    fun toDomain() = ForumQueueItem(
        type = ForumContentType.entries.firstOrNull { it.wire == tipo } ?: ForumContentType.POST,
        id = id,
        postId = publicacionId,
        channel = canal,
        title = titulo,
        excerpt = fragmento,
        state = estado,
        authorHandle = autorHandle,
        createdAt = creadoEn,
        hasImage = tieneImagen,
        pendingReports = reportesPendientes,
        reportReasons = motivosReporte,
    )
}

@Serializable
private data class ForumQueueDto(val items: List<ForumQueueItemDto> = emptyList(), val nextCursor: String? = null) {
    fun toDomain() = ForumQueuePage(items.map { it.toDomain() }, nextCursor)
}

@Serializable
private data class ForumModerateDto(val tipo: String, val id: String, val accion: String, val dias: Int? = null, val motivo: String? = null)

@Serializable
private data class ForumChannelDto(
    val id: String = "",
    val nombre: String = "",
    val slug: String = "",
    val descripcion: String? = null,
    val activa: Boolean = true,
    val posicion: Int = 1,
    val especialidadId: String? = null,
    val soloLectura: Boolean = false,
) {
    fun toDomain() = ForumChannel(id, nombre, slug, descripcion, activa, posicion, especialidadId, soloLectura)
}

@Serializable
private data class ForumChannelsDto(val data: List<ForumChannelDto> = emptyList())

/** Cuerpo de alta/edición de canal. En un PATCH viajan todas las claves del formulario (el servidor audita el cambio). */
@Serializable
private data class ForumChannelWriteDto(
    val nombre: String,
    val slug: String,
    val descripcion: String,
    val activa: Boolean,
    val posicion: Int,
    val especialidadId: String?,
    val soloLectura: Boolean,
)

private fun ForumChannelDraft.toDto() = ForumChannelWriteDto(
    nombre = name.trim(),
    slug = slug.trim(),
    descripcion = description.trim(),
    activa = active,
    posicion = position.trim().toInt(),
    especialidadId = specialtyId?.takeIf { it.isNotBlank() },
    soloLectura = readOnly,
)

@Serializable
private data class ForumBlockDto(val patron: String)

@Serializable
private data class ForumBlocksDto(val data: List<ForumBlockDto> = emptyList())

@Serializable
private data class ForumBlockBodyDto(val patron: String)

@Serializable
private data class ForumRestrictionDto(
    val usuarioId: String,
    val handle: String? = null,
    val hasta: String? = null,
    val motivo: String? = null,
    val creadoEn: String? = null,
) {
    fun toDomain() = ForumRestriction(usuarioId, handle, hasta, motivo, creadoEn)
}

@Serializable
private data class ForumRestrictionsDto(val data: List<ForumRestrictionDto> = emptyList())

@Serializable
private data class ExamEconomyDto(
    val id: String,
    val codigoExamen: String = "",
    val titulo: String = "",
    val tipoExamen: String = "",
    val esOficial: Boolean = false,
    val publicado: Boolean = false,
    val comunidadId: String? = null,
    val comunidadNombre: String? = null,
    val comprableConTickets: Boolean = false,
    val precioTickets: Int? = null,
    val diasAccesoCompra: Int = 30,
) {
    fun toDomain() = ExamEconomy(
        id, codigoExamen, titulo, tipoExamen, esOficial, publicado, comunidadId, comunidadNombre,
        comprableConTickets, precioTickets, diasAccesoCompra,
    )
}

@Serializable
private data class ExamEconomyPageDto(val data: List<ExamEconomyDto> = emptyList(), val total: Int = 0, val limit: Int = 0, val offset: Int = 0) {
    fun toDomain() = ExamEconomyPage(data.map { it.toDomain() }, total, limit, offset)
}

@Serializable
private data class ExamEconomyPatchDto(
    val comprableConTickets: Boolean,
    val precioTickets: Int?,
    val diasAccesoCompra: Int,
    val comunidadId: String?,
)

@Serializable
private data class RefundCaseDto(
    val id: String = "",
    val estado: String = "",
    val otRevertidos: Int = 0,
    val otGastados: Int = 0,
    val politica: String? = null,
    val creadoEn: String? = null,
    val resueltoEn: String? = null,
    val detalle: String? = null,
) {
    fun toDomain() = RefundCase(id, estado, otRevertidos, otGastados, politica, creadoEn, resueltoEn, detalle)
}

@Serializable
private data class OrderDto(
    val id: String,
    val estado: String = "",
    val proveedor: String = "",
    val productoCodigo: String = "",
    val tipoProducto: String = "",
    val cantidadTickets: Int? = null,
    val precioCentimos: Int? = null,
    val moneda: String = "",
    val identificadorTransaccion: String? = null,
    val usuarioId: String? = null,
    val usuarioHandle: String? = null,
    val creadoEn: String? = null,
    val pagadaEn: String? = null,
    val expiraEn: String? = null,
    val reembolso: RefundCaseDto? = null,
) {
    fun toDomain() = OrderItem(
        id, estado, proveedor, productoCodigo, tipoProducto, cantidadTickets, precioCentimos, moneda,
        identificadorTransaccion, usuarioId, usuarioHandle, creadoEn, pagadaEn, expiraEn, reembolso?.toDomain(),
    )
}

@Serializable
private data class OrderPageDto(
    val data: List<OrderDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val reembolsosHabilitados: Boolean = false,
) {
    fun toDomain() = OrderPage(data.map { it.toDomain() }, total, limit, offset, reembolsosHabilitados)
}

@Serializable
private data class ImagePurgeDto(val pendientes: Int = 0, val atascadas: Int = 0, val masAntiguaEn: String? = null)

@Serializable
private data class CountDto(val total: Int = 0)

@Serializable
private data class FastWindowDto(
    val usuarioId: String,
    val handle: String? = null,
    val ligaNombre: String? = null,
    val ventanaCalculo: Int? = null,
    val medianaMs: Long? = null,
    val actualizadoEn: String? = null,
)

@Serializable
private data class FastWindowPageDto(
    val data: List<FastWindowDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val umbralMs: Int? = null,
) {
    fun toDomain() = FastWindowPage(
        data.map { FastWindow(it.usuarioId, it.handle, it.ligaNombre, it.ventanaCalculo, it.medianaMs, it.actualizadoEn) },
        total, limit, offset, umbralMs,
    )
}

@Serializable
private data class ForumDriftItemDto(val id: String, val creadoEn: String? = null, val filas: Int? = null)

@Serializable
private data class ForumDriftDto(val data: List<ForumDriftItemDto> = emptyList())

@Serializable
private data class OfficialQuestionDto(
    val preguntaId: String,
    val posicion: Int = 0,
    val titulo: String = "",
    val anulada: Boolean = false,
    val motivoAnulacion: String? = null,
    val esReserva: Boolean = false,
    val ordenReserva: Int? = null,
    val esDemo: Boolean = false,
) {
    fun toDomain() = OfficialQuestion(preguntaId, posicion, titulo, anulada, motivoAnulacion, esReserva, ordenReserva, esDemo)
}

@Serializable
private data class OfficialQuestionsDto(
    val examenId: String,
    val preguntas: List<OfficialQuestionDto> = emptyList(),
    val reservasDisponibles: Boolean = false,
    val chequeo: GradingCheckDto? = null,
    val problema: String? = null,
    val avisos: List<String> = emptyList(),
) {
    fun toDomain() = OfficialQuestions(
        examenId, preguntas.map { it.toDomain() }, reservasDisponibles, chequeo?.let { GradingCheck(it.problema, it.avisos, it.regla) },
        problem = problema, warnings = avisos,
    )
}

/** Cuerpo de `PUT .../preguntas-oficiales/{id}`: sin valores por defecto, así que `anulada` y `esReserva` viajan SIEMPRE. */
@Serializable
private data class OfficialQuestionPutDto(
    val anulada: Boolean,
    val motivoAnulacion: String?,
    val esReserva: Boolean,
    val ordenReserva: Int?,
)
