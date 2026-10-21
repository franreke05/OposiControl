package com.oposicontrol.backend

// =====================================================================
// Pagos de tienda: decisiones de negocio ABIERTAS (D1..D11 del contrato, G1 y N3-6 de la re-auditoria) en UN solo sitio.
// Documento humano: docs/payments/DECISIONS_PENDING.md (pregunta, defecto conservador, clave, quien decide). `StoreDecisionsTest` comprueba
// que cada id y cada clave de [STORE_DECISIONS] esta en ese documento.
//
// Regla de los defectos: NUNCA acreditar de mas. Sin decision explicita:
//  - G1: las compras de PRODUCCION se verifican y se guardan, pero no se acreditan (ni OT ni Premium) hasta encender
//    PAYMENTS_STORE_LIVE_CREDIT_ENABLED, que ademas exige credenciales completas y una referencia a la evidencia de sandbox.
//  - D8: suscripciones apagadas (no se venden ni se aplican).
//  - D11: la revocacion / reembolso de una suscripcion sigue esperando al interruptor de reembolsos (comportamiento actual); que el acceso siga el
//    estado firmado de la tienda es SOLO una propuesta (PAYMENTS_STORE_SUBSCRIPTION_REVOCATION_IMMEDIATE=true).
//  - D7: nunca se envian datos de consumo a Apple; encenderlo no esta implementado y no arranca.
// =====================================================================

internal data class StoreDecision(
    val id: String,
    val question: String,
    /** Variable de entorno que lo cambia, o null si hoy lo fija la base / el codigo (cambiarlo = migracion o cambio de codigo revisado). */
    val configKey: String?,
    val conservativeDefault: String,
    val decidedBy: String,
)

internal val STORE_DECISIONS: List<StoreDecision> = listOf(
    StoreDecision("G1", "Acreditar compras de produccion (OT y Premium) de verdad", STORE_LIVE_CREDIT_ENV,
        "false: se verifican y se guardan, no se acreditan (503 al cliente, aviso retenido)", "Propietario, tras credenciales + evidencia de sandbox"),
    StoreDecision("D1", "Credito por SKU o por tolerancia de precio", STORE_CREDIT_POLICY_ENV, "sku (precio del catalogo; el de la tienda solo se audita)", "Propietario + Control"),
    StoreDecision("D2", "Reembolsos activos en produccion", REFUNDS_ENABLED_ENV, "false fuera de local/test: el reembolso se GUARDA (retenido) y se aplica al encenderlo", "Propietario + Control"),
    StoreDecision("D3", "Re-acreditar tras REFUND_REVERSED", null, "nunca automatico: el pedido pasa a revision de Control", "Control"),
    StoreDecision("D4", "Compras sandbox en produccion", "PAYMENTS_STORE_SANDBOX", "reject (allowlist solo con PAYMENTS_STORE_SANDBOX_USERS)", "Propietario"),
    StoreDecision("D5", "Transferir una compra / suscripcion entre cuentas", null, "409 STORE_PURCHASE_BOUND_TO_OTHER", "Propietario"),
    StoreDecision("D6", "Libro mayor y bandeja al borrar la cuenta; retencion de la bandeja", STORE_RETENTION_ENV,
        "sin purga (vacio); el reembolso de una cuenta borrada queda huerfano retenido", "Legal"),
    StoreDecision("D7", "Enviar datos de consumo a Apple (CONSUMPTION_REQUEST)", STORE_CONSUMPTION_ENV, "false (solo se registra); true no esta implementado y no arranca", "Propietario + Legal"),
    StoreDecision("D8", "Suscripciones de tienda en V1", STORE_SUBSCRIPTIONS_ENV, "false: no se venden ni se aplican (verify 503, aviso retenido)", "Propietario"),
    StoreDecision("D9", "Custodia de secretos de tienda", null, "ficheros 0600 en /etc/oposibots/production, nunca en el repo ni en logs", "Propietario + infra"),
    StoreDecision("D10", "Equivalencia OT/EUR y comision de la tienda", null, "precio y OT del catalogo (productos_comerciales); la comision no cambia el credito", "Propietario"),
    StoreDecision("D11", "La revocacion de una suscripcion quita el acceso al instante aunque los reembolsos esten apagados", STORE_SUB_REVOCATION_ENV,
        "false (espera al interruptor D2); true = PROPUESTA: el acceso sigue el estado firmado de la tienda", "Propietario + Control"),
    StoreDecision("N3-6", "Intentos antes de dead letter en produccion", STORE_DEAD_LETTER_ENV, "0 = nunca (nada se descarta); recomendado 20", "Propietario"),
    StoreDecision("N3-7", "Purgar filas *_HELD cuando el pedido sale de revision", null, "nunca se purgan (deciden dinero)", "Propietario + Legal"),
)

