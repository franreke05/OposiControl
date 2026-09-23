package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El CRUD de `/content/noticias` a través de HTTP real.
 *
 * Hasta ahora la vertical de Noticias se apoyaba en tests de unidad del gateway
 * y en un smoke manual contra PostgREST. Faltaba justo el tramo de en medio: lo
 * que hace Ktor entre la petición y el gateway —autenticación, negociación de
 * contenido, códigos de estado, `StatusPages`— no lo cubría nadie.
 *
 * **La autenticación es un STUB.** No se fabrica un JWT de producción ni se
 * habilita ningún bypass: se instala un proveedor `bearer` propio del test que
 * acepta un token fijo. Lo que se prueba es que la ruta EXIGE autenticación y
 * que, una vez dentro, se comporta; no la validación criptográfica del JWT de
 * Supabase, que es de `SupabaseJwtVerifier` y tiene sus propias pruebas.
 *
 * El gateway es [InMemoryNewsGateway], el que ya existe en producción para el
 * arranque sin Supabase. Así se ejercita el contrato de verdad y no un doble
 * escrito para la ocasión.
 */
class NewsContentRoutesTest {

    private val token = "stub-token-de-prueba"
    private val especialidad = "3c4a1b2d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    private val tipo = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"

    // -----------------------------------------------------------------------
    // La puerta
    // -----------------------------------------------------------------------

