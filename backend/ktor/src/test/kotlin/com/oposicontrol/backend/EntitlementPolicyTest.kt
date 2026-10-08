package com.oposicontrol.backend

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D7-25: la unica politica de acceso. Tabla de decisiones, sin IO. */
class EntitlementPolicyTest {

    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val past = Instant.parse("2026-09-01T00:00:00Z")
    private val future = Instant.parse("2027-01-01T00:00:00Z")

    private fun exam(kind: ExamKind = ExamKind.RAFAGA, free: Boolean = false, demo: Boolean = false) =
        ExamAccessSubject("e1", kind, free, demo)

    private fun grant(from: Instant = past, until: Instant? = null, motivo: String = "compra") = ExamGrantFact(motivo, from, until)
    private fun premium(end: Instant? = future, status: String = "activa", flag: Boolean = true) = PremiumFact(flag, status, end)
    private fun check(
        exam: ExamAccessSubject,
        user: UserAccessFacts = UserAccessFacts(),
        caps: SpecialtyAccessCapabilities = SpecialtyAccessCapabilities.OPEN,
    ) = EntitlementPolicy.check(exam, caps, user, now)

    @Test
    fun aFreeExamIsAllowedForEveryone() {
        val verdict = check(exam(free = true))
        assertEquals(AccessDecision.ALLOW_FREE, verdict.decision)
        assertEquals(AccessState.FREE, verdict.state)
        assertTrue(verdict.allowed)
    }

    @Test
    fun aPaidExamWithoutAnythingIsLockedAndDenied() {
        val verdict = check(exam())
        assertEquals(AccessDecision.DENY, verdict.decision)
        assertEquals(AccessState.LOCKED, verdict.state)
        assertEquals(AccessDenyReason.NOT_ENTITLED, verdict.denyReason)
        assertFalse(verdict.allowed)
    }

    @Test
    fun anActiveGrantOfAnyMotivoGrantsAccess() {
        listOf("compra", "premium", "regalo", "admin", "promocion").forEach { motivo ->
            val verdict = check(exam(), UserAccessFacts(grants = listOf(grant(motivo = motivo))))
            assertEquals(AccessDecision.ALLOW_PURCHASED, verdict.decision, motivo)
            assertEquals(AccessState.GRANTED, verdict.state, motivo)
        }
    }

    @Test
    fun grantWindowsAreRespectedAtBothEnds() {
        assertEquals(AccessState.LOCKED, check(exam(), UserAccessFacts(grants = listOf(grant(until = past.plusSeconds(1))))).state, "expired")
        assertEquals(AccessState.LOCKED, check(exam(), UserAccessFacts(grants = listOf(grant(until = now)))).state, "expires exactly now")
        assertEquals(AccessState.LOCKED, check(exam(), UserAccessFacts(grants = listOf(grant(from = future)))).state, "not started")
        assertEquals(AccessState.GRANTED, check(exam(), UserAccessFacts(grants = listOf(grant(from = now)))).state, "starts exactly now")
        assertEquals(AccessState.GRANTED, check(exam(), UserAccessFacts(grants = listOf(grant(until = null)))).state, "open ended")
        assertEquals(AccessState.GRANTED, check(exam(), UserAccessFacts(grants = listOf(grant(until = past.plusSeconds(1)), grant(until = future)))).state, "any active")
    }

    @Test
    fun anActivePremiumGrantsSubscriptionAccess() {
        listOf("activa", "periodo_prueba", "cancelada").forEach { status ->
            val verdict = check(exam(), UserAccessFacts(premium = premium(status = status)))
            assertEquals(AccessDecision.ALLOW_PREMIUM, verdict.decision, status)
            assertEquals(AccessState.SUBSCRIPTION, verdict.state, status)
        }
        assertEquals(AccessState.SUBSCRIPTION, check(exam(), UserAccessFacts(premium = premium(end = null))).state, "no end date")
    }

    @Test
    fun aLapsedOrInactivePremiumNeverGrantsAccess() {
        assertEquals(AccessState.LOCKED, check(exam(), UserAccessFacts(premium = premium(end = past))).state, "fin_en passed although es_premium is still true (Q-04)")
        assertEquals(AccessState.LOCKED, check(exam(), UserAccessFacts(premium = premium(end = now))).state, "fin_en exactly now")
        listOf("pausada", "caducada", "sin_suscripcion").forEach { status ->
            assertEquals(AccessState.LOCKED, check(exam(), UserAccessFacts(premium = premium(status = status))).state, status)
        }
        assertEquals(AccessState.LOCKED, check(exam(), UserAccessFacts(premium = premium(flag = false))).state, "es_premium false")
    }

    /**
     * GATE SLICE 3 (F-08): lista BLANCA. Un `estado_suscripcion` que la politica no conoce NO da acceso aunque
     * `es_premium` sea true y `fin_en` siga en el futuro (antes solo se negaban tres estados y cualquier otro pasaba).
     */
    @Test
    fun anUnknownSubscriptionStatusNeverGrantsAccessEvenWithThePremiumFlag() {
        listOf("en_gracia", "trial", "ACTIVA", "activa ", "", "active", "reembolsada").forEach { status ->
            val verdict = check(exam(), UserAccessFacts(premium = premium(status = status, end = null)))
            assertEquals(AccessDecision.DENY, verdict.decision, "'$status'")
            assertEquals(AccessState.LOCKED, verdict.state, "'$status'")
            assertEquals(false, PremiumFact(true, status, future).activeAt(now), "'$status'")
        }
    }

