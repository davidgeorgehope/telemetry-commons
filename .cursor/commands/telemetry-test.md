# telemetry-test

Given a class, package, or diff, strengthen its telemetry tests.

1. Identify every span the code under test emits (search for spanBuilder / TracingSupport usage).
2. Generate JUnit 5 tests using InMemorySpanExporter asserting, for each span: name is the expected low-cardinality template; required span-local attributes per business-telemetry.mdc; ubiquitous attributes present via processor enrichment; error paths record the exception and set status ERROR.
3. Flag any use of deprecated OTel APIs or semconv keys — verify against @Docs (OTel Java SDK, semantic conventions), not memory.
4. Flag spans with missing or weak existing tests (no attribute assertions, no error-path coverage).
5. Run ./gradlew test and report.
