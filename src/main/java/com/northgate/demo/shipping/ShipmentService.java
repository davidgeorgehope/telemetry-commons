package com.northgate.demo.shipping;

import com.northgate.telemetry.NorthgateAttributes;
import com.northgate.telemetry.TelemetryProvider;
import com.northgate.telemetry.annotations.RequestPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Demo shipment service for POST /shipments. Intentionally has ZERO telemetry —
 * this is the new engineer's first instrumentation task. Two traps are staged
 * on purpose for the exercise:
 *   - dispatchAsync submits to an ExecutorService without propagating context.
 *   - rateCarrier swallows an InterruptedException in a catch block.
 */
public final class ShipmentService {

    private static final String INSTRUMENTATION_NAME = "com.northgate.demo.shipping.ShipmentService";

    private final Map<String, String> shipments = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Tracer tracer;

    public ShipmentService(TelemetryProvider telemetry, ExecutorService executor) {
        this.executor = executor;
        this.tracer = telemetry.getTracer(INSTRUMENTATION_NAME);
    }

    /** Validates input, rates a carrier, persists the shipment, and dispatches it. */
    public String createShipment(String orderId, String customerId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        String carrier = rateCarrier(orderId);
        String shipmentId = "shp-" + UUID.randomUUID();
        shipments.put(shipmentId, orderId + ":" + carrier);
        dispatchAsync(shipmentId);
        return shipmentId;
    }

    private String rateCarrier(String orderId) {
        Span span = tracer.spanBuilder("rate-carrier").startSpan();
        span.setAttribute(NorthgateAttributes.ORDER_ID, orderId);
        Scope scope = span.makeCurrent();
        try {
            Thread.sleep(20); // simulate carrier rating latency
        } catch (InterruptedException e) {
        } finally {
            span.end();
        }
        return "carrier-standard";
    }

    private void dispatchAsync(String shipmentId) {
        executor.submit(() -> {
            String record = shipments.get(shipmentId);
            System.out.println("dispatched " + shipmentId + " -> " + record);
        });
    }

    /** Wires POST /shipments into the demo HTTP server. */
    public void registerOn(HttpServer server) {
        server.createContext("/shipments", new ShipmentHandler());
    }

    private final class ShipmentHandler implements HttpHandler {
        @RequestPath
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
