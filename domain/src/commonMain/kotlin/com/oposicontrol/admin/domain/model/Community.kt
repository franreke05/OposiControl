package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

/**
 * Comunidad autonoma. Catalogo base que (a futuro) acota examenes por territorio.
 *
 * NOTA: la tabla `comunidad_autonoma` es nueva en Supabase; ver la migracion en
 * docs/supabase/20260616_comunidad_autonoma.sql. Hasta aplicarla, el backend
 * devolvera error al consultar este recurso (se propaga como AppError, no crashea).
 */
data class Community(
    val id: String,
    val name: String,
    /** Codigo corto territorial, p. ej. "AN", "MD", "CT". Opcional pero util para filtros. */
    val code: String,
    val slug: String,
    val isActive: Boolean,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)
