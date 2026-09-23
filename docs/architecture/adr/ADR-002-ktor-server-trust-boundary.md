# ADR-002: Ktor server trust boundary

Status: `ACCEPTED_ARCHITECTURE_DIRECTION` · Date: `2026-08-19`

Decision: Ktor es el boundary servidor para credenciales, auth y acceso privilegiado; el cliente no se considera trust boundary.

Evidence: `backend/ktor`, gateways/routes actuales y auditoría de seguridad.
Consequence: todo contrato privilegiado se revisa por Backend, Architecture y Auth/Security. La efectividad live queda `NOT_VERIFIED`.
