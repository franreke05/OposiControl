# Build and Run

Windows commands:

```powershell
.\gradlew.bat :backend:ktor:test --console=plain
.\gradlew.bat :features:exams:testDebugUnitTest --console=plain
.\gradlew.bat :features:audit:compileDebugKotlinAndroid --console=plain
.\gradlew.bat :features:news:testDebugUnitTest --console=plain
.\gradlew.bat test --console=plain
.\gradlew.bat :composeApp:compileKotlinJvm --console=plain
.\gradlew.bat :composeApp:compileKotlinIosSimulatorArm64 --console=plain
```

Resultado de `OPC-20260819-004`: todas las tareas anteriores pasan. La tarea iOS confirma compilación Kotlin/Native, pero no Xcode, firma, simulator ni runtime iOS. El task `run` puede importar valores de `.env` al proceso local; no se deben imprimir ni commitear secretos.
