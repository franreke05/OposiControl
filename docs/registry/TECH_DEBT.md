# Active Technical Debt

Database redesign follow-up remains active for entitlement XOR semantics,
session/attempt idempotency, Storage consumer evidence and policy optimization.
See `docs/database/MIGRATION_STATE.md`.

Only concrete independent debt remains here.

| ID | TITLE | STATUS | NEXT_ACTION | REFERENCE |
|---|---|---|---|---|
| DEBT-006 | Runtime observability and platform verification | ACTIVE | Close release/runtime evidence gaps | ../engineering/DEPLOYMENT.md |
| DEBT-010 | Storage object/path inventory | ACTIVE | Inventory objects and consumers before any cleanup | ../database/STORAGE.md |
| DEBT-011 | OposiRed semantic adapter | ACTIVE | Define adapter contract without destructive rename | ../integration/README.md |
