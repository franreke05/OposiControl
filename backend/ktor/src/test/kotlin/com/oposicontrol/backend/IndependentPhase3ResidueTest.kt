package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HASHIRA C — residuos de FASE 3 que la propia FASE 3 declara cerrados.
 *
 * Cuatro afirmaciones que el codigo de produccion hace por escrito y que este
 * fichero comprueba una a una. Ninguna se prueba por su codigo de estado: se
 * prueban por **lo que sale por el cable**, que es lo unico que no puede pasar
 * por accidente.
 *
 * 1. La REGLA que escribe `patch("/preguntas/{id}")`: «todo identificador que
 *    venga del cliente y acabe dentro de un filtro de PostgREST se valida, da
 *    igual si llega por el PATH o por el CUERPO». La vertical de Importaciones
 *    tiene tres rutas que no la cumplen.
 * 2. E-2: «las tres capacidades de ciclo de vida devuelven 404». El PATCH de
 *    examen, que escribe con el mismo filtro, sigue devolviendo 500.
 * 3. E-1: «la visibilidad de un examen se cambia SOLO por publicar, despublicar
 *    y archivar». El POST de creacion la escribe directamente.
 * 4. El mismo vector de truncado por un parametro de QUERY, que ninguna de las
 *    dos redes (path y cuerpo) mira.
 */
class IndependentPhase3ResidueTest {

    private val examenId = "12121212-1212-4121-8121-121212121212"
    private val stagingId = "13131313-1313-4131-8131-131313131313"
    private val especialidadId = "14141414-1414-4141-8141-141414141414"

    // -----------------------------------------------------------------
    // 1 · La regla, aplicada a Importaciones
    // -----------------------------------------------------------------

    /**
     * ESPERADO ROJO. `PATCH /importaciones/staging/{id}` no valida el id.
     *
     * `updateStaging` compone `PATCH /preguntas_parseadas_staging?id=eq.$id`, y
     * el valor del cliente entra crudo.
     *
     * Aqui el vector no es el `#` —el filtro va en ultima posicion, asi que
     * truncar no borra nada— sino el `&`: el identificador es el final de la
     * consulta, de modo que el cliente **escribe parametros de PostgREST**. La
     * consulta no lleva `select` y el PATCH responde con `return=representation`,
     * asi que un id como `<uuid>&select=*,lotes_importacion_preguntas(*)` anade
     * un recurso embebido y devuelve filas de otra tabla desde una ruta de
     * escritura.
     *
     * El caso usa un marcador inofensivo (`hashira=1`) para que lo que se afirme
     * sea exactamente la propiedad, sin depender de que PostgREST acepte una u
     * otra forma de embebido: **el cliente no puede anadir parametros a la
     * consulta**. Si el marcador aparece en la URL que sale, tampoco hay nada que
     * impida que aparezca un `select`.
     */
    @Test fun theImportStagingPatchValidatesItsIdentifierLikeEveryOtherFilter() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { residueModule(upstream) }

        client.patch("/api/v1/admin/importaciones/staging/$stagingId%26hashira=1") {
            contentType(ContentType.Application.Json)
            setBody("""{"titulo":"editado"}""")
        }

