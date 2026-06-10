# instrument

Guide the engineer through instrumenting a code path in this repo.

1. Ask which class/method to instrument, and whether it is request-path or internal (check package conventions and annotations per business-telemetry rules).
2. Read the target file, NorthgateAttributes, TracingSupport, and BaggageEnrichingSpanProcessor so you reason from how enrichment actually works.
3. Generate instrumentation following ALL rules: TelemetryProvider tracer, try-with-resources Scope, route-template span names, span-local attributes (order/shipment IDs) where the contract requires them, recordException + setStatus(ERROR) in catch blocks. NEVER set customer/tenant attributes manually — the processor enriches them from Baggage. If the entrypoint does not establish business context via withBusinessContext, flag it and offer to add it at the edge.
4. Generate a matching JUnit 5 test using InMemorySpanExporter that asserts: span name, span-local attributes, AND processor enrichment (customer/tenant present without the handler setting them).
5. Run ./gradlew test and report results.
