package com.northgate.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import java.util.List;

/**
 * Canonical attribute keys for Northgate business telemetry. All instrumentation
 * must reference these constants rather than inlining attribute-key string literals.
 */
public final class NorthgateAttributes {

    private NorthgateAttributes() {
    }

    public static final AttributeKey<String> CUSTOMER_ID = AttributeKey.stringKey("northgate.customer.id");
    public static final AttributeKey<String> TENANT_ID = AttributeKey.stringKey("northgate.tenant.id");
    public static final AttributeKey<String> ORDER_ID = AttributeKey.stringKey("northgate.order.id");
    public static final AttributeKey<String> SHIPMENT_ID = AttributeKey.stringKey("northgate.shipment.id");

    /**
     * Baggage entries the {@code BaggageEnrichingSpanProcessor} is permitted to copy
     * onto spans. Only ubiquitous, non-sensitive context belongs here.
     */
    public static final List<String> BAGGAGE_ALLOWLIST = List.of(
            CUSTOMER_ID.getKey(),
            TENANT_ID.getKey());
}
