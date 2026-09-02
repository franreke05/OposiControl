package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdminSubscriptionsTest {

    private fun plan(
        codigo: String = "inmersivo",
        nombre: String = "Inmersivo",
        activo: Boolean = true,
        visible: Boolean = true,
        nivel: Int = 3,
    ) = AdminPlanDto(
        codigo = codigo,
        nombre = nombre,
        nivel = nivel,
        activo = activo,
        visible = visible,
    )

    // --- planes ---

    @Test
    fun aValidPlanIsNormalised() {
        // El código se usa como identificador estable en contratos, así que se
        // normaliza para que "Inmersivo" y "inmersivo" no acaben siendo dos.
        val saved = plan(codigo = "  Inmersivo  ", nombre = " Inmersivo ").validated()

        assertEquals("inmersivo", saved.codigo)
        assertEquals("Inmersivo", saved.nombre)
    }

    @Test
    fun aVisiblePlanMustBeActive() {
        // Se mostraría en el catálogo y no se podría contratar: vía muerta.
        assertEquals(
            "PLAN_VISIBLE_BUT_INACTIVE",
            assertFailsWith<BackendDomainException> {
                plan(activo = false, visible = true).validated()
            }.errorCode,
        )
    }

    @Test
    fun anInactiveHiddenPlanIsAllowedWhileItIsBeingPrepared() {
        plan(activo = false, visible = false).validated()
    }

    @Test
    fun requiredPlanFieldsAreEnforced() {
        assertEquals(
            "PLAN_CODE_REQUIRED",
            assertFailsWith<BackendDomainException> { plan(codigo = " ").validated() }.errorCode,
        )
        assertEquals(
            "PLAN_NAME_REQUIRED",
            assertFailsWith<BackendDomainException> { plan(nombre = "").validated() }.errorCode,
        )
    }

    @Test
    fun aNegativeLevelIsRejected() {
        assertEquals(
            "PLAN_LEVEL_NEGATIVE",
            assertFailsWith<BackendDomainException> { plan(nivel = -1).validated() }.errorCode,
        )
    }

    @Test
    fun levelZeroIsValidBecauseTheFreePlanExists() {
        assertEquals(0, plan(codigo = "free", nombre = "Gratuito", nivel = 0).validated().nivel)
    }

    // --- beneficios (modelo EAV) ---

    private fun benefit(
        boolean: Boolean? = null,
        integer: Int? = null,
        numeric: Double? = null,
        text: String? = null,
        codigo: String = "acceso_inmersivos",
    ) = AdminPlanBenefitDto(
        codigoBeneficio = codigo,
        valorBoolean = boolean,
        valorInteger = integer,
        valorNumeric = numeric,
        valorText = text,
    )

    @Test
    fun aBenefitWithExactlyOneValueIsValid() {
        assertEquals(true, benefit(boolean = true).validated().valorBoolean)
        assertEquals(40, benefit(integer = 40).validated().valorInteger)
        assertEquals(1.5, benefit(numeric = 1.5).validated().valorNumeric)
        assertEquals("texto", benefit(text = "texto").validated().valorText)
    }

    @Test
    fun aBenefitWithNoValueIsRejected() {
        // No diría nada: el consumidor no puede derivar ninguna capacidad.
        assertEquals(
            "BENEFIT_WITHOUT_VALUE",
            assertFailsWith<BackendDomainException> { benefit().validated() }.errorCode,
        )
    }

    @Test
    fun aBenefitWithTwoValuesIsRejectedBecauseItIsAmbiguous() {
        // Cuál gana dependería de qué lea primero el consumidor, que es peor que
        // no tener el beneficio.
        assertEquals(
            "BENEFIT_AMBIGUOUS_VALUE",
            assertFailsWith<BackendDomainException> {
                benefit(boolean = true, integer = 40).validated()
            }.errorCode,
        )
    }

    @Test
    fun aFalseBooleanCountsAsAValuePresent() {
        // `false` es una decisión explícita ("este plan NO da acceso"), no la
        // ausencia de valor. Tratarlo como vacío borraría esa decisión.
        assertEquals(1, benefit(boolean = false).filledValueCount)
        assertEquals(false, benefit(boolean = false).validated().valorBoolean)
    }

    @Test
    fun aZeroIntegerCountsAsAValuePresent() {
        // "cero OposiTickets diarios" es una configuración legítima.
        assertEquals(1, benefit(integer = 0).filledValueCount)
        assertEquals(0, benefit(integer = 0).validated().valorInteger)
    }

    @Test
    fun blankTextIsStoredAsNullRatherThanEmpty() {
        // Un texto en blanco no es un valor; se rechaza como beneficio sin valor
        // en lugar de guardar una cadena vacía que nadie sabe interpretar.
        assertFailsWith<BackendDomainException> { benefit(text = "   ").validated() }
    }

    @Test
    fun aBenefitNeedsACode() {
        assertEquals(
            "BENEFIT_CODE_REQUIRED",
            assertFailsWith<BackendDomainException> { benefit(boolean = true, codigo = " ").validated() }.errorCode,
        )
    }

    @Test
    fun benefitCodesAreNotConstrainedToAGuessedEnum() {
        // El catálogo de beneficios crece con el producto; codificar aquí una
        // lista cerrada obligaría a desplegar para añadir uno.
        benefit(boolean = true, codigo = "un_beneficio_futuro").validated()
    }

    // --- forma del DTO ---

    @Test
    fun planRowRoundTripPreservesEveryField() {
        val original = AdminPlanDto(
            id = "p-1",
            codigo = "rafagas",
            nombre = "Ráfagas",
            descripcion = "Solucionario sin explicación",
            nivel = 1,
            activo = true,
            visible = true,
            posicion = 2,
        )

        val back = original.toRow().toDto(emptyList())

        assertEquals(original.codigo, back.codigo)
        assertEquals(original.nombre, back.nombre)
        assertEquals(original.descripcion, back.descripcion)
        assertEquals(original.nivel, back.nivel)
        assertEquals(original.posicion, back.posicion)
    }

    @Test
    fun pricesAreDeliberatelyAbsentFromThePlanModel() {
        // Los precios viven en tarifas_producto_comercial, por periodo y fase,
        // que es lo que permite representar alta y renovación sin duplicar plan.
        // Si alguien añadiese un precio aquí, este test debería hacerle pensar.
        val plan = plan()
        assertNull(
            plan::class.members.firstOrNull { it.name.contains("precio", ignoreCase = true) },
            "El plan no debe llevar precio: eso vive en tarifas_producto_comercial.",
        )
    }
}