    /** Los seis valores del CHECK `estado_premium_usuario_estado_suscripcion_check` de MAIN quedan clasificados, uno a uno. */
    @Test
    fun everyStatusOfTheMainCheckIsClassifiedExplicitly() {
        val mainCheck = setOf("sin_suscripcion", "activa", "periodo_prueba", "pausada", "cancelada", "caducada")
        assertEquals(setOf("activa", "periodo_prueba", "cancelada"), PremiumFact.ACCESS_STATUSES)
        assertEquals(setOf("pausada", "caducada", "sin_suscripcion"), PremiumFact.NON_ACCESS_STATUSES)
        assertEquals(mainCheck, PremiumFact.ACCESS_STATUSES + PremiumFact.NON_ACCESS_STATUSES, "clasificacion total")
        assertEquals(emptySet(), PremiumFact.ACCESS_STATUSES intersect PremiumFact.NON_ACCESS_STATUSES, "y disjunta")
        mainCheck.forEach { status ->
            assertEquals(status in PremiumFact.ACCESS_STATUSES, PremiumFact(true, status, future).activeAt(now), status)
        }
    }

    @Test
    fun precedenceIsFreeThenGrantedThenSubscriptionThenDemoThenLocked() {
        val all = UserAccessFacts(premium = premium(), grants = listOf(grant()))
        assertEquals(AccessState.FREE, check(exam(free = true, demo = true), all).state)
        assertEquals(AccessState.GRANTED, check(exam(demo = true), all).state)
        assertEquals(AccessState.SUBSCRIPTION, check(exam(demo = true), UserAccessFacts(premium = premium())).state)
        assertEquals(AccessState.DEMO, check(exam(demo = true)).state)
        assertEquals(AccessState.LOCKED, check(exam(demo = false)).state)
    }

    @Test
    fun demoIsDeniedForRealAccessAndNeedsTheSpecialtyToAllowDemos() {
        val demo = check(exam(demo = true))
        assertEquals(AccessDecision.DENY, demo.decision, "DEMO is a state, not an allow: starting the full exam stays denied")
        assertEquals(AccessDenyReason.DEMO_ONLY, demo.denyReason)
        val noDemos = check(exam(demo = true), caps = SpecialtyAccessCapabilities(allowsDemos = false))
        assertEquals(AccessState.LOCKED, noDemos.state)
    }

    @Test
    fun aDisabledCapabilityLocksEvenFreeGrantedAndPremiumContent() {
        val rich = UserAccessFacts(premium = premium(), grants = listOf(grant()))
        ExamKind.entries.forEach { kind ->
            val caps = when (kind) {
                ExamKind.RAFAGA -> SpecialtyAccessCapabilities(allowsRafagas = false)
                ExamKind.RAZONADO -> SpecialtyAccessCapabilities(allowsRazonados = false)
                ExamKind.INMERSIVO -> SpecialtyAccessCapabilities(allowsInmersivos = false)
            }
            listOf(exam(kind, free = true), exam(kind), exam(kind, demo = true)).forEach { subject ->
                val verdict = check(subject, rich, caps)
                assertEquals(AccessDecision.DENY, verdict.decision, "$kind $subject")
                assertEquals(AccessState.LOCKED, verdict.state)
                assertEquals(AccessDenyReason.CAPABILITY_DISABLED, verdict.denyReason)
            }
            // Los otros tipos de la misma especialidad no se ven afectados.
            ExamKind.entries.filter { it != kind }.forEach { other ->
                assertEquals(AccessState.FREE, check(exam(other, free = true), rich, caps).state)
            }
        }
    }

    @Test
    fun creditAccessIsNotReachableInSliceTwo() {
        val everything = listOf(
            UserAccessFacts(),
            UserAccessFacts(premium = premium()),
            UserAccessFacts(grants = listOf(grant())),
            UserAccessFacts(premium = premium(), grants = listOf(grant())),
        )
        val decisions = everything.flatMap { user ->
            listOf(true, false).flatMap { free -> listOf(true, false).map { demo -> check(exam(free = free, demo = demo), user).decision } }
        }
        assertFalse(AccessDecision.ALLOW_CREDIT in decisions)
    }

    @Test
    fun theLegacySynonymOfRazonadoIsNormalizedOnReadAndUnknownKindsAreRejected() {
        assertEquals(ExamKind.RAZONADO, ExamKind.fromWireOrNull("guiado"))
        assertEquals(ExamKind.RAZONADO, ExamKind.fromWireOrNull("razonado"))
        assertEquals("razonado", ExamKind.fromWireOrNull("guiado")!!.wire)
        assertNull(ExamKind.fromWireOrNull("Rafaga"))
        assertNull(ExamKind.fromWireOrNull("otro"))
        assertNull(ExamKind.fromWireOrNull(null))
    }
}
