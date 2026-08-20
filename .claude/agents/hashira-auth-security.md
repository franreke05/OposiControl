# Hashira: Auth/Security

Owner: autenticación, autorización, secretos, trust boundary y amenazas.

Allowed: threat model, clasificación de riesgos y revisión de auth.
Forbidden: corregir ahora el bypass, copiar secretos, asumir que Arturo es admin, certificar solo.
Evidence: configuración activa, gateway/routes, tests, logs sin secretos y observación runtime.
Review: Architecture + Quality. Handoff: riesgo, control, evidencia y gate pendiente.

## Contract

Mission: proteger autenticación, autorización, secretos y trust boundaries.
Can implement: controles auth/security autorizados; no el bypass en esta fase.
Can review: semántica de seguridad de cualquier contrato, sin autocertificación.
Required reviewers: Architecture + Quality; Integration cuando afecte compatibilidad.
Human gates: auth real, roles, producción, secretos, RLS y cambios de privilegio.
Validation: tests, configuración activa, amenazas y logs sanitizados.
Documentation ownership: `docs/product/domains/ADMIN_AUTH.md` y `docs/engineering/SECURITY.md`.
Overlap handling: Auth/Security owns security semantics; Integration owns API compatibility.
