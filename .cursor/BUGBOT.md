# Bugbot review rules — telemetry-commons

Review every PR against the telemetry contract (.cursor/rules/business-telemetry.mdc and otel-mechanics.mdc):

- Follow helper-method indirection the static CI gate cannot: when a request-path span's attributes are set via helpers, verify the helper actually sets the required contract attributes.
- Flag manual setAttribute of customer/tenant IDs anywhere outside BaggageEnrichingSpanProcessor.
- Flag Span/Scope lifecycles not using try-with-resources / finally, and executor submissions missing Context.current().wrap().
- Flag deprecated OTel APIs or semconv keys.
- Flag new instrumentation lacking tests with attribute assertions and error-path coverage.

Advisory only: comment with suggested fixes; never block.
