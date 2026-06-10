package com.northgate.demo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.northgate.telemetry.NorthgateAttributes;
import com.northgate.telemetry.TelemetryProvider;
import com.northgate.telemetry.TracingSupport;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderControllerTest {

    private TelemetryProvider telemetry;
    private ExecutorService executor;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        telemetry = TelemetryProvider.createForLocalDev();
        executor = Executors.newFixedThreadPool(2);
        controller = new OrderController(telemetry, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        telemetry.close();
    }

    private SpanData spanNamed(InMemorySpanExporter exporter, String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Test
    void requestSpanIsEnrichedFromBaggageAndUsesRouteTemplate() {
        TracingSupport.withBusinessContext("cust-1", "tenant-1",
                () -> controller.serveOrder("order-42"));
        telemetry.forceFlush();

        SpanData orderSpan = spanNamed(telemetry.getSpanExporter(), "GET /orders/{id}");

        // (b) span name is the low-cardinality route template
        assertNotNull(orderSpan, "expected a span named with the route template");

        // span-local attribute set explicitly by the handler
        assertEquals("order-42", orderSpan.getAttributes().get(NorthgateAttributes.ORDER_ID));

        // (a) customer id is present via processor enrichment, even though the
        // handler never set it manually
        assertEquals("cust-1", orderSpan.getAttributes().get(NorthgateAttributes.CUSTOMER_ID));
        assertEquals("tenant-1", orderSpan.getAttributes().get(NorthgateAttributes.TENANT_ID));
    }

    @Test
    void asyncSpanRetainsTenantAcrossThreadPool() {
        TracingSupport.withBusinessContext("cust-1", "tenant-1",
                () -> controller.serveOrder("order-42"));
        telemetry.forceFlush();

        SpanData asyncSpan = spanNamed(telemetry.getSpanExporter(), "process-order-async");
        assertNotNull(asyncSpan, "expected the async span to be exported");

        // (c) Context.current().wrap() carried baggage to the worker thread, so the
        // enriching processor still applied tenant on the async span
        assertEquals("tenant-1", asyncSpan.getAttributes().get(NorthgateAttributes.TENANT_ID));
        assertEquals("cust-1", asyncSpan.getAttributes().get(NorthgateAttributes.CUSTOMER_ID));
    }

    @Test
    void handlerDoesNotSetUbiquitousAttributesWithoutBusinessContext() {
        // No withBusinessContext: nothing in baggage, so the enriching processor
        // has nothing to copy and the handler must not invent these values.
        controller.serveOrder("order-99");
        telemetry.forceFlush();

        List<SpanData> spans = telemetry.getSpanExporter().getFinishedSpanItems();
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("GET /orders/{id}")));

        SpanData orderSpan = spanNamed(telemetry.getSpanExporter(), "GET /orders/{id}");
        assertEquals("order-99", orderSpan.getAttributes().get(NorthgateAttributes.ORDER_ID));
        assertEquals(null, orderSpan.getAttributes().get(NorthgateAttributes.CUSTOMER_ID));
        assertEquals(null, orderSpan.getAttributes().get(NorthgateAttributes.TENANT_ID));
    }
}
