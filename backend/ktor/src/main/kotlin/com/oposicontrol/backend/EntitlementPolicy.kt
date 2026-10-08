package com.oposicontrol.backend

import java.time.Instant

// =====================================================================
// D7-25 EntitlementPolicy.check (SLICE 2, en proceso, SIN ruta HTTP)
//
// LA unica politica detras del acceso a un examen: la usan el listado (D2-01), el detalle (D2-02) y "mis examenes"
// (D2-13), y la usara el arranque de intentos (SLICE 3). Es pura (sin IO): recibe HECHOS ya leidos y decide. Cargar
// los hechos por lotes es del servicio ([CatalogService]); aqui no hay reglas duplicadas en ningun otro sitio.
//
// Reglas (deny-by-default; precedencia FREE > GRANTED > SUBSCRIPTION > DEMO > LOCKED, SLICE2_SCOPE Q-02):
//   0. La especialidad del examen no admite ese tipo (`capacidades_especialidad.permite_*`) -> DENY / LOCKED.
//   1. `tipo_acceso = gratuito`                                    -> ALLOW_FREE      / FREE
//   2. concesion vigente (`contenido_desbloqueado_usuario`, ANY motivo) -> ALLOW_PURCHASED / GRANTED
//   3. premium vigente (re-comprobando `fin_en`, Q-04)             -> ALLOW_PREMIUM   / SUBSCRIPTION
//   4. `demo_disponible` y la especialidad `permite_demos`         -> DENY            / DEMO (solo demo)
//   5. cualquier otra cosa                                         -> DENY            / LOCKED
// ALLOW_CREDIT existe en el enum del contrato pero NO es alcanzable en SLICE 2 (Q-03): `creditos_inmersivos_necesarios`
// y `requiere_plan_codigo` se ignoran a proposito hasta que producto decida su semantica.
// =====================================================================

/** Salida de D7-25. */
internal enum class AccessDecision { ALLOW_FREE, ALLOW_PREMIUM, ALLOW_CREDIT, ALLOW_PURCHASED, DENY }

/** `AccessState` del contrato (`common/identifiers.yaml`). */
internal enum class AccessState { FREE, GRANTED, SUBSCRIPTION, DEMO, LOCKED }

/** Por que se deniega (no sale por el cable: para logs y para SLICE 3, `details.accessState`). */
internal enum class AccessDenyReason { CAPABILITY_DISABLED, DEMO_ONLY, NOT_ENTITLED }

internal data class AccessVerdict(
    val decision: AccessDecision,
    val state: AccessState,
    val denyReason: AccessDenyReason? = null,
) {
    val allowed: Boolean get() = decision != AccessDecision.DENY
}

/** Tipos de examen canonicos. */
internal enum class ExamKind(val wire: String) {
    RAFAGA("rafaga"),
    RAZONADO("razonado"),
    INMERSIVO("inmersivo"),
    ;

    companion object {
        /** Canonico, o el sinonimo legacy de razonado (`guiado`) que se acepta SOLO al leer y se normaliza. */
        fun fromWireOrNull(value: String?): ExamKind? = when (value) {
            "rafaga" -> RAFAGA
            "razonado", "guiado" -> RAZONADO
            "inmersivo" -> INMERSIVO
            else -> null
        }
    }
}

/** Lo que la politica necesita saber DEL EXAMEN. */
internal data class ExamAccessSubject(
    val examId: String,
    val kind: ExamKind,
    val free: Boolean,
    val demoAvailable: Boolean,
)

/** `capacidades_especialidad` de la especialidad del examen. Sin fila = todo abierto (default de la tabla). */
internal data class SpecialtyAccessCapabilities(
    val allowsRafagas: Boolean = true,
    val allowsRazonados: Boolean = true,
    val allowsInmersivos: Boolean = true,
    val allowsDemos: Boolean = true,
) {
    fun allows(kind: ExamKind): Boolean = when (kind) {
        ExamKind.RAFAGA -> allowsRafagas
        ExamKind.RAZONADO -> allowsRazonados
        ExamKind.INMERSIVO -> allowsInmersivos
    }

    companion object {
        val OPEN = SpecialtyAccessCapabilities()
    }
}

/** Una concesion (`contenido_desbloqueado_usuario`) de un examen. */
internal data class ExamGrantFact(val motivo: String, val validFrom: Instant, val validUntil: Instant?) {
    fun activeAt(now: Instant): Boolean = !validFrom.isAfter(now) && (validUntil == null || validUntil.isAfter(now))
}

/** La proyeccion `estado_premium_usuario`. */
internal data class PremiumFact(val esPremium: Boolean, val status: String, val endsAt: Instant?) {
    /**
     * Premium VIGENTE: la proyeccion lo marca, el estado es EXPLICITAMENTE uno de los que dan acceso y `fin_en` no ha pasado
     * (Q-04: nada en la base caduca la fila cuando `fin_en` vence, asi que se re-comprueba al leer).
     */
    fun activeAt(now: Instant): Boolean =
        esPremium && status in ACCESS_STATUSES && (endsAt == null || endsAt.isAfter(now))

    companion object {
        /**
         * Lista BLANCA (deny-by-default): un estado desconocido NO da acceso aunque `es_premium` sea true. Los seis valores del
         * CHECK `estado_premium_usuario_estado_suscripcion_check` de MAIN, clasificados uno a uno:
         *   activa, periodo_prueba -> acceso; cancelada -> acceso hasta `fin_en` (la renovacion esta cancelada, el periodo
         *   pagado sigue vigente); pausada, caducada, sin_suscripcion -> sin acceso.
         * Es el mismo conjunto que `EntitlementService.LIVE_STATUSES` (CatalogService.kt).
         */
        internal val ACCESS_STATUSES = setOf("activa", "periodo_prueba", "cancelada")

        /** Los estados del CHECK de MAIN que NO dan acceso (documentados para que la clasificacion sea total). */
        internal val NON_ACCESS_STATUSES = setOf("pausada", "caducada", "sin_suscripcion")
    }
}

/** Lo que la politica necesita saber DEL USUARIO respecto de UN examen. */
internal data class UserAccessFacts(
    val premium: PremiumFact? = null,
    val grants: List<ExamGrantFact> = emptyList(),
)

internal object EntitlementPolicy {
    fun check(
        exam: ExamAccessSubject,
        capabilities: SpecialtyAccessCapabilities,
        user: UserAccessFacts,
        now: Instant,
    ): AccessVerdict {
        if (!capabilities.allows(exam.kind)) {
            return AccessVerdict(AccessDecision.DENY, AccessState.LOCKED, AccessDenyReason.CAPABILITY_DISABLED)
        }
        if (exam.free) return AccessVerdict(AccessDecision.ALLOW_FREE, AccessState.FREE)
        if (user.grants.any { it.activeAt(now) }) {
            return AccessVerdict(AccessDecision.ALLOW_PURCHASED, AccessState.GRANTED)
        }
        if (user.premium?.activeAt(now) == true) {
            return AccessVerdict(AccessDecision.ALLOW_PREMIUM, AccessState.SUBSCRIPTION)
        }
        if (exam.demoAvailable && capabilities.allowsDemos) {
            return AccessVerdict(AccessDecision.DENY, AccessState.DEMO, AccessDenyReason.DEMO_ONLY)
        }
        return AccessVerdict(AccessDecision.DENY, AccessState.LOCKED, AccessDenyReason.NOT_ENTITLED)
    }
}
