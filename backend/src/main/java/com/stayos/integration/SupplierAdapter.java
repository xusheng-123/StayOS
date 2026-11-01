package com.stayos.integration;

import java.util.List;
import java.util.Map;

/**
 * Supplier-neutral adapter SPI (guide section 14): vendor fields and wire formats stop inside
 * implementations; nothing here leaks into Stay/Reservation core DTOs. Implementations are small
 * and registered by supplierCode. Real suppliers join only after the guide's gate conditions
 * (user-specified supplier, legal API access, official docs, sandbox, mappings, callback auth).
 */
public interface SupplierAdapter {

    String supplierCode();

    /** Constant-time signature check of the raw callback body against the connection's callback secret. */
    boolean verifyCallback(Credentials credentials, Map<String, String> headers, String rawBody);

    /** Parse a VERIFIED callback into the neutral inbound event; this is the vendor boundary. */
    StandardInbound parseCallback(Credentials credentials, Map<String, String> headers, String rawBody);

    /** Deliver one outbound event. Called by the worker OUTSIDE any room/inventory transaction. */
    PushOutcome push(Credentials credentials, OutboundEvent event);

    /** Supplier-side order view for reconciliation; simulated suppliers answer from their echo registry. */
    List<RemoteOrder> snapshot(Credentials credentials, long connectionId);

    /** Resolved references only — the secret values never appear in logs, APIs or audit text. */
    record Credentials(String credentialRef, String callbackSecretRef, String credential, String callbackSecret) {}

    /** Neutral inbound order event produced by parseCallback (arrival/departure ISO dates, [from, to)). */
    record StandardInbound(String externalEventId, String eventType, long sequenceNo,
                           String arrivalDate, String departureDate, String roomTypeExternalCode,
                           String externalRef, String contactName, String contactPhone, String notes) {}

    record OutboundEvent(long connectionId, String eventType, String aggregateType, long aggregateId,
                         String externalRef, String payloadJson) {}

    record PushOutcome(boolean success, String detail) {}

    /** One supplier-side order fact used by reconciliation (no vendor identity data). */
    record RemoteOrder(String externalRef, String status, String arrivalDate, String departureDate) {}
}
