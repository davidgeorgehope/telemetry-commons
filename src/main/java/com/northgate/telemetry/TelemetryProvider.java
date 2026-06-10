package com.northgate.telemetry;

import com.northgate.telemetry.processors.BaggageEnrichingSpanProcessor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.concurrent.TimeUnit;

/**
 * Central, configured {@link OpenTelemetrySdk} for the application. Library code
 * obtains tracers through {@link #getTracer(String)} and must never call
 * {@code GlobalOpenTelemetry.get()}.
 *
 * <p>This local-dev configuration wires a {@link BaggageEnrichingSpanProcessor}
 * (start-time enrichment) ahead of a {@link BatchSpanProcessor} exporting to an
 * {@link InMemorySpanExporter}, with W3C trace-context and baggage propagation.
 */
public final class TelemetryProvider {

    private final OpenTelemetrySdk sdk;
    private final SdkTracerProvider tracerProvider;
    private final InMemorySpanExporter spanExporter;

    private TelemetryProvider(OpenTelemetrySdk sdk, SdkTracerProvider tracerProvider,
            InMemorySpanExporter spanExporter) {
        this.sdk = sdk;
        this.tracerProvider = tracerProvider;
        this.spanExporter = spanExporter;
    }

    /** Builds a TelemetryProvider suitable for local development and tests. */
    public static TelemetryProvider createForLocalDev() {
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(new BaggageEnrichingSpanProcessor(NorthgateAttributes.BAGGAGE_ALLOWLIST))
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(),
                        W3CBaggagePropagator.getInstance())))
                .build();

        return new TelemetryProvider(sdk, tracerProvider, spanExporter);
    }

    public Tracer getTracer(String instrumentationName) {
        return sdk.getTracer(instrumentationName);
    }

    public OpenTelemetry getOpenTelemetry() {
        return sdk;
    }

    /** Exposed for local dev and tests to inspect emitted spans. */
    public InMemorySpanExporter getSpanExporter() {
        return spanExporter;
    }

    /** Flushes the BatchSpanProcessor so emitted spans reach the exporter. */
    public void forceFlush() {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    }

    public void close() {
        sdk.close();
    }
}
