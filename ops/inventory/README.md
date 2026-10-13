# Inventario de red (puertas deterministas)

| Fichero | Origen | Lo comprueba |
| --- | --- | --- |
| `routes.combined.txt`, `routes.consumer.txt` | GENERADOS recorriendo el arbol REAL de Ktor (`installCombinedRoutes` / `configureConsumerAuthOnly`) | `RouteInventoryTest` (igualdad exacta; ruta nueva o retirada = fallo) |
| `undocumented-routes.tsv` | registro REVISADO de rutas servidas que el OpenAPI congelado no declara (no es un spec) | `RouteInventoryTest` (fila obsoleta o ya documentada falla; `# max:` solo baja) |
| `client-route-exceptions.tsv` | rutas centrales del cliente (`BackendRoutes`) sin ruta en el backend (vacio hoy) | `ClientRouteConstantsTest` (`:data:jvmTest`) |
| (codigo) `data/src/jvmTest/.../inventory/ClientCall*.kt`, `Rows*.kt` | registrador de PRUEBAS (sin codigo de produccion): filas que ejecutan cada cliente `*Api`/`Ktor*RepositoryImpl` contra `MockEngine` | `ClientCallInventoryTest` (`:data:jvmTest`), ver abajo |
| `direct-supabase-allowlist.tsv` | lista de permitidos fijada por fichero y numero de lineas | `scripts/verify-no-direct-supabase.sh` (+ `.test.sh`) |

Regenerar los manifiestos de rutas SOLO en local, tras revisar el cambio de ruta:
`INVENTORY_UPDATE=true ./gradlew :backend:ktor:test --tests "*RouteInventoryTest*"`. El CI nunca define esa variable.

## Registrador de llamadas del cliente (INV-NETWORK-COMPLETE)

La prueba `networkRecorderCountersAreBalanced` imprime y exige una linea legible por maquina:

`NETWORK_RECORDER_COUNTERS TOTAL_NETWORK_CALL_SITES=139 TOTAL_RECORDED_ROWS=139 UNMAPPED_CALLS=0 UNKNOWN_CALLS=0`

- `TOTAL_NETWORK_CALL_SITES`: sitios de egreso contados en TEXTO CRUDO en los 24 ficheros `HTTPCALL`: toda invocacion de un
  verbo Ktor (`get/post/put/delete/patch/head/options/request/submitForm*/prepare*`) seguida de `(` o de la lambda
  constructora `{`, con o sin receptor, en una o varias lineas, dentro de `with(client)`/`apply`, sin quitar comentarios;
  menos las exclusiones de `ClientCallSiteRules.kt` (fichero + patron + cuenta EXACTA + justificacion; cada una tapa exactamente
  un verbo y nunca una llamada con receptor). Cada sitio tiene clave `fichero#funcion` (ultima declaracion `fun` anterior).
- `TOTAL_RECORDED_ROWS`: filas `CallRow`. Por fichero y POR FUNCION, filas con `site=f` == sitios de `f`. No hay pendientes.
- `UNMAPPED_CALLS`: sitios sin fila (deficit por funcion) + firmas publicas sin fila ni justificacion + rutas en linea o
  `parameter("x"` que ninguna fila de su funcion graba.
- `UNKNOWN_CALLS`: llamadas grabadas que no son ruta servida (`routes.combined.txt`), identidad permitida (GoTrue) o host
  conocido + filas cuya secuencia grabada difiere de la declarada + filas sobrantes de una funcion + filas de relleno (las filas
  de una funcion con N sitios graban menos de N llamadas distintas).

Lo que la puerta PRUEBA:

1. Una llamada nueva en cualquiera de esas formas (incluida `client.get { url(..) }`) sube los sitios de su funcion y falla sin
   su fila; borrar, duplicar o mover una fila a otra funcion falla.
2. Cada fila graba metodo + plantilla + ORDEN de ids (centinela `ID<n>` -> `{n}`; varios `{}` declarados exigen ids crecientes,
   `{n}` exige ese id) + NOMBRES de la consulta, y debe coincidir exactamente con lo declarado: ruta, metodo, ids cambiados de
   sitio y filtros quitados o renombrados en el camino ejecutado fallan.
3. Toda FIRMA publica (nombre + tipos de parametros) de la clase cliente, de su fachada `<Fichero>Kt` y de toda clase del
   fichero o anidada (inner, companion, objeto) que guarda o recibe un `HttpClient` (o una clase del fichero que lo hace) tiene
   fila (`covers`) o justificacion sin HTTP. Una sobrecarga nueva o un metodo de una clase interna nueva falla.
4. Cada literal de ruta (cadena que empieza por `/` o por interpolaciones y `/`, con alguna letra fija) y cada `parameter("x"`
   de una funcion lo graba alguna fila que ejecuta esa funcion (o del fichero si ninguna fila la nombra): una rama nueva con una
   ruta o un filtro nuevo falla aunque no anada sitios.
5. Todo fichero de produccion fuera de `backend/` que toca el cliente Ktor es `HTTPCALL` o un no-cliente fijado con 0 sitios.

Lo que NO puede probar (limites exactos):

- Una rama nueva DENTRO de una funcion ya cubierta que no anade sitio, ni literal de ruta, ni `parameter("x"`: p. ej. elige otra
  constante de `BackendRoutes`, una propiedad o una URL calculada fuera de la funcion, o reutiliza un literal que otra fila
  que tambien ejecuta esa funcion ya graba (la fila de un ayudante con `covers` de varios metodos). Las rutas de
  `BackendRoutes` las cubre `ClientRouteConstantsTest`, no este registrador.
- Que la fila N de una funcion con varios sitios ejecute justo el sitio N: solo se exige N filas y N llamadas distintas.
- Los VALORES de la consulta, el cuerpo y las cabeceras; un parametro de consulta anadido con otra API que no sea
  `parameter("x"` (p. ej. `url { parameters.append(..) }`) solo se ve si la fila lo ejecuta.
- Un cliente que no se llame por los verbos Ktor contados (otro nombre de funcion de egreso, otro motor HTTP) o una clase que
  llega al cliente por un global (Koin `GlobalContext`) en vez de por campo o parametro; el tripwire de ficheros y
  `verify-no-direct-supabase.sh` (EGRESS_CTOR, `EGRESS_NONKTOR`) son las otras capas.
- Tres formas de egreso NO contadas: un verbo importado con alias (`import io.ktor.client.request.post as send`), un verbo entre comillas invertidas (client.`post`(..)) y una referencia invocable (`client::post`); en los tres la cuenta no sube y, sin literal de ruta en linea, la comprobacion estatica calla.
- Una rama cuyo literal conserva un sufijo ya grabado pero cambia el prefijo interpolado (`"${BackendRoutes.ADMIN_V1}/servicios"` en vez de `"$MARKET/servicios"`): la comprobacion estatica borra las interpolaciones y compara con `containsMatchIn`.
- La linea `NETWORK_RECORDER_COUNTERS` puede leer 139/139/0/0 con una exclusion que deriva (otra invocacion del ayudante excluido); falla `siteExclusionsArePinnedExactlyAndJustified`, asi que el veredicto es el resultado de la suite, no la linea.
- Una declaracion `fun` falsa en un comentario cambia la clave del sitio: el conteo por funcion FALLA (no pasa en silencio).
