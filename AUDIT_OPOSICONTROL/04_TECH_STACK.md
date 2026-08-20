# Technical Stack

| Tecnología | Versión/evidencia | Estado |
|---|---|---|
| Kotlin | `2.3.20` | VERIFIED config |
| AGP | `8.11.2` | VERIFIED config |
| Compile/target SDK | `36`; min `30` | VERIFIED config |
| Compose Multiplatform | `1.10.3` | VERIFIED config |
| Material 3 | `1.10.0-alpha05` | VERIFIED config |
| Ktor | `3.2.3` client/server | VERIFIED config |
| Ktor server | Netty | VERIFIED config |
| JVM backend | toolchain `21` | VERIFIED config |
| Koin | `4.1.0` | VERIFIED config |
| Coroutines | `1.10.2` | VERIFIED config |
| Serialization | `1.9.0` | VERIFIED config |
| kotlinx-datetime | `0.7.1` | VERIFIED config |
| SQLDelight | `2.1.0` | local session/settings/drafts |
| Supabase | PostgREST/Auth/Storage vía HTTP | VERIFIED source; runtime NOT_VERIFIED |
| PDF | Apache PDFBox `3.0.2` | backend JVM only |
| JWT | Auth0 `java-jwt 4.4.0` | backend app JWT |
| Rate limit | Lettuce `6.5.5.RELEASE` + memory dev | backend auth |
| Logging | Logback `1.5.16` dirty local Gradle change | configured, runtime NOT_VERIFIED |
| Testing | kotlin.test, coroutines-test, Ktor mock/test-host | VERIFIED config |

No DI framework adicional, ORM, queue, OCR, Dockerfile o CI workflow fueron encontrados en el inventario rastreado.
