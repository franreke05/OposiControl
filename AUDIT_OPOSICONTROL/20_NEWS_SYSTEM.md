# News System

## Existe

Editor UI, títulos, body/texto, tipos, assets de imagen, fondo/canvas/layers en modelos, estados draft/scheduled/published/archived, publish/unpublish/archive y programación (`publishFrom`). Upload de assets en legacy backend.

## Parcial

La persistencia de `noticia_layer` y el contrato de fondo/capas dependen de SQL documental opcional y gateways legacy. El propio alignment doc indica que sin `noticia_layer` el canvas no persiste profesionalmente. Historial editorial, targeting y notificación no están demostrados.

| Capacidad | Estado |
|---|---|
| editor/title/body/images/categories | EXISTS/PARTIAL |
| draft/publish/schedule/unpublish | EXISTS en modelos/use cases; runtime NOT_VERIFIED |
| preview | UNKNOWN |
| history/audit per article | UNKNOWN; audit general existe |
| notification on publish | MISSING/UNKNOWN |
| targeting | UNKNOWN |

Riesgo: `NewsViewModel` y `NewsGateway` tienen semántica local/remota y mapeos distintos documentados como inestables.
