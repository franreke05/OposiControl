package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.remote.api.AuditApi
import com.oposicontrol.admin.data.remote.api.RolesApi
import com.oposicontrol.admin.data.repository.KtorTagsRepositoryImpl
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.model.Tag

/**
 * Tabla del registrador (INV-NETWORK-COMPLETE): UNA FILA POR SITIO DE LLAMADA HTTP de cada `*Api` / `Ktor*RepositoryImpl`.
 *
 * Como rellenar una fila (copiar una de abajo):
 *  - `file`: ruta del fichero cliente RELATIVA A LA RAIZ DEL REPO (la misma que `HTTPCALL` en direct-supabase-allowlist.tsv).
 *  - `name`: metodo (si el metodo tiene varias ramas `client.get/post/...`, una fila por rama: `upsertTag(create)`).
 *  - `expect`: metodo + PLANTILLA de ruta; los ids se pasan como centinelas `ID0`, `ID1`... y salen como `{}`. La primera
 *    llamada es la del sitio; si el metodo encadena otra (p. ej. un refresco tras borrar) se declara tambien a continuacion.
 *  - la lambda construye el cliente con `client`/`appConfig`/`session`/`adapter` del [Env] y llama al metodo con args minimos.
 *    La respuesta del mock es `{"data":[]}`: los errores de mapeo se ignoran, solo cuenta lo enviado.
 *  - la ruta (metodo + plantilla) tiene que existir en `ops/inventory/routes.combined.txt`; si el cliente llama a una ruta que el
 *    backend no sirve, el fallo es REAL (arreglar el cliente o el backend, nunca la fila).
 *  - `site`: la funcion del fichero que contiene el sitio (por defecto `name` sin `(rama)`); `filas == sitios` por funcion o
 *    falla. No hay lista de pendientes: un sitio sin fila cuenta en UNMAPPED_CALLS y la puerta falla.
 *  - la consulta se declara por NOMBRES tras `?` (`get("/x?limit&offset")`); varios `{}` en una ruta exigen ids crecientes.
 */
internal val CLIENT_CALL_ROWS: List<CallRow> = listOf(

    // ---- Estilo Api (clase `*Api(client, appConfig, sessionLocalDataSource)`): 3 filas trabajadas ---------------------------
    // Todos los filtros y el cursor a la vez: la fila tiene que grabar cada `parameter("x"` que la funcion puede enviar.
    CallRow(AUDIT_API, "getMovements", get("/api/v1/admin/auditoria/movimientos?accion&actor&cursor&desde&hasta&limit")) {
        AuditApi(client, appConfig, session).getMovements(
            AuditFilter(action = "cuenta_dada_de_baja", actorId = "actor", from = "2026-10-01T00:00:00Z", until = "2026-10-14T00:00:00Z"),
            cursor = "c1",
        )
    },
    CallRow(AUDIT_API, "getMovementDetail", get("/api/v1/admin/auditoria/{}")) {
        AuditApi(client, appConfig, session).getMovementDetail("ID0")
    },
    CallRow(ROLES_API, "getRoles", get("/api/v1/admin/roles")) {
        RolesApi(client, appConfig, session).getRoles()
    },

    // ---- Estilo repositorio (clase `Ktor*RepositoryImpl(client, appConfig, session, adapter)`): fichero completo -----------
    CallRow(TAGS_REPO, "refreshTags", get("/api/v1/admin/etiquetas")) {
        KtorTagsRepositoryImpl(client, appConfig, session, adapter).refreshTags()
    },
    CallRow(TAGS_REPO, "upsertTag(create)", post("/api/v1/admin/etiquetas")) {
        KtorTagsRepositoryImpl(client, appConfig, session, adapter).upsertTag(Tag(id = "", name = "n", slug = "n", origin = "manual"))
    },
    CallRow(TAGS_REPO, "upsertTag(update)", patch("/api/v1/admin/etiquetas/{}")) {
        KtorTagsRepositoryImpl(client, appConfig, session, adapter).upsertTag(Tag(id = "ID0", name = "n", slug = "n", origin = "manual"))
    },
    // deleteTag: DELETE y despues el refresco que encadena (su propio sitio ya tiene la fila refreshTags)
    CallRow(TAGS_REPO, "deleteTag", listOf(delete("/api/v1/admin/etiquetas/{}"), get("/api/v1/admin/etiquetas"))) {
        KtorTagsRepositoryImpl(client, appConfig, session, adapter).deleteTag("ID0")
    },
) + ROWS_B_DASHBOARD_API + ROWS_B_USER_DETAIL_API + ROWS_B_ADMIN_OPERATIONS_REPO + ROWS_B_COMMERCE_REPO +
    ROWS_B_COMMUNITIES_REPO + ROWS_B_DASHBOARD_OVERVIEW_REPO + ROWS_B_MARKET_CATALOG_REPO + ROWS_B_NEWS_REPO +
    ROWS_B_QUESTIONS_REPO + ROWS_B_SYSTEM_STATUS_REPO + ROWS_B_TESTER_REPO + // R4-REC-B: ficheros RowsB*.kt
    ROWS_C_ADMIN_V1_CONTENT_REPO + // R5-REC-C1: RowsCKtorAdminV1ContentRepo.kt
    ROWS_C2_AUTH_API + ROWS_C2_USERS_API + ROWS_C2_COMMERCIAL_CATALOG_REPO + ROWS_C2_CONNECTION_DIAGNOSTICS_REPO +
    ROWS_C2_FORUM_REPO + ROWS_C2_MODERATION_REPO + ROWS_C2_PRODUCT_ANALYTICS_REPO + ROWS_C2_RANKINGS_REPO + // R5-REC-C2: RowsC2*.kt
    ROWS_D_ACCESS_REQUESTS_REPO // solicitudes de acceso al panel: RowsDKtorAccessRequestsRepo.kt

private const val DATA_MAIN = "data/src/commonMain/kotlin/com/oposicontrol/admin/data"
private const val API_DIR = "$DATA_MAIN/remote/api"
private const val REPO_DIR = "$DATA_MAIN/repository"
private const val AUDIT_API = "$API_DIR/AuditApi.kt"
private const val ROLES_API = "$API_DIR/RolesApi.kt"
private const val TAGS_REPO = "$REPO_DIR/KtorTagsRepositoryImpl.kt"