    @Test
    fun `sin token ninguna ruta de noticias responde`() = newsApp {
        listOf(
            "GET" to client.get("/content/noticias"),
            "GET_ID" to client.get("/content/noticias/$NOTICIA"),
        ).forEach { (nombre, respuesta) ->
            assertEquals(HttpStatusCode.Unauthorized, respuesta.status, nombre)
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/content/noticias") { contentType(ContentType.Application.Json); setBody("{}") }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/content/noticias/$NOTICIA").status)
    }

    @Test
    fun `un token que no es el esperado tampoco entra`() = newsApp {
        val respuesta = client.get("/content/noticias") {
            header(HttpHeaders.Authorization, "Bearer token-inventado")
        }
        assertEquals(HttpStatusCode.Unauthorized, respuesta.status)
    }

    // -----------------------------------------------------------------------
    // El ciclo completo: crear → releer → editar → publicar → borrar
    // -----------------------------------------------------------------------

    @Test
    fun `el ciclo completo de una noticia sobrevive el viaje por HTTP`() = newsApp {
        // 1 · La biblioteca arranca vacía, y vacía NO es un error.
        val inicial = client.get("/content/noticias") { auth() }
        assertEquals(HttpStatusCode.OK, inicial.status)
        assertEquals("[]", inicial.bodyAsText().trim())

        // 2 · Crear. Responde 201, no 200: la noticia no existía.
        val creada = client.post("/content/noticias") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(noticiaJson(titulo = "Simulacro Andalucía 2026"))
        }
        assertEquals(HttpStatusCode.Created, creada.status)
        val id = creada.bodyAsText().idDelCuerpo()
        assertNotNull(id, "la respuesta de alta debe traer el id asignado: ${creada.bodyAsText()}")

        // 3 · Releer UNA, sin arrastrar la biblioteca entera detrás.
        val releida = client.get("/content/noticias/$id") { auth() }
        assertEquals(HttpStatusCode.OK, releida.status)
        val cuerpo = releida.bodyAsText()
        assertTrue(cuerpo.contains("Simulacro Andalucía 2026"), cuerpo)
        // El frame de la capa es lo que edita la pantalla: si no vuelve igual,
        // mover un título no sirve de nada.
        assertTrue(cuerpo.contains("\"x\":48"), "la x de la capa no sobrevivió: $cuerpo")
        assertTrue(cuerpo.contains("\"width\":360"), "el ancho de la capa no sobrevivió: $cuerpo")
        assertTrue(cuerpo.contains("\"fontSizeSp\":42"), "la tipografía no sobrevivió: $cuerpo")

        // 4 · Editar por PUT con el MISMO id: actualiza, no duplica.
        val editada = client.put("/content/noticias") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(noticiaJson(titulo = "Simulacro Andalucía 2026 · revisado", id = id))
        }
        assertEquals(HttpStatusCode.OK, editada.status)

        val listaTrasEditar = client.get("/content/noticias") { auth() }.bodyAsText()
        assertEquals(
            // `titulo` solo lo tiene la noticia: la capa usa `nombre`, así que
            // contar ids habría contado también los de las capas.
            1,
            Regex("\"titulo\":").findAll(listaTrasEditar).count(),
            "editar creó una segunda noticia en vez de actualizar la existente: $listaTrasEditar",
        )
        assertTrue(listaTrasEditar.contains("revisado"), listaTrasEditar)

        // 5 · Publicar es un estado del propio recurso, no una ruta aparte.
        val publicada = client.put("/content/noticias") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(noticiaJson(titulo = "Simulacro Andalucía 2026 · revisado", id = id, estado = "published"))
        }
        assertEquals(HttpStatusCode.OK, publicada.status)
        assertTrue(publicada.bodyAsText().contains("\"estado\":\"published\""), publicada.bodyAsText())

        // 6 · Borrar: 204, sin cuerpo.
        val borrada = client.delete("/content/noticias/$id") { auth() }
        assertEquals(HttpStatusCode.NoContent, borrada.status)

        // 7 · Y la biblioteca vuelve a estar como al principio.
        assertEquals("[]", client.get("/content/noticias") { auth() }.bodyAsText().trim())
    }

    // -----------------------------------------------------------------------
    // Lo que la ruta rechaza
    // -----------------------------------------------------------------------

    @Test
    fun `una noticia sin titulo se rechaza con su motivo, no con un 500`() = newsApp {
        val respuesta = client.post("/content/noticias") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(noticiaJson(titulo = ""))
        }
        // El `require` del gateway viaja por `StatusPages` como 400, no como un
        // error genérico: quien administra tiene que poder leer qué falta.
        assertEquals(HttpStatusCode.BadRequest, respuesta.status)
        assertTrue(respuesta.bodyAsText().contains("titulo", ignoreCase = true), respuesta.bodyAsText())
    }

    @Test
    fun `una noticia que no existe es 404 y no una lista vacia`() = newsApp {
        val respuesta = client.get("/content/noticias/$NOTICIA") { auth() }
        assertEquals(HttpStatusCode.NotFound, respuesta.status)
        assertTrue(respuesta.bodyAsText().contains("noticia_no_encontrada"), respuesta.bodyAsText())
    }

    @Test
    fun `el limite de la lista se acota y no se puede pedir la tabla entera`() = newsApp {
        repeat(3) { indice ->
            client.post("/content/noticias") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(noticiaJson(titulo = "Noticia $indice"))
            }
        }
        // `limit=0` no devuelve una lista vacía sin explicación: se corrige a 1.
        val unaSola = client.get("/content/noticias?limit=0") { auth() }
        assertEquals(HttpStatusCode.OK, unaSola.status)
        assertEquals(1, Regex("\"titulo\":").findAll(unaSola.bodyAsText()).count(), unaSola.bodyAsText())

        // Y el offset se respeta, que es lo que hace útil el límite.
        val desdeLaSegunda = client.get("/content/noticias?limit=10&offset=1") { auth() }
        assertEquals(2, Regex("\"titulo\":").findAll(desdeLaSegunda.bodyAsText()).count(), desdeLaSegunda.bodyAsText())
    }

    // -----------------------------------------------------------------------

    private companion object {
        const val NOTICIA = "8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60"
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    /** El id que asigna el gateway al dar de alta, leído del cuerpo devuelto. */
    private fun String.idDelCuerpo(): String? =
        Regex("\"id\":\"([^\"]+)\"").find(this)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun noticiaJson(
        titulo: String,
        id: String = "",
        estado: String = "draft",
    ): String = """
        {
          "id": "$id",
          "titulo": "$titulo",
          "especialidad": "$especialidad",
          "tipo": "$tipo",
          "fondo": "A",
          "estado": "$estado",
          "texto": "Cuerpo editorial de la noticia.",
          "capas": [
            {
              "id": "capa-titulo",
              "nombre": "Título",
              "tipo": "titulo",
              "contenido": "Simulacro",
              "x": 48, "y": 142, "width": 360, "height": 44,
              "opacity": 92, "visible": true, "locked": false, "orden": 0,
              "fontSizeSp": 42, "fontWeight": "bold"
            }
          ]
        }
    """.trimIndent()

    /**
     * La aplicación de prueba: las rutas REALES de producción
     * ([newsContentRoutes]) con el `StatusPages` real, detrás de un proveedor de
     * autenticación de test.
     */
    private fun newsApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = false })
            }
            installRequestCorrelation()
            installBackendStatusPages()
            install(Authentication) {
                bearer("gateway-auth") {
                    authenticate { credentials ->
                        // Un token fijo. Ni JWT de producción ni bypass: sólo la
                        // certeza de que la ruta EXIGE credencial.
                        if (credentials.token == token) UserIdPrincipal("test") else null
                    }
                }
            }
            routing {
                authenticate("gateway-auth") {
                    route("/content") {
                        // Indexación de búsqueda a `{}`: no es lo que se prueba
                        // aquí, y un doble que "recuerda" llamadas invitaría a
                        // afirmar cosas del indexador desde el test del CRUD.
                        newsContentRoutes(InMemoryNewsGateway(), onIndexed = {}, onRemoved = {})
                    }
                }
            }
        }
        block()
    }
}
