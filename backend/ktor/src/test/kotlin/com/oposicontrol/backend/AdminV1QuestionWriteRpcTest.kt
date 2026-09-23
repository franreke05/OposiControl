package com.oposicontrol.backend

import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KQ2: alta y edición de preguntas por las RPC de Q2 (`admin_create_question`,
 * `admin_update_question`).
 *
 * La propiedad que importa del alta es la IDEMPOTENCIA: el id lo genera el cliente
 * una vez y lo repite en cada reintento, y la RPC responde `creada:false` en vez de
 * duplicar. Un reintento que creara otra pregunta sería invisible para el
 * administrador y visible para los opositores.
 */
class AdminV1QuestionWriteRpcTest {

    private val preguntaId = "77777777-7777-4777-8777-777777777777"
    private val especialidadId = "88888888-8888-4888-8888-888888888888"
    private val examenId = "99999999-9999-4999-8999-999999999999"

    // -----------------------------------------------------------------
    // Alta
    // -----------------------------------------------------------------

    @Test fun elAltaMandaElIdDelClienteYDecodificaLaRespuestaJsonb() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream)

        val creada = gateway.createPregunta(alta(), actorId = null)

        val rpc = readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_create_question") })
        assertTrue(rpc.contains("\"p_pregunta_id\":\"$preguntaId\""), rpc)
        assertTrue(rpc.contains("\"p_opciones\":[{\"letra\":\"A\",\"texto\":\"a\",\"es_correcta\":true}"), rpc)
        assertFalse(rpc.contains("posicion\":"), "las opciones no llevan posición: la deriva la base de la letra. $rpc")
        assertTrue(creada.creada)
        assertEquals(preguntaId, creada.pregunta.id)
    }

    /** Un id que no es UUID es un 400 en la ruta, antes de que salga nada. */
    @Test fun unIdQueNoEsUuidEsUn400SinLlamarASupabase() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { writeModule(gatewayWith(upstream)) }

        val response = client.post("/api/v1/admin/preguntas") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"no-soy-uuid","titulo":"T","opciones":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(upstream.isEmpty(), upstream.describe())
    }

    /** 201 cuando ESTA llamada creó; 200 cuando ya existía (un reintento con el mismo contenido). */
    @Test fun elReintentoRespondeDoscientosYNoAudita() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { writeModule(gatewayWith(upstream, creada = false)) }

        val response = client.post("/api/v1/admin/preguntas") {
            contentType(ContentType.Application.Json)
            setBody(altaJson())
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(upstream.none { it.uri().rawPath.endsWith("/auditoria_log") }, "un reintento no es otra alta: " + upstream.describe())
    }

    @Test fun laPrimeraVezRespondeCreado() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { writeModule(gatewayWith(upstream, creada = true)) }

        val response = client.post("/api/v1/admin/preguntas") {
            contentType(ContentType.Application.Json)
            setBody(altaJson())
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    /** M8 · TEST 1/10: la pregunta no tiene especialidad. El alta sin ella es válida y la RPC no la recibe. */
    @Test fun elAltaNoLlevaEspecialidadNiSeLaExige() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { writeModule(gatewayWith(upstream, creada = true)) }

        val response = client.post("/api/v1/admin/preguntas") {
            contentType(ContentType.Application.Json)
            setBody(altaJson())
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val rpc = readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_create_question") })
        assertFalse(rpc.contains("especialidad"), "la RPC de alta no recibe especialidad: $rpc")
    }

    /** Un cliente antiguo que aún manda `especialidadId` no rompe el alta: se ignora, no se reenvía. */
    @Test fun unEspecialidadIdSobranteSeIgnora() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { writeModule(gatewayWith(upstream, creada = true)) }

        val response = client.post("/api/v1/admin/preguntas") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$preguntaId","especialidadId":"$especialidadId","tipoPregunta":"rafaga","titulo":"T","opciones":[{"letra":"A","texto":"a","esCorrecta":true},{"letra":"B","texto":"b"},{"letra":"C","texto":"c"},{"letra":"D","texto":"d"}]}""")
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val rpc = readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_create_question") })
        assertFalse(rpc.contains("especialidad"), "no se reenvía: $rpc")
    }

    /** Un mismo id con otro contenido es el conflicto de la RPC, entero y con su 409. */
    @Test fun elMismoIdConOtroContenidoEsUn409() {
        val gateway = gatewayWith(
            CopyOnWriteArrayList(),
            createRpc = V1StubResponse(400, """{"code":"P0001","details":null,"hint":null,"message":"QUESTION_ID_CONFLICT"}"""),
        )

        val error = assertFailsWith<BackendDomainException> { gateway.createPregunta(alta()) }

        assertEquals("QUESTION_ID_CONFLICT", error.errorCode)
        assertEquals(409, error.status.value)
    }

    /** El alta con examen inicial viaja en la MISMA transacción; sin M4, la RPC lo dice y es un 409 legible. */
    @Test fun elAltaConExamenViajaEnLaMismaLlamadaYSinM4EsLegible() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gatewayWith(upstream).createPregunta(alta().copy(examenId = examenId, posicion = null))
        val rpc = readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_create_question") })
        assertTrue(rpc.contains("\"p_examen_id\":\"$examenId\""), rpc)
        assertFalse(rpc.contains("p_posicion"), "posición ausente = al final: no viaja. $rpc")

        val sinM4 = gatewayWith(
            CopyOnWriteArrayList(),
            createRpc = V1StubResponse(400, """{"code":"P0001","details":null,"hint":null,"message":"QUESTION_EXAM_LINK_UNAVAILABLE"}"""),
        )
        val error = assertFailsWith<BackendDomainException> { sinM4.createPregunta(alta().copy(examenId = examenId)) }
        assertEquals("QUESTION_EXAM_LINK_UNAVAILABLE", error.errorCode)
        assertEquals(409, error.status.value)
    }

    /**
     * El autor que llega a la RPC es el `sub` REAL del JWT autenticado, no un valor
     * fijo. Existe porque el test de más abajo a nivel de gateway
     * (`elAltaMandaComoAutorElSubDelJwtYNadaSinPersona`) sólo prueba que
     * `createPregunta` reenvía el `actorId` que se le pasa —nunca ejercita la
     * RUTA extrayendo ese id del principal—, así que un cableado roto en
     * `AdminV1Routes.kt` (`actorId = actorIdFromPrincipalName(...)` cambiado por
     * `actorId = null`) pasaba con los 704 tests en verde: la autoría se perdía
     * en silencio y nadie lo veía.
     *
     * Aquí SÍ hay una autenticación real instalada (`authenticate("gateway-auth")`
     * con `UserIdPrincipal(credentials.token)`, igual que en producción) y la
     * llamada entra con un `Authorization: Bearer <uuid>` de verdad: si la ruta
     * dejara de leer el principal, `p_creado_por` no llevaría ese uuid.
     */
    @Test fun laRutaMandaElSubRealDelJwtComoAutor() = testApplication {
        val autor = "abababab-abab-4bab-8bab-abababababab"
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { authenticatedWriteModule(gatewayWith(upstream)) }

        val response = client.post("/api/v1/admin/preguntas") {
            header(HttpHeaders.Authorization, "Bearer $autor")
            contentType(ContentType.Application.Json)
            setBody(altaJson())
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val rpc = readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_create_question") })
        assertTrue(rpc.contains("\"p_creado_por\":\"$autor\""), rpc)
    }

    // -----------------------------------------------------------------
    // Edición: sólo las claves presentes
    // -----------------------------------------------------------------

    @Test fun preguntaCambiosSoloLlevaLasClavesPresentesYTraduceLasOpciones() {
        val cambios = preguntaCambiosFrom(
            Json.parseToJsonElement(
                """{"titulo":"T","fuenteTexto":null,"opciones":[{"letra":"A","texto":"a","esCorrecta":true}],""" +
                    """"examenId":"$examenId","posicion":2,"recursos":[],"codigoPregunta":"PREG-9","especialidadId":"x"}""",
            ).jsonObject,
        )

        assertEquals(
            """{"titulo":"T","fuente_texto":null,"opciones":[{"letra":"A","texto":"a","es_correcta":true}]}""",
            cambios.toString(),
        )
    }

    /** `opciones: []` es «no las toques», no «déjala sin opciones». */
    @Test fun unaListaVaciaDeOpcionesNoViaja() {
        val cambios = preguntaCambiosFrom(Json.parseToJsonElement("""{"titulo":"T","opciones":[]}""").jsonObject)

        assertEquals("""{"titulo":"T"}""", cambios.toString())
    }

    @Test fun unasOpcionesQueNoSonListaSonUn400() {
        assertFailsWith<IllegalArgumentException> {
            preguntaCambiosFrom(Json.parseToJsonElement("""{"opciones":"A,B"}""").jsonObject)
        }
    }

    /** Por la ruta: el cuerpo crudo llega a la RPC como `p_cambios`, y la respuesta es la pregunta releída. */
    @Test fun laRutaDeEdicionLlamaALaRpcConElCuerpoCrudo() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { writeModule(gatewayWith(upstream)) }

        val response = client.patch("/api/v1/admin/preguntas/$preguntaId") {
            contentType(ContentType.Application.Json)
            setBody("""{"titulo":"Nuevo","razonamiento":"Porque sí"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val rpc = readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_update_question") })
        assertEquals("""{"p_pregunta_id":"$preguntaId","p_cambios":{"titulo":"Nuevo","razonamiento":"Porque sí"}}""", rpc)
        assertTrue(upstream.any { it.uri().rawPath.endsWith("/auditoria_log") }, "la edición se audita: " + upstream.describe())
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun alta() = AdminPreguntaDto(
        id = preguntaId,
        tipoPregunta = "rafaga",
        titulo = "Titulo",
        opciones = listOf(
            AdminOpcionDto(letra = "A", texto = "a", esCorrecta = true),
            AdminOpcionDto(letra = "B", texto = "b"),
            AdminOpcionDto(letra = "C", texto = "c"),
            AdminOpcionDto(letra = "D", texto = "d"),
        ),
    )

    private fun altaJson(): String =
        """{"id":"$preguntaId","tipoPregunta":"rafaga","titulo":"Titulo",""" +
            """"opciones":[{"letra":"A","texto":"a","esCorrecta":true},{"letra":"B","texto":"b"},""" +
            """{"letra":"C","texto":"c"},{"letra":"D","texto":"d"}]}"""

    private fun preguntaRow(): String =
        """[{"id":"$preguntaId","codigo_pregunta":"PREG-000001",""" +
            """"tipo_pregunta":"rafaga","titulo":"Titulo","activa":true}]"""

    private fun gatewayWith(
        upstream: MutableList<HttpRequest>,
        creada: Boolean = true,
        createRpc: V1StubResponse = V1StubResponse(
            200,
            """{"pregunta_id":"$preguntaId","codigo_pregunta":"PREG-000001","creada":$creada,"posicion_asignada":null}""",
        ),
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_create_question") -> createRpc
                uri.contains("/rest/v1/rpc/admin_update_question") -> V1StubResponse(200, "")
                uri.contains("/rest/v1/recursos_pregunta") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/opciones_pregunta") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/soluciones_pregunta") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/preguntas") -> V1StubResponse(200, preguntaRow(), contentRange = "0-0/1")
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun Application.writeModule(gateway: AdminV1Gateway) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { adminV1Routes(gateway) }
    }

    /** Como [writeModule], pero con la MISMA autenticación que `Application.module()` produce. */
    private fun Application.authenticatedWriteModule(gateway: AdminV1Gateway) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) {
            bearer("gateway-auth") {
                authenticate { credentials -> UserIdPrincipal(credentials.token) }
            }
        }
        routing { authenticate("gateway-auth") { adminV1Routes(gateway) } }
    }

    private fun List<HttpRequest>.describe(): String = describeRequests(this)
}
