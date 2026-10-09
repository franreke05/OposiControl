# Distribución macOS de OposiControl Desktop

El workflow [`.github/workflows/macos-desktop-release.yml`](../../.github/workflows/macos-desktop-release.yml)
genera `OposiControl.app` dentro de un `.dmg`, en runners macOS de GitHub Actions. El desarrollo
se hace desde Windows; nada de esto necesita un Mac local.

- La app lleva **su propio runtime Java 21** (jlink). No hace falta instalar Java, Gradle ni Kotlin.
- Apunta a producción: `https://api.oposibots.es` (`-PoposiProduction=true`). No arranca ni busca
  un Ktor local; el workflow falla si la configuración menciona `localhost`/`8080`.
- No publica releases: el resultado es un **artifact** de la ejecución.

## Arquitecturas: dos DMG, no uno universal

Compose Desktop empaqueta con `jpackage` para la arquitectura del equipo que construye, con un
runtime JDK y una librería Skiko de esa arquitectura. `jpackage` no fusiona binarios universales, así
que se generan **dos artefactos**:

| Mac | Artefacto | Runner |
|---|---|---|
| Apple Silicon (M1–M4) | `OposiControl-macOS-<versión>-arm64.dmg` | `macos-15` |
| Intel | `OposiControl-macOS-<versión>-x64.dmg` | `macos-15-intel` |

Para saber cuál usar: menú Apple → *Acerca de este Mac* → «Chip: Apple M…» = arm64; «Procesador: Intel» = x64.
El de Intel funciona en Apple Silicon con Rosetta, pero más lento: usa siempre el nativo.

Un binario universal exigiría fusionar a mano con `lipo` el runtime, el lanzador y cada librería
nativa de los JAR, y volver a firmarlo todo. No está soportado por el tooling y no se hace.

## Build interna sin firma (`UNSIGNED_INTERNAL_BUILD`)

GitHub → *Actions* → **macOS desktop release** → *Run workflow* → `signing = unsigned`.
(Mientras el workflow no esté en `main`, se ejecuta con cada push a
`feature/macos-desktop-distribution` o a cualquier rama `release/desktop-*`.)

El DMG se llama `OposiControl-macOS-<versión>-<arch>-UNSIGNED_INTERNAL_BUILD.dmg`. **No está firmado
con Developer ID ni notarizado**: sólo para pruebas internas. `jpackage` puede dejarle una firma
*ad-hoc* (requisito técnico de arm64); eso no es una firma de desarrollador.

#### Firma ad-hoc coherente (por qué existe)

`jpackage` deja el lanzador firmado *ad-hoc* (sin Team ID) mientras el runtime Java embebido conserva
la firma de su proveedor, con Team ID propio. En un Mac real eso impide arrancar:

```
libjli.dylib: code signature not valid for use in process
mapping process and mapped file (non-platform) have different Team IDs
```

Por eso la build interna se vuelve a firmar entera en ad-hoc con
[`composeApp/packaging/macos/adhoc-sign.sh`](../../composeApp/packaging/macos/adhoc-sign.sh), **de dentro
hacia fuera** (cada Mach-O del más profundo al menos profundo, luego el runtime como bundle, la app al
final; nunca `codesign --deep` para firmar) y **sin Hardened Runtime**, que sólo exige la notarización.
El workflow comprueba `codesign --verify --deep --strict`, que lanzador y `libjli.dylib` compartan Team ID
y que la app copiada fuera del DMG siga viva a los 40 s sin esos dos errores
([`inspect-signatures.sh`](../../composeApp/packaging/macos/inspect-signatures.sh),
[`launch-check.sh`](../../composeApp/packaging/macos/launch-check.sh)).

Es una solución **temporal para la build interna**. La definitiva es Developer ID + Hardened Runtime +
firma coherente del código anidado + notarización (sección siguiente).

### Qué hará Gatekeeper con la build sin firma

Al abrirla por primera vez macOS dirá que *no se puede verificar el desarrollador* / *Apple no
puede comprobar que no contenga software malicioso*. Para abrirla igualmente:

1. Arrastra `OposiControl.app` a *Aplicaciones* e intenta abrirla una vez (se bloqueará).
2. *Ajustes del Sistema* → *Privacidad y seguridad* → al final, «Se ha bloqueado OposiControl» →
   **Abrir igualmente** → confirmar con la contraseña. (En macOS 15 ya no vale clic derecho → Abrir.)
3. Alternativa en Terminal: `xattr -dr com.apple.quarantine /Applications/OposiControl.app`.

## Build firmada y notarizada

`Run workflow` → `signing = signed`, o simplemente un push a `release/desktop-*`: en cuanto existe
el secreto `MACOS_CERTIFICATE_P12_BASE64`, los push generan la build firmada. El job:

1. Crea un **keychain temporal** con contraseña aleatoria, importa el `.p12` y lo borra al terminar
   (paso `if: always()`), igual que la clave `.p8`.
