package com.northgate.demo.api;

import com.northgate.telemetry.NorthgateAttributes;
import com.northgate.telemetry.TelemetryProvider;
import com.northgate.telemetry.annotations.RequestPath;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/**
 * Intentionally non-compliant demo controller used to exercise the telemetry
 * contract gate. It lives under /api/ and adds a request-path handler, but:
 *   - it never establishes business context at the edge (CHECK A), and
 *   - it manually sets a ubiquitous attribute the processor owns (CHECK B).
 */
public final class LegacyOrderLookupController {

    private static final String INSTRUMENTATION_NAME =
            "com.northgate.demo.api.LegacyOrderLookupController";

    private final Tracer tracer;

    public LegacyOrderLookupController(TelemetryProvider telemetry) {
        this.tracer = telemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @RequestPath
    public String lookup(String orderId) {
        Span span = tracer.spanBuilder("GET /orders/{id}/legacy").startSpan();
        try {
            span.setAttribute(NorthgateAttributes.ORDER_ID, orderId);
            // VIOLATION: the BaggageEnrichingSpanProcessor owns ubiquitous attributes.
            span.setAttribute(NorthgateAttributes.CUSTOMER_ID, "cust-from-handler");
            return "order:" + orderId;
        } finally {
            span.end();
        }
    }
}
