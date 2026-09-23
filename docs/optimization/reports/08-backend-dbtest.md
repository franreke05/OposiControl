# Revisión completa — `backend/dbtest/` (14/14, sin muestreo)

Contexto verificado: `settings.gradle.kts` incluye `:backend:dbtest` como módulo standalone; `backend/ktor/build.gradle.kts` no lo referencia (grep de `com.oposicontrol.dbtest` fuera de `backend/dbtest/` → 0 resultados). Los 14 ficheros son su único universo de consumidores. Sin CI (no existe `.github/workflows`); la suite se ejecuta a mano. `docs/database/MIGRATION_STATE.md` y `docs/registry/RISKS.md` (SEC-006) documentan una corrida verificada 2026-09-21: 45/45 tests verdes.

---

## TIER 1 — dedup/dead-code puro, valor/riesgo muy favorable

### T1-a · `PostgresHarness.version()` muerto

**APLICADO.** `PostgresHarness.kt:25-26` borrado. Cero call sites en los 14 ficheros (verificado con grep exhaustivo). `EmbeddedPostgresSmokeTest.kt` hace su propio `select version()` crudo sobre una instancia separada, no pasa por este helper.

### T1-b · `Connection.entero()` muerto en el fichero activo

**APLICADO.** `PostgresHarness.kt:58` borrado. Existe una copia byte a byte en `content/ContentDbHarness.kt:143`, pero ese paquete no tiene ningún test consumidor (ver R-1 abajo) — no se tocó.

### T1-c · `puedeEjecutar` duplicado entre ficheros, mismo template exacto

**APLICADO.** Helper `Connection.puedeEjecutar(rol)` (antes privado en `RoleSyncRollbackAndCollationTest.kt:32-33`, e inlineado 3 veces en `RoleSyncInstallationTest.kt:86-88`) promovido a `PostgresHarness.kt`. Los 6 usos totales (3 en cada fichero) ahora llaman al mismo helper `internal`. Verificado que las cadenas SQL eran idénticas carácter a carácter salvo el nombre del rol antes de promoverlo.

## TIER 2 — vacío

El revisor no encontró candidatos de limpieza de valor medio con riesgo bajo-medio que no cayeran en Tier 1 o en REGISTRAR. Los hallazgos de valor medio-alto que surgieron al comparar los `RoleSync*Test.kt` byte a byte NO son de limpieza sino de **validez de test** — van en REGISTRAR porque tocarlos implica una decisión funcional sobre un guard P0 (SEC-006), no una simplificación.

## TIER 3 — cosmético, valor bajo (ninguno aplicado)

- **T3-a** `nombreDeRol()` con la misma forma pero prefijo distinto (`"intruso_"` vs `"guard_owner_"`) en dos ficheros de test de seguridad — valor insuficiente para el churn.
- **T3-b** `Connection.modo()` (`"true"/"false"` crudo) vs `Connection.modoDeSeguridad()` (`"definer"/"invoker"`) — mismo target, distinto contrato de salida, un uso cada uno.

---

## REGISTRAR, NO TOCAR

### Aislamiento/alcance

- **R-1.** Todo el paquete `content/` (`ContentDbHarness.kt` 198 líneas + `ContentRows.kt` 116 líneas) **sin ningún `@Test` que lo consuma**, confirmado por grep exhaustivo. Ya registrado como deuda conocida: `docs/registry/TECH_DEBT.md` DEBT-062 y `docs/registry/RISKS.md` TEST-001 — el fixture que instala es anterior a las migraciones M2-M10, así que `admin_import_lote_preguntas` etc. no se pueden ejercitar todavía contra él. Entró en el mismo commit que el resto del arnés SÍ usado (`b282d6e`): es andamiaje preparado, no abandono. **No proponer borrado.** La duplicación de `ejecutar`/`unico`/`entero` entre este paquete y `PostgresHarness.kt` se resuelve sola el día que se use o se borre.

