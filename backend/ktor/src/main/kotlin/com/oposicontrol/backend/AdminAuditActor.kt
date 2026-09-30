package com.oposicontrol.backend

import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * MASTER-OBS-002: el ACTOR de las escrituras de auditoría administrativas.
 *
 * `AdminV1Gateway.registrarAuditoria` tiene ~40 llamadores repartidos por el gateway, ninguno de los cuales recibe
 * la llamada HTTP. En vez de reescribir todas las firmas, el `sub` del JWT viaja como elemento de contexto de la
 * corrutina de la petición: `withUpstreamIo` (un `withContext(Dispatchers.IO)`) lo hereda y lo reinstala en el hilo
 * de IO, igual que [RequestMdcContext] hace con el `request_id`. Fuera de una petición (o con el token estático, que
 * no es una persona) el actor es `null`: nunca se fabrica uno (HL-23).
 */
internal object AdminAuditActor {
    private val current = ThreadLocal<String?>()

    fun current(): String? = current.get()

    fun contextElement(actorId: String?): ThreadContextElement<String?> = current.asContextElement(actorId)
}

/**
 * Instala el actor de auditoría para todas las rutas hijas de [this] (en producción, el bloque
 * `authenticate("gateway-auth")`). Corre en la fase `Call`, ya con el principal resuelto y autorizado.
 */
internal fun Route.installAdminAuditActor() {
    val node = checkNotNull(this as? RoutingNode) { "installAdminAuditActor necesita un RoutingNode" }
    node.intercept(ApplicationCallPipeline.Call) {
        val actorId = actorIdFromPrincipalName(call.principal<UserIdPrincipal>()?.name)
        withContext(AdminAuditActor.contextElement(actorId)) { proceed() }
    }
}
