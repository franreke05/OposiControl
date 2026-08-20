# Support / OposiTester

## Support/tickets

Existe UI y API para conversaciones, mensajes, notas internas, asignación modelada, reportes, moderación y cartera/transacciones de tickets. Tablas SQL documentales incluyen `support_*`, `moderation_action`, `audit_log`, wallets y transactions; exactitud live `NOT_VERIFIED`.

## OposiTester/OposiBot

No aparece un módulo externo OposiTester dentro de este repositorio. Sí hay contratos de feedback de pregunta, reportes, eventos de app y auth OposiBots.

```text
consumer → POST /api/v1/app/feedback/pregunta → feedback_pregunta
admin → GET /api/v1/admin/feedback/preguntas
admin → POST /api/v1/admin/feedback/preguntas/{id}/resolver
```

Flujo de ticket externo completo, adjuntos, device/app version/logs y respuesta al cliente: `PARTIAL/UNKNOWN`; no se deben inventar consumidores.

## Capacidades de ticket

ID/user/date/type/status/description: modelos/DTOs parciales. Priority/assignment/history/resolution: parcialmente modelados. Attachments/device/logs/client response: no confirmados.
