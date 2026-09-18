package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Las tres capacidades de Preguntas de la FASE 4, que no tenian ningun test.
 *
 * - **Q-2** `?activa=true|false|todas`: el banco tenia un filtro «Inactivas» que
 *   no podia devolver nada nunca, porque el servidor filtraba `activa = true`
 *   antes de mirar el filtro.
 * - **Q-3** un PATCH que no toca ninguna fila no puede responder exito.
 * - **Q-4** una pregunta no inmersiva no GANA recursos — desde KQ0-8 por su ÚNICA
 *   puerta, las rutas de recurso, y con el borde por donde se escapaba: desarchivar
 *   al editar.
 * - **KQ1 / QN-17** lo que el PATCH de pregunta ya no escribe: el código generado
 *   por la base, una fuente que no viaja y una dificultad vacía.
 *
 * Como en los ficheros hermanos, lo que se afirma es lo que sale hacia Supabase.
 * Un test que mirase solo el 422 daria por bueno un rechazo que ya hubiera
 * escrito media pregunta, que es exactamente el defecto que Q-4 tenia.
 */
class AdminV1QuestionCapabilitiesTest {

    private val preguntaId = "77777777-7777-4777-8777-777777777777"
    private val especialidadId = "88888888-8888-4888-8888-888888888888"
    private val recursoId = "99999999-9999-4999-8999-999999999999"
    private val examenId = "12121212-1212-4212-8212-121212121212"

    // -----------------------------------------------------------------
    // Q-2 · el filtro de estado
    // -----------------------------------------------------------------

    /**
     * Los tres valores producen literales fijos en la consulta. Importa que sean
     * LITERALES y no la cadena del cliente codificada: asi este parametro no
     * anade superficie de truncado a una tabla de alto volumen.
     */
    @Test fun elFiltroDeEstadoProduceLiteralesFijos() {
        val activas = mutableListOf<HttpRequest>()
        gatewayWith(activas).listPreguntasPagina(AdminPreguntaListQuery(activa = true))
        val inactivas = mutableListOf<HttpRequest>()
        gatewayWith(inactivas).listPreguntasPagina(AdminPreguntaListQuery(activa = false))
        val todas = mutableListOf<HttpRequest>()
        gatewayWith(todas).listPreguntasPagina(AdminPreguntaListQuery(activa = null))

        assertTrue(activas.single().uri().rawQuery.orEmpty().contains("activa=eq.true"), activas.describe())
        assertTrue(inactivas.single().uri().rawQuery.orEmpty().contains("activa=eq.false"), inactivas.describe())
        assertTrue(
            !todas.single().uri().rawQuery.orEmpty().contains("activa="),
            "«todas» no es un tercer valor del filtro: es NO filtrar. " + todas.describe(),
        )
    }

    /** `?activa=todas` llega hasta la consulta como ausencia de filtro. */
    @Test fun laRutaPideTodasSinFiltrarPorEstado() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { questionsTestModule(upstream) }

        val response = client.get("/api/v1/admin/preguntas?activa=todas")

