package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
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
}