2. Compose firma `OposiControl.app` con la identidad `Developer ID Application`, **Hardened Runtime**
   y **secure timestamp**, incluidas las librerías nativas que van dentro de los JAR y el runtime.
   Entitlements: [`composeApp/packaging/macos/entitlements.plist`](../../composeApp/packaging/macos/entitlements.plist)
   (sólo `allow-jit` y `allow-unsigned-executable-memory`, lo mínimo que exige la JVM).
3. Comprueba `codesign --verify --deep --strict`, autoridad Developer ID, flag `runtime` y timestamp.
4. **Notariza la app** con `xcrun notarytool submit --wait` (API key de App Store Connect; no se usa
   `altool`), exige `Accepted` (si no, imprime el log de Apple y falla), hace `stapler staple` y
   `stapler validate`, y `spctl --assess` debe dar «Notarized Developer ID».
5. Crea el DMG con `hdiutil`, lo **firma**, lo **notariza** y le hace **staple**.
6. Monta el DMG y vuelve a verificar firma y ticket de la app que contiene.

### Secretos de GitHub (Settings → Secrets and variables → Actions → *New repository secret*)

| Secreto | Qué es |
|---|---|
| `MACOS_CERTIFICATE_P12_BASE64` | Certificado **Developer ID Application** con su clave privada, exportado como `.p12` y codificado en Base64 |
| `MACOS_CERTIFICATE_PASSWORD` | Contraseña con la que se exportó ese `.p12` |
| `APPLE_TEAM_ID` | Team ID de 10 caracteres (se comprueba que la identidad le pertenece) |
| `APPLE_API_KEY_P8_BASE64` | Fichero `AuthKey_XXXXXXXXXX.p8` de App Store Connect, en Base64 |
| `APPLE_API_KEY_ID` | Key ID de esa clave (10 caracteres) |
| `APPLE_API_ISSUER_ID` | Issuer ID (UUID) de App Store Connect |

Valores **públicos** de la app (también como secretos, para no dejarlos en logs):

| Secreto | Qué es |
|---|---|
| `OPOSI_SUPABASE_URL` | URL del proyecto Supabase MAIN |
| `OPOSI_SUPABASE_PUBLISHABLE_KEY` | Clave *publishable/anon* (nunca `sb_secret_…`: Gradle la descarta) |
| `OPOSI_SENTRY_DESKTOP_DSN` | Opcional. DSN de Sentry de escritorio |

Nada de esto se guarda en Gradle ni en ficheros del repositorio.

## Descargar, comprobar e instalar

1. GitHub → *Actions* → la ejecución → sección *Artifacts* → `OposiControl-macOS-<versión>-<arch>….dmg`
   (GitHub lo entrega en un `.zip`; dentro están el `.dmg` y su `.sha256`).
2. Comprobar integridad en el Mac:
   ```sh
   shasum -a 256 -c OposiControl-macOS-1.0.0-arm64.dmg.sha256
   ```
   Debe decir `OK`.
3. Doble clic en el `.dmg` → arrastrar **OposiControl** a **Applications** → expulsar el disco.
4. Build firmada: `spctl --assess --type execute -vv /Applications/OposiControl.app` → `accepted`,
   `source=Notarized Developer ID`.

El artifact `diagnostics-macos-<arch>` contiene la captura de pantalla y el log del arranque de prueba
que hace el workflow.

## Troubleshooting

| Síntoma | Causa y solución |
|---|---|
| «No se puede abrir porque el desarrollador no se puede verificar» | Build sin firma. Ver *Qué hará Gatekeeper* arriba. |
| «Está dañado y no se puede abrir» | Descarga en cuarentena de una build sin firma o DMG corrupto. Verifica el SHA-256; si coincide, `xattr -dr com.apple.quarantine /Applications/OposiControl.app`. |
| «No se puede abrir en este Mac» / muy lenta | DMG de otra arquitectura. Descarga el `arm64` o el `x64` correcto. |
| La build firmada falla en *notarytool* con `Invalid` | El log de Apple sale en el propio paso. Suele ser un binario dentro de un JAR sin firmar o sin Hardened Runtime. |
| El workflow falla en *Verify production configuration* | Faltan `OPOSI_SUPABASE_URL` / `OPOSI_SUPABASE_PUBLISHABLE_KEY`. |
| El login no funciona | La app habla con `api.oposibots.es`; comprueba que el backend responde (`/health`). |

## Build local (sólo si tienes un Mac)

```sh
SUPABASE_URL=… SUPABASE_ANON_KEY=… ./gradlew :composeApp:createDistributable -PoposiProduction=true
open composeApp/build/compose/binaries/main/app/OposiControl.app
```

En Windows, `packageMsi` y `createDistributable` siguen igual (`OposiControlAdmin`); añadir
`-PoposiProduction=true` hace que la build apunte a producción.
