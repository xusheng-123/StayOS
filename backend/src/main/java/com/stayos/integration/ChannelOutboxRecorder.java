package com.stayos.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayos.reservation.ReservationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enqueues outbound events INTO THE CALLER'S transaction (guide section 14: 出站业务变更与 outbox 在一个
 * 本地事务). Kept as its own bean so ReservationService/InventoryService can depend on it without a
 * cycle back through ChannelService. Channel-origin bookings are located through processed inbound
 * events; desk bookings with no channel origin enqueue nothing.
 */
@Service
public class ChannelOutboxRecorder {

    final ChannelMapper channels;
    final ReservationMapper reservationRows;
    final ObjectMapper json;
    final org.springframework.jdbc.core.JdbcTemplate db;

    public ChannelOutboxRecorder(ChannelMapper channels, ReservationMapper reservationRows, ObjectMapper json,
                                 org.springframework.jdbc.core.JdbcTemplate db) {
        this.channels = channels;
        this.reservationRows = reservationRows;
        this.json = json;
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReservationEvent(long reservationId, String eventType, Long knownConnectionId) {
        Long connectionId = knownConnectionId;
        if (connectionId == null) {
            // Desk-originated change: sync only when the reservation itself came from a channel order.
            var found = db.queryForList("""
                select ie.connection_id from inbound_events ie
                where ie.result_reservation_id=? and ie.status='PROCESSED' order by ie.id desc limit 1
                """, Long.class, reservationId);
            connectionId = found.isEmpty() ? null : found.get(0);
        }
        if (connectionId == null) return; // desk-created booking with no channel origin: nothing to sync
        var row = reservationRows.byId(reservationId);
        if (row == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("externalRef", row.externalRef);
        payload.put("reservationId", reservationId);
        payload.put("status", statusNow(reservationId, eventType));
        payload.put("arrivalDate", row.arrivalDate == null ? null : row.arrivalDate.toString());
        payload.put("departureDate", row.departureDate == null ? null : row.departureDate.toString());
        enqueue(connectionId, "RESERVATION", reservationId, eventType, payload);
    }

    private String statusNow(long reservationId, String eventType) {
        return switch (eventType) {
            case "RESERVATION_CANCELLED" -> "CANCELLED";
            case "RESERVATION_CHECKED_IN" -> "CHECKED_IN";
            default -> db.queryForObject("select status from reservations where id=?", String.class, reservationId);
        };
    }

    /** Inventory blocks broadcast to every ACTIVE connection (no single origin). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInventoryBlock(long blockId, String eventType, Map<String, Object> payload) {
        try {
            channels.broadcastOutbox("INVENTORY_BLOCK", blockId, eventType, json.writeValueAsString(payload), null);
        } catch (Exception e) { throw new IllegalStateException("Outbox payload serialization failed", e); }
    }

    void enqueue(long connectionId, String aggregateType, long aggregateId, String eventType, Map<String, Object> payload) {
        var row = new ChannelMapper.OutboxRow();
        row.connectionId = connectionId; row.aggregateType = aggregateType; row.aggregateId = aggregateId;
        row.eventType = eventType;
        try { row.payload = json.writeValueAsString(payload); }
        catch (Exception e) { throw new IllegalStateException("Outbox payload serialization failed", e); }
        channels.insertOutbox(row);
    }
}
