# Baseline de optimización Kotlin/KMP

`OPTIMIZATION_BASELINE_SHA = 7187040ca1b323329a9525627c6339f40de9308a`

Worktree aislado: `OposiControl-KotlinOptimizer`
Rama: `refactor/kotlin-kmp-professional-optimization`
Fecha: 2026-09-23

## Alcance

| | |
|---|---|
| Archivos en alcance (`.kt` + `.kts`, sin `build/`, `.gradle/`, generados) | **777** |
| `.kt` | 739 |
| `.kts` | 38 |
| LOC Kotlin | **158.432** |
| Módulos Gradle | 35 |

## Herramientas de análisis estático

`STATIC_ANALYSIS = NOT_CONFIGURED`.

Comprobado uno por uno: detekt, ktlint, Konsist, JaCoCo, Kover y Spotless **no
están configurados** en este repositorio. No se instalan: la especificación del
optimizador prohíbe añadir dependencias solo para obtener métricas sin
autorización, y hacerlo cambiaría el sistema de build que estamos evaluando.

Consecuencia honesta: complejidad ciclomática y duplicación **no se miden**, se
declaran `NOT_MEASURED`. No se estiman.

## Tests baseline — ninguno rojo de partida

| Módulo | Tests | Fallos |
|---|---|---|
| `backend:ktor` | 875 | 0 |
| `features:exams` | 313 | 0 |
| `data` | 106 | 0 |
| `features:questions` | 86 | 0 |
| `core:ui` | 82 | 0 |
| `domain` | 81 | 0 |
| `features:news` | 34 | 0 |
| `features:settings` | 10 | 0 |
| **TOTAL** | **1.587** | **0** |

`:composeApp:compileKotlinJvm` PASS.

Importa que el baseline esté limpio: sin fallos preexistentes, cualquier rojo
posterior es atribuible al refactor y no hay que discutir de quién era.

## Archivos desproporcionados (> 1.000 LOC)

Medida objetiva, sin herramienta externa. 20 archivos:

| LOC | Archivo |
|---|---|
| 6.944 | `backend/.../AdminV1Routes.kt` |
| 3.185 | `features/exams/.../MobileContentScreen.kt` |
| 2.201 | `backend/.../Application.kt` |
| 2.042 | `features/exams/.../ExamsViewModel.kt` |
| 1.948 | `features/exams/.../ExamsViewModelTest.kt` |
| 1.944 | `features/exams/.../ExamWorkspacePresentationTest.kt` |
| 1.840 | `features/dashboard/.../DashboardV2Screen.kt` |
| 1.661 | `data/.../KtorAdminV1ContentRepositoryImpl.kt` |
| 1.544 | `composeApp/.../MobileAdminPanel.kt` |
| 1.533 | `features/exams/.../ExamsScreen.kt` |

Un archivo grande no es un defecto por sí mismo: `AdminV1Routes.kt` es un
catálogo de rutas y su tamaño es consecuencia de cuántas hay. Se anota como
señal, no como condena.

## Arquitectura observada — a PRESERVAR

Aprendida del repositorio, no impuesta desde fuera:

- **MVI** en presentación: `MviViewModel<State, Intent>`, un `State` inmutable
  por pantalla y un `sealed interface Intent` con `when` exhaustivo.
- **UiAsync** como envoltorio de carga: `Loading | Success | Error | Idle`. La
  ausencia de dato se distingue del cero; es una regla de producto repetida en
  todo el código y NO se toca.
- **AppResult** en dominio y datos, con `NetworkResultAdapter.safeCall`.
- **Repositorios** por vertical, interfaz en `domain`, implementación en `data`,
  inyección en `DataModule.kt` con Koin.
- **Ktor** como frontera: el cliente nunca habla con Supabase directamente.
- **Compose Multiplatform** con `core/ui` como sistema de diseño: tokens de
  espaciado/radio/tamaño en `DesignTokens`, colores solo por `GlassTheme.tokens`.
- **Medidas exactas en dp**, nunca `weight` ni fracciones. Es regla explícita
  del proyecto (AGENTS.md) y no se discute desde una referencia externa.
