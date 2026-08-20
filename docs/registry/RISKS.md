# Risk Registry

| ID | Riesgo | Severidad | Estado | Próximo gate |
|---|---|---|---|---|
| `RISK-001` | Schema/migration drift | P1 | abierto | baseline read-only + canonical migration decision |
| `RISK-002` | Login bypass | P1 | mitigado en cliente; backend authz parcial | explicit admin-role gate |
| `RISK-003` | PDF import non-atomic | P1 | abierto | staged/reviewable design |
| `RISK-004` | Global build failure | P1 | resuelto con evidencia local | maintain global test gate |
| `RISK-005` | Service-role concentration | P1 | abierto | security review |
| `RISK-006` | Legacy/V1 coexistence | P1 | abierto | compatibility evidence |
| `RISK-007` | Supabase runtime unverified | P1 | abierto | authorized read-only verification |
| `RISK-008` | UI runtime unverified | P1 | abierto | visual/runtime baseline |
| `RISK-009` | Admin authorization checks any assigned role; static token remains temporary | P1 | abierto | Security + product admin-role decision |

No risk is resolved by being listed here.
