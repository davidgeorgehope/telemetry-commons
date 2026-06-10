package com.northgate.telemetry;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * Helpers for establishing business context and starting spans.
 */
public final class TracingSupport {

    private TracingSupport() {
    }

    /**
     * Sets ubiquitous business context into Baggage exactly once at the request
     * edge and makes it current for the duration of {@code runnable}. Downstream
     * spans are enriched with these values by the BaggageEnrichingSpanProcessor;
     * callers must NOT set customer/tenant attributes on individual spans.
     */
    public static void withBusinessContext(String customerId, String tenantId, Runnable runnable) {
        Baggage baggage = Baggage.current().toBuilder()
                .put(NorthgateAttributes.CUSTOMER_ID.getKey(), customerId)
                .put(NorthgateAttributes.TENANT_ID.getKey(), tenantId)
                .build();
        Context context = Context.current().with(baggage);
        try (Scope scope = context.makeCurrent()) {
            runnable.run();
        }
    }

    /**
     * Starts a plain span. Intentionally sets no business attributes — span-local
     * business attributes are the caller's responsibility, and ubiquitous ones are
     * added by the enriching processor.
     */
    public static Span startSpan(Tracer tracer, String name) {
        return tracer.spanBuilder(name).startSpan();
    }
}
