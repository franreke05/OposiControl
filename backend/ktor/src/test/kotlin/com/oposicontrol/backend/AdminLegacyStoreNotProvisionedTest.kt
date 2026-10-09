package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DEBT-068 / LEG-003: las tablas legacy de Tienda no existen en MAIN. Leer devuelve la lista vacia y escribir un 409
 * explicito; antes era un 502 que parecia una caida de Supabase. Cualquier otro fallo se sigue propagando.
 */
class AdminLegacyStoreNotProvisionedTest {
    private val missingTable = V1StubResponse(
        404,
        """{"code":"PGRST205","details":null,"hint":null,"message":"Could not find the table 'public.articulo_tienda' in the schema cache"}""",
    )

    private fun gateway(response: V1StubResponse): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ _: HttpRequest -> }) { _ -> response },
    )

    @Test
    fun readingANonProvisionedLegacyStoreTableReturnsAnEmptyList() {
        val gateway = gateway(missingTable)
        assertEquals(emptyList(), gateway.listArticulosTienda())
        assertEquals(emptyList(), gateway.listOfertasTienda())
        assertEquals(emptyList(), gateway.listRecursosTemporalesTienda())
        assertEquals(emptyList(), gateway.listCampanasRafaga())
    }

    @Test
    fun writingToANonProvisionedLegacyStoreTableIsAnExplicitConflict() {
        val error = assertFailsWith<BackendDomainException> {
            gateway(missingTable).upsertArticuloTienda(AdminArticuloTiendaDto(nombre = "x"))
        }
        assertEquals("STORE_LEGACY_NOT_PROVISIONED", error.errorCode)
        assertEquals(HttpStatusCode.Conflict, error.status)
    }

    @Test
    fun anyOtherUpstreamFailureIsStillPropagated() {
        val error = assertFailsWith<IllegalStateException> {
            gateway(V1StubResponse(500, """{"code":"XX000","message":"boom"}""")).listArticulosTienda()
        }
        assertTrue(error !is BackendDomainException)
    }
}
