package com.stayos.integration;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controlled simulation supplier (guide section 14: 缺少条件时可完成 adapter 协议、持久化和可控模拟测试).
 * No external HTTP: pushes are echoed into an in-memory registry, callbacks are signed with
 * HMAC-SHA256 over the raw body. It exercises the real protocol paths — signatures, duplicate
 * callbacks, out-of-order sequences, bounded retries, reconciliation — without guessing any
 * private vendor API. The real supplier stays BLOCKED_EXTERNAL until the gate conditions exist.
 */
@Component
public class SimulatorAdapter implements SupplierAdapter {

    static final String SIGNATURE_HEADER = "X-StayOS-Signature";
    /** connectionId -> externalRef -> last pushed order fact (the simulated supplier's ledger). */
    static final Map<Long, Map<String, SupplierAdapter.RemoteOrder>> LEDGER = new ConcurrentHashMap<>();

    /** Test hook: reset the simulated supplier between scenarios (integration tests, same package). */
    static void clearLedger() { LEDGER.clear(); }

    @Override public String supplierCode() { return "SIMULATOR"; }

    static String hmacHex(String secret, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(64);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException("HmacSHA256 unavailable", e); }
    }

    @Override public boolean verifyCallback(Credentials credentials, Map<String, String> headers, String rawBody) {
        String provided = headers.getOrDefault(SIGNATURE_HEADER, headers.getOrDefault("x-stayos-signature", ""));
        if (provided.isBlank()) return false;
        return constantTimeEquals(hmacHex(credentials.callbackSecret(), rawBody), provided);
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    @Override public StandardInbound parseCallback(Credentials credentials, Map<String, String> headers, String rawBody) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawBody);
            StandardInbound inbound = new StandardInbound(
                node.path("eventId").asText(null), node.path("eventType").asText(null), node.path("sequenceNo").asLong(-1),
                node.path("payload").path("arrivalDate").asText(null), node.path("payload").path("departureDate").asText(null),
                node.path("payload").path("roomTypeCode").asText(null), node.path("payload").path("externalRef").asText(null),
                node.path("payload").path("contactName").asText(null), node.path("payload").path("contactPhone").asText(null),
                node.path("payload").path("notes").asText(null));
            if (inbound.externalEventId() == null || inbound.externalEventId().isBlank()
                || inbound.eventType() == null || inbound.eventType().isBlank() || inbound.sequenceNo() < 0)
                throw new com.stayos.common.ApiException(400, "CALLBACK_MALFORMED", "回调缺少事件标识、类型或序号");
            return inbound;
        } catch (com.stayos.common.ApiException e) { throw e;
        } catch (Exception e) { throw new com.stayos.common.ApiException(400, "CALLBACK_MALFORMED", "回调内容不是合法 JSON"); }
    }

    @Override public PushOutcome push(Credentials credentials, OutboundEvent event) {
        // The simulated supplier accepts every well-formed push and records the echo. An external
        // supplier would do real HTTP here with a short timeout, outside any database transaction.
        if (event.eventType() == null || event.payloadJson() == null) return new PushOutcome(false, "出站事件缺少类型或内容");
        if ("RESERVATION_CANCELLED".equals(event.eventType()) && event.externalRef() != null)
            LEDGER.computeIfAbsent(event.connectionId(), k -> new ConcurrentHashMap<>())
                  .put(event.externalRef(), new RemoteOrder(event.externalRef(), "CANCELLED", null, null));
        else if (event.externalRef() != null) {
            var node = readTree(event.payloadJson());
            LEDGER.computeIfAbsent(event.connectionId(), k -> new ConcurrentHashMap<>())
                  .put(event.externalRef(), new RemoteOrder(event.externalRef(),
                      event.eventType().endsWith("_CANCELLED") ? "CANCELLED" : "CONFIRMED",
                      node.path("arrivalDate").asText(null), node.path("departureDate").asText(null)));
        }
        return new PushOutcome(true, "模拟供应商已接收 " + event.eventType());
    }

    @Override public List<RemoteOrder> snapshot(Credentials credentials, long connectionId) {
        Map<String, RemoteOrder> ledger = LEDGER.get(connectionId);
        return ledger == null ? List.of() : new ArrayList<>(ledger.values());
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json); }
        catch (Exception e) { throw new IllegalStateException("Outbox payload is not valid JSON", e); }
    }
}
