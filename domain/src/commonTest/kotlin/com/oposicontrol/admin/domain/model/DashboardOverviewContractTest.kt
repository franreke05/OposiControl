package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fija por su literal el contrato de cable del dashboard.
 *
 * ### Por qué a mano, y por qué en dos sitios
 *
 * `:backend:ktor` no depende de `:domain` y no debe hacerlo: el servidor también
 * atiende a consumidores externos y acoplarlo al dominio de este cliente sería
 * peor que la duplicación. La consecuencia es que las 17 claves de métrica y los
 * 6 nombres de franja existen dos veces, y un renombrado en un solo lado
 * **compila sin quejarse**.
 *
 * El fallo que produce esa deriva es especialmente malo por silencioso: si una
 * clave se renombra en el backend, `DashboardBlockReport.metric(key)` devuelve
 * `null`, `renderable` no la incluye, y la tarjeta **desaparece de la pantalla
 * sin que nada falle ni se registre**. Con los nombres de franja es peor todavía
 * y además diferido: la carga inicial omite `?sections=` —así que abre bien— y es
 * el refresco automático el que empieza a recibir 400 un minuto después.
 *
 * Como no hay forma de comparar las dos listas en un mismo proceso, la red es
 * ésta: cada lado fija los mismos literales, escritos a mano. Cualquier
 * renombrado rompe uno de los dos tests. Es lo máximo que permite la topología de
 * módulos y basta para que la deriva no llegue nunca a producción en silencio.
 *
 * **Si cambias un literal aquí, cambia también su espejo en `:backend:ktor`.**
 */
class DashboardOverviewContractTest {

    @Test
    fun `los nombres de franja del cable son exactamente estos seis`() {
        assertEquals(
            listOf("system", "attention", "pulse", "content", "activity", "economy"),
            DashboardBlock.entries.map { it.wire },
            "Los nombres de franja viajan en `?sections=` y el backend responde 400 ante uno " +
                "desconocido. Si cambias uno, cambia su espejo en :backend:ktor o el refresco " +
                "por franja empezará a fallar en runtime sin fallar en compilación.",
        )
    }

    @Test
    fun `fromWire acepta los seis nombres y rechaza cualquier otro`() {
        DashboardBlock.entries.forEach { block ->
            assertEquals(block, DashboardBlock.fromWire(block.wire))
        }
        // Tolerancia declarada: espacios y mayúsculas sí; inventar, no.
        assertEquals(DashboardBlock.SYSTEM, DashboardBlock.fromWire("  SYSTEM  "))
        assertEquals(null, DashboardBlock.fromWire("sistema"))
        assertEquals(null, DashboardBlock.fromWire(""))
    }

    @Test
    fun `las diecisiete claves de metrica del cable son exactamente estas`() {
        // Escritas a mano, no derivadas de las constantes: un test que leyera
        // `DashboardMetricKeys.TOTAL_EXAMS` para comprobar que vale "totalExams"
        // pasaría igual después de renombrar la cadena, que es justo el caso que
        // esto tiene que detectar.
        val esperadas = listOf(
            // ATTENTION
            "reportsPending",
            "questionFeedbackPending",
            "draftExams",
            "subscriptionsExpiring7d",
            // PULSE
            "activeUsers30d",
            "attemptsLast30d",
            "publishedExams",
            "globalErrorRate",
            // CONTENT
            "totalExams",
            "totalQuestions",
            "totalResources",
            "importBatches",
            // ECONOMY
            "subscriptionsActive",
            "subscriptionsExpiring30d",
            "commercialProductsActive",
            "grossRevenueCents",
            "ticketsInCirculation",
        )

        val declaradas = listOf(
            DashboardMetricKeys.REPORTS_PENDING,
            DashboardMetricKeys.QUESTION_FEEDBACK_PENDING,
            DashboardMetricKeys.DRAFT_EXAMS,
            DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_7D,
            DashboardMetricKeys.ACTIVE_USERS_30D,
            DashboardMetricKeys.ATTEMPTS_LAST_30D,
            DashboardMetricKeys.PUBLISHED_EXAMS,
            DashboardMetricKeys.GLOBAL_ERROR_RATE,
            DashboardMetricKeys.TOTAL_EXAMS,
            DashboardMetricKeys.TOTAL_QUESTIONS,
            DashboardMetricKeys.TOTAL_RESOURCES,
            DashboardMetricKeys.IMPORT_BATCHES,
            DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE,
            DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D,
            DashboardMetricKeys.COMMERCIAL_PRODUCTS_ACTIVE,
            DashboardMetricKeys.GROSS_REVENUE_CENTS,
            DashboardMetricKeys.TICKETS_IN_CIRCULATION,
        )

        assertEquals(
            esperadas,
            declaradas,
            "Estas claves las nombra el backend en su respuesta y la UI las busca por nombre. " +
                "Un renombrado en un solo lado hace desaparecer la tarjeta de la pantalla sin " +
                "que nada falle. Si cambias una, cambia su espejo en :backend:ktor.",
        )
        assertEquals(17, declaradas.size)
        assertEquals(declaradas.size, declaradas.distinct().size, "Hay una clave repetida.")
    }

    @Test
    fun `renderable deja fuera lo no instrumentado y conserva lo que no se pudo leer`() {
        val report = DashboardBlockReport(
            block = DashboardBlock.ECONOMY,
            metrics = listOf(
                AnalyticsMetric("subscriptionsActive", AnalyticsValue.Count(7), AnalyticsMetricStatus.AVAILABLE),
                // Un cero legítimo ES un dato y debe sobrevivir.
                AnalyticsMetric("commercialProductsActive", AnalyticsValue.Count(0), AnalyticsMetricStatus.AVAILABLE),
                AnalyticsMetric("subscriptionsExpiring30d", null, AnalyticsMetricStatus.NO_DATA),
                AnalyticsMetric("grossRevenueCents", null, AnalyticsMetricStatus.NOT_INSTRUMENTED, "Necesita SUM"),
            ),
            generatedAt = kotlinx.datetime.Instant.parse("2026-09-10T10:00:00Z"),
        )

        assertEquals(
            listOf("subscriptionsActive", "commercialProductsActive", "subscriptionsExpiring30d"),
            report.renderable.map { it.key },
            "NOT_INSTRUMENTED no ocupa sitio en el panel; NO_DATA sí, porque reintentar puede " +
                "arreglarlo y el operador necesita saber que ese número le falta.",
        )
        assertEquals(2, report.availableCount)
    }
}
