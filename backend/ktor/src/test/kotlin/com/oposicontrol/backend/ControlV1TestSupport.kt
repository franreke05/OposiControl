package com.oposicontrol.backend

import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.client.request.setBody
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Doble del upstream (Supabase) para las pruebas de Control V1: registra cada peticion (metodo, ruta+query, cuerpo) y
 * contesta lo que decida [handler]. Lo que se observa es lo que sale por el cable, que es parte del contrato.
 */
internal class ControlFake(
    private val handler: (method: String, uri: String, body: String) -> V1StubResponse = { _, _, _ -> V1StubResponse(200, "[]") },
) {
    data class Call(val method: String, val pathAndQuery: String, val body: String)

    val calls = CopyOnWriteArrayList<Call>()

    @Volatile
    private var last: HttpRequest? = null

    private val client = V1StubHttpClient(
        record = {
            last = it
            calls += Call(it.method(), it.uri().rawPath + "?" + (it.uri().rawQuery ?: ""), readRequestBody(it))
        },
        responder = { uri -> handler(last!!.method(), uri, calls.last().body) },
    )

    fun gateway(actor: String? = ACTOR): AdminControlV1Gateway =
        AdminControlV1Gateway(
            SupabaseRequestTransport.forTesting("https://stub.supabase.test", "stub-key", client),
            Json { ignoreUnknownKeys = true },
            actor = { actor },
        )

    /** Escrituras reales: ni GET ni RPC de solo comprobacion (`*_check`, `*_list`). */
    fun writes(): List<Call> = calls.filter { it.method != "GET" && !Regex("/rpc/[a-z_]*_(check|list|get)[?]").containsMatchIn(it.pathAndQuery) }

    fun describe(): String = calls.joinToString(" | ") { "${it.method} ${it.pathAndQuery}" }

    companion object {
        const val ACTOR = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val EXAM = "22222222-2222-4222-8222-222222222222"
        const val USER = "33333333-3333-4333-8333-333333333333"
        const val OTHER = "44444444-4444-4444-8444-444444444444"
    }
}

/** Monta SOLO las rutas de Control V1 con el mismo manejo de errores que el servidor real. */
internal fun ApplicationTestBuilder.installControlRoutes(gateway: AdminControlV1Gateway) {
    application {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { adminControlV1Routes(gateway) }
    }
}

internal suspend fun ApplicationTestBuilder.sendJson(method: HttpMethod, url: String, body: String? = null): HttpResponse =
    client.request(url) {
        this.method = method
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

