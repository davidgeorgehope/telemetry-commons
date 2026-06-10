# telemetry-commons

Internal wrapper library around the OpenTelemetry Java SDK for Northgate services.
It centralizes SDK configuration, enforces the business-telemetry contract, and
provides helpers so application code never touches `GlobalOpenTelemetry`.

- **OTel BOM:** `io.opentelemetry:opentelemetry-bom:1.63.0`
- **Semantic conventions:** `io.opentelemetry.semconv:opentelemetry-semconv:1.41.1`
- **Testing:** `io.opentelemetry:opentelemetry-sdk-testing` (`InMemorySpanExporter`)
- **Java:** 17 (Gradle toolchain)

## Components

| Class | Responsibility |
| --- | --- |
| `com.northgate.telemetry.NorthgateAttributes` | Canonical `AttributeKey` constants + `BAGGAGE_ALLOWLIST` |
| `com.northgate.telemetry.TelemetryProvider` | Configured `OpenTelemetrySdk` (BatchSpanProcessor + InMemorySpanExporter, W3C trace + baggage); `getTracer(String)` |
| `com.northgate.telemetry.TracingSupport` | `withBusinessContext(...)` sets Baggage; `startSpan(...)` plain helper |
| `com.northgate.telemetry.processors.BaggageEnrichingSpanProcessor` | Copies allowlisted Baggage entries onto spans at start |
| `com.northgate.telemetry.annotations.RequestPath` / `InternalSpan` | Request-path classification markers |
| `com.northgate.demo.api.OrderController` | Demo `GET /orders/{id}` over `com.sun.net.httpserver` with an async path |

## How enrichment works

1. The auth filter calls `TracingSupport.withBusinessContext(customer, tenant, ...)`,
   putting `northgate.customer.id` / `northgate.tenant.id` into Baggage **once** at the edge.
2. Handlers create spans and set only span-local attributes (e.g. `northgate.order.id`).
3. `BaggageEnrichingSpanProcessor.onStart` copies allowlisted Baggage entries onto every span.
4. Async work submitted via `Context.current().wrap(task)` carries Baggage across threads,
   so child spans are enriched too.

## Build & test

The Gradle toolchain targets Java 17. If your JDK 17 is not auto-detected, point
Gradle at it (see `gradle.properties`) or run with `JAVA_HOME` set to a JDK 17.

```bash
./gradlew test
```