- **R-2.** `PostgresHarness.escenario()`/`escenarioConIcu()` (43 call sites) crean una base nueva por escenario y NUNCA la dropean; sólo desaparecen al morir el cluster entero (shutdown hook). Contraste: `ContentDbHarness.escenario()` sí hace `drop database ... with (force)` en un `finally`. No es un leak que sobreviva al proceso, pero es ~45 bases acumuladas por corrida sin razón declarada. **Riesgo de tocarlo: medio** — `RoleSyncConcurrencyTest` mantiene una segunda conexión sobre la misma base; imitar el patrón de `ContentDbHarness` exige correr la suite completa, no basta lectura estática.

- **R-3.** Nombre de rol fijo (`RoleSyncRlsTest.kt:26 PROPIETARIO = "guard_owner"`, con `drop role if exists` defensivo) donde el resto del módulo usa sufijo aleatorio con UUID. Verificado: no hay `maxParallelForks` en ningún `build.gradle.kts`, así que hoy los tests corren en serie y esto es inofensivo. Si algún día se paraleliza, el patrón con sufijo aleatorio de los otros dos ficheros sería el candidato natural.

- **R-4.** `RoleSyncRollbackAndCollationTest.kt:100-109` — si la instalación de PostgreSQL no trae ICU, hace `return` tras un `println("...NOT_AVAILABLE")` sin ejecutar ningún `assert*`, pasando en verde trivialmente. Patrón NOT_AVAILABLE deliberado y documentado en el docstring de la clase, pero sin un mecanismo de skip real (tipo `Assumptions` de JUnit5), un reporte de cobertura no distingue "no se pudo probar" de "se probó y pasó". Mención menor, no accionable sin tocar el runner.

### Validez de test (la diferencia ES el hallazgo — análisis estático, no ejecutado; Bash de sólo lectura)

- **V-1. Test sin ninguna aserción:** `RoleSyncRlsFailOpenTest.kt:80-109`, `rowSecurityOffConvierteElFiltradoSilenciosoEnError`. El cuerpo completo sólo tiene `println`, ningún `assert*`. El docstring dice "Se comprueba aquí" pero no se comprueba nada — el método pasa en verde sin importar lo que devuelva PostgreSQL. Corregirlo (añadir la aserción sobre el mensaje de error) es una decisión funcional sobre un guard P0, fuera de un mandato de sólo-lectura.

- **V-2. Posible paso en verde por motivo incidental:** `RoleSyncRlsTest.kt:64-85`, `unPropietarioSinBypassNoPuedeDejarLaInvarianteMUDA`. Su `crearPropietarioSinBypass()` sólo hace `GRANT select, insert, delete` — SIN política RLS permisiva de escritura. El fichero hermano `RoleSyncRlsFailOpenTest.propietarioQueEscribePeroNoVe` sí añade esas políticas, y su docstring explica por qué: sin ellas, el `insert` puede chocar con el rechazo por defecto de RLS por una razón totalmente distinta a la que el test dice proteger. Verificación gratuita ya disponible: la línea 75 ya hace `println("RLS2_ERROR: $error")` — basta releer ese log.

- **V-3. Test probablemente redundante bajo una etiqueta que no ejercita:** `RoleSyncRlsTest.kt:89-106`, `conForceRowLevelSecurityElComportamientoSigueSiendoCerrado`. Nunca reasigna el propietario de `admin_sync_roles`, así que sigue siendo dueña la conexión superusuario del harness — y `FORCE ROW LEVEL SECURITY` está documentado para NO afectar nunca a superusuarios. El `alter table ... force row level security` no tendría efecto en este escenario; el resto del cuerpo es esencialmente idéntico a `RoleSyncSemanticTest.degradarAlUltimoSuperadministradorAbortaYNoTocaLaBase`.

---

## Cierre

`FILES_IN_SCOPE = 14`, `FILES_READ = 14`. Ninguno pendiente.

**Aplicado de este informe**: T1-a, T1-b, T1-c. Verificado con `:backend:dbtest:test` — 45/45 tests, 0 fallos, 0 skipped (recuento exacto por fichero: EmbeddedPostgresSmokeTest 1, RoleSyncCanonicalizationTest 4, RoleSyncConcurrencyTest 2, RoleSyncInstallationTest 8, RoleSyncMutationTest 14, RoleSyncRlsFailOpenTest 2, RoleSyncRlsTest 4, RoleSyncRollbackAndCollationTest 3, RoleSyncSemanticTest 7 = 45).
