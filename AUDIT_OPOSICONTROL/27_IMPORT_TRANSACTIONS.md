# Import Transactions

## Question 73 of 100 scenario

For the PDF route, code loops `preguntas.forEachIndexed`, calls `gateway.createPregunta`, then optionally adds a resource. No surrounding transaction, batch RPC, staging insert or compensation is visible in the route. Therefore behavior is `PARTIAL/UNKNOWN`, with a strong code-based risk of `PARTIAL_INSERT` if question 73 fails.

For the UI text import, the ViewModel builds all questions first and invokes one repository method; actual DB atomicity depends on repository/gateway and is not proven.

The separate staging API suggests a possible all-at-once workflow, but the PDF endpoint does not write staging records before importing.

## Classification

PDF route: `PARTIAL_INSERT` likely; `ROLLBACK` not verified. UI bulk route: `UNKNOWN`. No destructive test was run.
