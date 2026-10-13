package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.remote.api.UsersApi
import com.oposicontrol.admin.data.remote.dto.UserDto

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/UsersApi.kt"
private const val U = "/api/v1/admin/usuarios"

private fun user(name: String, roles: List<String>) = UserDto("ID0", name, "e", roles, true, null)

/**
 * Filas del registrador (R5-REC-C2): UsersApi (4 sitios). `upsertUser` tiene dos ramas (PATCH nombre / POST roles) y ambas
 * terminan en el `getUser` que encadena; `previous` decide la rama. La respuesta minima de la lectura no mapea.
 */
internal val ROWS_C2_USERS_API: List<CallRow> = listOf(
    // Con `roleCode` (Sistema > Administradores): el mismo sitio que el censo entero, con su tercer nombre de consulta.
    CallRow(FILE, "getUsers", get("$U?limit&offset&roleCode")) { UsersApi(client, appConfig, session).getUsers("admin") },
    CallRow(FILE, "getUser", get("$U/{}")) { UsersApi(client, appConfig, session).getUser("ID0") },
    CallRow(FILE, "upsertUser(patch)", listOf(patch("$U/{}"), get("$U/{}"))) {
        UsersApi(client, appConfig, session).upsertUser(user("nuevo", listOf("r")), previous = user("viejo", listOf("r")))
    },
    CallRow(FILE, "upsertUser(roles)", listOf(post("$U/{}/roles"), get("$U/{}"))) {
        UsersApi(client, appConfig, session).upsertUser(user("n", listOf("r2")), previous = user("n", listOf("r")))
    },
)
