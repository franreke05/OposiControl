package com.oposicontrol.backend

import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
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
 * HASHIRA C — verificacion independiente de D-3.
 *
 * `AdminV1FilterTruncationValidationTest` y `AdminV1ExamUnlinkValidationTest`
 * cubren los identificadores que llegan por el **path**. Este fichero cubre los
 * que llegan por el **cuerpo**, que es el hueco que queda abierto: la
 * validacion se puso en la ruta, y hay llamadores del gateway que no pasan por
 * esa ruta.
 *
 * El KDoc de `DELETE /examenes/{id}/preguntas/{preguntaId}` dice literalmente:
 *
 * > «Se valida en la RUTA y no en el gateway a proposito: aqui es donde el
 * > valor deja de ser del cliente, y asi la validacion no se puede saltar por
 * > anadir otro llamador del gateway.»
 *
 * Ese otro llamador **ya existe**, y es anterior al arreglo:
 * `AdminV1Gateway.updatePregunta`, servido por
 * `PATCH /api/v1/admin/preguntas/{id}`, toma DOS identificadores del cuerpo y
 * los interpola sin validar:
 *
 * | Campo del cuerpo | A donde va | Filtro que compone |
 * |---|---|---|
 * | `recursos[].id` | `updateRecurso(preguntaId, recursoId, …)` | `?id=eq.$recursoId&pregunta_id=eq.$preguntaId` |
 * | `examenId` | `upsertPreguntaExamen(examenId, preguntaId, …)` | `?examen_id=eq.$examenId&pregunta_id=eq.$preguntaId` |
 *
 * Los dos ponen el identificador del cliente en PRIMERA posicion, que es
 * exactamente la forma que describe D-3: un `#` no estrecha la consulta, borra
 * el filtro que viene detras — y el que viene detras es el de PERTENENCIA.
 *
 * Lo que se afirma aqui NO es un codigo de estado: es que **ninguna escritura
 * sale hacia Supabase sin su filtro de pertenencia**. Es la unica forma de la
 * asercion que no puede pasar por accidente si alguien valida tarde.
 */
class IndependentBodyIdentifierTruncationTest {

    private val preguntaId = "11111111-1111-4111-8111-111111111111"
    private val examenId = "33333333-3333-4333-8333-333333333333"
    private val recursoId = "44444444-4444-4444-8444-444444444444"

    // -----------------------------------------------------------------
    // recursos[].id  ->  updateRecurso
    // -----------------------------------------------------------------

    /**
     * ESPERADO VERDE. Cerrado primero validando `recursos[].id` y, desde KQ0-8, de
     * raíz: el PATCH de pregunta ignora `recursos[]`, así que ningún identificador
     * de esa lista llega a ningún filtro.
     *
     * Escenario de fallo real que fija: el admin edita la pregunta A y manda
     * `recursos[0].id = "<uuid-de-un-recurso-de-la-pregunta-B>#"`. El PATCH
     * llegaba a Supabase como `?id=eq.<uuid-de-B>` —sin `pregunta_id`— y
     * `updateRecurso` **reescribía la fila entera, incluido `pregunta_id`**: el
     * recurso dejaba de pertenecer a B y pasaba a A, con un 200.
     */
    @Test fun aFragmentInTheResourceIdOfTheQuestionBodyMustNotReachSupabase() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { truncationModule(upstream) }

        client.patch("/api/v1/admin/preguntas/$preguntaId") {
            contentType(ContentType.Application.Json)
            setBody(questionBody(recursoIdInBody = "$recursoId#"))
        }

