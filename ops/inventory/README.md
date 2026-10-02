# Inventario de red (puertas deterministas)

| Fichero | Origen | Lo comprueba |
| --- | --- | --- |
| `routes.combined.txt`, `routes.consumer.txt` | GENERADOS recorriendo el arbol REAL de Ktor (`installCombinedRoutes` / `configureConsumerAuthOnly`) | `RouteInventoryTest` (igualdad exacta; ruta nueva o retirada = fallo) |
| `undocumented-routes.tsv` | registro REVISADO de rutas servidas que el OpenAPI congelado no declara (no es un spec) | `RouteInventoryTest` (fila obsoleta o ya documentada falla; `# max:` solo baja) |
| `client-route-exceptions.tsv` | rutas centrales del cliente (`BackendRoutes`) sin ruta en el backend (vacio hoy) | `ClientRouteConstantsTest` (`:data:jvmTest`) |
| (codigo) `data/src/jvmTest/.../inventory/ClientCall*.kt` | registrador de PRUEBAS: una fila por sitio de llamada HTTP de cada `*Api`/`Ktor*RepositoryImpl` contra `MockEngine`; `filas + pendientes == sitios de egreso` por fichero (texto crudo sin receptor: multilinea y `with(client)` cuentan; descuentos solo en `ClientCallSiteRules.kt` con cuenta exacta y justificacion) y cobertura por reflexion de cada metodo publico (fila `covers`, `PENDING_METHODS` o `NON_HTTP_MEMBERS`) | `ClientCallInventoryTest` (`:data:jvmTest`); `PENDING_ROWS` y `PENDING_METHODS` solo pueden encoger |
| `direct-supabase-allowlist.tsv` | lista de permitidos fijada por fichero y numero de lineas | `scripts/verify-no-direct-supabase.sh` (+ `.test.sh`) |

Regenerar los manifiestos de rutas SOLO en local, tras revisar el cambio de ruta:
`INVENTORY_UPDATE=true ./gradlew :backend:ktor:test --tests "*RouteInventoryTest*"`. El CI nunca define esa variable.

Limites conocidos (honestos): `HTTPCALL` detecta ficheros que llaman a `client.get/post/...` (no un cliente con otro nombre) y los
cuatro GET de medios publicos estan fijados como `EGRESS_NONKTOR`. El registrador en tiempo de ejecucion (metodo+ruta de cada repositorio) cubre los 122 sitios de egreso de los 23 ficheros cliente (`PENDING_ROWS` y `PENDING_METHODS` vacios; solo pueden encoger: un sitio o metodo publico nuevo sin fila falla). Filas por fichero: AuditApi 1, RolesApi 1, DashboardApi 1, UserDetailApi 1, AuthApi 3, UsersApi 4,
KtorTags 4, KtorAdminOperations 12, KtorCommerce 8, KtorCommunities 4, KtorDashboardOverview 1, KtorMarketCatalog 2, KtorNews 5, KtorQuestions 13, KtorSystemStatus 1, KtorTester 3,
KtorAdminV1Content 36, KtorCommercialCatalog 3, KtorConnectionDiagnostics 9, KtorForum 3, KtorModeration 5, KtorProductAnalytics 1, KtorRankings 1 (total 122). Fuera de alcance del registrador: la consulta (`?...`) se descarta
y el orden de los ids `{}` no se compara. `ClientRouteConstantsTest` cubre las rutas centrales de `BackendRoutes`, no las plantillas en linea.
