package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fija por su literal el contrato de cable del dashboard, **lado backend**.
 *
 * ### Espejo
 *
 * Su reflejo vive en `:domain`, en
 * `domain/src/commonTest/.../model/DashboardOverviewContractTest.kt`, y afirma
 * exactamente los mismos literales en el mismo orden.
 *
 * Están duplicados porque `:backend:ktor` no depende de `:domain` y no debe
 * hacerlo: este servidor también atiende a consumidores que no son este cliente,
 * y acoplarlo al dominio del panel sería peor que la duplicación. La
 * consecuencia es que las 17 claves de métrica y los 6 nombres de franja existen
 * dos veces, y un renombrado en un solo lado **compila sin quejarse en ninguno**.
 *
 * El fallo que produce esa deriva es especialmente malo por silencioso: si una
 * clave se renombra aquí, `DashboardBlockReport.metric(key)` devuelve `null` al
 * otro lado, `renderable` no la incluye, y la tarjeta desaparece de la pantalla
 * sin que nada falle ni se registre. Con los nombres de franja es peor y además
 * diferido: la carga inicial omite `?sections=` —así que abre bien— y es el
 * refresco por franja el que empieza a recibir 400 un minuto después.
 *
 * Como no hay forma de comparar las dos listas en un mismo proceso, la red es
 * ésta: cada lado fija los mismos literales, escritos a mano. Cualquier
 * renombrado rompe uno de los dos tests.
 *
 * **Si cambias un literal aquí, cambia también su espejo en `:domain`.**
 */
class AdminDashboardOverviewContractTest {

    @Test
    fun theSixSectionNamesOnTheWireAreExactlyThese() {
        // A mano, no derivados de las constantes: comprobar que
        // `DashboardOverviewBlock.SYSTEM` vale `DashboardOverviewBlock.SYSTEM`
        // pasaría igual después de renombrar la cadena, que es justo el caso a
        // detectar.
        assertEquals(
            listOf("system", "attention", "pulse", "content", "activity", "economy"),
            DashboardOverviewBlock.ALL.toList(),
            "Estos nombres llegan en `?sections=` y esta ruta responde 400 ante uno desconocido. " +
                "Si cambias uno, cambia su espejo en :domain o el refresco por franja del panel " +
                "empezará a fallar en runtime sin fallar en compilación.",
        )
    }

    @Test
    fun theSectionParserAcceptsExactlyThoseSixNames() {
        // El parser y la lista no pueden divergir: si `ALL` creciera y
        // `parseDashboardSections` no lo supiera, la franja nueva se rechazaría
        // con 400 pese a estar declarada como válida.
        DashboardOverviewBlock.ALL.forEach { name ->
            val selection = parseDashboardSections(name)
            assertEquals(
                DashboardSectionsSelection.Selected(setOf(name)),
                selection,
                "'$name' está en ALL pero el parser no lo acepta.",
            )
        }
    }

    @Test
    fun theSeventeenMetricKeysOnTheWireAreExactlyThese() {
        val expected = listOf(
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

        val declared = listOf(
            DashboardOverviewMetricKey.REPORTS_PENDING,
            DashboardOverviewMetricKey.QUESTION_FEEDBACK_PENDING,
            DashboardOverviewMetricKey.DRAFT_EXAMS,
            DashboardOverviewMetricKey.SUBSCRIPTIONS_EXPIRING_7D,
            DashboardOverviewMetricKey.ACTIVE_USERS_30D,
            DashboardOverviewMetricKey.ATTEMPTS_LAST_30D,
            DashboardOverviewMetricKey.PUBLISHED_EXAMS,
            DashboardOverviewMetricKey.GLOBAL_ERROR_RATE,
            DashboardOverviewMetricKey.TOTAL_EXAMS,
            DashboardOverviewMetricKey.TOTAL_QUESTIONS,
            DashboardOverviewMetricKey.TOTAL_RESOURCES,
            DashboardOverviewMetricKey.IMPORT_BATCHES,
            DashboardOverviewMetricKey.SUBSCRIPTIONS_ACTIVE,
            DashboardOverviewMetricKey.SUBSCRIPTIONS_EXPIRING_30D,
            DashboardOverviewMetricKey.COMMERCIAL_PRODUCTS_ACTIVE,
            DashboardOverviewMetricKey.GROSS_REVENUE_CENTS,
            DashboardOverviewMetricKey.TICKETS_IN_CIRCULATION,
        )

        assertEquals(
            expected,
            declared,
            "Este backend nombra estas claves en su respuesta y la UI las busca por nombre. " +
                "Un renombrado en un solo lado hace desaparecer la tarjeta de la pantalla sin " +
                "que nada falle. Si cambias una, cambia su espejo en :domain.",
        )
        assertEquals(17, declared.size)
        assertEquals(declared.size, declared.distinct().size, "Hay una clave repetida.")
    }

    @Test
    fun everyDeclaredKeyIsAlsoAFieldOfItsBlockDto() {
        // La otra mitad de la red: los literales de arriba coinciden con :domain,
        // y éstos coinciden con lo que realmente se serializa. Sin este segundo
        // paso, las dos listas podrían estar de acuerdo entre sí y ninguna de las
        // dos coincidir con el JSON.
        val fields = listOf(
            AdminDashboardAttentionDto::class,
            AdminDashboardPulseDto::class,
            AdminDashboardContentDto::class,
            AdminDashboardEconomyDto::class,
        ).flatMap { klass -> klass.java.declaredFields.map { it.name } }.toSet()

        listOf(
            "reportsPending", "questionFeedbackPending", "draftExams", "subscriptionsExpiring7d",
            "activeUsers30d", "attemptsLast30d", "publishedExams", "globalErrorRate",
            "totalExams", "totalQuestions", "totalResources", "importBatches",
            "subscriptionsActive", "subscriptionsExpiring30d", "commercialProductsActive",
            "grossRevenueCents", "ticketsInCirculation",
        ).forEach { key ->
            assertEquals(
                true,
                key in fields,
                "La clave '$key' no corresponde a ningún campo serializado: viajaría en " +
                    "unavailableMetrics apuntando a un campo que no existe.",
            )
        }
    }
}
