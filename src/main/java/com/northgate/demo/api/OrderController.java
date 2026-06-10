package com.northgate.demo.api;

import com.northgate.telemetry.NorthgateAttributes;
import com.northgate.telemetry.TelemetryProvider;
import com.northgate.telemetry.TracingSupport;
import com.northgate.telemetry.annotations.RequestPath;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Demo HTTP controller for {@code GET /orders/{id}} built on
 * {@code com.sun.net.httpserver}. An auth filter establishes business context via
 * {@link TracingSupport#withBusinessContext}; the handler creates the request
 * span and sets the span-local order id. A downstream async step is dispatched on
 * an {@link ExecutorService} with {@code Context.current().wrap(...)} so trace
 * context and baggage cross the thread boundary.
 */
public final class OrderController {

    private static final String INSTRUMENTATION_NAME = "com.northgate.demo.api.OrderController";

    private final Tracer tracer;
    private final ExecutorService executor;

    public OrderController(TelemetryProvider telemetry, ExecutorService executor) {
        this.tracer = telemetry.getTracer(INSTRUMENTATION_NAME);
        this.executor = executor;
    }

    /**
     * Handler logic for {@code GET /orders/{id}}. Must be invoked within an active
     * business context (set by the auth filter). Creates the request span, records
     * the span-local order id, and performs an async sub-step.
     */
    @RequestPath
    public String serveOrder(String orderId) {
        Span span = TracingSupport.startSpan(tracer, "GET /orders/{id}");
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute(NorthgateAttributes.ORDER_ID, orderId);

            // Baggage + trace context are captured here and restored on the worker
            // thread, so the async span is also enriched with customer/tenant.
            Future<String> result = executor.submit(Context.current().wrap(() -> processOrderAsync(orderId)));
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    private String processOrderAsync(String orderId) {
        Span span = TracingSupport.startSpan(tracer, "process-order-async");
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute(NorthgateAttributes.ORDER_ID, orderId);
            return "order:" + orderId;
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Starts an HTTP server exposing {@code GET /orders/{id}} with an auth filter
     * that establishes business context. Returns the running server (call
     * {@code stop} to shut it down).
     */
    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/orders", new OrderHandler()).getFilters().add(new AuthFilter());
        server.setExecutor(executor);
        server.start();
        return server;
    }

    /** Resolves business context from request headers and installs it as Baggage. */
    private static final class AuthFilter extends Filter {
        @Override
        public void doFilter(HttpExchange exchange, Chain chain) {
            String customerId = headerOrDefault(exchange, "X-Customer-Id", "anonymous");
            String tenantId = headerOrDefault(exchange, "X-Tenant-Id", "public");
            TracingSupport.withBusinessContext(customerId, tenantId, () -> {
                try {
                    chain.doFilter(exchange);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public String description() {
            return "northgate-auth";
        }

        private static String headerOrDefault(HttpExchange exchange, String name, String fallback) {
            String value = exchange.getRequestHeaders().getFirst(name);
            return value != null ? value : fallback;
        }
    }

    private final class OrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String orderId = path.substring(path.lastIndexOf('/') + 1);
            String body = serveOrder(orderId);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
