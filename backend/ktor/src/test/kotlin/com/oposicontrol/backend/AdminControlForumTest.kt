package com.oposicontrol.backend

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminControlForumTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private val id = ControlFake.OTHER

    @Test
    fun approveAndRestoreNeedNoReasonButDestructiveActionsDo() {
        ForumRules.moderation(obj("""{"tipo":"publicacion","id":"$id","accion":"aprobar"}"""))
        ForumRules.moderation(obj("""{"tipo":"comentario","id":"$id","accion":"restaurar"}"""))
        listOf("rechazar", "ocultar", "silenciar").forEach { accion ->
            val e = assertFailsWith<BackendDomainException>(accion) { ForumRules.moderation(obj("""{"tipo":"publicacion","id":"$id","accion":"$accion","motivo":"ab"}""")) }
            assertEquals("FORO_REASON_REQUIRED", e.errorCode)
        }
        val m = ForumRules.moderation(obj("""{"tipo":"publicacion","id":"${id.uppercase()}","accion":"rechazar","motivo":"  Spam  evidente "}"""))
        assertEquals("Spam  evidente", m.notas)
        assertEquals(id, m.id)
    }

    @Test
    fun muteDaysAreBoundedDefaultedAndOnlyForMute() {
        val ok = ForumRules.moderation(obj("""{"tipo":"publicacion","id":"$id","accion":"silenciar","motivo":"reincidente"}"""))
        assertEquals(ForumRules.DIAS_DEFAULT, ok.dias)
        listOf(0, 366, -3).forEach {
            assertEquals("FORO_DAYS_INVALID", assertFailsWith<BackendDomainException>("$it") {
                ForumRules.moderation(obj("""{"tipo":"publicacion","id":"$id","accion":"silenciar","motivo":"reincidente","dias":$it}"""))
            }.errorCode)
        }
        assertEquals("FORO_DAYS_INVALID", assertFailsWith<BackendDomainException> {
            ForumRules.moderation(obj("""{"tipo":"publicacion","id":"$id","accion":"ocultar","motivo":"motivo ok","dias":3}"""))
        }.errorCode)
        assertNull(ForumRules.moderation(obj("""{"tipo":"publicacion","id":"$id","accion":"aprobar"}""")).dias)
    }

    @Test
    fun unknownActionsTypesIdsAndKeysAreRejected() {
        assertEquals("FORO_ACTION_INVALID", assertFailsWith<BackendDomainException> { ForumRules.moderation(obj("""{"tipo":"publicacion","id":"$id","accion":"borrar"}""")) }.errorCode)
        assertEquals("FORO_TYPE_INVALID", assertFailsWith<BackendDomainException> { ForumRules.moderation(obj("""{"tipo":"usuario","id":"$id","accion":"aprobar"}""")) }.errorCode)
        assertFailsWith<BadRequestException> { ForumRules.moderation(obj("""{"tipo":"publicacion","id":"x","accion":"aprobar"}""")) }
        assertFailsWith<BadRequestException> { ForumRules.moderation(obj("""{"tipo":"publicacion","id":"$id","accion":"aprobar","actor":"$id"}""")) }
    }

    @Test
    fun moderationCallsTheCanonicalRpcWithTheJwtActorOnly() {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, """{"accion":"silenciar"}""") }
        val result = f.gateway().moderateForum(ForumRules.moderation(obj("""{"tipo":"comentario","id":"$id","accion":"silenciar","dias":30,"motivo":"insultos"}""")))
        assertEquals("silenciar", result.accion)
        val call = f.calls.single()
        assertTrue(call.pathAndQuery.contains("/rpc/admin_foro_moderate"), call.pathAndQuery)
        val body = obj(call.body)
        assertEquals(ControlFake.ACTOR, body["p_actor"].contentOrNullSafe())
        assertEquals("30", body["p_dias"].contentOrNullSafe())
        assertEquals("insultos", body["p_notas"].contentOrNullSafe())
    }

    @Test
    fun aStaticTokenWithoutAPersonCannotModerate() {
        val f = ControlFake()
        val e = assertFailsWith<BackendDomainException> { f.gateway(actor = null).moderateForum(ForumRules.moderation(obj("""{"tipo":"comentario","id":"$id","accion":"aprobar"}"""))) }
        assertEquals(HttpStatusCode.Forbidden, e.status)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun theDatabaseNotFoundBecomesA404Domain() = testApplication {
        val f = ControlFake { _, _, _ -> V1StubResponse(400, """{"code":"P0001","message":"FORO_NOT_FOUND"}""") }
        installControlRoutes(f.gateway())
        val r = sendJson(HttpMethod.Post, "/api/v1/admin/foro/moderar", """{"tipo":"publicacion","id":"$id","accion":"aprobar"}""")
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    private fun queueFake() = ControlFake { _, uri, _ ->
        when {
            uri.contains("/rest/v1/publicaciones_oposired") -> V1StubResponse(
                200,
                """[{"id":"$id","categoria_id":"${ControlFake.USER}","usuario_id":"${ControlFake.EXAM}","titulo":"T‮itulo","contenido":"<script>alert(1)</script> hola\u0007","estado":"pendiente_moderacion","creado_en":"2026-10-18T08:00:00Z","imagen_id":"${ControlFake.ACTOR}"},""" +
                    """{"id":"${ControlFake.USER}","categoria_id":null,"usuario_id":null,"titulo":null,"contenido":"x","estado":"pendiente_moderacion","creado_en":"2026-10-18T07:00:00Z","imagen_id":null}]""",
            )
            uri.contains("/rest/v1/perfiles_usuario") -> V1StubResponse(200, """[{"usuario_id":"${ControlFake.EXAM}","username":"marta"}]""")
            uri.contains("/rest/v1/categorias_oposired") -> V1StubResponse(200, """[{"id":"${ControlFake.USER}","nombre":"General"}]""")
            uri.contains("/rest/v1/reportes_contenido") -> V1StubResponse(200, """[{"entidad_id":"$id","motivo_codigo":"spam"},{"entidad_id":"$id","motivo_codigo":"otro"}]""")
            else -> V1StubResponse(200, "[]")
        }
    }

    @Test
    fun theQueueSanitizesTextAndShowsHandlesNeverNames() {
        val f = queueFake()
        val q = f.gateway().forumQueue("publicacion", "pendiente_moderacion", 20, null)
        assertEquals(2, q.items.size)
        val first = q.items.first()
        assertEquals("marta", first.autorHandle)
        assertEquals("General", first.canal)
        assertEquals(true, first.tieneImagen)
        assertEquals(2, first.reportesPendientes)
        assertEquals(listOf("otro", "spam"), first.motivosReporte)
        assertTrue(first.titulo!!.none { it == '‮' })
        assertTrue(first.fragmento.none { it == '\u0007' })
        // el contenido llega como texto literal: el panel lo pinta con Text(), nunca como HTML
        assertTrue(first.fragmento.startsWith("<script>"))
        assertTrue(f.calls.first().pathAndQuery.contains("estado=eq.pendiente_moderacion"), f.describe())
        assertTrue(f.calls.first().pathAndQuery.contains("limit=21"), f.describe())
    }

    @Test
    fun theReportedViewFiltersByTheReportedIds() {
        val f = queueFake()
        f.gateway().forumQueue("publicacion", "reportada", 20, null)
        assertTrue(f.calls.any { it.pathAndQuery.contains("id=in.($id)") }, f.describe())
    }

    @Test
    fun theQueueRouteRejectsBadFiltersAndCursorsBeforeTheNetwork() = testApplication {
        val f = ControlFake()
        installControlRoutes(f.gateway())
        assertEquals(HttpStatusCode.UnprocessableEntity, sendJson(HttpMethod.Get, "/api/v1/admin/foro/cola?tipo=usuario").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, sendJson(HttpMethod.Get, "/api/v1/admin/foro/cola?estado=publicada%27%29").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Get, "/api/v1/admin/foro/cola?cursor=basura").status)
        assertTrue(f.calls.isEmpty(), f.describe())
    }

    @Test
    fun channelInputsAreStrict() {
        val ok = parseChannel(obj("""{"nombre":" General ","slug":"general","descripcion":"","soloLectura":true,"posicion":3,"especialidadId":"$id"}"""), creating = true)
        assertEquals("General", ok["nombre"].contentOrNullSafe())
        assertEquals("true", ok["solo_lectura"].contentOrNullSafe())
        assertEquals(id, ok["especialidad_id"].contentOrNullSafe())
        assertFailsWith<BackendDomainException> { parseChannel(obj("""{"nombre":"A","slug":"a"}"""), creating = true) }
        assertFailsWith<BackendDomainException> { parseChannel(obj("""{"nombre":"Canal","slug":"Con Espacios"}"""), creating = true) }
        assertFailsWith<BackendDomainException> { parseChannel(obj("""{"nombre":"Canal","slug":"ok","posicion":0}"""), creating = true) }
        assertFailsWith<BadRequestException> { parseChannel(obj("""{"nombre":"Canal","slug":"ok","id":"$id"}"""), creating = true) }
        assertFailsWith<BadRequestException> { parseChannel(obj("""{"nombre":"Canal","slug":"ok","especialidadId":"no-uuid"}"""), creating = true) }
        // en un PATCH solo viaja lo enviado
        assertEquals(setOf("activa"), parseChannel(obj("""{"activa":false}"""), creating = false).keys)
    }

    @Test
    fun channelWritesAreAudited() {
        val f = ControlFake { method, _, _ ->
            when (method) {
                "POST" -> V1StubResponse(201, """[{"id":"$id","nombre":"General","slug":"general","activa":true,"posicion":1,"solo_lectura":false}]""")
                else -> V1StubResponse(200, """[{"id":"$id","nombre":"General","slug":"general","activa":false,"posicion":1,"solo_lectura":false}]""")
            }
        }
        val g = f.gateway()
        val created = g.createChannel(parseChannel(obj("""{"nombre":"General","slug":"general"}"""), creating = true))
        assertEquals(id, created.id)
        val updated = g.updateChannel(id, parseChannel(obj("""{"activa":false}"""), creating = false))
        assertNotNull(updated)
        assertEquals(false, updated.activa)
        val audits = f.writes().filter { it.pathAndQuery.contains("auditoria_log") }
        assertEquals(2, audits.size, f.describe())
        assertTrue(audits.all { it.body.contains(ControlFake.ACTOR) })
    }

    @Test
    fun blockAndRestrictionFunctionsDegradeHonestlyWhenMissing() = testApplication {
        val f = ControlFake { _, _, _ -> V1StubResponse(404, """{"code":"PGRST202"}""") }
        installControlRoutes(f.gateway())
        assertEquals(HttpStatusCode.NotImplemented, sendJson(HttpMethod.Get, "/api/v1/admin/foro/bloqueos").status)
        assertEquals(HttpStatusCode.NotImplemented, sendJson(HttpMethod.Get, "/api/v1/admin/foro/restricciones").status)
        assertEquals(HttpStatusCode.NotImplemented, sendJson(HttpMethod.Post, "/api/v1/admin/foro/bloqueos", """{"patron":"casino"}""").status)
    }

    @Test
    fun blockPatternsAreNormalizedAndBounded() {
        assertEquals("casino", ForumRules.pattern("  CASINO "))
        assertFailsWith<BackendDomainException> { ForumRules.pattern("a") }
        assertTrue(ForumRules.pattern("x".repeat(500)).length <= ForumRules.PATTERN_MAX)
    }

    @Test
    fun liftingARestrictionNeedsAUuidAndAPerson() = testApplication {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, "null") }
        installControlRoutes(f.gateway())
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Delete, "/api/v1/admin/foro/restricciones/no-uuid").status)
        assertEquals(HttpStatusCode.NoContent, sendJson(HttpMethod.Delete, "/api/v1/admin/foro/restricciones/$id").status)
        val body = obj(f.calls.single().body)
        assertEquals(ControlFake.ACTOR, body["p_actor"].contentOrNullSafe())
    }

    @Test
    fun aDomainNotFoundWithHttp404IsNotMistakenForAMissingFunction() = testApplication {
        val f = ControlFake { _, _, _ -> V1StubResponse(404, """{"code":"P0001","message":"FORO_NOT_FOUND"}""") }
        installControlRoutes(f.gateway())
        val r = sendJson(HttpMethod.Post, "/api/v1/admin/foro/moderar", """{"tipo":"publicacion","id":"$id","accion":"aprobar"}""")
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun aMalformedCursorIdIsA400() = testApplication {
        val f = ControlFake()
        installControlRoutes(f.gateway())
        val bad = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("2020-01-01T00:00:00Z|1-1-1-1-1".toByteArray())
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Get, "/api/v1/admin/foro/cola?cursor=$bad").status)
    }
}
