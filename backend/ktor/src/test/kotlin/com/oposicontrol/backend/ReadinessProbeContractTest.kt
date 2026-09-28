package com.oposicontrol.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El contrato de `/health/ready` en su parte peligrosa: **que la sonda ejerza
 * la credencial real y que el rango de estados se mueva con ella**.
 *
 * Este fichero existe porque el rango no estaba cubierto por ningun test:
 * `ObservabilityTest` prueba "sin config" y "conexion rechazada", que son los
 * dos extremos, y deja fuera justo lo que decide si un proceso sano entra en
 * rotacion.
 *
 * Las dos mitades son una sola regla y por eso viven juntas:
 *
 * - `200..299` y solo `200..299` es "up" — un 401 no es "el servicio
 *   responde", es "el servicio no me deja entrar", y con esa credencial
 *   ninguna query de negocio funcionaria.
 * - Ese rango estrecho **solo es legitimo si la sonda manda lo que manda el
 *   trafico real**. Una sonda que ejerciera una combinacion de cabeceras que
 *   ningun gateway usa estaria decidiendo la rotacion de TODAS las replicas a
 *   partir de una credencial que nadie ejerce.
 *
 * Se usa un servidor HTTP local de la JDK y no un doble: `checkSupabaseReachable`
 * habla por su propio `HttpClient` privado, asi que la unica forma de observar
 * lo que sale por el cable es ponerse al otro lado del cable.
 */
class ReadinessProbeContractTest {

    /** Forma de JWT: tres segmentos no vacios. Es lo que hoy contiene `SUPABASE_SERVICE_ROLE_KEY`. */
    private val legacyJwtShapedKey = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoic2VydmljZV9yb2xlIn0.firma-de-prueba"

    /** La clave nueva de Supabase. No es un JWT y no puede viajar como Bearer. */
    private val modernSecretKey = "sb_secret_clave_de_prueba"

    // -----------------------------------------------------------------
    // La sonda manda lo mismo que el trafico real
    // -----------------------------------------------------------------

    @Test
    fun aLegacyJwtShapedKeyTravelsAsApikeyAndAsBearer_exactlyLikeTheGateways() {
        withProbeServer(status = 200) { baseUrl, calls ->
            val status = checkSupabaseReachable(url = baseUrl, apiKey = legacyJwtShapedKey)

            assertEquals("UP", status)
            val headers = calls.single().headers
            assertEquals(
                listOf(legacyJwtShapedKey),
                headers["apikey"],
                "la sonda tiene que mandar la misma apikey que los gateways",
            )
            assertEquals(
                listOf("Bearer $legacyJwtShapedKey"),
                headers["authorization"],
                "con la clave legacy el trafico real manda Authorization: Bearer. Si la sonda no lo " +
                    "manda, comprueba una combinacion de cabeceras que ningun camino de negocio usa " +
                    "y el rango 200..299 deja de significar nada.",
            )
        }
    }

    @Test
    fun aModernSecretKeyTravelsOnlyAsApikeyAndNeverAsABearer() {
        withProbeServer(status = 200) { baseUrl, calls ->
            val status = checkSupabaseReachable(url = baseUrl, apiKey = modernSecretKey)

            assertEquals("UP", status)
            val headers = calls.single().headers
            assertEquals(listOf(modernSecretKey), headers["apikey"])
            assertNull(
                headers["authorization"],
                "una clave sb_secret_... no es un JWT; mandarla como Bearer a PostgREST es " +
                    "incorrecto, y la sonda no puede hacer lo que el trafico real no hace",
            )
        }
    }

    @Test
    fun theProbeAsksThePostgrestRootAndTouchesNoBusinessTable() {
        withProbeServer(status = 200) { baseUrl, calls ->
            checkSupabaseReachable(url = baseUrl, apiKey = legacyJwtShapedKey)

            val call = calls.single()
            assertEquals("GET", call.method, "una sonda de readiness no escribe")
            assertEquals(
                "/rest/v1/",
                call.path,
                "la sonda lee el root de PostgREST, no una tabla: no debe depender de que exista " +
                    "ninguna fila ni pagar el coste de una query de negocio",
            )
        }
    }

    // -----------------------------------------------------------------
    // El rango
    // -----------------------------------------------------------------

    @Test
    fun onlyA2xxIsUp() {
        listOf(200, 204).forEach { status ->
            withProbeServer(status = status) { baseUrl, _ ->
                assertEquals("UP", checkSupabaseReachable(url = baseUrl, apiKey = legacyJwtShapedKey), "$status")
            }
        }
    }

