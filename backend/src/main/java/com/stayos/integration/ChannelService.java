package com.stayos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayos.audit.AuditService;
import com.stayos.common.ApiException;
import com.stayos.reservation.ReservationDtos;
import com.stayos.reservation.ReservationMapper;
import com.stayos.reservation.ReservationService;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Channel integration core (guide section 14). Inbound callbacks persist FIRST and then flow through
 * the SAME ReservationService the desk uses; outbound business changes land in outbox_events inside the
 * caller's local transaction; the worker claims rows in database order with bounded retries. Vendor
 * secrets are resolved from environment references only and never appear in errors, logs or audit text.
 */
@Service
public class ChannelService {

    static final int MAX_ATTEMPTS = 8;

    final ChannelMapper channels;
    final ReservationService reservations;
    final ReservationMapper reservationRows;
    final IdempotencyService idempotency;
    final CredentialResolver credentials;
    final Map<String, SupplierAdapter> adapters;
    final ObjectMapper json;
    final TransactionTemplate tx;
    final org.springframework.jdbc.core.JdbcTemplate db;
    final AuditService audit;
    final ChannelOutboxRecorder recorder;

    public ChannelService(ChannelMapper channels, ReservationService reservations, ReservationMapper reservationRows,
                          IdempotencyService idempotency, CredentialResolver credentials, List<SupplierAdapter> adapters,
                          ObjectMapper json, TransactionTemplate tx, org.springframework.jdbc.core.JdbcTemplate db,
                          AuditService audit, ChannelOutboxRecorder recorder) {
        this.channels = channels;
        this.reservations = reservations;
        this.reservationRows = reservationRows;
        this.idempotency = idempotency;
        this.credentials = credentials;
        this.json = json;
        this.tx = tx;
        this.db = db;
        this.audit = audit;
        this.recorder = recorder;
        this.adapters = new HashMap<>();
        for (SupplierAdapter adapter : adapters) this.adapters.put(adapter.supplierCode(), adapter);
    }

    SupplierAdapter adapter(String supplierCode) {
        SupplierAdapter adapter = adapters.get(supplierCode);
        if (adapter == null) throw invalid("未注册的渠道供应商: " + supplierCode);
        return adapter;
    }

    // ---------- connections ----------

