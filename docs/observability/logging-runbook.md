# Runbook de logging seguro

Fecha de corte: 29 de julio de 2026. Noticias permanece congelada y no forma
parte de esta revisión.

## Contrato

Los logs permanentes se emiten con `LogEvent` y uno de estos tags:

| Tag | Capa |
|---|---|
| `OC-UI` | navegación y acciones explícitas de pantalla |
| `OC-VM` | intent, resultado y effect de ViewModel |
| `OC-REPO` | entrada y resultado de repositorio |
| `OC-NET` | petición, retry y resultado HTTP |
| `OC-DIAG` | diagnóstico técnico iniciado por el usuario |
| `OC-UPLOAD` | fases de subida y compensación |
| `OC-KTOR` | recepción y resultado de Ktor |
| `OC-SUPA` | llamada y resultado de Supabase/RPC/Storage |

Un evento debe tener un nombre estable, por ejemplo
`exam.publish.start`, `exam.publish.success` o `exam.publish.failure`.
`correlationId` identifica la acción completa y `requestId` un intento HTTP.
Un retry conserva `correlationId` y genera un `requestId` nuevo.

Solo se admiten los campos definidos en `SafeLogField`. Sus valores deben ser
categorías, contadores, métodos, estados, rutas parametrizadas o resultados.
No se registran cuerpos, tokens, claves, cookies, nombres, emails, preguntas,
mensajes de soporte, búsquedas, nombres de archivo, query strings ni URLs
firmadas. Los IDs de negocio tampoco se guardan como fields; para seguimiento
se usan exclusivamente `correlationId` y `requestId`.

La configuración esperada es:

- dev: `DEBUG`, con un único stacktrace por error;
- staging: `INFO`;
- release: `WARN` y `ERROR`;
- la ampliación de verbosidad solo está permitida en dev.

## Captura local

Los capturadores escriben únicamente eventos estructurados con tags `OC-*`.
Vuelven a redactar credenciales como defensa adicional, eliminan query strings,
parametrizan IDs de ruta y conservan solo frames de stacktrace sin mensajes de
excepción.

```powershell
# Android/Logcat
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\logs\Capture-AndroidLogs.ps1 `
  -Session users-save

# Aplicación JVM
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\logs\Capture-JvmLogs.ps1 `
  -Session exams-import `
  -Tags OC-UI,OC-VM,OC-NET

# Backend Ktor
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\logs\Capture-BackendLogs.ps1 `
  -Session ticket-adjustment `
  -Tags OC-KTOR,OC-SUPA
```

Se puede aislar una operación con `-CorrelationId <id>` o un intento con
`-RequestId <id>`. Los resultados quedan en
`.logs/dev/<sesion>/<origen>.log`. Por defecto cada fichero rota a 10 MB y
conserva cinco generaciones; se ajusta con `-MaxFileSizeMb` y `-MaxFiles`.
`.logs/dev` está excluido del repositorio.

Antes de compartir una captura:

1. Buscar `Bearer`, `eyJ`, `authorization=`, `token=`, `secret=`, `@` y `?`.
2. Verificar que cada fallo tenga un solo bloque de frames.
3. Compartir únicamente el fichero filtrado, nunca el Logcat o stdout bruto.
4. Eliminar instrumentación temporal `DEBUG-<sesion>` al cerrar la incidencia.

## Matriz de cobertura y gaps

Esta matriz refleja el código local en la fecha de corte. `Pendiente` significa
que el área no cumple todavía el gate de observabilidad.

| Área | Estado | Evidencia actual | Gap para cerrar |
|---|---|---|---|
| Modelo común y redactor | Cubierto | `LogEvent`, tags, `SafeLogField`, límites y redacción; tests de credenciales, allowlist y nivel mínimo | Mantener el enum cerrado y revisar cualquier campo nuevo |
| Logger de tests | Cubierto | `InMemoryLogger` común captura la representación ya redactada y permite comprobar un único throwable | Usarlo en pruebas de ViewModel, repositorio y `MockEngine` |
| Niveles por entorno | Cubierto | dev `DEBUG`, staging `INFO`, prod `WARN`; stacktrace habilitado solo en dev | Añadir gate que impida activar verbose en un build release |
| Sinks Android/JVM/iOS | Cubierto localmente | Android/JVM emiten en un solo bloque únicamente tipo, causas y hasta 32 frames allowlisted; iOS emite solo el tipo seguro | Validar el sink iOS en CI macOS |
| Cliente HTTP | Cubierto | `safeCall` propaga `OperationContext` por el contexto de coroutine; el plugin Ktor conserva `correlationId`, crea un `requestId` por intento, normaliza la ruta y registra start/success/failure/retry | Mantener verdes las pruebas `MockEngine` de propagación y retry |
| Repositorios Ktor | Parcial | Comercio nombra sus operaciones; contenido nombra seis puntos de entrada | Contenido tiene 16 `safeCall` sin nombre; admin 12, diagnósticos 3 y búsqueda 3. Hoy aparecen como `unspecified` |
| ViewModels no-News | Pendiente | Se localizaron 13 ViewModels activos y ninguno inyecta `Logger` | Añadir `OC-VM` para acción y resultado, sin log por recomposición ni por carácter; propagar un `OperationContext` por submit |
| UI no-News | Pendiente | Analytics puede registrar pantalla/acción si está habilitado | Faltan eventos explícitos de retry, confirmación destructiva y navegación con el mismo `correlationId` |
| Uploads y recursos | Pendiente | Las excepciones llegan al adaptador de red | Faltan fases `select/validate/upload/db/compensate/result` con `OC-UPLOAD`, MIME agrupado y duración; nunca ruta ni nombre de fichero |
| Ktor | Pendiente, propietario Backend | El código heredado aún contiene salidas `System.err` | Sustituirlas por `OC-KTOR`, CallId/MDC, respuesta/estado/duración y un solo stacktrace seguro |
| Supabase/RPC/Storage | Pendiente, propietario Backend | El tag existe, pero no hay instrumentación estructurada completa | Añadir `OC-SUPA` por operación y resultado, sin SQL, query, body, URL firmada ni actor |
| Captura Android/JVM/backend | Cubierto | Scripts con tags, filtros por IDs, redacción defensiva, rutas parametrizadas y rotación | Ejecutar smoke real cuando haya app/backend activos |

## Gate de aceptación por corte vertical

Un corte no pasa revisión hasta que:

- una acción genera como máximo un `start` y un resultado terminal;
- el mismo `correlationId` atraviesa UI, ViewModel, repositorio y Ktor;
- cada intento HTTP tiene un `requestId` distinto;
- cancelación no se registra como error;
- un fallo genera un único stacktrace y ninguna recomposición genera logs;
- las búsquedas no registran el texto consultado;
- una búsqueda automatizada no encuentra secretos ni datos de usuario en la
  captura filtrada.
