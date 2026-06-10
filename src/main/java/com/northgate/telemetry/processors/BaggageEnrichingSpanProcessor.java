package com.northgate.telemetry.processors;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.List;

/**
 * Copies allowlisted Baggage entries from the active context onto each span at
 * start time. Modeled on the otel-java-contrib BaggageSpanProcessor, but
 * restricted to an explicit allowlist so non-queryable or sensitive baggage is
 * never promoted to span attributes.
 */
public final class BaggageEnrichingSpanProcessor implements SpanProcessor {

    private final List<String> allowlist;

    public BaggageEnrichingSpanProcessor(List<String> allowlist) {
        this.allowlist = List.copyOf(allowlist);
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        Baggage baggage = Baggage.fromContext(parentContext);
        for (String key : allowlist) {
            String value = baggage.getEntryValue(key);
            if (value != null) {
                span.setAttribute(key, value);
            }
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // Enrichment happens at start; nothing to do on end.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
