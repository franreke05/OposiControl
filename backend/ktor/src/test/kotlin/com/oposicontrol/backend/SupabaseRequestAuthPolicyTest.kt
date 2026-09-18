package com.oposicontrol.backend

import java.net.URI
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SupabaseRequestAuthPolicyTest {
    @Test fun elevatedRequestsSendSecretOnlyAsApiKey() {
        val request = HttpRequest.newBuilder(URI("https://example.test/rest/v1/examenes"))
            .supabaseElevatedAuth("sb_secret_test_fixture").GET().build()
        assertEquals("sb_secret_test_fixture", request.headers().firstValue("apikey").orElseThrow())
        assertFalse(request.headers().firstValue("Authorization").isPresent)
    }

    @Test fun userScopedRequestsSendUserJwtAsBearer() {
        val headers = SupabaseRequestAuthPolicy("sb_secret_test_fixture").userScopedHeaders("header.payload.signature")
        assertEquals("Bearer header.payload.signature", headers["Authorization"])
        assertEquals("sb_secret_test_fixture", headers["apikey"])
    }

    @Test fun apiKeysCannotBePassedAsUserJwt() {
        val policy = SupabaseRequestAuthPolicy("sb_secret_test_fixture")
        listOf("", "sb_secret_invalid", "sb_publishable_invalid", "opaque").forEach {
            assertFailsWith<IllegalArgumentException> { policy.userScopedHeaders(it) }
        }
    }

    // ----- §3.6 casos obligatorios de la politica -----

    /**
     * Caso 1. Una clave `sb_secret_...` no tiene forma de JWT, asi que viaja
     * SOLO como `apikey`. Mandarla como `Bearer` seria incorrecto: PostgREST y
     * GoTrue esperan un JWT en esa cabecera.
     */
    @Test fun aSecretKeyIsNeverABearerToken() {
        val headers = SupabaseRequestAuthPolicy("sb_secret_test_fixture").elevatedHeaders()
        assertEquals("sb_secret_test_fixture", headers["apikey"])
        assertNull(headers["Authorization"])
    }

    /**
     * Caso 2. La `service_role` legacy SI es un JWT firmado y PostgREST resuelve
     * el rol por su claim `role`. Este es el caso que preserva byte a byte lo
     * que el backend envia hoy: quitarle el `Bearer` seria un cambio de
     * comportamiento no verificado contra el proyecto vivo.
     */
    @Test fun aJwtShapedServerKeyAlsoTravelsAsBearer() {
        val headers = SupabaseRequestAuthPolicy("a.b.c").elevatedHeaders()
        assertEquals("a.b.c", headers["apikey"])
        assertEquals("Bearer a.b.c", headers["Authorization"])
    }

    /**
     * Casos 3 y 4. El predicado de forma exige tres segmentos y NINGUNO vacio.
     * Es a proposito mas estricto que el `count { it == '.' } == 2` que usa
     * `OposiBotsAuthGateway`, que aceptaria `".."`, `"a..b"` y `".x."`: un
     * segmento vacio no es un JWT, y tratarlo como tal manda una credencial
     * rota en vez de fallar donde se ve.
     */
    @Test fun onlyThreeNonEmptySegmentsCountAsJwtShape() {
        listOf("a..c", "..", ".b.c", "a.b.", "a.b", "a.b.c.d", "abc", "").forEach { key ->
            assertFalse(key.hasJwtShape(), "no debe tener forma de JWT: '$key'")
        }
        listOf("a.b.c", "header.payload.signature").forEach { key ->
            assertTrue(key.hasJwtShape(), "debe tener forma de JWT: '$key'")
        }
        // Y la consecuencia observable: sin forma de JWT no hay Authorization.
        listOf("a..c", "a.b", "a.b.c.d").forEach { key ->
            assertNull(SupabaseRequestAuthPolicy(key).elevatedHeaders()["Authorization"], "clave '$key'")
        }
    }

    /**
     * Caso 5. Con clave de servidor CON forma de JWT, una peticion user-scoped
     * manda el JWT del usuario y nunca el del servidor. El orden del `+` en
     * `userScopedHeaders` es lo que lo garantiza, y por eso se fija aqui: si se
     * invirtiera, la peticion correria con el rol de servicio en silencio.
     */
    @Test fun theUserJwtWinsOverTheServerJwt() {
        val headers = SupabaseRequestAuthPolicy("server.key.jwt").userScopedHeaders("x.y.z")
        assertEquals("Bearer x.y.z", headers["Authorization"])
        assertEquals("server.key.jwt", headers["apikey"])
    }

    /**
     * Caso 6. Precedencia de variables de entorno, decidida en un solo sitio.
     *
     * Este test **afirma lo contrario** de lo que afirmaba antes. La version
     * anterior (`productionNeedsNoLegacyEnvironmentVariable`) hacia
     * `error("Legacy key requested")` si se consultaba la legacy, es decir
     * fijaba que el fallback NO debia existir. La decision tomada es la
     * contraria: `SUPABASE_SERVICE_ROLE_KEY` es un fallback explicito y
     * deprecado, porque es la variable que hoy leen los `fromEnvironment()` del
     * backend y quitarla de golpe deja el servicio sin credencial. Lo que si se
     * fija es que **nunca gana sobre la canonica**.
     */
    @Test fun theCanonicalKeyWinsAndTheLegacyOneIsOnlyAFallback() {
        // Solo la canonica -> esa, y la legacy ni se consulta.
        val requested = mutableListOf<String>()
        assertEquals("sb_secret_fixture", SupabaseRequestAuthPolicy.secretFromEnvironment {
            requested += it
            if (it == "SUPABASE_SECRET_KEY") " sb_secret_fixture " else null
        })
        assertEquals(listOf("SUPABASE_SECRET_KEY"), requested)

        // Solo la legacy -> esa.
        assertEquals("legacy.service.role", SupabaseRequestAuthPolicy.secretFromEnvironment {
            if (it == "SUPABASE_SERVICE_ROLE_KEY") " legacy.service.role " else null
        })

        // Las dos -> gana la canonica, sin ambiguedad.
        assertEquals("sb_secret_fixture", SupabaseRequestAuthPolicy.secretFromEnvironment {
            if (it == "SUPABASE_SECRET_KEY") "sb_secret_fixture" else "legacy.service.role"
        })

        // Una canonica presente pero en blanco NO bloquea el fallback: vacia es
        // como ausente, o un `SUPABASE_SECRET_KEY=` en un .env dejaria el
        // backend sin credencial teniendo la legacy bien puesta al lado.
        assertEquals("legacy.service.role", SupabaseRequestAuthPolicy.secretFromEnvironment {
            if (it == "SUPABASE_SECRET_KEY") "   " else "legacy.service.role"
        })

        // Ninguna -> "" (no una excepcion: quien llama decide que hacer).
        assertEquals("", SupabaseRequestAuthPolicy.secretFromEnvironment { null })
    }

    /**
     * Caso 7. La asercion de `supabaseElevatedAuth` cambia de sentido, no
     * desaparece: lo que impide es que el llamante fije SU propio
     * `Authorization` y acaben dos identidades en la misma peticion.
     */
    @Test fun theCallerCannotSupplyItsOwnAuthorization() {
        val builder = HttpRequest.newBuilder(URI("https://example.test/rest/v1/examenes"))
            .header("Authorization", "Bearer intruso.del.llamante")
            .GET()
        assertFailsWith<IllegalArgumentException> { builder.supabaseElevatedAuth("sb_secret_test_fixture") }
    }

    /** Y sobre un builder limpio con clave JWT, la politica si escribe el suyo. */
    @Test fun elevatedBuilderGetsTheServerBearerWhenTheKeyIsAJwt() {
        val request = HttpRequest.newBuilder(URI("https://example.test/rest/v1/examenes"))
            .supabaseElevatedAuth("a.b.c").GET().build()
        assertEquals("a.b.c", request.headers().firstValue("apikey").orElseThrow())
        assertEquals("Bearer a.b.c", request.headers().firstValue("Authorization").orElseThrow())
    }
}
