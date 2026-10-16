package com.oposicontrol.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GATE-1 (E2): paridad entre la politica Kotlin ([EntitlementPolicy]) y la UNICA definicion SQL del veredicto
 * (`private.exam_access_verdict`, migracion 20261018100300), sobre una MATRIZ generada de hechos.
 *
 * Este test (1) comprueba el tamano y la cobertura de la matriz y (2) cuando existe `PARITY_OUT`, vuelca matriz + veredictos de Kotlin a ese
 * fichero JSON. `ops/local-exam/verdict-parity.mjs` ejecuta este test, construye los MISMOS hechos en la base local (dentro de una transaccion que
 * se deshace), evalua la funcion SQL con la misma hora y compara caso por caso: cualquier diferencia hace fallar el script.
 *
 * Si el propietario decide la regla de autorizacion (A/B/C, E1-V1-AUTHZ-DECISION.md), cambian a la vez la politica Kotlin y la funcion SQL, y este
 * test es lo que obliga a que cambien juntas.
 */
class AccessVerdictParityTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")

    private data class ExamCombo(val kind: ExamKind, val free: Boolean, val demo: Boolean, val caps: SpecialtyAccessCapabilities?)
    private data class UserCombo(val premium: PremiumFact?, val grants: List<ExamGrantFact>)

    private fun days(n: Long) = now.plus(Duration.ofDays(n))

    private val exams: List<ExamCombo> = buildList {
        val capsVariants: List<SpecialtyAccessCapabilities?> = listOf<SpecialtyAccessCapabilities?>(null) + (0 until 16).map {
            SpecialtyAccessCapabilities(it and 1 != 0, it and 2 != 0, it and 4 != 0, it and 8 != 0)
        }
        for (kind in ExamKind.entries) for (free in listOf(true, false)) for (demo in listOf(true, false)) for (caps in capsVariants) add(ExamCombo(kind, free, demo, caps))
    }

    private val users: List<UserCombo> = buildList {
        val premiums: List<PremiumFact?> = buildList {
            add(null)
            for (status in listOf("activa", "periodo_prueba", "cancelada", "pausada", "caducada", "sin_suscripcion")) {
                for (end in listOf(days(30), days(-1), null)) add(PremiumFact(true, status, end))
            }
            add(PremiumFact(false, "activa", days(30)))
        }
        val grantSets: List<List<ExamGrantFact>> = listOf(
            emptyList(),
            listOf(ExamGrantFact("compra", days(-10), days(20))),
            listOf(ExamGrantFact("compra", days(-10), null)),
            listOf(ExamGrantFact("compra", days(-40), days(-10))),
            listOf(ExamGrantFact("compra", days(5), days(35))),
            listOf(ExamGrantFact("compra", days(-40), days(-10)), ExamGrantFact("admin", days(-1), days(1))),
            listOf(ExamGrantFact("regalo", days(-40), days(-10)), ExamGrantFact("promocion", days(-2), null)),
        )
        for (premium in premiums) for (grants in grantSets) add(UserCombo(premium, grants))
    }

    /** Relacion especialidad usuario/examen (puerta de especialidad, propietario 2026-10-16): 0 = sin especialidad elegida, 1 = la del examen, 2 = otra. */
    private val modes = listOf(0, 1, 2)

    private fun verdict(exam: ExamCombo, user: UserCombo, mode: Int): AccessState = EntitlementPolicy.check(
        exam = ExamAccessSubject("exam", exam.kind, free = exam.free, demoAvailable = exam.demo, specialtyId = "E"),
        capabilities = exam.caps ?: SpecialtyAccessCapabilities.OPEN,
        user = UserAccessFacts(premium = user.premium, grants = user.grants, specialtyId = when (mode) { 0 -> null; 1 -> "E"; else -> "X" }),
        now = now,
    ).state

    private fun code(state: AccessState): Char = when (state) {
        AccessState.FREE -> 'F'
        AccessState.GRANTED -> 'G'
        AccessState.SUBSCRIPTION -> 'S'
        AccessState.DEMO -> 'D'
        AccessState.LOCKED -> 'L'
    }

    @Test
    fun theMatrixIsBigAndReachesEveryStateOfTheContract() {
        val seen = mutableSetOf<AccessState>()
        var cases = 0
        for (user in users) for (exam in exams) for (mode in modes) {
            seen += verdict(exam, user, mode)
            cases++
        }
        assertEquals(204 * 140 * 3, cases)
        assertEquals(AccessState.entries.toSet(), seen, "la matriz tiene que producir los cinco estados")
    }

    @Test
    fun dumpTheMatrixAndTheKotlinVerdictsForTheSqlComparison() {
        val out = System.getenv("PARITY_OUT")?.takeIf { it.isNotBlank() } ?: return
        fun instant(value: Instant?) = value?.let { JsonPrimitive(it.toString()) } ?: JsonNull
        val json = JsonObject(
            mapOf(
                "now" to JsonPrimitive(now.toString()),
                "exams" to JsonArray(
                    exams.map { e ->
                        JsonObject(
                            mapOf(
                                "kind" to JsonPrimitive(e.kind.wire),
                                "free" to JsonPrimitive(e.free),
                                "demo" to JsonPrimitive(e.demo),
                                "caps" to (e.caps?.let { JsonArray(listOf(it.allowsRafagas, it.allowsRazonados, it.allowsInmersivos, it.allowsDemos).map(::JsonPrimitive)) } ?: JsonNull),
                            ),
                        )
                    },
                ),
                "users" to JsonArray(
                    users.map { u ->
                        JsonObject(
                            mapOf(
                                "premium" to (u.premium?.let {
                                    JsonObject(mapOf("esPremium" to JsonPrimitive(it.esPremium), "status" to JsonPrimitive(it.status), "endsAt" to instant(it.endsAt)))
                                } ?: JsonNull),
                                "grants" to JsonArray(
                                    u.grants.map {
                                        JsonObject(mapOf("motivo" to JsonPrimitive(it.motivo), "from" to instant(it.validFrom), "until" to instant(it.validUntil)))
                                    },
                                ),
                            ),
                        )
                    },
                ),
                // una cadena por usuario con una letra por (examen, modo de especialidad 0/1/2, en ese orden): F GRANTED=G SUBSCRIPTION=S DEMO=D LOCKED=L
                "expected" to JsonArray(users.map { u -> JsonPrimitive(exams.flatMap { e -> modes.map { m -> code(verdict(e, u, m)) } }.joinToString("")) }),
            ),
        )
        File(out).also { it.parentFile?.mkdirs() }.writeText(json.toString())
        assertTrue(File(out).length() > 0)
    }
}
