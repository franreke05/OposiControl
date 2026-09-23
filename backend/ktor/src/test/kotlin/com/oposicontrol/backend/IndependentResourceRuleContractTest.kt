package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HASHIRA C — verificacion independiente de Q-4, «solo INMERSIVA admite recursos».
 *
 * La regla no tiene ningun test: ningun fichero de `src/test` menciona
 * `QUESTION_TYPE_REJECTS_RESOURCES` ni `enforceImmersiveOnly`. Se fija aqui lo
 * que la regla SI dice hoy, y se atacan dos sitios por donde se escapa.
 *
 * ### Donde esta trazada la linea (revisada con KQ0-8)
 *
 * La regla que se impone es **una pregunta no inmersiva no GANA recursos**, y
 * desde KQ0-8 tiene UNA sola puerta: las rutas de recurso, contra el tipo GUARDADO.
 * El PATCH de pregunta dejaba entrar recursos por su cuerpo —nuevos, validados
 * contra el tipo del propio parche, y existentes, editables sin mirar el tipo— y
 * ahora ignora `recursos[]` entero.
 *
 * Los dos bordes por donde se escapaba siguen fijados: la validacion no puede
 * llegar despues de escribir, y editar un recurso no puede resucitarlo
 * (`updateRecurso` escribia `activo = true` incondicionalmente).
 */
class IndependentResourceRuleContractTest {

    private val preguntaId = "f1f1f1f1-f1f1-4f1f-8f1f-f1f1f1f1f1f1"
    private val recursoId = "f3f3f3f3-f3f3-4f3f-8f3f-f3f3f3f3f3f3"

    // -----------------------------------------------------------------
    // 1 · La regla, por su ÚNICA puerta: las rutas de recurso (KQ0-8)
    // -----------------------------------------------------------------