        upstream.forEach { request ->
            assertTrue(
                !request.uri().rawQuery.orEmpty().contains("hashira=1"),
                "el cliente escribio un parametro propio en la consulta de PostgREST: " +
                    "${request.method()} ${request.uri().rawPath}?${request.uri().rawQuery}",
            )
        }
    }

    /**
     * ESPERADO ROJO. Mismo hueco en `POST /importaciones/{loteId}/importar`.
     *
     * `importarLote` es la operacion mas cara de la vertical —crea preguntas
     * reales, una por fila de staging— y su identificador tampoco se valida.
     * Aqui el `#` no borra ningun filtro de pertenencia, pero deja la lectura de
     * `preguntas_parseadas_staging` SIN su `order`, y sobre todo deja entrar el
     * `&`: la misma inyeccion de parametros de PostgREST que arriba, esta vez
     * sobre la ruta que escribe en `preguntas`.
     */
    @Test fun theImportRunValidatesItsBatchIdentifier() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { residueModule(upstream) }

        client.post("/api/v1/admin/importaciones/$stagingId%23/importar")

        upstream.forEach { request ->
            val query = request.uri().rawQuery.orEmpty()
            assertTrue(
                query.contains(stagingId) || !query.contains("eq."),
                "el identificador del cliente llego truncado al upstream: " +
                    "${request.method()} ${request.uri().rawPath}?$query",
            )
        }
    }

    // -----------------------------------------------------------------
    // 2 · E-2, la cuarta ruta
    // -----------------------------------------------------------------

    /**
     * ESPERADO ROJO. `PATCH /examenes/{id}` sobre un id inexistente da 500.
     *
     * Las tres capacidades de ciclo de vida se arreglaron; el PATCH quedo con su
     * `?: error("Examen no encontrado para actualizar: $id")`, que sale por el
     * handler de `IllegalStateException`: un 500 «Error al consultar el servicio
     * de datos. Referencia: …», indistinguible de un Supabase caido de verdad.
     *
     * Es la misma condicion exacta que las otras tres —una lista vacia con 200 y
     * el filtro `id=eq.` sin casar— y el editor de examenes lo alcanza sin
     * malicia: basta guardar una pestaña abierta sobre un examen que otro
     * administrador borro. El mensaje ademas ECHA el id en el texto de la
     * excepcion; no llega al cliente porque el handler lo redacta, pero se
     * apoya en esa redaccion en vez de no ponerlo.
     */
    @Test fun patchingAnExamThatDoesNotExistIsAlsoANotFound() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { residueModule(upstream, examenRows = "[]") }

        val response = client.patch("/api/v1/admin/examenes/$examenId") {
            contentType(ContentType.Application.Json)
            setBody("""{"especialidadId":"$especialidadId","titulo":"Examen"}""")
        }

        assertEquals(
            HttpStatusCode.NotFound,
            response.status,
            "un examen que no existe no es una averia del servidor: ${response.bodyAsText()}",
        )
    }

    // -----------------------------------------------------------------
    // 3 · E-1, por la puerta del POST
    // -----------------------------------------------------------------

    /**
     * ESPERADO ROJO. `POST /examenes` puede crear un examen YA PUBLICADO.
     *
     * `updateExamen` filtra `activo` y `publicado_en` del parche; `createExamen`
     * no filtra nada: construye la fila con `toRow()` y la manda entera. Un
     * `POST` con `"publicadoEn":"2020-01-01T00:00:00Z"` inserta una fila con
     * `activo=true` y `publicado_en` no nulo, que es exactamente la condicion
     * que `AppV1Gateway.listExamenes` usa para servir un examen a los usuarios
     * de la app.
     *
     * Lo que lo convierte en defecto y no en decision de producto es la
     * AUDITORIA: `createExamen` registra `examen_creado` y nada mas.
     * `publicarExamen` registra `examen_publicado`. Por esta puerta un examen se
     * vuelve visible para los usuarios finales **sin una sola linea de auditoria
     * que diga que alguien lo publico** — el mismo argumento con el que esta
     * misma fase justifico quitar el `runCatching` de `resolver`: una pista de
     * auditoria que se puede esquivar no sirve para lo unico que sirve.
     *
     * Si se decide que es legitimo, la forma de decirlo no es el silencio: es
     * registrar `examen_publicado` tambien aqui.
     */
    @Test fun creatingAnExamCannotPublishItBehindTheAuditTrail() = testApplication {
        val bodies = CopyOnWriteArrayList<String>()
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { residueModule(upstream, record = { bodies += it.bodyText() }) }

        client.post("/api/v1/admin/examenes") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"especialidadId":"$especialidadId","comunidadId":"77777777-7777-4777-8777-777777777777","titulo":"Examen",""" +
                    """"publicadoEn":"2020-01-01T00:00:00Z"}""",
            )
        }

        val insert = assertNotNull(
            bodies.firstOrNull { it.contains("\"titulo\"") },
            "no salio el INSERT: $bodies",
        )
        assertTrue(
            !insert.contains("\"publicado_en\":\""),
            "el INSERT sello la fecha de publicacion sin pasar por `publicar`, " +
                "asi que no hay `examen_publicado` en auditoria: $insert",
        )
    }

    // -----------------------------------------------------------------
    // 4 · El mismo truncado por un parametro de query
    // -----------------------------------------------------------------

    /**
     * ESPERADO ROJO. `GET /feedback/preguntas?estado=…` interpola el valor CRUDO.
     *
     * `listFeedback` compone
     * `?select=*$filtro$tipoFiltro$cursorFilter&order=creado_en.desc,id.desc&limit=${'$'}{limit + 1}`
     * y el `filtro` es `"&estado=eq.$estado"` **sin codificar**. `tipoFeedback`
     * si pasa por `urlEncode()` y el `cursor` si se valida
     * (`decodeFeedbackCursor` exige un `Instant` y un UUID): `estado` es el
     * unico de los tres que entra a pelo.
     *
     * Con `estado=pendiente%23` la peticion llega al upstream sin `order` y
     * **sin `limit`**: `feedback_pregunta` completa en una respuesta, a una
     * peticion de distancia y sin nada raro en el log. Es la misma
     * amplificacion de lectura que el propio arreglo de `hilos/{id}/respuestas`
     * describe como motivo suficiente para validar.
     *
     * La red que se puso en esta fase mira el PATH y el CUERPO. La QUERY no la
     * mira nadie, y la regla escrita no distingue.
     */
    @Test fun aFragmentInTheFeedbackStateFilterCannotStripTheRowLimit() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { residueModule(upstream) }

        client.get("/api/v1/admin/feedback/preguntas?estado=pendiente%23")

        val read = assertNotNull(
            upstream.firstOrNull { it.uri().rawPath.contains("feedback_pregunta") },
            "no salio la lectura: ${upstream.map { it.uri() }}",
        )
        assertTrue(
            read.uri().rawQuery.orEmpty().contains("limit="),
            "la pagina de incidencias salio sin cota: el `#` del cliente se llevo el `limit`. " +
                "query=${read.uri().rawQuery} fragment=${read.uri().rawFragment}",
        )
    }

    /**
     * Control obligatorio, ESPERADO VERDE: sin el, «rechazarlo todo» pasaria el
     * caso de arriba. Un `estado` normal sigue filtrando y sigue acotando.
     */
    @Test fun anOrdinaryStateFilterStillReachesSupabaseWithItsLimit() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { residueModule(upstream) }

        client.get("/api/v1/admin/feedback/preguntas?estado=pendiente")

        val read = assertNotNull(
            upstream.firstOrNull { it.uri().rawPath.contains("feedback_pregunta") },
            "no salio la lectura: ${upstream.map { it.uri() }}",
        )
        val query = read.uri().rawQuery.orEmpty()
        assertTrue(query.contains("estado=eq.pendiente"), query)
        assertTrue(query.contains("limit="), query)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun Application.residueModule(
        upstream: MutableList<HttpRequest>,
        examenRows: String = "[${examenRowJson()}]",
        record: (HttpRequest) -> Unit = {},
    ) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(StatusPages) {
            exception<BackendDomainException> { call, cause ->
                call.respond(cause.status, ErrorDto(error = cause.errorCode.lowercase(), message = cause.message))
            }
            exception<BadRequestException> { call, _ ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(error = "bad_request", message = "El cuerpo de la peticion no es valido."),
                )
            }
            exception<IllegalArgumentException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(error = "bad_request", message = cause.message ?: "Invalid request"),
                )
            }
            // Igual que en `Application.kt`: el detalle del upstream se registra,
            // no se devuelve. Aqui basta con que el 500 sea observable.
            exception<IllegalStateException> { call, _ ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorDto(error = "backend_supabase_error", message = "Error al consultar el servicio de datos."),
                )
            }
        }
        routing {
            adminV1Routes(
                AdminV1Gateway.forTesting(
                    supabaseUrl = "https://stub.supabase.test",
                    serviceRoleKey = "stub-key",
                    json = Json { ignoreUnknownKeys = true },
                    client = V1StubHttpClient({ upstream += it; record(it) }) { uri ->
                        when {
                            uri.contains("/rest/v1/examenes") -> V1StubResponse(200, examenRows)
                            uri.contains("/rest/v1/comunidades_autonomas") -> V1StubResponse(200, """[{"id":"77777777-7777-4777-8777-777777777777"}]""")
                            else -> V1StubResponse(200, "[]")
                        }
                    },
                ),
            )
        }
    }

    private fun examenRowJson(): String =
        """{"id":"$examenId","codigo_examen":"EX-1","especialidad_id":"$especialidadId","titulo":"Examen",""" +
            """"tipo_examen":"rafaga","tipo_acceso":"gratuito","activo":true,"total_preguntas":0}"""

    private fun HttpRequest.bodyText(): String = readRequestBody(this)
}
