package com.stayos.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayos.staff.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * 渠道连接可控模拟集成（手册§14：无真实供应商时验证协议与持久化闭环，不宣称端到端集成）。
 * 覆盖：签名回调→入站持久化→共享预订服务处理→发件箱与模拟供应商回显、重复/乱序/签名失败、
 * 有界重试到人工、人工重试、暂停跳过投递、对账差异、前台操作不产生渠道出站。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChannelIntegrationTest {

    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
    @LocalServerPort int port;

    static final String CRED_REF = "stayos.test.channel.credential";
    static final String SECRET_REF = "stayos.test.channel.secret";
    static final String SECRET_REF_2 = SECRET_REF + "-2";
    String password, credential, secret, secret2;
    long managerId, frontId, typeId, connectionId;
    Browser browser;

    final HttpClient supplier = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)).build();

    /** Logged-in manager session for channel/inventory/reservation APIs. */
    final class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> request(String method, String path, Object body, String idempotencyKey) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
            if (!method.equals("GET")) {
                var csrf = json.readTree(get("/api/auth/csrf").body()).path("data");
                builder.header(csrf.path("headerName").asText(), csrf.path("token").asText());
            }
            if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
            return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, null); }
        HttpResponse<String> post(String path, Object body) throws Exception { return request("POST", path, body, UUID.randomUUID().toString()); }
        HttpResponse<String> post(String path, Object body, String key) throws Exception { return request("POST", path, body, key); }
        HttpResponse<String> patch(String path, Object body) throws Exception { return request("PATCH", path, body, null); }
        HttpResponse<String> delete(String path) throws Exception { return request("DELETE", path, null, null); }
    }

    /** Raw supplier-side HTTP: unsigned, unauthenticated — exactly what a real vendor would send. */
    HttpResponse<String> callback(String rawBody, String signature) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/integration/callbacks/SIMULATOR"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(rawBody));
        if (signature != null) builder.header(SimulatorAdapter.SIGNATURE_HEADER, signature);
        return supplier.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    String orderBody(String eventId, String eventType, long sequenceNo, String externalRef, String roomTypeCode,
                     String contactName, String contactPhone) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("arrivalDate", LocalDate.now().plusDays(2).toString());
        payload.put("departureDate", LocalDate.now().plusDays(4).toString());
        payload.put("roomTypeCode", roomTypeCode);
        payload.put("externalRef", externalRef);
        payload.put("contactName", contactName);
        if (contactPhone != null) payload.put("contactPhone", contactPhone);
        payload.put("notes", "渠道模拟订单");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId); body.put("eventType", eventType); body.put("sequenceNo", sequenceNo);
        body.put("payload", payload);
        return json.writeValueAsString(body);
    }

    Map<String, Object> code(int status, HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var node = json.readTree(response.body());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", node.path("code").asText(null));
        map.put("data", node.path("data"));
        return map;
    }

    com.fasterxml.jackson.databind.JsonNode data(Map<String, Object> reply) {
        return (com.fasterxml.jackson.databind.JsonNode) reply.get("data");
    }

    /** Polls until the supplier returns non-null, for worker-vs-process-now races (bounded, 15s). */
    <T> T eventually(Supplier<T> probe, java.util.function.Predicate<T> done, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        T value = probe.get();
        while (!done.test(value)) {
            if (System.currentTimeMillis() > deadline) fail("等待超时：" + what);
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); fail(e); }
            value = probe.get();
        }
        return value;
    }

    Map<String, Object> inboundRow(String externalEventId) {
        return db.queryForMap("select * from inbound_events where external_event_id=? and connection_id=?", externalEventId, connectionId);
    }

    long countOutbox(String status) {
        Long count = db.queryForObject("select count(*) from outbox_events where connection_id=?"
            + (status == null ? "" : " and status='" + status + "'"), Long.class, connectionId);
        return count == null ? 0 : count;
    }

    void processNow() throws Exception {
        code(200, browser.post("/api/channels/" + connectionId + "/process-now", Map.of("inboundLimit", 50, "outboxLimit", 50)));
    }

    @BeforeAll void setup() throws Exception {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("stayos_test");
        password = UUID.randomUUID().toString();
        credential = "sim-cred-" + UUID.randomUUID();
        secret = "sim-secret-" + UUID.randomUUID();
        secret2 = "sim-secret-2-" + UUID.randomUUID();
        // The @SpringBootTest server runs in THIS jvm: system properties are how the resolver finds the values.
        System.setProperty(CRED_REF, credential);
        System.setProperty(SECRET_REF, secret);
        System.setProperty(SECRET_REF_2, secret2);
        cleanAll();
        managerId = db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",
            Long.class, "channel_test_manager", passwords.encode(password), "虚构渠道经理");
        users.addRole(managerId, "HOTEL_MANAGER");
        frontId = db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",
            Long.class, "channel_test_front", passwords.encode(password), "虚构渠道前台");
        users.addRole(frontId, "FRONT_DESK");
        typeId = db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('CH_T','虚构渠道房型',2) returning id", Long.class);
        db.update("insert into rooms(room_number,floor,room_type_id,cleaning_status) values('CH_T_A',1,?,'CLEAN'),('CH_T_B',1,?,'CLEAN')", typeId, typeId);
        browser = new Browser();
        code(200, browser.post("/api/auth/login", Map.of("username", "channel_test_manager", "password", password)));
        var created = code(200, browser.post("/api/channels", Map.of(
            "supplierCode", "SIMULATOR", "name", "测试渠道主连接",
            "credentialRef", CRED_REF, "callbackSecretRef", SECRET_REF, "notes", "可控模拟联调")));
        connectionId = data(created).path("id").asLong();
        SimulatorAdapter.clearLedger();
    }

    @AfterAll void teardown() throws Exception {
        SimulatorAdapter.clearLedger();
        cleanAll();
        db.update("delete from rooms where room_type_id=?", typeId);
        db.update("delete from room_types where id=?", typeId);
        System.clearProperty(CRED_REF);
        System.clearProperty(SECRET_REF);
        System.clearProperty(SECRET_REF_2);
    }

    /** Full business cleanup (test-created reservations first: triggers must be disabled for deletion). */
    void cleanAll() {
        String owner = "(select id from users where username like 'channel_test_%')";
        db.update("delete from idempotency_requests where operator_id in"+owner);
        // Channel side first: inbound_events.result_reservation_id references reservations.
        db.update("delete from outbox_events where connection_id in(select id from channel_connections where created_by in"+owner+")");
        db.update("delete from sync_attempts where connection_id in(select id from channel_connections where created_by in"+owner+")");
        db.update("delete from reconciliation_runs where connection_id in(select id from channel_connections where created_by in"+owner+")");
        db.update("delete from inbound_events where connection_id in(select id from channel_connections where created_by in"+owner+")");
        db.update("delete from channel_mappings where connection_id in(select id from channel_connections where created_by in"+owner+")");
        db.update("delete from channel_connections where created_by in"+owner);
        db.execute("alter table reservation_events disable trigger reservation_events_append_only");
        db.execute("alter table reservations disable trigger reservation_update");
        db.update("delete from reservation_events where reservation_id in(select id from reservations where room_type_id in(select id from room_types where type_code='CH_T'))");
        db.update("delete from reservations where room_type_id in(select id from room_types where type_code='CH_T')");
        db.execute("alter table reservation_events enable trigger reservation_events_append_only");
        db.execute("alter table reservations enable trigger reservation_update");
        db.update("delete from inventory_days where room_type_id in(select id from room_types where type_code='CH_T')");
        db.update("delete from inventory_blocks where room_type_id in(select id from room_types where type_code='CH_T')");
        db.update("delete from audit_logs where operator_id in"+owner);
        db.update("delete from rooms where room_type_id in(select id from room_types where type_code='CH_T')");
        db.update("delete from room_types where type_code='CH_T'");
        db.update("delete from user_roles where user_id in"+owner);
        db.update("delete from users where username like 'channel_test_%'");
    }

    @Test @Order(1) void signedChannelOrderBecomesConfirmedReservationAndEcho() throws Exception {
        // ROOM_TYPE mapping is a precondition for order creation.
        var mapping = code(200, browser.post("/api/channels/" + connectionId + "/mappings",
            Map.of("kind", "ROOM_TYPE", "localId", typeId, "externalCode", "CH_EXT_KING")));
        assertThat(data(mapping).path("externalCode").asText()).isEqualTo("CH_EXT_KING");

        String body = orderBody("evt-ord-001", "ORDER_CREATE", 1, "CH-EXT-001", "CH_EXT_KING", "渠道测试客人", "13800001111");
        var received = code(200, callback(body, SimulatorAdapter.hmacHex(secret, body)));
        assertThat(data(received).path("status").asText()).isEqualTo("RECEIVED");

        // Duplicate delivery of the same external event id is refused, nothing new persisted.
        var duplicate = callback(body, SimulatorAdapter.hmacHex(secret, body));
        assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(409);
        assertThat(json.readTree(duplicate.body()).path("code").asText()).isEqualTo("DUPLICATE_EVENT");
        assertThat(db.queryForObject("select count(*) from inbound_events where connection_id=?", Integer.class, connectionId)).isEqualTo(1);

        // Wrong signature authenticates to nothing and persists nothing.
        String other = orderBody("evt-ord-002", "ORDER_CREATE", 2, "CH-EXT-002", "CH_EXT_KING", "渠道测试客人", "13800001111");
        var bad = callback(other, "deadbeef");
        assertThat(bad.statusCode()).as(bad.body()).isEqualTo(401);
        // Well-formed signature on a malformed body: verified, then refused as malformed — still nothing persisted.
        var malformed = callback("{not json", SimulatorAdapter.hmacHex(secret, "{not json"));
        assertThat(malformed.statusCode()).as(malformed.body()).isEqualTo(400);
        assertThat(db.queryForObject("select count(*) from inbound_events where connection_id=?", Integer.class, connectionId)).isEqualTo(1);

        // Unknown supplier code is refused before any connection is consulted.
        var unknown = supplier.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/integration/callbacks/NOSUCH"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(400);

        processNow();
        var row = inboundRow("evt-ord-001");
        assertThat(row.get("status")).isEqualTo("PROCESSED");
        assertThat(row.get("result_reservation_id")).isNotNull();

        var reservation = db.queryForMap("select status,source,external_ref from reservations where id=?", row.get("result_reservation_id"));
        assertThat(reservation.get("status")).isEqualTo("CONFIRMED");
        assertThat(reservation.get("source")).isEqualTo("OTA");
        assertThat(reservation.get("external_ref")).isEqualTo("CH-EXT-001");

        // Outbound: CREATED + CONFIRMED leave through the outbox and land in the simulated supplier ledger.
        Long reservationId = ((Number) row.get("result_reservation_id")).longValue();
        eventually(() -> countOutbox("SENT"), sent -> sent >= 2, "两条出站事件投递成功");
        assertThat(countOutbox(null)).isEqualTo(2);
        var reconcile = code(200, browser.post("/api/channels/" + connectionId + "/reconcile", Map.of()));
        var runs = data(reconcile).path("runs");
        assertThat(runs.get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(runs.get(0).path("checkedCount").asInt()).isEqualTo(1);
        assertThat(runs.get(0).path("missingLocalCount").asInt()).isZero();
        assertThat(runs.get(0).path("missingRemoteCount").asInt()).isZero();
        assertThat(runs.get(0).path("mismatchCount").asInt()).isZero();
    }

    @Test @Order(2) void staleSequenceIsRecordedObsoleteNeverApplied() throws Exception {
        // seq 4 settles first; then seq 3 arrives late — recorded as OBSOLETE fact, reservation untouched.
        String newer = orderBody("evt-ord-101", "ORDER_MODIFY", 4, "CH-EXT-001", "CH_EXT_KING", "渠道测试客人二", null);
        code(200, callback(newer, SimulatorAdapter.hmacHex(secret, newer)));
        processNow();
        String older = orderBody("evt-ord-102", "ORDER_MODIFY", 3, "CH-EXT-001", "CH_EXT_KING", "渠道测试客人三", null);
        code(200, callback(older, SimulatorAdapter.hmacHex(secret, older)));
        processNow();
        // 背景工作器也可能先处理：最终事实一致即可（乱序事件归档为 OBSOLETE，不应用）。
        var row = eventually(() -> inboundRow("evt-ord-102"), r -> "OBSOLETE".equals(r.get("status")), "乱序事件归档");
        assertThat(row.get("status")).isEqualTo("OBSOLETE");
        assertThat(String.valueOf(row.get("last_error"))).contains("序号低于已处理水位");
        var contact = db.queryForObject("select contact_name from reservations where external_ref='CH-EXT-001'", String.class);
        assertThat(contact).isEqualTo("渠道测试客人二");
    }

    @Test @Order(3) void channelCancelPropagatesBothWays() throws Exception {
        String body = orderBody("evt-ord-201", "ORDER_CANCEL", 5, "CH-EXT-001", null, null, null);
        code(200, callback(body, SimulatorAdapter.hmacHex(secret, body)));
        processNow();
        assertThat(db.queryForObject("select status from reservations where external_ref='CH-EXT-001'", String.class)).isEqualTo("CANCELLED");
        eventually(() -> db.queryForObject("select count(*) from outbox_events where connection_id=? and event_type='RESERVATION_CANCELLED' and status='SENT'", Integer.class, connectionId),
            sent -> sent != null && sent >= 1, "取消事件投递成功");
        var cancelRow = inboundRow("evt-ord-201");
        assertThat(cancelRow.get("status")).isEqualTo("PROCESSED");

        // Ledger echo now says CANCELLED: reconcile still agrees (status-only comparison for cancelled orders).
        var reconcile = code(200, browser.post("/api/channels/" + connectionId + "/reconcile", Map.of()));
        var run = data(reconcile).path("runs").get(0);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("mismatchCount").asInt()).isZero();
    }

    @Test @Order(4) void unmappedRoomTypeRetriesBoundedThenManualRetry() throws Exception {
        // Own connection: the sequence watermark is per connection, and the main one already settled seq 5.
        var created = data(code(200, browser.post("/api/channels", Map.of(
            "supplierCode", "SIMULATOR", "name", "测试渠道重试连接",
            "credentialRef", CRED_REF, "callbackSecretRef", SECRET_REF + "-2", "notes", "重试场景"))));
        long retryConnectionId = created.path("id").asLong();
        try {
            String body = orderBody("evt-ord-301", "ORDER_CREATE", 1, "CH-EXT-301", "NOT_MAPPED", "渠道测试客人", "13800001111");
            code(200, callback(body, SimulatorAdapter.hmacHex(secret2, body)));
            Long eventId = ((Number) db.queryForMap("select id from inbound_events where external_event_id=? and connection_id=?", "evt-ord-301", retryConnectionId).get("id")).longValue();
            // Bounded retry: attempts²×2s backoff; the test clears next_attempt_at to move the clock forward.
            for (int attempt = 1; attempt <= 10; attempt++) {
                String status = String.valueOf(db.queryForObject("select status from inbound_events where id=?", String.class, eventId));
                if ("MANUAL".equals(status)) break;
                db.update("update inbound_events set next_attempt_at=null where id=?", eventId);
                browser.post("/api/channels/" + retryConnectionId + "/process-now", Map.of("inboundLimit", 50, "outboxLimit", 50));
            }
            var row = db.queryForMap("select status,attempts,last_error from inbound_events where id=?", eventId);
            assertThat(row.get("status")).isEqualTo("MANUAL");
            assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(8); // MAX_ATTEMPTS: no infinite retry
            assertThat(String.valueOf(row.get("last_error"))).contains("未建立映射");
            assertThat(String.valueOf(row.get("last_error"))).doesNotContain("13800001111"); // PII never reaches the error surface

            code(200, browser.post("/api/channels/inbound/" + eventId + "/retry", Map.of()));
            var after = db.queryForMap("select status,attempts from inbound_events where id=?", eventId);
            assertThat(after.get("status")).isEqualTo("PENDING");
            assertThat(((Number) after.get("attempts")).intValue()).isZero();
            db.update("update inbound_events set next_attempt_at=null where id=?", eventId);
            browser.post("/api/channels/" + retryConnectionId + "/process-now", Map.of("inboundLimit", 50, "outboxLimit", 50));
            var failedAgain = db.queryForMap("select attempts from inbound_events where id=?", eventId);
            assertThat(((Number) failedAgain.get("attempts")).intValue()).isGreaterThanOrEqualTo(1); // really re-processed, not just marked
            // Failure attempts were recorded for diagnosis (append-only).
            Integer failures = db.queryForObject("select count(*) from sync_attempts where event_id=? and outcome='FAILURE'", Integer.class, eventId);
            assertThat(failures).isGreaterThanOrEqualTo(8);
        } finally {
            db.update("delete from outbox_events where connection_id=?", retryConnectionId);
            db.update("delete from sync_attempts where connection_id=?", retryConnectionId);
            db.update("delete from inbound_events where connection_id=?", retryConnectionId);
            db.update("delete from channel_connections where id=?", retryConnectionId);
        }
        // Manual retry of a non-MANUAL event is refused (main connection, PRICE_UPDATE settles as PROCESSED).
        String okBody = orderBody("evt-ord-302", "PRICE_UPDATE", 7, null, null, null, null);
        code(200, callback(okBody, SimulatorAdapter.hmacHex(secret, okBody)));
        processNow();
        Long okId = ((Number) inboundRow("evt-ord-302").get("id")).longValue();
        var refused = browser.post("/api/channels/inbound/" + okId + "/retry", Map.of());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
    }

    @Test @Order(5) void pausedConnectionSkipsOutboundDelivery() throws Exception {
        // Backlog exists (independent of the worker's 5s cadence): a pre-pause pending event.
        var block = data(code(200, browser.post("/api/inventory/blocks", Map.of(
            "roomTypeId", typeId, "startDate", LocalDate.now().plusDays(10).toString(),
            "endDate", LocalDate.now().plusDays(12).toString(), "reason", "渠道联调停售"))));
        long blockId = block.path("id").asLong();
        assertThat(countOutbox("PENDING") + countOutbox("SENT")).isGreaterThanOrEqualTo(1); // broadcast to the ACTIVE connection

        // Pause, then verify delivery is skipped (row kept) until the connection is resumed.
        var current = data(code(200, browser.get("/api/channels/" + connectionId)));
        code(200, browser.patch("/api/channels/" + connectionId, Map.of("version", current.path("version").asInt(), "status", "PAUSED")));
        long pendingBefore = countOutbox("PENDING") + countOutbox("SENT");
        db.update("insert into outbox_events(connection_id,aggregate_type,aggregate_id,event_type,payload) values(?, 'INVENTORY_BLOCK', ?, 'INVENTORY_BLOCKED', ?::jsonb)",
            connectionId, blockId, json.writeValueAsString(Map.of("blockId", blockId, "typeCode", "CH_T")));
        var result = data(code(200, browser.post("/api/channels/" + connectionId + "/process-now", Map.of("inboundLimit", 50, "outboxLimit", 50))));
        assertThat(result.path("outbound").path("skipped").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(countOutbox("PENDING") + countOutbox("SENT")).isEqualTo(pendingBefore + 1); // nothing was delivered

        code(200, browser.patch("/api/channels/" + connectionId, Map.of("version", current.path("version").asInt() + 1, "status", "ACTIVE")));
        Long backlogId = db.queryForObject("select id from outbox_events where connection_id=? and aggregate_id=? and event_type='INVENTORY_BLOCKED' order by id desc limit 1", Long.class, connectionId, blockId);
        eventually(() -> db.queryForObject("select status from outbox_events where id=?", String.class, backlogId),
            status -> "SENT".equals(status), "恢复后积压事件投递成功");
    }

    @Test @Order(6) void reconcileReportsBothDirectionsOfDifference() throws Exception {
        // A remote order StayOS never received -> missing_local; a settled local order the ledger lost -> missing_remote.
        SimulatorAdapter.clearLedger();
        SimulatorAdapter.LEDGER.computeIfAbsent(connectionId, k -> new HashMap<>())
            .put("CH-REMOTE-ONLY", new SupplierAdapter.RemoteOrder("CH-REMOTE-ONLY", "CONFIRMED",
                LocalDate.now().plusDays(2).toString(), LocalDate.now().plusDays(4).toString()));
        var reply = data(code(200, browser.post("/api/channels/" + connectionId + "/reconcile", Map.of())));
        var run = reply.path("runs").get(0);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("checkedCount").asInt()).isEqualTo(1); // CH-EXT-001 is the settled local channel order
        assertThat(run.path("missingLocalCount").asInt()).isEqualTo(1);
        assertThat(run.path("missingRemoteCount").asInt()).isEqualTo(1); // ledger was cleared: CH-EXT-001 is remote-lost
        assertThat(String.valueOf(run.path("detail"))).contains("CH-REMOTE-ONLY");

        SimulatorAdapter.clearLedger();
        var second = data(code(200, browser.post("/api/channels/" + connectionId + "/reconcile", Map.of())));
        var run2 = second.path("runs").get(0);
        assertThat(run2.path("missingLocalCount").asInt()).isZero();
        assertThat(run2.path("missingRemoteCount").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("select last_sync_at is not null from channel_connections where id=?", Boolean.class, connectionId)).isTrue();
    }

    @Test @Order(7) void deskOperationsEnqueueNoChannelOutbound() throws Exception {
        long before = countOutbox(null);
        var reservation = data(code(200, browser.post("/api/reservations", Map.of(
            "arrivalDate", LocalDate.now().plusDays(3).toString(), "departureDate", LocalDate.now().plusDays(5).toString(),
            "roomTypeId", typeId, "source", "PHONE", "contactName", "前台测试客人", "contactPhone", "13900002222"), UUID.randomUUID().toString())));
        long deskId = reservation.path("reservation").path("id").asLong();
        code(200, browser.post("/api/reservations/" + deskId + "/confirm", null));
        code(200, browser.post("/api/reservations/" + deskId + "/cancel", Map.of("reason", "前台测试取消")));
        assertThat(countOutbox(null)).isEqualTo(before); // desk lifecycle touches no channel outbox
        assertThat(db.queryForObject("select count(*) from outbox_events where aggregate_type='RESERVATION' and aggregate_id=?",
            Integer.class, deskId)).isZero();
    }

    /** Real browser acceptance over the channels UI; fixtures feed frontend/e2e/channels.spec.ts. */
    @Test @Order(8) void realBrowserAcceptance() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"), "Browser tests explicitly enabled");
        var fixtures = new LinkedHashMap<String, Object>();
        fixtures.put("connectionId", connectionId);
        fixtures.put("roomTypeId", typeId);
        fixtures.put("credentialRef", CRED_REF);
        fixtures.put("secretRef", SECRET_REF_2);
        var root = java.nio.file.Path.of(System.getProperty("user.dir")).getParent();
        var log = root.resolve(".local/channel-browser.log");
        var builder = new ProcessBuilder("cmd.exe", "/d", "/c", "npm", "run", "test:e2e", "--", "channels.spec.ts")
            .directory(root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL", "http://127.0.0.1:" + port);
        builder.environment().put("STAYOS_E2E_PASSWORD", password);
        builder.environment().put("STAYOS_CHANNEL_FIXTURES", json.writeValueAsString(fixtures));
        builder.environment().put("STAYOS_E2E_REPORT", "../.local/playwright-channel-results.json");
        var process = builder.start();
        if (!process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            fail("Channel browser timeout");
        }
        assertThat(process.exitValue()).as("See private channel-browser.log").isZero();
    }
}
