package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MASTER2-AUTH-008: la regla SUPERADMIN de D1-26 se comprobaba en Ktor con una lectura aparte y la RPC no conocia al
 * actor (TOCTOU). Ahora Ktor llama a la sobrecarga de 3 argumentos (migracion 20260930101600), que repite la regla
 * bajo el cerrojo de `admin_sync_roles`, y SIEMPRE envia `p_actor_id` (null incluido): omitirlo resolveria en
 * PostgREST a la sobrecarga antigua, que no conoce al actor.
 */
class AdminRoleSyncActorRpcTest {
    private val actor = "5a5a5a5a-0000-4000-8000-000000000011"
    private val target = "5a5a5a5a-0000-4000-8000-000000000012"
    private val roleUser = "aaaaaaaa-0000-4000-8000-00000000000c"
    private val roleMod = "aaaaaaaa-0000-4000-8000-00000000000d"

    private fun syncBody(actorId: String?): kotlinx.serialization.json.JsonObject {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ sent += it }) { uri ->
                when {
                    "/rest/v1/roles_app" in uri -> V1StubResponse(
                        200,
                        """[{"id":"$roleUser","codigo":"usuario","nombre":"Usuario"},{"id":"$roleMod","codigo":"moderador","nombre":"Moderador"}]""",
                    )
                    "/rest/v1/roles_usuario?select=rol_id" in uri -> V1StubResponse(200, """[{"usuario_id":"$target","rol_id":"$roleUser"}]""")
                    "/rest/v1/rpc/admin_sync_roles" in uri -> V1StubResponse(204, "")
                    "/rest/v1/auditoria_log" in uri -> V1StubResponse(201, "")
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
        gateway.syncRoles(target, listOf(roleUser, roleMod), actorId)
        val rpc = sent.single { "/rest/v1/rpc/admin_sync_roles" in it.uri().toString() }
        return Json.parseToJsonElement(readRequestBody(rpc)).jsonObject
    }

    @Test
    fun theRpcAlwaysReceivesTheActor() {
        val body = syncBody(actor)
        assertEquals(setOf("p_usuario_id", "p_role_ids", "p_actor_id"), body.keys)
        assertEquals(target, body["p_usuario_id"]!!.jsonPrimitive.content)
        assertEquals(listOf(roleUser, roleMod), body["p_role_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(actor, body["p_actor_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aMissingActorIsSentAsExplicitNullSoTheActorAwareOverloadIsStillChosen() {
        val body = syncBody(null)
        assertTrue("p_actor_id" in body.keys, "p_actor_id presente aunque sea null: $body")
        assertEquals(JsonNull, body["p_actor_id"])
    }
}
