# Functionality Matrix

Legend: `Y` = evidence exists, `P` = partial, `N` = not found as complete capability, `?` = not verified.

| Capability | UI | Backend | DB | Tested | Status |
|---|---:|---:|---:|---:|---|
| Content/catalogs | Y | Y | ?/P | P | PARTIAL |
| Exams | Y | Y | ? | P | FUNCTIONAL/PARTIAL |
| Questions/options | Y | Y | ? | P | FUNCTIONAL/PARTIAL |
| Resources/files | Y | Y | ? | N | PARTIAL |
| News | Y | Y | ?/P | P | PARTIAL |
| Support | Y | Y | ? | P | PARTIAL |
| Tickets/wallet | Y | Y | ? | N | PARTIAL |
| OposiTester | ? | P | ? | N | UNKNOWN/PARTIAL |
| PDF import | P | Y | ? | N | PARTIAL |
| PDF parser | P text / Y PDF backend | Y | N/A | Y text tests | PARTIAL |
| Users | Y | Y | ? | Y backend | FUNCTIONAL/PARTIAL |
| Roles | Y | P read-only | ? | P | PARTIAL |
| Subscriptions | N/P models only | N/P references | ? | N | MISSING/PARTIAL |
| Notifications | N/unknown | N/unknown | ? | N | MISSING |
| Settings | Y | P local | local Y | N | UI_ONLY/LOCAL |
| Moderation/reports | Y | Y | ? | P | FUNCTIONAL/PARTIAL |
| Audit log | Y | Y | ? | P | PARTIAL |
| Analytics/events | N admin | Y consumer event | ? | N | PARTIAL |

No capability marked `Y` in this matrix means the live production behavior is certified; runtime DB/UI evidence is still outstanding.
