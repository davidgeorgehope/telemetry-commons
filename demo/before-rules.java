package com.northgate.demo.shipping;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Demo shipment service for POST /shipments, instrumented with OpenTelemetry so
 * shipment creation is visible in the observability backend. createShipment runs
 * inside a span carrying the shipment's order, customer, carrier, and id; the
 * dispatch worker runs as a child span via propagated context, and a failed
 * carrier rating is recorded on the span rather than silently swallowed.
 */
public final class ShipmentService {

    private static final Tracer TRACER =
            GlobalOpenTelemetry.getTracer("com.northgate.demo.shipping");

    private final Map<String, String> shipments = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public ShipmentService(ExecutorService executor) {
        this.executor = executor;
    }

    /** Validates input, rates a carrier, persists the shipment, and dispatches it. */
    public String createShipment(String orderId, String customerId) {
        Span span = TRACER.spanBuilder("ShipmentService.createShipment").startSpan();
        try (Scope scope = span.makeCurrent()) {
            if (orderId == null || orderId.isBlank()) {
                throw new IllegalArgumentException("orderId is required");
            }
            if (customerId == null || customerId.isBlank()) {
                throw new IllegalArgumentException("customerId is required");
            }
            span.setAttribute("shipment.order_id", orderId);
            span.setAttribute("shipment.customer_id", customerId);
            String carrier = rateCarrier(orderId);
            span.setAttribute("shipment.carrier", carrier);
            String shipmentId = "shp-" + UUID.randomUUID();
            shipments.put(shipmentId, orderId + ":" + carrier);
            span.setAttribute("shipment.id", shipmentId);
            dispatchAsync(shipmentId);
            return shipmentId;
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private String rateCarrier(String orderId) {
        try {
            Thread.sleep(20); // simulate carrier rating latency
        } catch (InterruptedException e) {
            // Restore the interrupt flag and surface the failure as a recorded,
            // propagated error rather than swallowing it.
            Thread.currentThread().interrupt();
            Span.current().recordException(e);
            throw new IllegalStateException("carrier rating interrupted", e);
        }
        return "carrier-standard";
    }

    private void dispatchAsync(String shipmentId) {
        // Propagate the current trace context (and Baggage) into the worker thread
        // so the dispatch runs as a child of createShipment instead of a new trace.
        executor.submit(Context.current().wrap(() -> {
            Span span = TRACER.spanBuilder("ShipmentService.dispatch").startSpan();
            try (Scope scope = span.makeCurrent()) {
                span.setAttribute("shipment.id", shipmentId);
                String record = shipments.get(shipmentId);
                System.out.println("dispatched " + shipmentId + " -> " + record);
            } finally {
                span.end();
            }
        }));
    }

    /** Wires POST /shipments into the demo HTTP server. */
    public void registerOn(HttpServer server) {
        server.createContext("/shipments", new ShipmentHandler());
    }

    private final class ShipmentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String orderId = header(exchange, "X-Order-Id");
            String customerId = header(exchange, "X-Customer-Id");
            String shipmentId = createShipment(orderId, customerId);
            byte[] body = shipmentId.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private String header(HttpExchange exchange, String name) {
            String value = exchange.getRequestHeaders().getFirst(name);
            return value != null ? value : "";
        }
    }
}