    @Test
    fun aRejectedCredentialIsDownAndNotUp() {
        // El modo de fallo que la sonda existe para ver, y el que el rango
        // `200..499` anterior escondia: Supabase alcanzable, credencial
        // rechazada. Para este backend eso es indistinguible de estar caido.
        listOf(401, 403).forEach { status ->
            withProbeServer(status = status) { baseUrl, _ ->
                assertEquals("DOWN", checkSupabaseReachable(url = baseUrl, apiKey = legacyJwtShapedKey), "$status")
            }
        }
    }

    @Test
    fun anUpstreamErrorIsDown() {
        listOf(500, 502, 503).forEach { status ->
            withProbeServer(status = status) { baseUrl, _ ->
                assertEquals("DOWN", checkSupabaseReachable(url = baseUrl, apiKey = legacyJwtShapedKey), "$status")
            }
        }
    }

    /**
     * **Residuo conocido, fijado a proposito.**
     *
     * Un 404 en `/rest/v1/` no es un problema de credencial: es que el root
     * spec de PostgREST este desactivado (`db-root-spec`/`openapi-mode`). Con
     * el rango estrecho eso pone "down" en TODAS las replicas a la vez con el
     * backend sano.
     *
     * Este caso NO afirma que ese comportamiento sea el deseable: afirma cual
     * es, para que el dia que se decida tolerar el 404 el cambio sea visible
     * aqui y no una sorpresa en produccion.
     *
     * ### Riesgo real del residuo: BAJO, y con evidencia fechada
     *
     * Una version anterior de este KDoc decia que "no se ha podido verificar
     * contra el proyecto vivo que el root spec este activo". Era falso: el
     * repositorio lo documenta dos veces, y las dos describen la misma
     * inspeccion, que **es** un `GET /rest/v1/`.
     *
     * - `docs/database/TABLE_CATALOG.md:29-33` — "Inventario en vivo —
     *   2026-09-02. Inspeccion de solo lectura del esquema expuesto por
     *   PostgREST (OpenAPI): 90 tablas y vistas".
     * - `docs/brain/CURRENT_STATE.md:108-109` — "Una auditoria del esquema en
     *   vivo (PostgREST OpenAPI, solo lectura) devuelve 90 tablas".
     *
     * Si el root spec estuviera desactivado, esa inspeccion no habria devuelto
     * ninguna spec. Asi que el 404 solo aparece si alguien desactiva
     * `db-root-spec`/`openapi-mode` o si Supabase lo cambia en una
     * actualizacion: es un riesgo de cambio futuro, no una incognita abierta.
     * Se deja escrito para que nadie vuelva a leer este caso como si hubiera una
     * verificacion pendiente que en realidad ya esta hecha y fechada.
     */
    @Test
    fun aDisabledPostgrestRootSpecIsReportedAsDown_knownResidue() {
        withProbeServer(status = 404) { baseUrl, _ ->
            assertEquals("DOWN", checkSupabaseReachable(url = baseUrl, apiKey = legacyJwtShapedKey))
        }
    }

    @Test
    fun anUnconfiguredProbeIsUnknownAndNeverDown() {
        // "No se ha configurado" y "no responde" son dos frases distintas y no
        // pueden llegar iguales: la primera no debe sacar el proceso de rotacion.
        assertEquals("UNKNOWN", checkSupabaseReachable(url = null, apiKey = legacyJwtShapedKey))
        assertEquals("UNKNOWN", checkSupabaseReachable(url = "http://127.0.0.1:1", apiKey = null))
        assertEquals("UNKNOWN", checkSupabaseReachable(url = "http://127.0.0.1:1", apiKey = "   "))
    }

    // -----------------------------------------------------------------
    // Infraestructura
    // -----------------------------------------------------------------

    private data class ProbeCall(
        val method: String,
        val path: String,
        /** Claves en minuscula: HTTP no distingue mayusculas y las aserciones tampoco deben hacerlo. */
        val headers: Map<String, List<String>>,
    )

    private fun withProbeServer(status: Int, block: (baseUrl: String, calls: List<ProbeCall>) -> Unit) {
        val calls = CopyOnWriteArrayList<ProbeCall>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            calls += ProbeCall(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                headers = exchange.requestHeaders.entries.associate { (name, values) ->
                    name.lowercase() to values.toList()
                },
            )
            // -1 = sin cuerpo. `checkSupabaseReachable` descarta el cuerpo, asi
            // que lo unico que viaja de vuelta es el estado, que es el dato bajo
            // prueba.
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", calls)
            assertTrue(calls.isNotEmpty(), "la sonda no llego a hacer ninguna peticion")
        } finally {
            server.stop(0)
        }
    }
}