    /**
     * ESPERADO VERDE. Una pregunta de RAFAGA no puede ganar un recurso nuevo,
     * y **la fila no llega a insertarse**: no basta con el 422.
     *
     * Desde KQ0-8 la regla se comprueba contra el tipo GUARDADO de la pregunta,
     * porque el PATCH de pregunta ya no admite recursos (ver el caso 2).
     */
    @Test fun aRafagaQuestionCannotGainANewResource() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream, tipoGuardado = "rafaga")

        // `texto` y no `audio`: desde KQ4 el material con archivo entra SÓLO por la
        // ruta multipart, y un `audio` por JSON se rechaza antes por otro motivo
        // (`QUESTION_RESOURCE_UPLOAD_REQUIRED`). Lo que se prueba aquí es la regla
        // de tipo de PREGUNTA, así que el recurso tiene que ser uno que sí entra por JSON.
        val error = assertFailsWith<BackendDomainException> {
            gateway.addRecurso(preguntaId, AdminRecursoDto(tipoRecurso = "texto", titulo = "a", contenidoTexto = "t"))
        }

        assertEquals("QUESTION_TYPE_REJECTS_RESOURCES", error.errorCode)
        assertEquals(422, error.status.value)
        assertTrue(
            upstream.none { it.method() == "POST" && it.uri().rawPath.contains("recursos_pregunta") },
            "el recurso se inserto igualmente: " + upstream.describe(),
        )
    }

    /**
     * ESPERADO VERDE — control obligatorio. Sin el, «rechazar todo recurso
     * nuevo» pasaria el caso de arriba y la funcionalidad estaria rota sin que
     * ningun test lo dijera.
     */
    @Test fun anImmersiveQuestionStillGainsItsNewResource() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream, tipoGuardado = "inmersiva")

        gateway.addRecurso(preguntaId, AdminRecursoDto(tipoRecurso = "texto", titulo = "a", contenidoTexto = "t"))

        assertTrue(
            upstream.any { it.method() == "POST" && it.uri().rawPath.contains("recursos_pregunta") },
            "la inmersiva tiene que poder adjuntar: " + upstream.describe(),
        )
    }

    // -----------------------------------------------------------------
    // 2 · El PATCH de pregunta ya no es una puerta de recursos
    // -----------------------------------------------------------------

    /**
     * ESPERADO VERDE desde KQ0-8.
     *
     * Por el PATCH de pregunta entraban recursos NUEVOS validados contra el tipo
     * del propio parche —no contra el guardado— y recursos EXISTENTES editables
     * sin mirar el tipo. Las dos rendijas se cierran igual: el PATCH ignora
     * `recursos[]` entero, así que ni un parche de rafaga con un audio nuevo, ni
     * uno inmersivo con un recurso existente, escriben nada en `recursos_pregunta`.
     */
    @Test fun aQuestionPatchNeverWritesResourcesWhateverItsTypeOrItsList() {
        listOf("rafaga", "inmersiva").forEach { tipo ->
            val upstream = CopyOnWriteArrayList<HttpRequest>()
            val gateway = gatewayWith(upstream, tipoGuardado = "inmersiva")

            gateway.updatePregunta(
                preguntaId,
                Json.parseToJsonElement(
                    """{"tipoPregunta":"$tipo","titulo":"Titulo","recursos":[{"tipoRecurso":"audio","titulo":"nuevo"},""" +
                        """{"id":"$recursoId","tipoRecurso":"texto","titulo":"editado"}]}""",
                ).jsonObject,
            )

            assertTrue(
                upstream.none { it.method() in setOf("POST", "PATCH", "DELETE") && it.uri().rawPath.contains("recursos_pregunta") },
                "un PATCH de pregunta ($tipo) escribió recursos: " + upstream.describe(),
            )
        }
    }

    // -----------------------------------------------------------------
    // 3 · «Editar» no es «resucitar»
    // -----------------------------------------------------------------

    /**
     * ESPERADO VERDE.
     *
     * `updateRecurso` reconstruía la fila entera y fijaba `activo = true` sin
     * condición, y `listRecursos` sólo devuelve `activo=eq.true`: archivar es lo que
     * hace desaparecer un recurso, así que editar uno archivado lo resucitaba. La
     * propiedad mínima, ahora sobre la ruta de edición del recurso: **ninguna
     * edición de contenido puede re-activar un recurso.**
     */
    @Test fun editingAResourceCannotReactivateIt() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream, tipoGuardado = "rafaga")

        gateway.updateRecurso(preguntaId, recursoId, AdminRecursoDto(id = recursoId, tipoRecurso = "texto", titulo = "editado"))

        val escritura = assertNotNull(
            upstream.firstOrNull { it.method() == "PATCH" && it.uri().rawPath.contains("recursos_pregunta") },
            "no salio la edicion del recurso: " + upstream.describe(),
        )
        assertTrue(
            !escritura.bodyText().contains("\"activo\":true"),
            "editar re-activo un recurso: ${escritura.bodyText()}",
        )
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    /**
     * Orden del `when`: `recursos_pregunta` y `opciones_pregunta` ANTES que
     * `preguntas`, porque la segunda es prefijo de las otras dos. [tipoGuardado] es
     * el tipo que la BASE tiene para la pregunta, que es contra el que se impone la
     * regla desde KQ0-8.
     */
    private fun gatewayWith(upstream: MutableList<HttpRequest>, tipoGuardado: String): AdminV1Gateway =
        AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ upstream += it }) { uri ->
                when {
                    uri.contains("/rest/v1/recursos_pregunta") -> V1StubResponse(
                        200,
                        """[{"id":"$recursoId","pregunta_id":"$preguntaId","codigo_recurso":"R-1",""" +
                            """"tipo_recurso":"texto","posicion":1,"activo":true}]""",
                    )
                    uri.contains("/rest/v1/opciones_pregunta") -> V1StubResponse(200, "[]")
                    uri.contains("/rest/v1/soluciones_pregunta") -> V1StubResponse(200, "[]")
                    uri.contains("/rest/v1/preguntas") -> V1StubResponse(
                        200,
                        """[{"id":"$preguntaId","codigo_pregunta":"PREG-000001",""" +
                            """"tipo_pregunta":"$tipoGuardado","titulo":"Titulo","activa":true}]""",
                    )
                    else -> V1StubResponse(200, "[]")
                }
            },
        )

    private fun List<HttpRequest>.describe(): String = describeRequests(this)

    private fun HttpRequest.bodyText(): String = readRequestBody(this)
}
