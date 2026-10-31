package com.stayos.cashier;

import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface CashierMapper {
    // ---------- business days ----------
    String DAY_SELECT="""
        select bd.id,bd.business_date,bd.status,bd.policy_id,bd.closed_at,u.display_name closed_by_name,
               bp.billing_cutoff_time cutoff,
               exists(select 1 from daily_reports dr where dr.business_date=bd.business_date) has_report
        from business_days bd join billing_policies bp on bp.id=bd.policy_id
        left join users u on u.id=bd.closed_by
        """;
    @Select(DAY_SELECT+" where bd.business_date=#{date}") BusinessDayRow dayByDate(LocalDate date);
    @Select(DAY_SELECT+" order by bd.business_date desc limit #{limit}") List<BusinessDayRow> recentDays(int limit);
    @Update("""
        insert into business_days(business_date,policy_id)
        select #{date},bp.id from billing_policies bp where bp.is_active
        on conflict (business_date) do nothing
        """) int ensureDay(LocalDate date);
    @Update("update business_days set status='CLOSED',closed_by=#{actor},closed_at=now(),updated_at=now() where business_date=#{date} and status='OPEN'")
    int closeDay(@Param("date") LocalDate date,@Param("actor") long actor);

    // ---------- close runs ----------
    @Insert("insert into daily_close_runs(business_date,status,started_by) values(#{businessDate},'RUNNING',#{actor})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertRun(CloseRunRow row);
    @Update("update daily_close_runs set status='COMPLETED',finished_at=now(),steps_done=#{steps},last_step=#{step} where id=#{id} and status='RUNNING'")
    int completeRun(@Param("id") long id,@Param("steps") int steps,@Param("step") String step);
    @Update("update daily_close_runs set status='FAILED',finished_at=now(),failure_reason=#{reason} where id=#{id} and status='RUNNING'")
    int failRun(@Param("id") long id,@Param("reason") String reason);
    @Update("update daily_close_runs set steps_done=#{steps},last_step=#{step} where id=#{id} and status='RUNNING'")
    int bumpStep(@Param("id") long id,@Param("steps") int steps,@Param("step") String step);

    // ---------- report source sums ----------
    @Select("""
        select coalesce(sum(amount) filter (where direction='CHARGE'),0) charges,
               coalesce(sum(amount) filter (where direction='CREDIT'),0) credits,
               (count(*) filter (where source_type='ROOM_NIGHT' and direction='CHARGE')) room_nights,
               coalesce(sum(amount) filter (where source_type='ROOM_NIGHT' and direction='CHARGE'),0) room_revenue
        from folio_charges where business_date=#{date}
        """) ChargeSumRow chargeSums(LocalDate date);
    @Select("""
        select coalesce(sum(p.amount) filter (where p.direction='RECEIPT'),0) receipts,
               coalesce(sum(p.amount) filter (where p.direction='REFUND'),0) refunds,
               coalesce(sum(p.amount) filter (where p.direction='RECEIPT' and p.method='CASH'),0) cash_in,
               coalesce(sum(p.amount) filter (where p.direction='REFUND' and p.method='CASH'),0) cash_out
        from payment_records p join business_days bd on bd.business_date=#{date}
        join billing_policies bp on bp.id=bd.policy_id
        where (case when (p.received_at at time zone 'Asia/Shanghai')::time < bp.billing_cutoff_time
                    then ((p.received_at at time zone 'Asia/Shanghai')::date - 1)
                    else (p.received_at at time zone 'Asia/Shanghai')::date end) = #{date}
        """) PaymentSumRow paymentSumsByDay(LocalDate date);
    @Select("""
        select count(distinct seg.room_id) from stay_room_segments seg
        where (seg.entered_at at time zone 'Asia/Shanghai')::date <= #{date}
          and (seg.exited_at is null or (seg.exited_at at time zone 'Asia/Shanghai')::date >= #{date})
        """) int occupiedRooms(LocalDate date);
    @Select("""
        select (select count(*) from rooms where enabled)
             - (select count(*) from room_maintenance_stops
                where (stopped_at at time zone 'Asia/Shanghai')::date <= #{date}
                  and (resumed_at is null or (resumed_at at time zone 'Asia/Shanghai')::date >= #{date}))
        """) int availableRooms(LocalDate date);
    @Select("""
        select c.id,c.folio_id,c.direction,c.amount,c.created_at,orig.business_date original_date
        from folio_charges c join folio_charges orig on orig.id=c.reversal_of
        where orig.business_date=#{date} and c.business_date>#{date}
        order by c.id
        """) List<Map<String,Object>> laterAdjustments(LocalDate date);

    // ---------- room fee verification ----------
    @Select("""
        select seg.id segment_id,seg.stay_record_id,seg.room_number_snapshot room_number,r.room_type_id,
               rt.type_name,f.pricing_status,
               dp.day_price,
               rn.id charge_id,rn.amount posted_amount,rn.rate_plan_id posted_plan
        from stay_room_segments seg
        join rooms r on r.id=seg.room_id
        join room_types rt on rt.id=r.room_type_id
        left join folios f on f.stay_record_id=seg.stay_record_id
        left join lateral (
            select rp.id from rate_plans rp where rp.room_type_id=r.room_type_id and rp.enabled order by rp.id limit 1
        ) pl on f.pricing_status='PRICED'
        left join lateral (
            select rrd.nightly_price day_price from room_rate_days rrd where rrd.rate_plan_id=pl.id and rrd.stay_date=#{date}
        ) dp on true
        left join lateral (
            select c.id,c.amount,c.rate_plan_id from folio_charges c
            where c.stay_record_id=seg.stay_record_id and c.business_date=#{date}
              and c.source_type='ROOM_NIGHT' and c.room_segment_id=seg.id
            order by c.id limit 1
        ) rn on true
        where (seg.entered_at at time zone 'Asia/Shanghai')::date <= #{date}
          and seg.entered_at < (#{date}::date + interval '1 day')
          and (seg.exited_at is null or seg.exited_at >= #{date}::date)
        order by seg.stay_record_id
        """) List<SegmentFeeRow> segmentFees(LocalDate date);
    @Select("select nightly_price from room_rate_days where rate_plan_id=#{plan} and stay_date=#{date}")
    BigDecimal dayPrice(@Param("plan") long plan,@Param("date") LocalDate date);

    // ---------- cashier shifts ----------
    String SHIFT_SELECT="""
        select cs.id,cs.business_date,cs.operator_id,cs.status,cs.opened_float,cs.opened_at,cs.counted_cash,
               cs.difference_reason,cs.submitted_at,cs.confirmed_by,cs.confirmed_at,cs.version,
               u.display_name operator_name,u.username,
               cu.display_name confirmed_by_name,
               coalesce(m.receipts,0) receipts,coalesce(m.refunds,0) refunds
        from cashier_shifts cs
        join users u on u.id=cs.operator_id
        left join users cu on cu.id=cs.confirmed_by
        left join lateral (
            select coalesce(sum(amount) filter (where direction='RECEIPT'),0) receipts,
                   coalesce(sum(amount) filter (where direction='REFUND'),0) refunds
            from payment_records p where p.cashier_shift_id=cs.id and p.method='CASH'
        ) m on true
        """;
    @Select("<script>"+SHIFT_SELECT+
        "<where><if test='operatorId!=null'>cs.operator_id=#{operatorId}</if>"+
        "<if test='status!=null and status!=\"ALL\"'> and cs.status=#{status}</if></where>"+
        " order by cs.id desc limit #{size} offset #{offset}</script>")
    List<ShiftRow> shiftList(@Param("operatorId") Long operatorId,@Param("status") String status,@Param("size") int size,@Param("offset") long offset);
    @Select("<script>select count(*) from cashier_shifts cs <where><if test='operatorId!=null'>cs.operator_id=#{operatorId}</if>"+
        "<if test='status!=null and status!=\"ALL\"'> and cs.status=#{status}</if></where></script>")
    long shiftCount(@Param("operatorId") Long operatorId,@Param("status") String status);
    @Select(SHIFT_SELECT+" where cs.id=#{id} for update of cs") ShiftRow lockShift(long id);
    @Select(SHIFT_SELECT+" where cs.id=#{id}") ShiftRow shiftById(long id);
    @Insert("""
        insert into cashier_shifts(business_date,operator_id,opened_float)
        values(#{businessDate},#{operatorId},#{openedFloat})
        """) @Options(useGeneratedKeys=true,keyProperty="id") int insertShift(ShiftRow row);
    @Update("""
        update cashier_shifts set status='SUBMITTED',counted_cash=#{countedCash},difference_reason=#{differenceReason},
            submitted_at=now(),version=version+1,updated_at=now()
        where id=#{id} and version=#{version} and status='OPEN'
        """) int submitShift(@Param("id") long id,@Param("countedCash") BigDecimal countedCash,
        @Param("differenceReason") String differenceReason,@Param("version") long version);
    @Update("update cashier_shifts set status='CONFIRMED',confirmed_by=#{actor},confirmed_at=now(),version=version+1,updated_at=now() where id=#{id} and version=#{version} and status='SUBMITTED'")
    int confirmShift(@Param("id") long id,@Param("actor") long actor,@Param("version") long version);
    @Select("select id from cashier_shifts where operator_id=#{operator} and status='OPEN' order by id desc limit 1")
    Long openShiftOf(long operator);

    @Insert("""
        insert into cash_counts(cashier_shift_id,expected_cash,counted_cash,difference,difference_reason,counted_by)
        values(#{shiftId},#{expectedCash},#{countedCash},#{difference},#{differenceReason},#{actor})
        """) @Options(useGeneratedKeys=true,keyProperty="id") int insertCount(CountRow row);

    @Select("""
        select p.id,p.direction,p.method,p.amount,p.received_at,u.display_name received_by_name
        from payment_records p join users u on u.id=p.received_by
        where p.cashier_shift_id=#{shift} order by p.id
        """) List<Map<String,Object>> shiftPayments(long shift);

    @Insert("insert into daily_reports(business_date,run_id,snapshot,created_by) values(#{date},#{run},cast(#{snapshot} as jsonb),#{actor})")
    int insertReport(@Param("date") LocalDate date,@Param("run") long run,@Param("snapshot") String snapshot,@Param("actor") long actor);

    // ---------- report queries ----------
    String REPORT_SELECT="""
        select dr.id,dr.business_date,dr.snapshot,dr.created_at,u.display_name created_by_name
        from daily_reports dr join users u on u.id=dr.created_by
        """;
    @Select("<script>"+REPORT_SELECT+
        "<where><if test='from!=null'>dr.business_date&gt;=#{from}</if><if test='to!=null'> and dr.business_date&lt;=#{to}</if></where>"+
        " order by dr.business_date desc limit #{size} offset #{offset}</script>")
    List<Map<String,Object>> reportList(@Param("from") LocalDate from,@Param("to") LocalDate to,@Param("size") int size,@Param("offset") long offset);
    @Select("<script>select count(*) from daily_reports dr <where><if test='from!=null'>dr.business_date&gt;=#{from}</if><if test='to!=null'> and dr.business_date&lt;=#{to}</if></where></script>")
    long reportCount(@Param("from") LocalDate from,@Param("to") LocalDate to);

    class BusinessDayRow {
        public long id; public LocalDate businessDate; public String status; public long policyId;
        public OffsetDateTime closedAt; public String closedByName; public LocalTime cutoff; public boolean hasReport;
    }
    class CloseRunRow { public long id; public LocalDate businessDate; public long actor; }
    class ChargeSumRow { public BigDecimal charges; public BigDecimal credits; public long roomNights; public BigDecimal roomRevenue; }
    class PaymentSumRow { public BigDecimal receipts; public BigDecimal refunds; public BigDecimal cashIn; public BigDecimal cashOut; }
    class SegmentFeeRow {
        public long segmentId; public long stayRecordId; public String roomNumber; public long roomTypeId;
        public String typeName; public String pricingStatus; public BigDecimal dayPrice;
        public Long chargeId; public BigDecimal postedAmount; public Long postedPlan;
    }
    class ShiftRow {
        public long id; public LocalDate businessDate; public long operatorId; public String status;
        public BigDecimal openedFloat; public OffsetDateTime openedAt; public BigDecimal countedCash;
        public String differenceReason; public OffsetDateTime submittedAt; public Long confirmedBy;
        public OffsetDateTime confirmedAt; public long version; public String operatorName; public String username;
        public String confirmedByName; public BigDecimal receipts; public BigDecimal refunds;
    }
    class CountRow {
        public long id; public long shiftId; public BigDecimal expectedCash; public BigDecimal countedCash;
        public BigDecimal difference; public String differenceReason; public long actor;
    }
}
