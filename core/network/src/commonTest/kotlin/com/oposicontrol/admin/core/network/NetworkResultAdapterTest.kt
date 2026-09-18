package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NetworkResultAdapterTest {
    private val adapter = NetworkResultAdapter(NoOpLogger)

    @Test
    fun backend401BecomesSessionMessage() = runTest {
        val result = adapter.safeCall<Unit> { error("backend 401 /api/v1/admin/especialidades: technical detail") }

        val failure = assertIs<AppResult.Failure>(result)
        assertEquals(AppError.Authentication("Tu sesión ha caducado. Vuelve a iniciar sesión."), failure.error)
    }

    @Test
    fun backend403BecomesPermissionMessage() = runTest {
        val result = adapter.safeCall<Unit> { error("backend 403 /api/v1/admin/dashboard: technical detail") }

        val failure = assertIs<AppResult.Failure>(result)
        assertEquals(AppError.Authorization("No tienes permisos para realizar esta acción."), failure.error)
    }

    @Test
    fun backend5xxDoesNotExposeTechnicalResponse() = runTest {
        val result = adapter.safeCall<Unit> { error("backend 500 /api/v1/admin/dashboard: secret response") }

        val failure = assertIs<AppResult.Failure>(result)
        assertEquals("Ha ocurrido un problema en el servidor.", failure.error.message)
    }

    @Test
    fun backend404BecomesNotFound_notAGenericValidationError() = runTest {
        val result = adapter.safeCall<Unit> { error("backend 404 /api/v1/admin/moderacion/hilos/xyz: not_found") }

        val failure = assertIs<AppResult.Failure>(result)
        assertIs<AppError.NotFound>(failure.error)
    }

    @Test
    fun aBusinessConflictShowsTheBackendMessageAndNothingElse() = runTest {
        // Forma real de la respuesta cuando la guarda de roles rechaza la
        // operación: `ErrorDto(error, message)` con estado 409.
        val result = adapter.safeCall<Unit> {
            error(
                "backend 409 /api/v1/admin/usuarios/8f3c2b1a-0000-4000-8000-000000000001/roles: " +
                    "{\"error\":\"role_sync_last_superadmin\",\"message\":\"No puedes retirar este rol " +
                    "porque debe existir al menos un superadministrador.\"}",
            )
        }

        val failure = assertIs<AppResult.Failure>(result)
        assertEquals(
            "No puedes retirar este rol porque debe existir al menos un superadministrador.",
            failure.error.message,
        )
    }

    @Test
    fun aBusinessConflictNeverLeaksTheEndpointTheIdentifierOrTheRawBody() = runTest {
        // Antes de esto, un 409 caía en el `else` y la cadena entera —ruta, UUID
        // del usuario y JSON— se pintaba tal cual en la pantalla, porque
        // `AppError.Validation` se muestra literal. Es lo que §52/§55 prohíben.
        val result = adapter.safeCall<Unit> {
            error(
                "backend 409 /api/v1/admin/usuarios/8f3c2b1a-0000-4000-8000-000000000001/roles: " +
                    "{\"error\":\"role_sync_last_superadmin\",\"message\":\"No puedes retirar este rol.\"}",
            )
        }

        val shown = assertIs<AppResult.Failure>(result).error.message
        listOf("usuarios", "8f3c2b1a", "{", "}", "role_sync", "backend 409", "/api/").forEach { leak ->
            assertEquals(false, shown.contains(leak, ignoreCase = true), "El mensaje filtra '$leak': $shown")
        }
    }

    @Test
    fun anUnprocessableEntityAlsoShowsItsHumanMessage() = runTest {
        val result = adapter.safeCall<Unit> {
            error(
                "backend 422 /api/v1/admin/usuarios/abc/roles: " +
                    "{\"error\":\"role_sync_empty_roles\",\"message\":\"El usuario debe conservar al menos un rol.\"}",
            )
        }

        val failure = assertIs<AppResult.Failure>(result)
        assertEquals("El usuario debe conservar al menos un rol.", failure.error.message)
    }

    /**
     * El código de dominio llega a la UI junto al mensaje.
     *
     * Es la forma real del 422 de `examenes_total_preguntas_check`. Sin el código,
     * la pantalla sólo puede pintar una línea roja al pie del formulario: sabe que
     * algo se ha rechazado, pero no QUÉ campo marcar.
     */
    @Test
    fun aDomainRejectionCarriesItsErrorCodeNextToTheMessage() = runTest {
        val result = adapter.safeCall<Unit> {
            error(
                "backend 422 /api/v1/admin/examenes: " +
                    "{\"error\":\"EXAM_QUESTION_COUNT_INVALID\",\"message\":\"El número de preguntas tiene " +
                    "que ser mayor que 0. Un examen no puede guardarse con 0 preguntas.\"}",
            )
        }

        val error = assertIs<AppError.Validation>(assertIs<AppResult.Failure>(result).error)
        assertEquals("EXAM_QUESTION_COUNT_INVALID", error.code)
        assertEquals(
            "El número de preguntas tiene que ser mayor que 0. Un examen no puede guardarse con 0 preguntas.",
            error.message,
        )
    }

    /**
     * Sin clave `error` en el cuerpo, el código queda nulo: no se inventa uno.
     *
     * Un código fabricado sería peor que ninguno — haría que la pantalla marcara
     * un campo que el servidor no ha señalado.
     */
    @Test
    fun aBusinessErrorWithoutAnErrorKeyLeavesTheCodeNull() = runTest {
        val result = adapter.safeCall<Unit> {
            error("backend 409 /api/v1/admin/examenes: {\"message\":\"No se ha podido guardar el examen.\"}")
        }

        val error = assertIs<AppError.Validation>(assertIs<AppResult.Failure>(result).error)
        assertEquals(null, error.code)
        assertEquals("No se ha podido guardar el examen.", error.message)
    }

    @Test
    fun aBusinessErrorWithoutAReadableBodyFallsBackToSomethingHumane() = runTest {
        // Nunca se enseña el cuerpo crudo, ni siquiera cuando no hay mensaje que
        // extraer: se dice que no se pudo y se corta ahí.
        val result = adapter.safeCall<Unit> { error("backend 409 /api/v1/admin/usuarios/abc/roles: <html>nginx</html>") }

        val failure = assertIs<AppResult.Failure>(result)
        assertEquals("No se ha podido completar la operación.", failure.error.message)
    }

    // -----------------------------------------------------------------
    // F-04 · Timeouts: «tardando» no es «inalcanzable»
    // -----------------------------------------------------------------

    /**
     * Un timeout de PETICIÓN no es "no se pudo conectar": el servidor recibió la
     * petición y no ha terminado. Decir lo contrario invita a repetir algo que quizá
     * ya se hizo. Tiene que resolverse ANTES que `IOException`, de la que hereda.
     */
    @Test
    fun aRequestTimeoutSaysTheServerIsSlowAndNotThatItIsUnreachable() = runTest {
        val result = adapter.safeCall<Unit> {
            throw HttpRequestTimeoutException("https://backend.test/api/v1/admin/importaciones/preguntas/pdf", 180_000)
        }

        val error = assertIs<AppError.Network>(assertIs<AppResult.Failure>(result).error)
        assertEquals("El servidor está tardando demasiado.", error.message)
        assertEquals(408, error.code)
    }

    @Test
    fun aSocketTimeoutWhileReadingTheResponseIsAlsoASlowServer() = runTest {
        val result = adapter.safeCall<Unit> { throw SocketTimeoutException("Socket timeout has expired") }

        val error = assertIs<AppError.Network>(assertIs<AppResult.Failure>(result).error)
        assertEquals("El servidor está tardando demasiado.", error.message)
        assertEquals(408, error.code)
    }

    /** No llegar a conectar SÍ es inalcanzable, y conserva su propio mensaje. */
    @Test
    fun aConnectTimeoutIsStillUnreachableAndNotSlow() = runTest {
        val result = adapter.safeCall<Unit> { throw ConnectTimeoutException("Connect timeout has expired") }

        val error = assertIs<AppError.Network>(assertIs<AppResult.Failure>(result).error)
        assertEquals("Unable to reach the server", error.message)
        assertEquals(null, error.code)
    }

    // -----------------------------------------------------------------
    // F-12c · La referencia de un 5xx llega a la pantalla; el resto no
    // -----------------------------------------------------------------

    /**
     * «Referencia: <id>» es lo único que conecta lo que ve el operador con el log del
     * servidor, y se tiraba con el resto del cuerpo. Se conserva SÓLO el id.
     */
    @Test
    fun aServerErrorKeepsItsReferenceAndNothingElseFromTheBody() = runTest {
        val result = adapter.safeCall<Unit> {
            error(
                "backend 500 /api/v1/admin/examenes: " +
                    "{\"error\":\"backend_supabase_error\",\"message\":\"Error al consultar el servicio de datos. " +
                    "Referencia: c-ref-12345678\"}",
            )
        }

        val error = assertIs<AppError.Network>(assertIs<AppResult.Failure>(result).error)
        assertEquals("Ha ocurrido un problema en el servidor. Referencia: c-ref-12345678", error.message)
        assertEquals(500, error.code)
    }

    @Test
    fun aReferenceIsCutAtTheFirstCharacterThatCannotBeARequestId() = runTest {
        val result = adapter.safeCall<Unit> {
            error("backend 502 /x: {\"error\":\"backend_contract_error\",\"message\":\"Referencia: abcd1234<script>\"}")
        }

        assertEquals(
            "Ha ocurrido un problema en el servidor. Referencia: abcd1234",
            assertIs<AppResult.Failure>(result).error.message,
        )
    }

    /** Los tres rechazos transitorios de la base traen un mensaje constante que dice cuándo reintentar. */
    @Test
    fun aTransientDatabaseRejectionShowsItsRetryableMessage() = runTest {
        val message = "Los datos estaban bloqueados por otra operación en curso. Vuelve a intentarlo en unos segundos."
        val result = adapter.safeCall<Unit> {
            error("backend 503 /api/v1/admin/examenes: {\"error\":\"database_lock_timeout\",\"message\":\"$message\"}")
        }

        val error = assertIs<AppError.Network>(assertIs<AppResult.Failure>(result).error)
        assertEquals(message, error.message)
        assertEquals(503, error.code)
    }

    /** Cualquier otro 5xx con cuerpo sigue sin enseñarlo: podría ser la página de una pasarela. */
    @Test
    fun anyOtherServerBodyStillNeverReachesTheScreen() = runTest {
        val result = adapter.safeCall<Unit> {
            error("backend 503 /api/v1/admin/examenes: {\"error\":\"gateway\",\"message\":\"x-probe-c internals\"}")
        }

        assertEquals("Ha ocurrido un problema en el servidor.", assertIs<AppResult.Failure>(result).error.message)
    }

    @Test
    fun aClientSideRefusalKeepsItsOwnWording() = runTest {
        // Sin prefijo `backend NNN` no viene de una respuesta HTTP: son los
        // `error(...)` del propio cliente, como el de crear usuarios, y su texto
        // ya es el mensaje bueno. No puede quedar sepultado por el genérico.
        val result = adapter.safeCall<Unit> {
            error("Crear usuarios no está disponible desde la app admin. Usa el dashboard de Supabase.")
        }

        val failure = assertIs<AppResult.Failure>(result)
        assertEquals(
            "Crear usuarios no está disponible desde la app admin. Usa el dashboard de Supabase.",
            failure.error.message,
        )
    }
}
