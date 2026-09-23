package com.oposicontrol.backend

import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HASHIRA C — verificación independiente de FASE 1.
 *
 * `SupabaseRequestTransport` absorbió el transporte de tres gateways, así que
 * cada detalle que se pierda aquí se pierde en ~160 llamadas a la vez y de forma
 * silenciosa: el cuerpo sigue viajando, sólo cambia lo que Supabase hace con él.
 *
 * `SupabaseRequestTransportTest` cubre credenciales, traducción de errores, rpc,
 * `count` y `storageDelete`. Estos casos cubren lo que quedó fuera y que, si
 * alguien lo borrara, **no pondría rojo ningún test existente**:
 *
 * | Línea que se podría borrar | Qué rompe en producción | ¿Test previo? |
 * |---|---|---|
 * | `if (prefer != null) builder.header("Prefer", prefer)` | todo `return=representation` devuelve cuerpo vacío → `error("... no encontrado para actualizar")` en cada PATCH | no |
 * | `.header("Content-Type", "application/json")` | PostgREST rechaza los cuerpos (415) | no |
 * | `.timeout(UPSTREAM_REQUEST_TIMEOUT)` | una petición colgada ocupa un hilo para siempre — justo lo que §6 quería evitar | no |
 * | `.header("Range-Unit", "items")` | `count` pierde su unidad de rango | no |
 * | `if (upsert) builder.header("x-upsert", "true")` | sustituir una portada devuelve 409 y la portada nueva no se guarda | no |
 *
 * Los dobles (`V1StubHttpClient`, `V1StubResponse`) se reutilizan de
 * `SupabaseV1GatewayExamAccessTest`: observan exactamente lo que sale por el
 * cable, que es lo único que aquí es contrato.
 */
class IndependentTransportContractTest {

    private fun transportWith(
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> V1StubResponse,
    ): SupabaseRequestTransport = SupabaseRequestTransport.forTesting(
        baseUrl = "https://stub.supabase.test",
        secretKey = "sb_secret_independent",
        client = V1StubHttpClient(record, responder),
    )

    /**
     * `Prefer` es lo que hace que un PATCH devuelva la fila guardada. Los
     * gateways migrados lo asumen: `.decodeList<Row>().firstOrNull() ?: error(...)`.
     * Si el transporte dejara de reenviarlo, cada escritura respondería un 500
     * "no encontrado para actualizar" sobre una fila que SÍ se actualizó.
     */
    @Test fun sendForwardsPreferVerbatim() {
        val sent = mutableListOf<HttpRequest>()
        transportWith(record = { sent += it }) { V1StubResponse(200, "[]") }
            .send(method = "PATCH", path = "/rest/v1/examenes?id=eq.x", body = "{}", prefer = "return=representation")

        assertEquals("return=representation", sent.single().headers().firstValue("Prefer").orElseThrow())
    }

    /** Y sin `prefer` no se inventa ninguno: un `Prefer` de más también cambia la respuesta. */
    @Test fun sendOmitsPreferWhenThereIsNone() {
        val sent = mutableListOf<HttpRequest>()
        transportWith(record = { sent += it }) { V1StubResponse(200, "[]") }.get("/rest/v1/examenes")

        assertTrue(sent.single().headers().firstValue("Prefer").isEmpty)
    }

    @Test fun everyPostgrestRequestDeclaresJsonContentType() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) { V1StubResponse(200, "[]") }

        transport.get("/rest/v1/examenes")
        transport.send("POST", "/rest/v1/examenes", body = "{}")

        sent.forEach { assertEquals("application/json", it.headers().firstValue("Content-Type").orElseThrow()) }
    }

    /**
     * El timeout es la mitad del arreglo de §6: `withContext(Dispatchers.IO)`
     * mueve el bloqueo al pool `IO`, pero sólo el timeout garantiza que el hilo
     * vuelve. Sin él, `Dispatchers.IO` se llena igual, sólo que más despacio.
     */
    @Test fun everyRequestCarriesTheUpstreamTimeout() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) { V1StubResponse(206, "[]", contentRange = "0-0/1") }

        transport.get("/rest/v1/examenes")
        transport.send("PATCH", "/rest/v1/examenes?id=eq.x", body = "{}")
        transport.count("/rest/v1/examenes?select=id")
        transport.storageEnsureBucket("exam-resources", public = false)
        transport.storagePut("exam-resources", "p/a.png", ByteArray(1), "image/png")
        transport.storageDelete("exam-resources", "p/a.png")

        assertEquals(6, sent.size)
        sent.forEach {
            assertEquals(UPSTREAM_REQUEST_TIMEOUT, it.timeout().orElseThrow(), "sin timeout: ${it.uri()}")
        }
    }

    /** `Range: 0-0` sin `Range-Unit: items` no es el mismo conteo. */
    @Test fun countAsksForItemsAsTheRangeUnit() {
        val sent = mutableListOf<HttpRequest>()
        transportWith(record = { sent += it }) { V1StubResponse(206, "[]", contentRange = "0-0/7") }
            .count("/rest/v1/examenes?select=id")

        assertEquals("items", sent.single().headers().firstValue("Range-Unit").orElseThrow())
    }

    /**
     * La subida a Storage lleva el tipo del FICHERO, no `application/json`, y
     * `x-upsert` sólo cuando se pide. Sustituir una portada existente sin
     * `x-upsert` devuelve 409 y el admin ve "no se pudo guardar" sobre un
     * objeto que sí subió la primera vez.
     */
    @Test fun storagePutCarriesTheFileContentTypeAndUpsertOnlyWhenAsked() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) { V1StubResponse(200, "{}") }

        transport.storagePut("exam-resources", "exam-covers/E1/p.png", ByteArray(3), "image/png", upsert = true)
        transport.storagePut("exam-resources", "exam-covers/E1/q.png", ByteArray(3), "image/png", upsert = false)

        assertEquals("image/png", sent[0].headers().firstValue("Content-Type").orElseThrow())
        assertEquals("true", sent[0].headers().firstValue("x-upsert").orElseThrow())
        assertTrue(sent[1].headers().firstValue("x-upsert").isEmpty, "sin upsert no debe ir la cabecera")
        assertEquals("POST", sent[0].method())
    }

    /**
     * La URL pública y la URL de subida tienen que codificar IGUAL. Si
     * divergieran, `portada_url` apuntaría a un objeto que no existe y la
     * portada saldría rota en el panel sin ningún error en el log.
     */
    @Test fun thePublicUrlEncodesExactlyLikeTheUpload() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) { V1StubResponse(200, "{}") }
        val objectPath = "exam-covers/EX 1/portada final.png"

        transport.storagePut("exam-resources", objectPath, ByteArray(1), "image/png")
        val publicUrl = transport.storagePublicUrl("exam-resources", objectPath)

        assertEquals(
            "https://stub.supabase.test/storage/v1/object/exam-resources/exam-covers/EX%201/portada%20final.png",
            sent.single().uri().toString(),
        )
        assertEquals(
            "https://stub.supabase.test/storage/v1/object/public/exam-resources/exam-covers/EX%201/portada%20final.png",
            publicUrl,
        )
        // Y `+` nunca: en un path `+` es un `+` literal, no un espacio. El
        // objeto quedaría con un nombre distinto del que dice la URL pública.
        assertFalse(publicUrl.contains('+'), publicUrl)
    }
}
