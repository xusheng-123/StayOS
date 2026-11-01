package com.stayos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ChannelMapper {

    class ConnectionRow {
        public Long id; public String supplierCode, name, status, baseUrl, credentialRef, callbackSecretRef,
            protocolVersion, lastError, notes; public OffsetDateTime lastInboundAt, lastOutboundAt, lastSyncAt,
            createdAt, updatedAt; public long createdBy; public int version;
    }

    class MappingRow {
        public Long id; public long connectionId; public String kind, externalCode; public long localId;
        public boolean active; public OffsetDateTime createdAt; public String localCode, localName;
        public long createdBy;
    }

    class InboundRow {
        public Long id; public long connectionId; public String externalEventId, eventType, status, lastError;
        public long sequenceNo; public String payload; public int attempts;
        public OffsetDateTime nextAttemptAt, receivedAt, processedAt; public Long resultReservationId;
    }

    class OutboxRow {
        public Long id; public long connectionId; public String aggregateType, eventType, status, lastError;
        public long aggregateId; public String payload; public int attempts;
        public OffsetDateTime nextAttemptAt, createdAt, sentAt;
    }

    class AttemptRow {
        public Long id; public long connectionId; public String direction, eventKind, outcome, detail;
        public long eventId; public Integer durationMs; public OffsetDateTime attemptedAt;
    }

    class ReconcileRow {
        public Long id; public long connectionId; public String status, lastError; public int checkedCount,
            missingLocalCount, missingRemoteCount, mismatchCount; public String detail;
        public OffsetDateTime startedAt, finishedAt; public long startedBy;
    }

    String COLUMNS = "id, supplier_code, name, status, base_url, credential_ref, callback_secret_ref, " +
        "protocol_version, last_inbound_at, last_outbound_at, last_sync_at, last_error, notes, created_by, created_at, updated_at, version";

    @Select("select " + COLUMNS + " from channel_connections where id=#{id}")
    ConnectionRow connectionById(long id);

    @Select("select " + COLUMNS + " from channel_connections order by id")
    List<ConnectionRow> connections();

    @Select("select " + COLUMNS + " from channel_connections where supplier_code=#{supplierCode} and status='ACTIVE' order by id")
    List<ConnectionRow> activeConnections(String supplierCode);

    @Insert("""
        insert into channel_connections(supplier_code,name,status,base_url,credential_ref,callback_secret_ref,notes,created_by)
        values(#{row.supplierCode},#{row.name},#{row.status},#{row.baseUrl},#{row.credentialRef},#{row.callbackSecretRef},#{row.notes},#{row.createdBy})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertConnection(@Param("row") ConnectionRow row);

    @Update("""
        <script>update channel_connections set
        <if test="row.status != null">status=#{row.status},</if>
        <if test="row.baseUrl != null">base_url=#{row.baseUrl},</if>
        <if test="row.credentialRef != null">credential_ref=#{row.credentialRef},</if>
        <if test="row.callbackSecretRef != null">callback_secret_ref=#{row.callbackSecretRef},</if>
        <if test="row.notes != null">notes=#{row.notes},</if>
        last_error=#{row.lastError}, version=version+1, updated_at=now()
        where id=#{row.id} and version=#{expectedVersion}</script>
        """)
    int updateConnection(@Param("row") ConnectionRow row, @Param("expectedVersion") long expectedVersion);

    @Update("update channel_connections set last_inbound_at=now() where id=#{id}")
    void touchInbound(long id);

    @Update("update channel_connections set last_outbound_at=now(), last_error=#{lastError} where id=#{id}")
    void touchOutbound(@Param("id") long id, @Param("lastError") String lastError);

    @Update("update channel_connections set last_sync_at=now() where id=#{id}")
    void touchSync(long id);

    String MAPPING_SELECT = """
        select m.id, m.connection_id, m.kind, m.local_id, m.external_code, m.active, m.created_at,
          case m.kind when 'ROOM_TYPE' then rt.type_code else rp.plan_code end as local_code,
          case m.kind when 'ROOM_TYPE' then rt.type_name else rp.plan_name end as local_name
        from channel_mappings m
        left join room_types rt on m.kind='ROOM_TYPE' and rt.id=m.local_id
        left join rate_plans rp on m.kind='RATE_PLAN' and rp.id=m.local_id
        """;

    @Select(MAPPING_SELECT + " where m.id=#{id}")
    MappingRow mappingById(long id);

    @Select(MAPPING_SELECT + " where m.connection_id=#{connectionId} order by m.kind, m.external_code")
    List<MappingRow> mappings(long connectionId);

    @Select("select local_id from channel_mappings where connection_id=#{connectionId} and kind=#{kind} and active and external_code=#{externalCode}")
    Long mappedLocalId(@Param("connectionId") long connectionId, @Param("kind") String kind, @Param("externalCode") String externalCode);

    @Insert("insert into channel_mappings(connection_id,kind,local_id,external_code,active,created_by) values(#{row.connectionId},#{row.kind},#{row.localId},#{row.externalCode},true,#{row.createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertMapping(@Param("row") MappingRow row);

    @Delete("delete from channel_mappings where id=#{id}")
    int deleteMapping(long id);

    String INBOUND_SELECT = "id, connection_id, external_event_id, event_type, sequence_no, payload::text as payload, status, attempts, next_attempt_at, last_error, result_reservation_id, received_at, processed_at";

    @Select("select " + INBOUND_SELECT + " from inbound_events where id=#{id}")
    InboundRow inboundById(long id);

    @Insert("insert into inbound_events(connection_id,external_event_id,event_type,sequence_no,payload,status,next_attempt_at) values(#{row.connectionId},#{row.externalEventId},#{row.eventType},#{row.sequenceNo},#{row.payload}::jsonb,'PENDING',now())")
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertInbound(@Param("row") InboundRow row);

    @Select("<script>select " + INBOUND_SELECT + " from inbound_events"
        + " <where><if test='connectionId != null'>connection_id=#{connectionId} and </if>"
        + " <if test=\"status != null and status != 'ALL'\">status=#{status} and </if>id&gt;0</where>"
        + " order by id desc limit #{pageSize} offset #{offset}</script>")
    List<InboundRow> inboundPage(@Param("connectionId") Long connectionId, @Param("status") String status,
                                 @Param("pageSize") int pageSize, @Param("offset") int offset);

    @Select("""
        <script>select count(*) from inbound_events
        <where><if test="connectionId != null">connection_id=#{connectionId} and </if>
        <if test="status != null and status != 'ALL'">status=#{status} and </if>id&gt;0</where></script>
        """)
    long countInbound(@Param("connectionId") Long connectionId, @Param("status") String status);

    /** Ordered claim inside a short transaction: connection, then supplier sequence, then arrival id. */
    @Select("<script>select " + INBOUND_SELECT + " from inbound_events"
        + " where status='PENDING' and (next_attempt_at is null or next_attempt_at&lt;=now())"
        + " <if test='connectionId != null'>and connection_id=#{connectionId} </if>"
        + " order by connection_id, sequence_no, id limit #{limit} for update skip locked</script>")
    List<InboundRow> claimInbound(@Param("connectionId") Long connectionId, @Param("limit") int limit);

    @Update("update inbound_events set status=#{status},attempts=#{attempts},next_attempt_at=#{nextAttemptAt},last_error=#{lastError},processed_at=now(),result_reservation_id=#{resultReservationId} where id=#{id}")
    int finishInbound(@Param("id") long id, @Param("status") String status, @Param("attempts") int attempts,
                      @Param("nextAttemptAt") OffsetDateTime nextAttemptAt, @Param("lastError") String lastError,
                      @Param("resultReservationId") Long resultReservationId);

    @Update("update inbound_events set status='PENDING',attempts=0,next_attempt_at=null,last_error=null,processed_at=null where id=#{id} and status='MANUAL'")
    int retryInbound(long id);

    @Select("select max(sequence_no) from inbound_events where connection_id=#{connectionId} and status in ('PROCESSED','OBSOLETE')")
    Long highestSettledSequence(long connectionId);

    String OUTBOX_SELECT = "id, connection_id, aggregate_type, aggregate_id, event_type, payload::text as payload, status, attempts, next_attempt_at, last_error, created_at, sent_at";

    @Select("select " + OUTBOX_SELECT + " from outbox_events where id=#{id}")
    OutboxRow outboxById(long id);

    @Insert("insert into outbox_events(connection_id,aggregate_type,aggregate_id,event_type,payload) values(#{row.connectionId},#{row.aggregateType},#{row.aggregateId},#{row.eventType},#{row.payload}::jsonb)")
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertOutbox(@Param("row") OutboxRow row);

    @Select("<script>select " + OUTBOX_SELECT + " from outbox_events"
        + " <where><if test='connectionId != null'>connection_id=#{connectionId} and </if>"
        + " <if test=\"status != null and status != 'ALL'\">status=#{status} and </if>id&gt;0</where>"
        + " order by id desc limit #{pageSize} offset #{offset}</script>")
    List<OutboxRow> outboxPage(@Param("connectionId") Long connectionId, @Param("status") String status,
                               @Param("pageSize") int pageSize, @Param("offset") int offset);

    @Select("""
        <script>select count(*) from outbox_events
        <where><if test="connectionId != null">connection_id=#{connectionId} and </if>
        <if test="status != null and status != 'ALL'">status=#{status} and </if>id&gt;0</where></script>
        """)
    long countOutbox(@Param("connectionId") Long connectionId, @Param("status") String status);

    /** Id-ordered claim (guide: 数据库有序领取) — short transaction, SKIP LOCKED, no business locks held. */
    @Select("<script>select " + OUTBOX_SELECT + " from outbox_events"
        + " where status='PENDING' and (next_attempt_at is null or next_attempt_at&lt;=now())"
        + " <if test='connectionId != null'>and connection_id=#{connectionId} </if>"
        + " order by id limit #{limit} for update skip locked</script>")
    List<OutboxRow> claimOutbox(@Param("connectionId") Long connectionId, @Param("limit") int limit);

    @Update("update outbox_events set status=#{status},attempts=#{attempts},next_attempt_at=#{nextAttemptAt},last_error=#{lastError},sent_at=case when #{status}='SENT' then now() else sent_at end where id=#{id}")
    int finishOutbox(@Param("id") long id, @Param("status") String status, @Param("attempts") int attempts,
                     @Param("nextAttemptAt") OffsetDateTime nextAttemptAt, @Param("lastError") String lastError);

    @Update("update outbox_events set status='PENDING',attempts=0,next_attempt_at=null,last_error=null where id=#{id} and status='MANUAL'")
    int retryOutbox(long id);

    @Insert("""
        <script>insert into outbox_events(connection_id,aggregate_type,aggregate_id,event_type,payload)
        select c.id, #{aggregateType}, #{aggregateId}, #{eventType}, #{payload}::jsonb from channel_connections c
        where c.status='ACTIVE' <if test="supplierCode != null">and c.supplier_code=#{supplierCode}</if></script>
        """)
    int broadcastOutbox(@Param("aggregateType") String aggregateType, @Param("aggregateId") long aggregateId,
                        @Param("eventType") String eventType, @Param("payload") String payload,
                        @Param("supplierCode") String supplierCode);

    @Insert("insert into sync_attempts(connection_id,direction,event_kind,event_id,outcome,detail,duration_ms) values(#{connectionId},#{direction},#{eventKind},#{eventId},#{outcome},#{detail},#{durationMs})")
    int insertAttempt(@Param("connectionId") long connectionId, @Param("direction") String direction,
                      @Param("eventKind") String eventKind, @Param("eventId") long eventId,
                      @Param("outcome") String outcome, @Param("detail") String detail, @Param("durationMs") Integer durationMs);

    @Select("select id, connection_id, direction, event_kind, event_id, outcome, detail, duration_ms, attempted_at from sync_attempts where event_kind=#{eventKind} and event_id=#{eventId} order by id desc limit 5")
    List<AttemptRow> attemptsFor(@Param("eventKind") String eventKind, @Param("eventId") long eventId);

    @Select("""
        select id, connection_id, direction, event_kind, event_id, outcome, detail, duration_ms, attempted_at
        from sync_attempts where connection_id=#{connectionId} order by id desc limit #{limit}
        """)
    List<AttemptRow> recentAttempts(@Param("connectionId") long connectionId, @Param("limit") int limit);

    String RECONCILE_SELECT = "id, connection_id, status, checked_count, missing_local_count, missing_remote_count, mismatch_count, detail::text as detail, last_error, started_at, finished_at";

    @Insert("insert into reconciliation_runs(connection_id,started_by) values(#{row.connectionId},#{row.startedBy})")
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertReconcile(@Param("row") ReconcileRow row);

    @Update("""
        update reconciliation_runs set status=#{status},checked_count=#{checkedCount},missing_local_count=#{missingLocalCount},
        missing_remote_count=#{missingRemoteCount},mismatch_count=#{mismatchCount},detail=#{detail}::jsonb,last_error=#{lastError},
        finished_at=now() where id=#{id} and status='RUNNING'
        """)
    int finishReconcile(@Param("id") long id, @Param("status") String status, @Param("checkedCount") int checkedCount,
                        @Param("missingLocalCount") int missingLocalCount, @Param("missingRemoteCount") int missingRemoteCount,
                        @Param("mismatchCount") int mismatchCount, @Param("detail") String detail, @Param("lastError") String lastError);

    @Select("select " + RECONCILE_SELECT + " from reconciliation_runs where connection_id=#{connectionId} order by id desc limit 10")
    List<ReconcileRow> reconciliations(long connectionId);

    /** Local OTA-sourced bookings linked to this connection through processed inbound events. */
    @Select("""
        select r.external_ref, r.status, r.arrival_date::text, r.departure_date::text
        from inbound_events ie join reservations r on r.id=ie.result_reservation_id
        where ie.connection_id=#{connectionId} and ie.status='PROCESSED' and r.external_ref is not null
        order by r.id
        """)
    List<LocalOrderRow> localChannelOrders(long connectionId);

    class LocalOrderRow { public String externalRef, status, arrivalDate, departureDate; }

    @Select("""
        select count(*) filter (where status='PENDING') as pending_inbound,
               count(*) filter (where status='MANUAL') as manual_inbound
        from inbound_events where connection_id=#{connectionId}
        """)
    Map<String, Object> inboundStats(long connectionId);

    @Select("""
        select count(*) filter (where status='PENDING') as pending_outbox,
               count(*) filter (where status='MANUAL') as manual_outbox
        from outbox_events where connection_id=#{connectionId}
        """)
    Map<String, Object> outboxStats(long connectionId);
}
