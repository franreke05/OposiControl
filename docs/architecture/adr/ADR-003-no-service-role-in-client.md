# ADR-003: No service role in client

Status: `ACCEPTED_SECURITY_DIRECTION` · Date: `2026-08-19`

Decision: service-role credentials no se exponen al cliente.

Evidence: boundary Ktor y auditoría `18_SECURITY_AUDIT.md`.
Consequence: gateways server-side y configuración segura son obligatorios para cambios futuros; no se inspeccionan secretos en esta reorganización.
