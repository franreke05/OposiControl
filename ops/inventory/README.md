# Inventario de red (puertas deterministas)

| Fichero | Origen | Lo comprueba |
| --- | --- | --- |
| `routes.combined.txt`, `routes.consumer.txt` | GENERADOS recorriendo el arbol REAL de Ktor (`installCombinedRoutes` / `configureConsumerAuthOnly`) | `RouteInventoryTest` (igualdad exacta; ruta nueva o retirada = fallo) |
| `undocumented-routes.tsv` | registro REVISADO de rutas servidas que el OpenAPI congelado no declara (no es un spec) | `RouteInventoryTest` (fila obsoleta o ya documentada falla; `# max:` solo baja) |
| `client-route-exceptions.tsv` | rutas centrales del cliente (`BackendRoutes`) sin ruta en el backend (vacio hoy) | `ClientRouteConstantsTest` (`:data:jvmTest`) |
| (codigo) `data/src/jvmTest/.../inventory/ClientCall*.kt` | registrador de PRUEBAS: una fila por sitio de llamada HTTP de cada `*Api`/`Ktor*RepositoryImpl` contra `MockEngine`; `filas + pendientes == sitios HTTPCALL` por fichero | `ClientCallInventoryTest` (`:data:jvmTest`); las filas pendientes (`PENDING_ROWS`) solo pueden encoger |
| `direct-supabase-allowlist.tsv` | lista de permitidos fijada por fichero y numero de lineas | `scripts/verify-no-direct-supabase.sh` (+ `.test.sh`) |

Regenerar los manifiestos de rutas SOLO en local, tras revisar el cambio de ruta:
`INVENTORY_UPDATE=true ./gradlew :backend:ktor:test --tests "*RouteInventoryTest*"`. El CI nunca define esa variable.

Limites conocidos (honestos): `HTTPCALL` detecta ficheros que llaman a `client.get/post/...` (no un cliente con otro nombre) y los
cuatro GET de medios publicos estan fijados como `EGRESS_NONKTOR`. El registrador en tiempo de ejecucion (metodo+ruta de cada repositorio) existe como marco de pruebas con filas de ejemplo
(Audit, Roles, Tags); el resto esta en `PENDING_ROWS` hasta rellenarse; `ClientRouteConstantsTest` cubre las rutas centrales de `BackendRoutes`, no las
plantillas en linea.