        assertNoOwnerlessWrite(upstream, table = "recursos_pregunta", ownershipFilter = "pregunta_id=eq.")
    }

    // -----------------------------------------------------------------
    // examenId  ->  upsertPreguntaExamen
    // -----------------------------------------------------------------

    /**
     * ESPERADO ROJO mientras `updatePregunta` no valide `examenId`.
     *
     * Escenario de fallo real: `upsertPreguntaExamen` primero LEE
     * `?select=*&examen_id=eq.$examenId&pregunta_id=eq.$preguntaId&limit=1`.
     * Con un `#`, esa lectura se convierte en «la primera fila cualquiera de
     * ese examen», asi que `existing != null` casi siempre; y entonces escribe
     * `?examen_id=eq.<uuid>` con `{"posicion":N}`: **todas las preguntas del
     * examen acaban en la misma posicion**. Es el mismo dano que se arreglo en
     * `PATCH /examenes/{id}/preguntas/orden`, por otra puerta.
     */
    @Test fun aFragmentInTheExamIdOfTheQuestionBodyMustNotReachSupabase() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { truncationModule(upstream) }

        client.patch("/api/v1/admin/preguntas/$preguntaId") {
            contentType(ContentType.Application.Json)
            setBody(questionBody(examenIdInBody = "$examenId#"))
        }

        assertNoOwnerlessWrite(upstream, table = "preguntas_examen", ownershipFilter = "pregunta_id=eq.")
    }

    // -----------------------------------------------------------------
    // Control: sin el, "rechazarlo todo" pasaria los dos casos de arriba
    // -----------------------------------------------------------------

    /**
     * ESPERADO VERDE. Demuestra que el doble de upstream de este fichero llega de
     * verdad a la escritura bajo prueba —hoy, la RPC de edición—: si este caso se
     * pusiera rojo, los de arriba estarían diciendo algo sobre un stub mal montado y
     * no sobre el defecto.
     *
     * Y fija dos cierres de raíz por el camino feliz: con un `recursos[].id` VÁLIDO
     * no sale ninguna escritura a `recursos_pregunta` (KQ0-8), y con un `examenId`
     * VÁLIDO tampoco sale ninguna a `preguntas_examen` (D-03: el PATCH ya no
     * vincula; la única puerta es `POST /preguntas/{id}/examenes`). Los dos
     * identificadores del cuerpo que este fichero vigilaba ya no llegan a ningún
     * filtro porque ya no llegan a ninguna parte.
     */
    @Test fun validBodyIdentifiersProduceOnlyTheUpdateRpcAndNoLinkOrResourceWrite() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { truncationModule(upstream) }

        val response = client.patch("/api/v1/admin/preguntas/$preguntaId") {
            contentType(ContentType.Application.Json)
            setBody(questionBody(recursoIdInBody = recursoId, examenIdInBody = examenId))
        }

        assertEquals(HttpStatusCode.OK, response.status, "el camino feliz tiene que seguir respondiendo 200")

        assertTrue(
            upstream.none { it.method() in setOf("POST", "PATCH", "DELETE") && it.uri().rawPath.contains("recursos_pregunta") },
            "el PATCH de pregunta escribió recursos: ${upstream.describe()}",
        )
        assertTrue(
            upstream.none { it.uri().rawPath.contains("preguntas_examen") || it.uri().rawPath.contains("admin_link_question_to_exam") },
            "el PATCH de pregunta vinculó: ${upstream.describe()}",
        )
        val rpc = assertNotNull(
            upstream.singleOrNull { it.uri().rawPath.endsWith("/rpc/admin_update_question") },
            "la edición tiene que llegar a la RPC: ${upstream.describe()}",
        )
        assertTrue(readRequestBody(rpc).contains("\"p_pregunta_id\":\"$preguntaId\""), readRequestBody(rpc))
    }

    // -----------------------------------------------------------------

    /**
     * La asercion que importa: de todas las escrituras que salieron hacia
     * [table], ninguna puede haber perdido su filtro de pertenencia.
     *
     * Se mira `rawQuery` y no `toString()` a proposito: `URI` deja el `#` y
     * todo lo que viene detras en el FRAGMENTO, que no viaja en el
     * request-target. Mirar la cadena completa veria un filtro que Supabase
     * nunca recibe — que es justamente la ilusion que causa el defecto.
     */
    private fun assertNoOwnerlessWrite(
        upstream: List<HttpRequest>,
        table: String,
        ownershipFilter: String,
    ) {
        val ownerless = upstream.filter {
            it.method() in setOf("PATCH", "DELETE") &&
                it.uri().rawPath.contains(table) &&
                !it.uri().rawQuery.orEmpty().contains(ownershipFilter)
        }
        assertTrue(
            ownerless.isEmpty(),
            "salio una escritura a $table SIN su filtro de pertenencia ('$ownershipFilter'). El " +
                "identificador venia del CUERPO, que ninguna ruta valida. Peticiones: ${ownerless.describe()}",
        )
    }

    private fun List<HttpRequest>.describe(): String =
        "(${size}) " + joinToString(", ") { "${it.method()} ${it.uri().rawPath}?${it.uri().rawQuery.orEmpty()}" }

    /**
     * `tipoPregunta` es **inmersiva** a proposito, y la eleccion no es
     * cosmetica.
     *
     * Este fichero prueba TRUNCADO DE FILTROS y nada mas. La primera version
     * mandaba `"rafaga"`, y eso acoplaba sin querer estos casos a una decision
     * de OTRA fase: Q-4 («solo inmersiva admite recursos») tuvo que decidir si
     * la regla gobierna el ATTACH o tambien la EDICION de lo ya adjunto, y con
     * `"rafaga"` + un recurso adjunto el control de aqui abajo solo pasaba con
     * una de las dos respuestas. Un test de truncado no puede ser quien decida
     * eso.
     *
     * Con `"inmersiva"` los tres casos pasan bajo CUALQUIERA de las dos
     * lecturas de Q-4, asi que vuelven a hablar solo de lo suyo. La regla de
     * recursos se fija donde le toca: `IndependentResourceRuleContractTest`.
     */
    private fun questionBody(
        recursoIdInBody: String? = null,
        examenIdInBody: String? = null,
    ): String {
        val recursos = recursoIdInBody
            ?.let { ""","recursos":[{"id":"$it","tipoRecurso":"texto","titulo":"r","posicion":1}]""" }
            .orEmpty()
        val examen = examenIdInBody?.let { ""","examenId":"$it","posicion":3""" }.orEmpty()
        return """{"id":"$preguntaId","codigoPregunta":"P-1",""" +
            """"tipoPregunta":"inmersiva","titulo":"Titulo"$examen$recursos}"""
    }

    private fun Application.truncationModule(upstream: MutableList<HttpRequest>) {
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
        routing { adminV1Routes(gatewayRecording(upstream)) }
    }

    /**
     * El doble responde lo minimo para que `updatePregunta` recorra su camino
     * completo: la RPC confirma y la pregunta se relee. Las ramas de
     * `preguntas_examen` y `recursos_pregunta` se conservan a propósito: si alguna
     * escritura volviera a salir hacia ellas, este doble la aceptaría y las
     * aserciones de arriba la verían.
     *
     * El orden de las ramas importa: `preguntas_examen` se comprueba ANTES que
     * `preguntas`, porque la segunda es prefijo de la primera.
     */
    private fun gatewayRecording(upstream: MutableList<HttpRequest>): AdminV1Gateway =
        AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ upstream += it }) { uri ->
                when {
                    uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(
                        200,
                        """[{"examen_id":"$examenId","pregunta_id":"$preguntaId","posicion":1,"numero_en_examen":1}]""",
                    )
                    uri.contains("/rest/v1/recursos_pregunta") -> V1StubResponse(
                        200,
                        """[{"id":"$recursoId","pregunta_id":"$preguntaId","codigo_recurso":"R-1",""" +
                            """"tipo_recurso":"texto","posicion":1,"activo":true}]""",
                    )
                    uri.contains("/rest/v1/preguntas") -> V1StubResponse(
                        200,
                        """[{"id":"$preguntaId","codigo_pregunta":"P-1",""" +
                            """"tipo_pregunta":"rafaga","titulo":"Titulo","activa":true}]""",
                    )
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
}
