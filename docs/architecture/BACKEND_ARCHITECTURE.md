# Backend Architecture

## CURRENT_IMPLEMENTATION

El backend activo del repositorio está en `backend/ktor`. Las rutas delegan en servicios/gateways y usan configuración de Supabase esperada. El detalle completo se mantiene en [ROUTE_MAP](../backend/ROUTE_MAP.md), [SERVICE_MAP](../backend/SERVICE_MAP.md) y [GATEWAY_MAP](../backend/GATEWAY_MAP.md).

## Boundaries

- Ktor es el trust boundary del servidor.
- El cliente no debe recibir service-role credentials.
- Supabase live y su runtime no están verificados.
- OposiBot es una integración separada; consultar [OPOSIBOT_CONTRACT](../integration/OPOSIBOT_CONTRACT.md).

## NOT_VERIFIED

No se afirma atomicidad, disponibilidad, RLS efectiva ni despliegue live. La reorganización no modifica backend funcional.