internal const val STORE_LIVE_CREDIT_ENV = "PAYMENTS_STORE_LIVE_CREDIT_ENABLED"
internal const val STORE_SANDBOX_EVIDENCE_ENV = "PAYMENTS_STORE_SANDBOX_EVIDENCE_REF"
internal const val STORE_CREDIT_POLICY_ENV = "PAYMENTS_STORE_CREDIT_POLICY"
internal const val STORE_CONSUMPTION_ENV = "PAYMENTS_APPLE_CONSUMPTION_RESPONSES"
internal const val STORE_SUBSCRIPTIONS_ENV = "PAYMENTS_STORE_SUBSCRIPTIONS_ENABLED"
internal const val STORE_SUB_REVOCATION_ENV = "PAYMENTS_STORE_SUBSCRIPTION_REVOCATION_IMMEDIATE"
internal const val STORE_DEAD_LETTER_ENV = "PAYMENTS_STORE_DEAD_LETTER_ATTEMPTS"
internal const val STORE_RETENTION_ENV = "PAYMENTS_STORE_INBOX_RETENTION_DAYS"

/** Los valores efectivos de las decisiones. El constructor por defecto es el CONSERVADOR (nada se acredita de mas). */
internal data class StorePaymentDecisions(
    /** G1. */
    val liveCredit: Boolean = false,
    /** D2. */
    val refundsEnabled: Boolean = false,
    /** D8. */
    val subscriptionsEnabled: Boolean = false,
    /** D11 (propuesta). */
    val subscriptionRevocationImmediate: Boolean = false,
    /** N3-6 / NEW-4: 0 = nunca. */
    val deadLetterAttempts: Int = 0,
    /** D6 / NEW-4: null = no se purga nada. */
    val retentionDays: Int? = null,
) {
    companion object {
        val CONSERVATIVE = StorePaymentDecisions()

        /** Lee y valida (sin valores en los mensajes). Lo llama [StorePaymentsSettings.fromEnvironment]. */
        fun fromEnvironment(environment: (String) -> String?, anyPlatformEnabled: Boolean): StorePaymentDecisions {
            fun flag(name: String): Boolean = when (environment(name)?.trim()?.lowercase()) {
                null, "", "false" -> false
                "true" -> true
                else -> throw StoreConfigException("$name debe ser true o false")
            }
            when (environment(STORE_CREDIT_POLICY_ENV)?.trim()?.lowercase()) {
                null, "", "sku" -> Unit
                else -> throw StoreConfigException("$STORE_CREDIT_POLICY_ENV: solo 'sku' esta implementado (D1)")
            }
            if (flag(STORE_CONSUMPTION_ENV)) throw StoreConfigException("$STORE_CONSUMPTION_ENV=true no esta implementado (D7)")
            val live = flag(STORE_LIVE_CREDIT_ENV)
            if (live && !anyPlatformEnabled) throw StoreConfigException("$STORE_LIVE_CREDIT_ENV=true sin ninguna tienda activada con credenciales")
            if (live && environment(STORE_SANDBOX_EVIDENCE_ENV).isNullOrBlank()) {
                throw StoreConfigException("$STORE_LIVE_CREDIT_ENV=true exige $STORE_SANDBOX_EVIDENCE_ENV (referencia a la evidencia de sandbox)")
            }
            val deadLetter = environment(STORE_DEAD_LETTER_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw StoreConfigException("$STORE_DEAD_LETTER_ENV debe ser un entero >= 0")
            } ?: 0
            val retention = environment(STORE_RETENTION_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { n -> n >= 30 } ?: throw StoreConfigException("$STORE_RETENTION_ENV debe ser un entero >= 30")
            }
            return StorePaymentDecisions(
                liveCredit = live,
                refundsEnabled = refundsEnabledFrom(environment),
                subscriptionsEnabled = flag(STORE_SUBSCRIPTIONS_ENV),
                subscriptionRevocationImmediate = flag(STORE_SUB_REVOCATION_ENV),
                deadLetterAttempts = deadLetter,
                retentionDays = retention,
            )
        }
    }
}
