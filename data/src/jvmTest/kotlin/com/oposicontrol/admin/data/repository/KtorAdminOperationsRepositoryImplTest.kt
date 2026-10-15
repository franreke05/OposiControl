package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.AuditApi
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.model.NewsType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tipos de noticia, moderación y soporte sobre el contrato canónico; los fallos del backend son fallos, nunca datos. */
class KtorAdminOperationsRepositoryImplTest {
    private val appConfig = AppConfig(
        environment = Environment.DEV, baseUrl = "http://backend.test", backendToken = "t",
        supabaseUrl = "https://example.supabase.co", supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "b", featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "x", appVersion = "test",
    )
    private val session = SessionLocalDataSource(AdminDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AdminDatabase.Schema.create(it) }))
    private val adapter = NetworkResultAdapter(NoOpLogger)

    private class Seen(val method: HttpMethod, val path: String, val query: Map<String, String>, val body: String)

    private val seen = mutableListOf<Seen>()

    private fun client(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>) = HttpClient(
        MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            seen += Seen(request.method, request.url.encodedPath, request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }, body)
            val (status, response) = handler(request)
            respond(response, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun repo(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>) =
        KtorAdminOperationsRepositoryImpl(client(handler), appConfig, session, adapter)

    private fun tipo(n: Int, activo: Boolean = true) =
        """{"id":"00000000-0000-4000-8000-00000000000$n","codigo":"tipo-$n","nombre":"Tipo $n","slug":"tipo-$n","descripcion":null,"activo":$activo,"posicion":$n}"""

    @Test
    fun newsTypesComeFromTheCanonicalListIncludingInactiveAndAreMappedWithTheirCode() = runTest {
        val repo = repo { HttpStatusCode.OK to """{"items":[${tipo(1)},${tipo(2, activo = false)}],"total":2,"limit":200,"offset":0}""" }
        val result = repo.getNewsTypes()
        assertTrue(result is AppResult.Success, "$result")
        assertEquals(
            listOf(
                NewsType("00000000-0000-4000-8000-000000000001", "Tipo 1", "tipo-1", true, code = "tipo-1", position = 1),
                NewsType("00000000-0000-4000-8000-000000000002", "Tipo 2", "tipo-2", false, code = "tipo-2", position = 2),
            ),
            result.value,
        )
        val call = seen.single()
        assertEquals(HttpMethod.Get, call.method)
        assertEquals("/api/v1/admin/tipos-noticia", call.path)
        assertEquals(mapOf("includeInactive" to "true", "limit" to "200", "offset" to "0"), call.query)
    }

    @Test
    fun newsTypesFollowTheOffsetPagesUntilTheTotal() = runTest {
        val repo = repo { req ->
            if (req.url.parameters["offset"] == "0") HttpStatusCode.OK to """{"items":[${tipo(1)}],"total":2,"limit":1,"offset":0}"""
            else HttpStatusCode.OK to """{"items":[${tipo(2)}],"total":2,"limit":1,"offset":1}"""
        }
        val result = repo.getNewsTypes()
        assertTrue(result is AppResult.Success)
        assertEquals(listOf("tipo-1", "tipo-2"), result.value.map { it.code })
        assertEquals(listOf("0", "1"), seen.map { it.query["offset"] })
    }

    @Test
    fun aBackendErrorOnTheListIsAFailureNotAnEmptyCatalog() = runTest {
        val repo = repo { HttpStatusCode.InternalServerError to """{"code":"INTERNAL"}""" }
        assertTrue(repo.getNewsTypes() is AppResult.Failure)
    }

    @Test
    fun upsertPutsTheExactParseTipoBodyToTheCodeRoute() = runTest {
        val repo = repo { HttpStatusCode.Created to tipo(3) }
        val result = repo.upsertNewsType(NewsType("", " Tipo 3 ", "tipo-3", true, code = "tipo-3", description = "d", position = 3))
        assertTrue(result is AppResult.Success, "$result")
        assertEquals("00000000-0000-4000-8000-000000000003", result.value.id)
        val call = seen.single()
        assertEquals(HttpMethod.Put, call.method)
        assertEquals("/api/v1/admin/tipos-noticia/tipo-3", call.path)
        val body = Json.parseToJsonElement(call.body).jsonObject
        assertEquals(setOf("nombre", "slug", "descripcion", "activo", "posicion"), body.keys, "parseTipo rechaza cualquier otro campo")
        assertEquals("Tipo 3", body["nombre"]!!.jsonPrimitive.content)
        assertEquals("3", body["posicion"]!!.jsonPrimitive.content)
    }

    @Test
    fun upsertWithoutACodeFailsWithoutCallingTheBackend() = runTest {
        val repo = repo { HttpStatusCode.OK to tipo(1) }
        assertTrue(repo.upsertNewsType(NewsType("", "x", "x", true)) is AppResult.Failure)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aSlugConflictOnUpsertIsAFailure() = runTest {
        val repo = repo { HttpStatusCode.Conflict to """{"code":"SLUG_CONFLICT"}""" }
        assertTrue(repo.upsertNewsType(NewsType("", "x", "x", true, code = "x")) is AppResult.Failure)
    }

    @Test
    fun moderationPostsTheCanonicalCommandAndA422IsAFailure() = runTest {
        val uid = "00000000-0000-4000-8000-000000000009"
        val action = ModerationAction("", "advertencia", " $uid ", "Yo", "motivo", "")
        val ok = repo { HttpStatusCode.Created to """{"ok":true}""" }.appendModerationAction(action)
        assertTrue(ok is AppResult.Success, "$ok")
        val call = seen.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/v1/admin/moderacion/actions", call.path)
        val body = Json.parseToJsonElement(call.body).jsonObject
        assertEquals(setOf("targetUserId", "actionType", "reason"), body.keys, "el ejecutor sale del JWT, nunca del cliente")
        assertEquals(uid, body["targetUserId"]!!.jsonPrimitive.content)
        assertEquals("advertencia", body["actionType"]!!.jsonPrimitive.content)

        val rejected = repo { HttpStatusCode.UnprocessableEntity to """{"code":"MODERATION_ACTION_NOT_ENFORCED"}""" }
            .appendModerationAction(action.copy(actionType = "ban"))
        assertTrue(rejected is AppResult.Failure)
    }

    @Test
    fun theSupportAssigneeOfTheCallerNeverTravels() = runTest {
        val repo = repo { HttpStatusCode.Created to """{"id":"m","conversationId":"c","authorName":"a","text":"t","timestamp":"x","internal":false}""" }
        assertTrue(repo.sendSupportMessage("c", "Ana", "hola", false) is AppResult.Success)
        val body = Json.parseToJsonElement(seen.single().body).jsonObject
        assertEquals("", body["assignee"]!!.jsonPrimitive.content, "el backend lo ignora: no se manda el del llamador")
        assertEquals("hola", body["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun auditReadsRaiseTheBackendStatusInsteadOfDecodingTheErrorBody() = runTest {
        val api = AuditApi(client { HttpStatusCode.Forbidden to """{"code":"FORBIDDEN"}""" }, appConfig, session)
        val list = assertFailsWith<IllegalStateException> { api.getMovements(AuditFilter(), null) }
        assertTrue(list.message!!.startsWith("backend 403 /api/v1/admin/auditoria/movimientos"), list.message)
        val detail = assertFailsWith<IllegalStateException> { api.getMovementDetail("abc") }
        assertTrue(detail.message!!.startsWith("backend 403 /api/v1/admin/auditoria/abc"), detail.message)
    }
}
