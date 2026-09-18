package com.oposicontrol.backend

/**
 * Las invariantes administrativas de la sincronización de roles, escritas como
 * decisión pura.
 *
 * **Esto NO es la garantía.** La garantía vive dentro de
 * `public.admin_sync_roles`, en su propia transacción y detrás de un cerrojo
 * consultivo — ver `supabase/canonical/role_sync_guard_candidate_v1/`. Tiene que
 * estar ahí porque la RPC es alcanzable con la clave de `service_role` sin pasar
 * por este servidor: una comprobación en Ktor protegería a este cliente y a
 * nadie más, y además dejaría la ventana clásica de «leo, parece seguro, llamo».
 *
 * Lo que este fichero aporta es el **espejo** de esas reglas: una especificación
 * ejecutable que los tests pueden atacar sin base de datos, con los nueve
 * escenarios del contrato. Misma figura que
 * `AdminDashboardOverviewContractTest` y su reflejo en `:domain` (DEBT-018): dos
 * copias escritas a mano, cada una con su test, porque no hay forma de
 * compararlas en un mismo proceso.
 *
 * **Si cambias una regla aquí, cámbiala también en el SQL del candidate**, y al
 * revés. `RoleSyncInvariantsTest.laGarantiaDefinitivaSigueEnElSql` vigila que la
 * del SQL no desaparezca.
 */
object RoleSyncInvariants {

    /** Roles que dan acceso al panel. Espejo de [CanonicalAuthorization.adminRoleCodes]. */
    val ADMINISTRATIVE_ROLE_CODES: Set<String> = CanonicalAuthorization.adminRoleCodes

    /** Espejo de [CanonicalRole.SUPERADMIN]. */
    val SUPERADMIN_CODE: String = CanonicalRole.SUPERADMIN.code

    /**
     * Estado necesario para decidir, expresado en códigos de rol y no en
     * identificadores: las invariantes hablan de privilegios, no de filas.
     *
     * [superadminsBefore] es el recuento **global** anterior a la operación, y
     * por eso la decisión no se puede tomar mirando sólo al usuario afectado.
     */
    data class State(
        val currentRoleCodes: Set<String>,
        val requestedRoleCodes: Set<String>,
        val knownRoleCodes: Set<String>,
        val superadminsBefore: Int,
    )

    /** Por qué se rechaza una sincronización. `null` = se acepta. */
    enum class Rejection(val code: String) {
        /** Algún rol pedido no existe en el catálogo. */
        UNKNOWN_ROLE("ROLE_SYNC_UNKNOWN_ROLE"),

        /** Un usuario con acceso al panel se quedaría sin ningún rol. */
        EMPTY_ROLES("ROLE_SYNC_EMPTY_ROLES"),

        /** La operación dejaría el sistema sin ningún superadministrador. */
        LAST_SUPERADMIN("ROLE_SYNC_LAST_SUPERADMIN"),
    }

    /** ¿El usuario tiene hoy acceso administrativo? */
    fun isAdministrative(roleCodes: Set<String>): Boolean =
        roleCodes.any { it.trim().lowercase() in ADMINISTRATIVE_ROLE_CODES }

    /**
     * Superadministradores que quedarían tras aplicar [state].
     *
     * Sólo cambia por el usuario afectado: gana uno si lo recibe sin tenerlo,
     * pierde uno si lo tenía y no lo conserva.
     */
    fun superadminsAfter(state: State): Int {
        val teniaSuperadmin = SUPERADMIN_CODE in state.currentRoleCodes.normalized()
        val tendraSuperadmin = SUPERADMIN_CODE in state.requestedRoleCodes.normalized()
        val delta = when {
            teniaSuperadmin && !tendraSuperadmin -> -1
            !teniaSuperadmin && tendraSuperadmin -> +1
            else -> 0
        }
        return (state.superadminsBefore + delta).coerceAtLeast(0)
    }

    /**
     * Motivo de rechazo, o `null` si la operación es legítima.
     *
     * El orden importa y es el mismo que en el SQL: primero lo que no existe,
     * después la invariante A, y sólo al final la B, que es la que se evalúa
     * sobre el estado resultante.
     */
    fun rejectionFor(state: State): Rejection? {
        val pedidos = state.requestedRoleCodes.normalized()
        val conocidos = state.knownRoleCodes.normalized()
        if (pedidos.any { it !in conocidos }) return Rejection.UNKNOWN_ROLE

        // INVARIANTE A. «Administrativo» y no «tiene algún rol»: a un usuario
        // corriente sí se le pueden retirar todos, porque eso no deja ninguna
        // puerta administrativa a medio cerrar.
        if (isAdministrative(state.currentRoleCodes) && pedidos.isEmpty()) {
            return Rejection.EMPTY_ROLES
        }

        // INVARIANTE B. Prohibido PROVOCAR la transición a cero: si el sistema ya
        // estaba sin superadministradores, bloquear aquí impediría crear el
        // primero y dejaría el panel inservible.
        if (state.superadminsBefore > 0 && superadminsAfter(state) == 0) {
            return Rejection.LAST_SUPERADMIN
        }

        return null
    }

    private fun Set<String>.normalized(): Set<String> =
        mapTo(mutableSetOf()) { it.trim().lowercase() }
}