    public List<Map<String, Object>> list(long actor) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChannelMapper.ConnectionRow row : channels.connections()) result.add(connectionView(row));
        return result;
    }

    Map<String, Object> connectionView(ChannelMapper.ConnectionRow row) {
        Map<String, Object> inbound = channels.inboundStats(row.id);
        Map<String, Object> outbox = channels.outboxStats(row.id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id); view.put("supplierCode", row.supplierCode); view.put("name", row.name);
        view.put("status", row.status); view.put("baseUrl", row.baseUrl);
        view.put("credentialRef", row.credentialRef); view.put("callbackSecretRef", row.callbackSecretRef);
        view.put("protocolVersion", row.protocolVersion);
        view.put("lastInboundAt", row.lastInboundAt); view.put("lastOutboundAt", row.lastOutboundAt);
        view.put("lastSyncAt", row.lastSyncAt); view.put("lastError", row.lastError);
        view.put("notes", row.notes); view.put("version", row.version); view.put("createdAt", row.createdAt);
        view.put("pendingInbound", inbound.get("pending_inbound")); view.put("manualInbound", inbound.get("manual_inbound"));
        view.put("pendingOutbox", outbox.get("pending_outbox")); view.put("manualOutbox", outbox.get("manual_outbox"));
        return view;
    }

    @Transactional
    public Map<String, Object> create(long actor, ChannelDtos.ConnectionInput input) {
        if (input == null || input.supplierCode() == null) throw invalid("请提供供应商代码");
        SupplierAdapter adapter = adapter(input.supplierCode());   // unregistered suppliers are refused outright
        String name = trim(input.name(), 1, 100, "连接名称");
        var row = new ChannelMapper.ConnectionRow();
        row.supplierCode = input.supplierCode(); row.name = name;
        row.status = "ACTIVE"; row.baseUrl = trimOrNull(input.baseUrl(), 300, "接口地址");
        row.credentialRef = trim(input.credentialRef(), 1, 200, "凭据引用");
        row.callbackSecretRef = trim(input.callbackSecretRef(), 1, 200, "回调密钥引用");
        row.notes = trimOrNull(input.notes(), 500, "备注") == null ? "" : trimOrNull(input.notes(), 500, "备注");
        row.createdBy = actor;
        if (channels.insertConnection(row) != 1) throw new IllegalStateException("Connection was not saved");
        // Resolve once at creation so a typo in the reference surfaces immediately (value stays out of the reply).
        credentials.credentials(row.credentialRef, row.callbackSecretRef);
        audit.success(actor, "CHANNEL_CONNECTION_CREATE", "CHANNEL", row.id);
        return connectionView(channels.connectionById(row.id));
    }

    @Transactional
    public Map<String, Object> update(long actor, long id, ChannelDtos.ConnectionUpdate input) {
        var row = channels.connectionById(id);
        if (row == null) throw new ApiException(404, "NOT_FOUND", "渠道连接不存在");
        if (input == null) throw invalid("请提供修改内容");
        if (input.version() == null) throw invalid("请提供当前版本号");
        if (input.status() != null && !List.of("ACTIVE", "PAUSED", "BLOCKED_EXTERNAL").contains(input.status()))
            throw invalid("连接状态只能是 ACTIVE、PAUSED 或 BLOCKED_EXTERNAL");
        var patch = new ChannelMapper.ConnectionRow();
        patch.id = id; patch.status = input.status(); patch.baseUrl = trimOrNull(input.baseUrl(), 300, "接口地址");
        patch.credentialRef = trimOrNull(input.credentialRef(), 200, "凭据引用");
        patch.callbackSecretRef = trimOrNull(input.callbackSecretRef(), 200, "回调密钥引用");
        patch.notes = trimOrNull(input.notes(), 500, "备注"); patch.lastError = row.lastError;
        if (channels.updateConnection(patch, input.version()) != 1)
            throw conflict("STALE_VERSION", "连接已被他人修改，请刷新后重试");
        audit.success(actor, "CHANNEL_CONNECTION_UPDATE", "CHANNEL", id);
        return connectionView(channels.connectionById(id));
    }

    Map<String, Object> detailView(long id) {
        var row = channels.connectionById(id);
        if (row == null) throw new ApiException(404, "NOT_FOUND", "渠道连接不存在");
        Map<String, Object> view = connectionView(row);
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (ChannelMapper.AttemptRow attempt : channels.recentAttempts(id, 10)) attempts.add(attemptView(attempt));
        view.put("recentAttempts", attempts);
        return view;
    }

    Map<String, Object> attemptView(ChannelMapper.AttemptRow row) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", row.id); view.put("direction", row.direction); view.put("eventKind", row.eventKind);
        view.put("eventId", row.eventId); view.put("outcome", row.outcome); view.put("detail", row.detail);
        view.put("durationMs", row.durationMs); view.put("attemptedAt", row.attemptedAt);
        return view;
    }

    // ---------- mappings ----------

    public List<ChannelMapper.MappingRow> mappings(long connectionId) {
        requireConnection(connectionId);
        return channels.mappings(connectionId);
    }

    @Transactional
    public ChannelMapper.MappingRow addMapping(long actor, long connectionId, ChannelDtos.MappingInput input) {
        requireConnection(connectionId);
        if (input == null || input.kind() == null || !List.of("ROOM_TYPE", "RATE_PLAN").contains(input.kind()))
            throw invalid("映射类型只能是 ROOM_TYPE 或 RATE_PLAN");
        if (input.kind().equals("ROOM_TYPE")) {
            if (!roomTypeExists(input.localId())) throw invalid("房型不存在或已停用");
        } else if (!ratePlanExists(input.localId())) throw invalid("价格计划不存在或已停用");
        var row = new ChannelMapper.MappingRow();
        row.connectionId = connectionId; row.kind = input.kind(); row.localId = input.localId();
        row.externalCode = trim(input.externalCode(), 1, 120, "渠道编码"); row.active = true; row.createdBy = actor;
        try {
            channels.insertMapping(row);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw conflict("MAPPING_EXISTS", "该渠道编码或本地对象已建立映射");
        }
        audit.success(actor, "CHANNEL_MAPPING_CREATE", "CHANNEL", row.id);
        return channels.mappingById(row.id);
    }

    @Transactional
    public void deleteMapping(long actor, long id) {
        if (channels.deleteMapping(id) != 1) throw new ApiException(404, "NOT_FOUND", "映射不存在或已删除");
        audit.success(actor, "CHANNEL_MAPPING_DELETE", "CHANNEL", id);
    }

    boolean roomTypeExists(long id) {
        return id > 0 && db.queryForObject("select count(*) from room_types where id=? and enabled", Integer.class, id) == 1;
    }

    boolean ratePlanExists(long id) {
        return id > 0 && db.queryForObject("select count(*) from rate_plans where id=?", Integer.class, id) == 1;
    }

    ChannelMapper.ConnectionRow requireConnection(long id) {
        var row = channels.connectionById(id);
        if (row == null) throw new ApiException(404, "NOT_FOUND", "渠道连接不存在");
        return row;
    }

    String trim(String value, int min, int max, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() < min || trimmed.length() > max) throw invalid(label + "长度须在 " + min + "—" + max + " 字之间");
        return trimmed;
    }

    String trimOrNull(String value, int max, String label) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > max) throw invalid(label + "最长 " + max + " 字");
        return trimmed;
    }

    private static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_ERROR", message); }
    private static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }

    // ---------- inbound: receive first, process later ----------

    /**
     * Callback entry (public path, signature-guarded). Signature failures authenticate to nothing and
     * persist nothing. Verified events persist before any processing; duplicates are refused by the
     * (connection, external_event_id) unique fact. No sensitive value reaches the error surface.
     */
    public Map<String, Object> receiveCallback(String supplierCode, Map<String, String> headers, String rawBody) {
        SupplierAdapter adapter = adapter(supplierCode);
        ChannelMapper.ConnectionRow matched = null;
        SupplierAdapter.Credentials verified = null;
        SupplierAdapter.StandardInbound inbound = null;
        for (ChannelMapper.ConnectionRow connection : channels.activeConnections(supplierCode)) {
            var candidate = credentials.credentials(connection.credentialRef, connection.callbackSecretRef);
            if (adapter.verifyCallback(candidate, headers, rawBody)) { matched = connection; verified = candidate; break; }
        }
        if (matched == null) {
            audit.failure(null, "CHANNEL_CALLBACK_SIGNATURE", "CHANNEL", null);
            throw new ApiException(401, "CALLBACK_SIGNATURE_INVALID", "回调签名验证失败");
        }
        inbound = adapter.parseCallback(verified, headers, rawBody);
        if (!List.of("ORDER_CREATE", "ORDER_MODIFY", "ORDER_CANCEL", "PRICE_UPDATE").contains(inbound.eventType()))
            throw new ApiException(400, "CALLBACK_EVENT_TYPE", "回调事件类型不在协议范围内");
        var row = new ChannelMapper.InboundRow();
        row.connectionId = matched.id; row.externalEventId = inbound.externalEventId();
        row.eventType = inbound.eventType(); row.sequenceNo = inbound.sequenceNo();
        row.payload = String.valueOf(payloadNode(inbound));
        try {
            tx.executeWithoutResult(s -> channels.insertInbound(row));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw conflict("DUPLICATE_EVENT", "重复回调：该外部事件已接收（按供应商协议拒绝重复投递）");
        }
        channels.touchInbound(matched.id);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("eventId", row.externalEventId); reply.put("inboundId", row.id); reply.put("status", "RECEIVED");
        return reply;
    }

    private JsonNode payloadNode(SupplierAdapter.StandardInbound inbound) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("arrivalDate", inbound.arrivalDate()); payload.put("departureDate", inbound.departureDate());
        payload.put("roomTypeCode", inbound.roomTypeExternalCode()); payload.put("externalRef", inbound.externalRef());
        payload.put("contactName", inbound.contactName()); payload.put("contactPhone", inbound.contactPhone());
        payload.put("notes", inbound.notes());
        try { return json.readTree(json.writeValueAsString(payload)); }
        catch (Exception e) { throw new IllegalStateException("Neutral payload serialization failed", e); }
    }

    // ---------- inbound: ordered, bounded processing through the shared ReservationService ----------

    public Map<String, Object> processInbound(Long connectionId, int limit) {
        List<ChannelMapper.InboundRow> claimed = tx.execute(s -> channels.claimInbound(connectionId, Math.max(1, limit)));
        int processed = 0, obsolete = 0, failed = 0;
        for (ChannelMapper.InboundRow event : claimed == null ? List.<ChannelMapper.InboundRow>of() : claimed) {
            Long settled = channels.highestSettledSequence(event.connectionId);
            if (settled != null && event.sequenceNo < settled) {
                // Supplier sequence protocol: stale events are recorded as OBSOLETE facts, never applied by arrival time.
                tx.executeWithoutResult(s -> {
                    channels.finishInbound(event.id, "OBSOLETE", event.attempts, null, "乱序事件：序号低于已处理水位 " + settled, null);
                    channels.insertAttempt(event.connectionId, "INBOUND_PROCESS", "INBOUND", event.id, "SKIPPED",
                        "sequence " + event.sequenceNo + " below settled " + settled, null);
                });
                obsolete++;
                continue;
            }
            long startedAt = System.currentTimeMillis();
            try {
                Long reservationId = tx.execute(s -> applyInbound(event));
                long duration = System.currentTimeMillis() - startedAt;
                tx.executeWithoutResult(s -> {
                    channels.finishInbound(event.id, "PROCESSED", event.attempts, null, null, reservationId);
                    channels.insertAttempt(event.connectionId, "INBOUND_PROCESS", "INBOUND", event.id, "SUCCESS", null, (int) duration);
                });
                processed++;
            } catch (Exception cause) {
                long duration = System.currentTimeMillis() - startedAt;
                int attempts = event.attempts + 1;
                boolean manual = attempts >= MAX_ATTEMPTS;
                String error = briefCause(cause);
                OffsetDateTime next = manual ? null : OffsetDateTime.now().plusSeconds(backoffSeconds(attempts));
                tx.executeWithoutResult(s -> {
                    channels.finishInbound(event.id, manual ? "MANUAL" : "PENDING", attempts, next, error, null);
                    channels.insertAttempt(event.connectionId, "INBOUND_PROCESS", "INBOUND", event.id, "FAILURE", error, (int) duration);
                });
                failed++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processed", processed); result.put("obsolete", obsolete); result.put("failed", failed);
        return result;
    }

    /** Runs inside one transaction per event; all booking changes go through the desk's own service. */
    private Long applyInbound(ChannelMapper.InboundRow event) {
        ChannelMapper.ConnectionRow connection = channels.connectionById(event.connectionId);
        if (connection == null || !"ACTIVE".equals(connection.status))
            throw invalid("渠道连接不可用（已暂停或被封控），事件保留待人工处理");
        JsonNode payload;
        try { payload = json.readTree(event.payload); }
        catch (Exception e) { throw new IllegalStateException("Inbound payload is not valid JSON", e); }
        String externalRef = text(payload, "externalRef");
        switch (event.eventType) {
            case "ORDER_CREATE" -> {
                String roomTypeCode = text(payload, "roomTypeCode");
                if (roomTypeCode == null || roomTypeCode.isBlank()) throw invalid("回调缺少渠道房型编码");
                Long roomTypeId = channels.mappedLocalId(event.connectionId, "ROOM_TYPE", roomTypeCode);
                if (roomTypeId == null) throw invalid("渠道房型 " + roomTypeCode + " 未建立映射，请先维护房型映射");
                String contactName = text(payload, "contactName");
                if (contactName == null || contactName.isBlank()) throw invalid("渠道订单缺少联系人姓名");
                var input = new ReservationDtos.CreateInput(text(payload, "arrivalDate"), text(payload, "departureDate"),
                    roomTypeId, "OTA", externalRef == null ? event.externalEventId : externalRef, contactName,
                    text(payload, "contactPhone"), null, text(payload, "notes"));
                var detail = reservations.create(connection.createdBy, channelKey("create", event.connectionId, event.externalEventId), input);
                long reservationId = detail.reservation().id();
                recorder.recordReservationEvent(reservationId, "RESERVATION_CREATED", event.connectionId);
                reservations.confirm(connection.createdBy, channelKey("confirm", event.connectionId, event.externalEventId), reservationId);
                // confirm() 内部的钩子此刻还查不到入站关联（result_reservation_id 在事件事务后才落库），
                // 由这里显式补一条已确认出站事件；MODIFY/CANCEL 则完全交给服务钩子，避免重复入队。
                recorder.recordReservationEvent(reservationId, "RESERVATION_CONFIRMED", event.connectionId);
                return reservationId;
            }
            case "ORDER_MODIFY" -> {
                Long reservationId = requireChannelReservation(event.connectionId, externalRef);
                var current = reservationRows.byId(reservationId);
                if (current == null) throw new ApiException(404, "RESERVATION_NOT_FOUND", "预订单不存在");
                String arrival = text(payload, "arrivalDate"), departure = text(payload, "departureDate");
                String roomTypeCode = text(payload, "roomTypeCode");
                Long roomTypeId = current.roomTypeId;
                if (roomTypeCode != null && !roomTypeCode.isBlank()) {
                    roomTypeId = channels.mappedLocalId(event.connectionId, "ROOM_TYPE", roomTypeCode);
                    if (roomTypeId == null) throw invalid("渠道房型 " + roomTypeCode + " 未建立映射，请先维护房型映射");
                }
                var input = new ReservationDtos.ModifyInput(current.version, arrival, departure, roomTypeId,
                    text(payload, "contactName"), text(payload, "contactPhone"), text(payload, "notes"));
                reservations.modify(connection.createdBy, channelKey("modify", event.connectionId, event.externalEventId), reservationId, input);
                return reservationId; // RESERVATION_MODIFIED 由 modify() 内部的出站钩子按入站关联入队
            }
            case "ORDER_CANCEL" -> {
                Long reservationId = requireChannelReservation(event.connectionId, externalRef);
                reservations.cancel(connection.createdBy, channelKey("cancel", event.connectionId, event.externalEventId),
                    reservationId, new ReservationDtos.CancelInput("渠道取消（外部单号 " + externalRef + "）"));
                return reservationId; // RESERVATION_CANCELLED 由 cancel() 内部的出站钩子按入站关联入队
            }
            default -> { // PRICE_UPDATE: recorded as a fact; the first version never auto-applies price changes.
                return null;
            }
        }
    }

    /**
     * Deterministic idempotency key for a channel event: the idempotency store requires UUID form, and
     * replays of the same event (worker retry, manual requeue) must hash to the same key as the first attempt.
     */
    static String channelKey(String scope, long connectionId, String externalEventId) {
        return UUID.nameUUIDFromBytes(("stayos-channel:" + scope + ":" + connectionId + ":" + externalEventId)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    Long requireChannelReservation(long connectionId, String externalRef) {
        if (externalRef == null || externalRef.isBlank()) throw invalid("回调缺少外部单号");
        Long reservationId = reservationRows.idByExternalRef(externalRef);
        if (reservationId == null) throw new ApiException(404, "RESERVATION_NOT_FOUND", "外部单号对应的预订单不存在");
        Integer linked = db.queryForObject("""
            select count(*) from inbound_events where connection_id=? and result_reservation_id=? and status='PROCESSED'
            """, Integer.class, connectionId, reservationId);
        if (linked == null || linked == 0) throw invalid("外部单号不属于该渠道连接，拒绝跨渠道操作");
        return reservationId;
    }

    static String text(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Backoff: attempts² × 2 seconds, hard-capped; MANUAL stops automatic retries for human handling. */
    static long backoffSeconds(int attempts) { return Math.min((long) attempts * attempts * 2, 600); }

    /** Error text for storage: exception class + first message line; payload/phone values never included. */
    static String briefCause(Throwable cause) {
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        int cut = message.indexOf('\n');
        if (cut > 0) message = message.substring(0, cut);
        String brief = (cause.getClass().getSimpleName() + ": " + message);
        return brief.length() > 500 ? brief.substring(0, 500) : brief;
    }

    // ---------- outbox: enqueue with the business change, deliver outside any business transaction ----------

    public Map<String, Object> processOutbox(Long connectionId, int limit) {
        List<ChannelMapper.OutboxRow> claimed = tx.execute(s -> channels.claimOutbox(connectionId, Math.max(1, limit)));
        int sent = 0, failed = 0, skipped = 0;
        for (ChannelMapper.OutboxRow event : claimed == null ? List.<ChannelMapper.OutboxRow>of() : claimed) {
            ChannelMapper.ConnectionRow connection = channels.connectionById(event.connectionId);
            if (connection == null || !"ACTIVE".equals(connection.status)) { skipped++; continue; }
            long startedAt = System.currentTimeMillis();
            try {
                var cred = credentials.credentials(connection.credentialRef, connection.callbackSecretRef);
                String payloadJson = event.payload;
                var pushPayload = json.readTree(event.payload);
                var outcome = adapter(connection.supplierCode).push(cred, new SupplierAdapter.OutboundEvent(
                    event.connectionId, event.eventType, event.aggregateType, event.aggregateId,
                    pushPayload.path("externalRef").asText(null), payloadJson));
                if (!outcome.success()) throw new IllegalStateException(outcome.detail());
                long duration = System.currentTimeMillis() - startedAt;
                tx.executeWithoutResult(s -> {
                    channels.finishOutbox(event.id, "SENT", event.attempts, null, null);
                    channels.insertAttempt(event.connectionId, "OUTBOUND", "OUTBOX", event.id, "SUCCESS", null, (int) duration);
                    channels.touchOutbound(event.connectionId, null);
                });
                sent++;
            } catch (Exception cause) {
                long duration = System.currentTimeMillis() - startedAt;
                int attempts = event.attempts + 1;
                boolean manual = attempts >= MAX_ATTEMPTS;
                String error = briefCause(cause);
                OffsetDateTime next = manual ? null : OffsetDateTime.now().plusSeconds(backoffSeconds(attempts));
                tx.executeWithoutResult(s -> {
                    channels.finishOutbox(event.id, manual ? "MANUAL" : "PENDING", attempts, next, error);
                    channels.insertAttempt(event.connectionId, "OUTBOUND", "OUTBOX", event.id, "FAILURE", error, (int) duration);
                    channels.touchOutbound(event.connectionId, error);
                });
                failed++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", sent); result.put("failed", failed); result.put("skipped", skipped);
        return result;
    }

    // ---------- manual handling ----------

    @Transactional
    public void retryInbound(long actor, long id) {
        if (channels.retryInbound(id) != 1) throw conflict("RESERVATION_STATE", "仅人工处理状态的入站事件可重试");
        audit.success(actor, "CHANNEL_INBOUND_RETRY", "CHANNEL", id);
    }

    @Transactional
    public void retryOutbox(long actor, long id) {
        if (channels.retryOutbox(id) != 1) throw conflict("RESERVATION_STATE", "仅人工处理状态的出站事件可重试");
        audit.success(actor, "CHANNEL_OUTBOX_RETRY", "CHANNEL", id);
    }

    public Map<String, Object> processNow(long actor, long connectionId, int inboundLimit, int outboxLimit) {
        requireConnection(connectionId);
        var inbound = processInbound(connectionId, inboundLimit);
        var outbound = processOutbox(connectionId, outboxLimit);
        audit.success(actor, "CHANNEL_PROCESS_NOW", "CHANNEL", connectionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inbound", inbound); result.put("outbound", outbound);
        return result;
    }

    // ---------- reconciliation ----------

    public Map<String, Object> reconcile(long actor, long connectionId) {
        var connection = requireConnection(connectionId);
        var run = new ChannelMapper.ReconcileRow();
        run.connectionId = connectionId; run.startedBy = actor;
        channels.insertReconcile(run);
        List<SupplierAdapter.RemoteOrder> remote;
        try {
            var cred = credentials.credentials(connection.credentialRef, connection.callbackSecretRef);
            remote = adapter(connection.supplierCode).snapshot(cred, connectionId);
        } catch (Exception cause) {
            channels.finishReconcile(run.id, "FAILED", 0, 0, 0, 0, "{}", briefCause(cause));
            audit.success(actor, "CHANNEL_RECONCILE", "CHANNEL", run.id);
            return reconcileView(channels.connectionById(connectionId), run.id);
        }
        Map<String, ChannelMapper.LocalOrderRow> local = new LinkedHashMap<>();
        for (ChannelMapper.LocalOrderRow order : channels.localChannelOrders(connectionId)) local.put(order.externalRef, order);
        Map<String, SupplierAdapter.RemoteOrder> remoteMap = new LinkedHashMap<>();
        for (SupplierAdapter.RemoteOrder order : remote) remoteMap.put(order.externalRef(), order);
        List<String> missingRemote = new ArrayList<>(), missingLocal = new ArrayList<>(), mismatch = new ArrayList<>();
        int checked = 0;
        for (var entry : local.entrySet()) {
            checked++;
            SupplierAdapter.RemoteOrder counterpart = remoteMap.get(entry.getKey());
            if (counterpart == null) { missingRemote.add(entry.getKey()); continue; }
            String localStatus = "CANCELLED".equals(entry.getValue().status) ? "CANCELLED" : "CONFIRMED";
            boolean datesDiffer = "CANCELLED".equals(counterpart.status()) ? false
                : !Objects.equals(counterpart.arrivalDate(), entry.getValue().arrivalDate)
                    || !Objects.equals(counterpart.departureDate(), entry.getValue().departureDate);
            if (!localStatus.equals(counterpart.status()) || datesDiffer) mismatch.add(entry.getKey());
        }
        for (String ref : remoteMap.keySet()) if (!local.containsKey(ref)) missingLocal.add(ref);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("missingRemote", missingRemote); detail.put("missingLocal", missingLocal); detail.put("mismatch", mismatch);
        String detailJson;
        try { detailJson = json.writeValueAsString(detail); } catch (Exception e) { detailJson = "{}"; }
        channels.finishReconcile(run.id, "COMPLETED", checked, missingLocal.size(), missingRemote.size(), mismatch.size(), detailJson, null);
        channels.touchSync(connectionId);
        audit.success(actor, "CHANNEL_RECONCILE", "CHANNEL", run.id);
        return reconcileView(channels.connectionById(connectionId), run.id);
    }

    Map<String, Object> reconcileView(ChannelMapper.ConnectionRow connection, long runId) {
        var runs = channels.reconciliations(connection.id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("connectionId", connection.id);
        view.put("runs", runs.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", r.id); item.put("status", r.status); item.put("checkedCount", r.checkedCount);
            item.put("missingLocalCount", r.missingLocalCount); item.put("missingRemoteCount", r.missingRemoteCount);
            item.put("mismatchCount", r.mismatchCount); item.put("detail", r.detail);
            item.put("lastError", r.lastError); item.put("startedAt", r.startedAt); item.put("finishedAt", r.finishedAt);
            return item;
        }).toList());
        return view;
    }

    // ---------- views ----------

    public Map<String, Object> detail(long id) { return detailView(id); }

    public List<Map<String, Object>> reconciliationRuns(long connectionId) {
        requireConnection(connectionId);
        return channels.reconciliations(connectionId).stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", r.id); item.put("status", r.status); item.put("checkedCount", r.checkedCount);
            item.put("missingLocalCount", r.missingLocalCount); item.put("missingRemoteCount", r.missingRemoteCount);
            item.put("mismatchCount", r.mismatchCount); item.put("detail", r.detail);
            item.put("lastError", r.lastError); item.put("startedAt", r.startedAt); item.put("finishedAt", r.finishedAt);
            return item;
        }).toList();
    }

    public Map<String, Object> inboundPage(Long connectionId, String status, int page, int pageSize) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total", channels.countInbound(connectionId, status));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChannelMapper.InboundRow row : channels.inboundPage(connectionId, status, pageSize, (page - 1) * pageSize)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", row.id); item.put("connectionId", row.connectionId);
            item.put("externalEventId", row.externalEventId); item.put("eventType", row.eventType);
            item.put("sequenceNo", row.sequenceNo); item.put("status", row.status); item.put("attempts", row.attempts);
            item.put("nextAttemptAt", row.nextAttemptAt); item.put("lastError", row.lastError);
            item.put("resultReservationId", row.resultReservationId);
            item.put("receivedAt", row.receivedAt); item.put("processedAt", row.processedAt);
            items.add(item);
        }
        view.put("items", items);
        return view;
    }

    public Map<String, Object> outboxPage(Long connectionId, String status, int page, int pageSize) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total", channels.countOutbox(connectionId, status));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChannelMapper.OutboxRow row : channels.outboxPage(connectionId, status, pageSize, (page - 1) * pageSize)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", row.id); item.put("connectionId", row.connectionId);
            item.put("aggregateType", row.aggregateType); item.put("aggregateId", row.aggregateId);
            item.put("eventType", row.eventType); item.put("status", row.status); item.put("attempts", row.attempts);
            item.put("nextAttemptAt", row.nextAttemptAt); item.put("lastError", row.lastError);
            item.put("createdAt", row.createdAt); item.put("sentAt", row.sentAt);
            items.add(item);
        }
        view.put("items", items);
        return view;
    }
}
