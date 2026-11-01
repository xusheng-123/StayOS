package com.stayos.reservation;

import com.stayos.billing.BillingMapper.PaymentRow;
import com.stayos.billing.BillingMapper.SumRow;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ReservationMapper {
    /** List filter, mirroring StayFilter: MyBatis reads the record components directly. */
    record ReservationQuery(String status,LocalDate arrivalFrom,LocalDate arrivalTo,String query,int page,int pageSize) {
        public long offset() { return (long)(page-1)*pageSize; }
    }
    class Row { public Long id,roomTypeId,checkedInStayId,assignedRoomId,confirmedBy,cancelledBy,noShowBy,assignedBy,createdBy;
        public long version; public LocalDate arrivalDate,departureDate;
        public String status,source,externalRef,contactName,contactPhoneCiphertext,contactPhoneMask,contactEncryptionKeyId,
            cancelReason,notes,roomTypeCode,roomTypeName,roomNumber,createdByName,confirmedByName,cancelledByName,noShowByName,assignedByName;
        public OffsetDateTime heldUntil,confirmedAt,cancelledAt,noShowAt,assignedAt,createdAt; }
    class EventRow { public Long id; public String action,actorName,detail; public OffsetDateTime createdAt; }
    class NightRow { public LocalDate stayDate; public long enabledRooms,occupied,sold,blocked,available; }
    class DayRow { public Long roomTypeId; public LocalDate stayDate; public int sold; }
    class TypeRow { public Long id; public String typeCode,typeName; }
    class BlockRow { public Long id,roomTypeId,createdBy; public LocalDate startDate,endDate; public String reason,roomTypeCode,roomTypeName,
        createdByName,releasedByName; public OffsetDateTime createdAt,releasedAt; }

    String SELECT="""
        select r.*,t.type_code room_type_code,t.type_name room_type_name,rm.room_number,
          u.display_name created_by_name,cu.display_name confirmed_by_name,lu.display_name cancelled_by_name,
          nu.display_name no_show_by_name,au.display_name assigned_by_name
        from reservations r
        join room_types t on t.id=r.room_type_id
        join users u on u.id=r.created_by
        left join rooms rm on rm.id=r.assigned_room_id
        left join users cu on cu.id=r.confirmed_by
        left join users lu on lu.id=r.cancelled_by
        left join users nu on nu.id=r.no_show_by
        left join users au on au.id=r.assigned_by
        """;
    @Select(SELECT+" where r.id=#{id}")
    Row byId(long id);
    @Select("select r.id from reservations r where r.id=#{id} for update")
    Long lock(long id);

    String FILTER="""
        <where>
          <if test="q.status != 'ALL'">r.status=#{q.status}</if>
          <if test="q.arrivalFrom != null">and r.arrival_date &gt;= #{q.arrivalFrom}</if>
          <if test="q.arrivalTo != null">and r.arrival_date &lt;= #{q.arrivalTo}</if>
          <if test="q.query != ''">and (strpos(r.external_ref,#{q.query}) &gt; 0 or strpos(r.contact_name,#{q.query}) &gt; 0)</if>
        </where>
        """;
    @Select("<script>"+SELECT+FILTER+" order by r.arrival_date desc,r.id desc limit #{q.pageSize} offset #{offset}</script>")
    List<Row> list(@Param("q") ReservationQuery query,@Param("offset") long offset);
    @Select("<script>select count(*) from reservations r "+FILTER+"</script>")
    long count(@Param("q") ReservationQuery query);

    @Insert("""
        insert into reservations(arrival_date,departure_date,room_type_id,status,source,external_ref,contact_name,
          contact_phone_ciphertext,contact_phone_mask,contact_encryption_key_id,held_until,notes,created_by)
        values(#{arrivalDate},#{departureDate},#{roomTypeId},'HELD',#{source},#{externalRef},#{contactName},
          #{contactPhoneCiphertext},#{contactPhoneMask},#{contactEncryptionKeyId},#{heldUntil},#{notes},#{createdBy})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(Row row);

    @Update("update reservations set status='CONFIRMED',confirmed_at=#{now},confirmed_by=#{actor},version=version+1 where id=#{id} and status='HELD'")
    int confirm(@Param("id") long id,@Param("actor") long actor,@Param("now") OffsetDateTime now);
    @Update("update reservations set status='CANCELLED',cancelled_at=#{now},cancelled_by=#{actor},cancel_reason=#{reason},version=version+1 where id=#{id} and status in ('HELD','CONFIRMED')")
    int cancel(@Param("id") long id,@Param("actor") long actor,@Param("now") OffsetDateTime now,@Param("reason") String reason);
    @Update("update reservations set status='NO_SHOW',no_show_at=#{now},no_show_by=#{actor},version=version+1 where id=#{id} and status='CONFIRMED'")
    int markNoShow(@Param("id") long id,@Param("actor") long actor,@Param("now") OffsetDateTime now);
    @Update("update reservations set status='CHECKED_IN',checked_in_stay_id=#{stayId},version=version+1 where id=#{id} and status='CONFIRMED'")
    int checkIn(@Param("id") long id,@Param("stayId") long stayId);
    @Update("update reservations set assigned_room_id=#{roomId},assigned_at=#{now},assigned_by=#{actor},version=version+1 where id=#{id} and status in ('HELD','CONFIRMED')")
    int assignRoom(@Param("id") long id,@Param("roomId") long roomId,@Param("actor") long actor,@Param("now") OffsetDateTime now);
    @Update("""
        <script>update reservations set arrival_date=#{row.arrivalDate},departure_date=#{row.departureDate},room_type_id=#{row.roomTypeId},
          <if test="row.contactPhoneCiphertext != null">contact_phone_ciphertext=#{row.contactPhoneCiphertext},contact_phone_mask=#{row.contactPhoneMask},contact_encryption_key_id=#{row.contactEncryptionKeyId},</if>
          contact_name=#{row.contactName},notes=#{row.notes},version=version+1
          where id=#{row.id} and status in ('HELD','CONFIRMED') and version=#{expectedVersion}</script>
        """)
    int modify(Row row,@Param("expectedVersion") long expectedVersion);

    @Select("""
        select e.id,e.action,u.display_name actor_name,e.detail,e.created_at
        from reservation_events e join users u on u.id=e.actor
        where e.reservation_id=#{id} order by e.id
        """)
    List<EventRow> events(long id);
    @Insert("insert into reservation_events(reservation_id,action,actor,detail) values(#{reservationId},#{action},#{actor},#{detail})")
    int insertEvent(@Param("reservationId") long reservationId,@Param("action") String action,@Param("actor") long actor,@Param("detail") String detail);

    // ---------- future inventory ----------
    // Lock-row protocol: the counter rows are guaranteed to exist first, then locked in (room_type_id, stay_date)
    // order so concurrent reservations serialize per night. Never trust a SELECT of a possibly-absent row.
    @Insert("""
        <script>insert into inventory_days(room_type_id,stay_date) values
          <foreach collection="dates" item="d" separator=",">(#{rt},#{d})</foreach>
          on conflict do nothing</script>
        """)
    int ensureDays(@Param("rt") long rt,@Param("dates") List<LocalDate> dates);
    @Select("select room_type_id,stay_date,sold from inventory_days where room_type_id=#{rt} and stay_date between #{from} and #{to} order by room_type_id,stay_date for update")
    List<DayRow> lockDays(@Param("rt") long rt,@Param("from") LocalDate from,@Param("to") LocalDate to);
    @Update("update inventory_days set sold=sold+1,version=version+1,updated_at=now() where room_type_id=#{rt} and stay_date between #{from} and #{to}")
    int occupy(@Param("rt") long rt,@Param("from") LocalDate from,@Param("to") LocalDate to);
    @Update("update inventory_days set sold=sold-1,version=version+1,updated_at=now() where room_type_id=#{rt} and stay_date between #{from} and #{to}")
    int release(@Param("rt") long rt,@Param("from") LocalDate from,@Param("to") LocalDate to);

    // available = enabled rooms - live segment occupancy - sold (not-yet-checked-in reservations); a planned
    // stop-sell night sells nothing regardless of the remainder. A segment occupies business-date nights
    // [check-in date, exit-or-expected-departure date) — the same half-open convention as reservations, so a
    // noon checkout never spills into the next night.
    @Select("""
        select stay_date,enabled_rooms,occupied,sold,blocked,
               case when blocked>0 then 0 else enabled_rooms-occupied-sold end available
        from (
          select d::date stay_date,
            (select count(*) from rooms r where r.room_type_id=#{rt} and r.enabled) enabled_rooms,
            (select count(*) from stay_room_segments seg
               join rooms r on r.id=seg.room_id
               join stay_records s on s.id=seg.stay_record_id
              where r.room_type_id=#{rt}
                and (seg.entered_at AT TIME ZONE 'Asia/Shanghai')::date <= d::date
                and (coalesce(seg.exited_at, s.expected_check_out_at) AT TIME ZONE 'Asia/Shanghai')::date > d::date) occupied,
            coalesce((select i.sold from inventory_days i where i.room_type_id=#{rt} and i.stay_date=d::date),0) sold,
            case when exists(select 1 from inventory_blocks b where b.room_type_id=#{rt} and b.released_at is null
               and b.start_date <= d::date and b.end_date > d::date)
              then (select count(*) from rooms r where r.room_type_id=#{rt} and r.enabled) else 0 end blocked
          from generate_series(CAST(#{from} AS date),CAST(#{to} AS date),interval '1 day') d
        ) n
        """)
    List<NightRow> nightRows(@Param("rt") long rt,@Param("from") LocalDate from,@Param("to") LocalDate to);

    @Select("""
        select b.id,b.room_type_id,b.start_date,b.end_date,b.reason,b.created_at,b.released_at,
               t.type_code room_type_code,t.type_name room_type_name,
               cu.display_name created_by_name,ru.display_name released_by_name
        from inventory_blocks b
        join room_types t on t.id=b.room_type_id
        join users cu on cu.id=b.created_by
        left join users ru on ru.id=b.released_by
        where (#{includeReleased} or b.released_at is null)
        order by b.start_date desc,b.id desc limit #{pageSize} offset #{offset}
        """)
    List<BlockRow> blocks(@Param("includeReleased") boolean includeReleased,@Param("pageSize") int pageSize,@Param("offset") long offset);
    @Select("select count(*) from inventory_blocks b where (#{includeReleased} or b.released_at is null)")
    long blockCount(@Param("includeReleased") boolean includeReleased);
    @Select("select b.id from inventory_blocks b where b.id=#{id} for update")
    Long lockBlock(long id);
    @Insert("insert into inventory_blocks(room_type_id,start_date,end_date,reason,created_by) values(#{roomTypeId},#{startDate},#{endDate},#{reason},#{createdBy})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertBlock(BlockRow row);
    @Select("""
        select b.id,b.room_type_id,b.start_date,b.end_date,b.reason,b.created_at,b.released_at,
               t.type_code room_type_code,t.type_name room_type_name,
               cu.display_name created_by_name,ru.display_name released_by_name
        from inventory_blocks b
        join room_types t on t.id=b.room_type_id
        join users cu on cu.id=b.created_by
        left join users ru on ru.id=b.released_by
        where b.id=#{id}
        """)
    BlockRow blockById(long id);
    @Update("update inventory_blocks set released_by=#{actor},released_at=#{now} where id=#{id} and released_at is null")
    int releaseBlock(@Param("id") long id,@Param("actor") long actor,@Param("now") OffsetDateTime now);

    // ---------- reservation deposits (real money facts from V2, attached to the booking) ----------
    String DEPOSIT_SELECT="""
        select p.id,p.reservation_id,p.folio_id,p.direction,p.method,p.purpose,p.amount,p.currency,p.method_reference,
               p.refund_of,p.note,p.received_at,u.display_name received_by_name
        from payment_records p join users u on u.id=p.received_by
        """;
    @Select(DEPOSIT_SELECT+" where p.reservation_id=#{id} order by p.id")
    List<PaymentRow> deposits(long id);
    @Select(DEPOSIT_SELECT+" where p.id=#{id} and p.reservation_id=#{reservationId}")
    PaymentRow depositById(@Param("id") long id,@Param("reservationId") long reservationId);
    @Select("select coalesce(sum(amount) filter (where direction='RECEIPT'),0) receipts,coalesce(sum(amount) filter (where direction='REFUND'),0) refunds from payment_records where reservation_id=#{id}")
    SumRow depositSums(long id);
    @Insert("""
        insert into payment_records(reservation_id,direction,method,purpose,amount,currency,method_reference,note,received_by,cashier_shift_id)
        values(#{reservationId},#{direction},#{method},#{purpose},#{amount},'CNY',#{methodReference},#{note},#{actor},#{cashierShiftId})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insertDeposit(PaymentRow row);

    @Select("select id,type_code,type_name from room_types where enabled order by id")
    List<TypeRow> enabledRoomTypes();

    @Select("select id from reservations where external_ref=#{externalRef}")
    Long idByExternalRef(@Param("externalRef") String externalRef);
    @Select("select count(*) from room_types where id=#{id} and enabled")
    int roomTypeEnabled(long id);
    @Select("select coalesce(sum(amount),0) from payment_records where refund_of=#{receipt}")
    BigDecimal refundedTotal(long receipt);
    @Select("""
        select r.id from reservations r
        where r.status='HELD' and r.held_until < clock_timestamp()
        order by r.id limit 200 for update
        """)
    List<Long> expiredHeldIds();
}
