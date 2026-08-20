# Observability

Current logging changes in the working tree are protected preexisting changes. Runtime dashboards, traces, deployed logs and end-to-end visibility are `NOT_VERIFIED`. Evidence must be sanitized; secrets/tokens never enter docs.

Source inspection for `OPC-20260819-004`: Logback is a backend runtime dependency; OposiBots auth uses structured slf4j logging and request IDs; general status handling still includes `System.err` paths. Ktor `CallLogging`, global metrics, tracing and a global request-id plugin are not installed. Health endpoints and local compile/tests are not runtime observability evidence.