        assertEquals(HttpStatusCode.OK, response.status)
        val lectura = assertNotNull(upstream.firstOrNull { it.uri().rawPath.endsWith("/rest/v1/preguntas") })
        assertTrue(!lectura.uri().rawQuery.orEmpty().contains("activa="), lectura.uri().toString())
    }

    /**
     * Un valor fuera del vocabulario es un 400 **y no sale ni una peticion**. La
     * alternativa —dejarlo pasar codificado— haria del parametro una via de
     * lectura sin filtro sobre el banco entero.
     */
    @Test fun unEstadoDesconocidoSeRechazaAntesDeConsultar() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { questionsTestModule(upstream) }

        val response = client.get("/api/v1/admin/preguntas?activa=todas-menos-las-mias")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "no debia salir ninguna consulta: " + upstream.describe())
    }

    // -----------------------------------------------------------------
    // Q-3 · un PATCH sobre cero filas no es exito
    // -----------------------------------------------------------------

    /**
     * Editar solo la explicacion de una pregunta que no tiene fila en
     * `soluciones_pregunta` devolvia 200 sin haber escrito nada: el editor
     * pintaba «guardado» y el texto se perdia. Un exito falso sobre una ESCRITURA
     * no tiene reintento posible, porque nadie sabe que hay algo que reintentar.
     *
     * Desde KQ2-2 la decisión es de la RPC —que es quien puede mirarlo dentro de la
     * transacción— y Kotlin sólo tiene que dejar que su código de dominio llegue
     * entero al cliente, sin convertirlo en un 500.
     */
    @Test fun editarSoloLaExplicacionSobreCeroFilasNoRespondeExito() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream, updateRpcFailure = "QUESTION_SOLUTION_MISSING")

        val error = assertFailsWith<BackendDomainException> {
            gateway.updatePregunta(preguntaId, cambios("""{"razonamiento":"Articulo 82"}"""))
        }

        assertEquals("QUESTION_SOLUTION_MISSING", error.errorCode)
        assertEquals(422, error.status.value)
    }

    /** Control: la edición normal es UNA llamada a la RPC con la explicación dentro. */
    @Test fun editarSoloLaExplicacionEsUnaSolaLlamadaALaRpc() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updatePregunta(preguntaId, cambios("""{"razonamiento":"Articulo 82"}"""))

        val rpc = upstream.filter { it.uri().rawPath.endsWith("/rpc/admin_update_question") }
        assertEquals(1, rpc.size, "la edición tiene que ser UNA transacción: " + upstream.describe())
        val cuerpo = rpc.single().bodyText()
        assertTrue(cuerpo.contains("\"p_pregunta_id\":\"$preguntaId\""), cuerpo)
        assertTrue(cuerpo.contains("\"razonamiento\":\"Articulo 82\""), cuerpo)
        assertTrue(
            upstream.none { it.method() == "PATCH" },
            "la edición ya no escribe tabla a tabla: " + upstream.describe(),
        )
    }

    // -----------------------------------------------------------------
    // Q-4 · una pregunta no inmersiva no GANA recursos — por sus rutas (KQ0-8)
    // -----------------------------------------------------------------

    /**
     * **KQ0-8: el PATCH de pregunta ignora `recursos[]` entero.** Ni añade, ni
     * edita, ni rechaza: los recursos tienen sus propias rutas, que miran el tipo
     * GUARDADO. Por el cuerpo de la pregunta entraban un id de recurso ajeno en un
     * filtro y la URL firmada del editor de Exámenes persistida como `url_externa`.
     */
    @Test fun elPatchDePreguntaNoTocaRecursosNiSiquieraSiLosTrae() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updatePregunta(
            preguntaId,
            cambios(
                """{"tipoPregunta":"rafaga","recursos":[{"tipoRecurso":"audio","titulo":"nuevo"},""" +
                    """{"id":"$recursoId","tipoRecurso":"texto","titulo":"editado"}]}""",
            ),
        )

        assertTrue(
            upstream.none { it.method() in setOf("POST", "PATCH", "DELETE") && it.uri().rawPath.contains("recursos_pregunta") },
            "el PATCH de pregunta escribió recursos: " + upstream.describe(),
        )
        val cuerpo = upstream.single { it.uri().rawPath.endsWith("/rpc/admin_update_question") }.bodyText()
        assertTrue(!cuerpo.contains("recursos"), "los recursos viajaron dentro de p_cambios: $cuerpo")
    }

    /**
     * D-03/D-04: el PATCH **ya no vincula**. `examenId` y `posicion` del cuerpo se
     * ignoran; la única puerta es `POST /preguntas/{id}/examenes`.
     *
     * Vincular desde aquí obligaba a reenviar el borrador entero —guardando ediciones
     * que nadie había confirmado— y escribía la relación con un `upsert` sin cerrojo.
     */
    @Test fun elPatchDePreguntaYaNoVincula() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updatePregunta(preguntaId, cambios("""{"titulo":"T","examenId":"$examenId","posicion":3}"""))

        assertTrue(
            upstream.none { it.uri().rawPath.contains("preguntas_examen") || it.uri().rawPath.contains("admin_link_question_to_exam") },
            "el PATCH vinculó: " + upstream.describe(),
        )
        val cuerpo = upstream.single { it.uri().rawPath.endsWith("/rpc/admin_update_question") }.bodyText()
        assertTrue(!cuerpo.contains("examen") && !cuerpo.contains("posicion"), cuerpo)
    }

    /** La regla, por la ruta de recursos: afirmada sobre la ausencia del INSERT y no sobre el 422. */
    @Test fun unaPreguntaDeRafagaNoGanaUnRecursoNuevo() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream, preguntaRows = preguntaRow(tipo = "rafaga"))

        val error = assertFailsWith<BackendDomainException> {
            gateway.addRecurso(preguntaId, AdminRecursoDto(tipoRecurso = "texto", titulo = "a", contenidoTexto = "t"))
        }

        assertEquals("QUESTION_TYPE_REJECTS_RESOURCES", error.errorCode)
        assertTrue(
            upstream.none { it.method() == "POST" && it.uri().rawPath.contains("recursos_pregunta") },
            "el recurso se inserto igualmente: " + upstream.describe(),
        )
    }

    /** Control obligatorio: sin el, «rechazar todo recurso» pasaria el anterior. */
    @Test fun unaPreguntaInmersivaSiGanaSuRecurso() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream, preguntaRows = preguntaRow(tipo = "inmersiva"))

        gateway.addRecurso(preguntaId, AdminRecursoDto(tipoRecurso = "texto", titulo = "a", contenidoTexto = "t"))

        assertTrue(
            upstream.any { it.method() == "POST" && it.uri().rawPath.contains("recursos_pregunta") },
            "la inmersiva tiene que poder adjuntar: " + upstream.describe(),
        )
    }

    /**
     * **Editar no es desarchivar.**
     *
     * `updateRecurso` reconstruia la fila entera con `activo = true`, y como
     * `listRecursos` solo lee `activo=eq.true`, archivar es lo que hace
     * desaparecer un recurso: editar uno archivado lo devolvia a la vista.
     *
     * Se afirma sobre la columna y no sobre el valor: un PATCH de contenido no
     * tiene por que decir nada de `activo`, ni `true` ni `false`.
     */
    @Test fun editarUnRecursoNoLoDesarchiva() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updateRecurso(preguntaId, recursoId, AdminRecursoDto(id = recursoId, tipoRecurso = "texto", titulo = "editado"))

        val escritura = assertNotNull(
            upstream.firstOrNull { it.method() == "PATCH" && it.uri().rawPath.contains("recursos_pregunta") },
            "no salio la edicion del recurso: " + upstream.describe(),
        )
        assertTrue(
            !escritura.bodyText().contains("\"activo\""),
            "un PATCH de contenido no decide el archivado: ${escritura.bodyText()}",
        )
    }

    /**
     * Un PATCH de recurso tampoco puede reescribir lo que no se esta editando:
     * `codigo_recurso` se fabricaba desde el id y sobrescribia el codigo real en
     * cada guardado, y `metadatos` se vaciaba.
     */
    @Test fun editarUnRecursoNoReescribeSuCodigoNiSusMetadatos() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updateRecurso(preguntaId, recursoId, AdminRecursoDto(id = recursoId, tipoRecurso = "texto", titulo = "editado"))

        val cuerpo = assertNotNull(
            upstream.firstOrNull { it.method() == "PATCH" && it.uri().rawPath.contains("recursos_pregunta") },
        ).bodyText()
        assertTrue(!cuerpo.contains("codigo_recurso"), cuerpo)
        assertTrue(!cuerpo.contains("metadatos"), cuerpo)
        assertTrue(cuerpo.contains("\"titulo\":\"editado\""), cuerpo)
    }

    // -----------------------------------------------------------------
    // KQ1 · QN-17 · lo que el PATCH de pregunta ya no escribe
    // -----------------------------------------------------------------

    /**
     * KQ1: `codigo_pregunta` lo genera la base (Q1, `PREG-000001`) y es inmutable.
     * Ni el PATCH de la fila ni el alta lo mandan: fabricarlo en Kotlin
     * (`"P-" + shortId()`) y reescribirlo desde el cuerpo era QN-06.
     */
    @Test fun niElPatchNiElAltaEnvianElCodigoDePregunta() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updatePregunta(preguntaId, cambios("""{"titulo":"T","codigoPregunta":"P-INVENTADO"}"""))
        runCatching {
            gateway.createPregunta(
                preguntaDto().copy(
                    id = "",
                    codigoPregunta = "",
                    opciones = listOf(AdminOpcionDto(letra = "A", texto = "a", esCorrecta = true)),
                ),
            )
        }

        val cuerpos = upstream.filter { it.method() in setOf("POST", "PATCH") }.map { it.bodyText() }
        assertTrue(cuerpos.isNotEmpty(), "no salió ninguna escritura: " + upstream.describe())
        cuerpos.forEach { cuerpo ->
            assertTrue(!cuerpo.contains("codigo_pregunta"), "se escribió el código de pregunta: $cuerpo")
            assertTrue(!cuerpo.contains("P-INVENTADO") && !cuerpo.contains("\"P-"), "se fabricó un código: $cuerpo")
        }
    }

    /**
     * Q2-4: el alta manda como autor el `sub` del administrador autenticado —un id
     * que existe en `auth.users`— y NADA cuando no hay persona (token estático). Un
     * autor inventado sería un 23503 en la base.
     */
    @Test fun elAltaMandaComoAutorElSubDelJwtYNadaSinPersona() {
        val autor = "abababab-abab-4bab-8bab-abababababab"
        val conPersona = mutableListOf<HttpRequest>()
        val sinPersona = mutableListOf<HttpRequest>()
        val alta = preguntaDto().copy(id = "", opciones = listOf(AdminOpcionDto(letra = "A", texto = "a", esCorrecta = true)))

        runCatching { gatewayWith(conPersona).createPregunta(alta, actorId = autor) }
        runCatching { gatewayWith(sinPersona).createPregunta(alta, actorId = null) }

        val rpcConPersona = readRequestBody(conPersona.single { it.uri().rawPath.endsWith("/rpc/admin_create_question") })
        val rpcSinPersona = readRequestBody(sinPersona.single { it.uri().rawPath.endsWith("/rpc/admin_create_question") })
        assertTrue(rpcConPersona.contains("\"p_creado_por\":\"$autor\""), rpcConPersona)
        assertTrue(!rpcSinPersona.contains("p_creado_por"), rpcSinPersona)
    }

    /**
     * QN-17: el editor de Exámenes no edita la fuente, así que no la manda; y
     * `fuenteTexto` ausente NO es «vacíala» sino «no la toques». Antes el editor
     * mandaba el enunciado como fuente y el PATCH la reescribía en cada guardado.
     * La dificultad vacía tampoco se escribe: la columna sólo admite
     * fácil/media/difícil o NULL.
     */
    @Test fun unaFuenteAusenteYUnaDificultadVaciaNoSeEscriben() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updatePregunta(preguntaId, cambios("""{"titulo":"Titulo"}"""))

        val cuerpo = upstream.single { it.uri().rawPath.endsWith("/rpc/admin_update_question") }.bodyText()
        assertTrue(!cuerpo.contains("fuente_texto"), cuerpo)
        assertTrue(!cuerpo.contains("dificultad"), cuerpo)
    }

    /**
     * Control: quien SÍ edita la fuente (el banco de Preguntas) la guarda, vacía
     * incluida — y un `null` explícito también viaja, porque «vacía la fuente» es una
     * edición legítima que `explicitNulls = false` se habría comido.
     */
    @Test fun unaFuenteQueViajaSeEscribeAunqueSeaVaciaONula() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updatePregunta(preguntaId, cambios("""{"fuenteTexto":""}"""))
        gateway.updatePregunta(preguntaId, cambios("""{"fuenteTexto":null}"""))

        val cuerpos = upstream.filter { it.uri().rawPath.endsWith("/rpc/admin_update_question") }.map { it.bodyText() }
        assertEquals(2, cuerpos.size, upstream.describe())
        assertTrue(cuerpos[0].contains("\"fuente_texto\":\"\""), cuerpos[0])
        assertTrue(cuerpos[1].contains("\"fuente_texto\":null"), cuerpos[1])
    }

    // -----------------------------------------------------------------
    // «No existe» es un 404, no un 500
    // -----------------------------------------------------------------

    /**
     * Misma correccion que E-2 hizo en los examenes. Se alcanza sin malicia:
     * guardar una pestana abierta sobre una pregunta que otro administrador
     * archivo. Un `IllegalStateException` sale como «Error al consultar el
     * servicio de datos», indistinguible de un Supabase caido.
     */
    @Test fun guardarUnaPreguntaQueYaNoExisteEsUn404YNoEscribeNada() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream, updateRpcFailure = "QUESTION_NOT_FOUND")

        val error = assertFailsWith<BackendDomainException> {
            gateway.updatePregunta(preguntaId, cambios("""{"titulo":"Titulo"}"""))
        }

        assertEquals("QUESTION_NOT_FOUND", error.errorCode)
        assertEquals(404, error.status.value)
        assertTrue(
            upstream.none { it.method() == "PATCH" },
            "no debia escribirse nada: " + upstream.describe(),
        )
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    /** El cuerpo CRUDO del PATCH, que es lo que la ruta recibe desde KQ2-2. */
    private fun cambios(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun preguntaDto(
        tipo: String = "rafaga",
        razonamiento: String = "",
        recursos: List<AdminRecursoDto> = emptyList(),
    ) = AdminPreguntaDto(
        id = preguntaId,
        codigoPregunta = "PREG-000001",
        especialidadId = especialidadId,
        tipoPregunta = tipo,
        titulo = "Titulo",
        razonamiento = razonamiento,
        recursos = recursos,
    )

    /**
     * Orden del `when`: `recursos_pregunta`, `opciones_pregunta` y
     * `soluciones_pregunta` ANTES que `preguntas`, que es prefijo de las tres.
     *
     * La rama de `soluciones_pregunta` distingue la LECTURA de la ESCRITURA por
     * el `select=` de la consulta: sin esa distincion, el control de Q-3 no
     * podria devolver fila donde el caso negativo devuelve vacio.
     */
    private fun preguntaRow(tipo: String): String =
        """[{"id":"$preguntaId","codigo_pregunta":"PREG-000001","especialidad_id":"$especialidadId",""" +
            """"tipo_pregunta":"$tipo","titulo":"Titulo","activa":true}]"""

    /**
     * `preguntas` responde con `Content-Range`: el listado del banco es una página
     * con su total (KQ0-3), y sin cabecera el transporte se niega a inventar un 0.
     */
    private fun gatewayWith(
        upstream: MutableList<HttpRequest>,
        preguntaRows: String = preguntaRow(tipo = "inmersiva"),
        solucionPatchRows: String = """[{"razonamiento":""}]""",
        /** Código de dominio que levanta `admin_update_question`, como lo emitiría `raise`. */
        updateRpcFailure: String? = null,
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rpc/admin_update_question") && updateRpcFailure != null -> V1StubResponse(
                    400,
                    """{"code":"P0001","details":null,"hint":null,"message":"$updateRpcFailure"}""",
                )
                uri.contains("/rpc/admin_update_question") -> V1StubResponse(200, "")
                uri.contains("/rpc/admin_create_question") -> V1StubResponse(
                    200,
                    """{"pregunta_id":"$preguntaId","codigo_pregunta":"PREG-000001","creada":true,"posicion_asignada":null}""",
                )
                uri.contains("/rest/v1/recursos_pregunta") -> V1StubResponse(
                    200,
                    """[{"id":"$recursoId","pregunta_id":"$preguntaId","codigo_recurso":"R-1",""" +
                        """"tipo_recurso":"texto","posicion":1,"activo":true}]""",
                )
                uri.contains("/rest/v1/opciones_pregunta") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/soluciones_pregunta") ->
                    if (uri.contains("select=")) V1StubResponse(200, "[]") else V1StubResponse(200, solucionPatchRows)
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/preguntas") -> V1StubResponse(200, preguntaRows, contentRange = "0-0/1")
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun Application.questionsTestModule(upstream: MutableList<HttpRequest>) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(error = "bad_request", message = cause.message ?: "Invalid request"),
                )
            }
        }
        routing { adminV1Routes(gatewayWith(upstream)) }
    }

    private fun List<HttpRequest>.describe(): String =
        "(${size}) " + joinToString(", ") { "${it.method()} ${it.uri().rawPath}?${it.uri().rawQuery.orEmpty()}" }

    private fun HttpRequest.bodyText(): String = readRequestBody(this)
}
