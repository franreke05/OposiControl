package com.oposicontrol.backend

/**
 * Perfil de ejecucion del UNICO artefacto Ktor (`SERVER_PROFILE`). Decide QUE rutas se montan y, por tanto, que
 * configuracion hace falta; no cambia ningun handler.
 *
 *  - [BOT]      : superficie de OposiBot (auth de consumidor, perfil, `/api/v1/app/...`, lecturas publicas `/v1/...`) + salud + metricas.
 *  - [CONTROL]  : superficie de OposiControl (todo lo admin/contenido/legacy admin, tras `gateway-auth`) + lecturas publicas
 *                 `/v1/...` (diagnostico de OC) + salud + metricas. No monta `/v1/auth/...` ni `/api/v1/app/...`.
 *  - [COMBINED] : todo (valor por defecto; desarrollo/compat). Conjunto de rutas identico al historico.
 *  - [CONSUMER] : perfil historico MINIMO (solo auth + perfil canonico + `/health/live` + `/metrics`; sin `/health/ready` ni
 *                 `/api/v1/app/...`). NO es un alias de [BOT] (seria otro conjunto de rutas); su inventario sigue siendo
 *                 `ops/inventory/routes.consumer.txt`. [BOT] es un superconjunto estricto, asi que `consumer` es
 *                 candidato a retirarse cuando ningun despliegue lo use.
 *
 * La matriz ruta -> perfil versionada es `ops/inventory/route-profiles.tsv`; `RouteProfileGateTest` la cruza con el arbol real.
 */
internal enum class ServerProfile(val wire: String) {
    BOT("bot"),
    CONTROL("control"),
    COMBINED("combined"),
    CONSUMER("consumer"),
    ;

    /** Monta las rutas de consumidor (AUTH + `/api/v1/app/...`): requiere el runtime de auth. */
    val mountsBot: Boolean get() = this == BOT || this == COMBINED

    /** Monta las rutas admin (`gateway-auth`): requiere los gateways de administracion. */
    val mountsControl: Boolean get() = this == CONTROL || this == COMBINED

    companion object {
        /** Vacio/nulo = `combined` (historico). Un valor desconocido NO cae a `combined`: aborta el arranque. */
        fun parse(raw: String?): ServerProfile {
            val value = raw.orEmpty().trim()
            if (value.isEmpty()) return COMBINED
            return entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
                ?: error("SERVER_PROFILE='$value' no es valido. Valores admitidos: ${entries.joinToString { it.wire }}.")
        }
    }
}
