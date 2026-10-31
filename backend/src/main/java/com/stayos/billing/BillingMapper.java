package com.stayos.billing;

import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

@Mapper
public interface BillingMapper {
    class PolicyRow { public Long id; public int version; public LocalTime billingCutoffTime,checkoutDeadline;
        public String earlyCheckinMode,lateCheckoutMode,earlyCheckoutMode,roomChangePriceMode,extensionPriceMode,notes,createdByName;
        public boolean isActive; public long actor; public OffsetDateTime createdAt; }

    class RatePlanRow { public Long id,roomTypeId; public String roomTypeCode,roomTypeName,planCode,planName,pricingUnit;
        public BigDecimal nightlyPrice; public boolean enabled; public long version; public long actor; public OffsetDateTime createdAt,updatedAt; }
    class RateDayRow { public Long ratePlanId; public LocalDate stayDate; public BigDecimal nightlyPrice; public OffsetDateTime updatedAt; }

    class CatalogRow { public Long id; public String itemCode,itemName,pricingUnit,currency; public BigDecimal unitPrice;
        public boolean enabled; public long version; public long actor; public OffsetDateTime createdAt,updatedAt; }

    class FolioRow { public Long id,stayRecordId,stayVersion,version,openedBy,closedBy; public String status,pricingStatus,currency,
        openedByName,closedByName,roomNumber,roomTypeName,primaryGuestName,stayStatus;
        public LocalDate chargesEffectiveFrom; public OffsetDateTime openedAt,closedAt,checkInAt,expectedCheckOutAt,actualCheckOutAt; }
    class ChargeRow { public Long id,folioId,stayRecordId,roomSegmentId,policyVersion,ratePlanId,catalogItemId,reversalOf;
        public long actor; public LocalDate businessDate; public String direction,category,nameSnapshot,pricingUnitSnapshot,currency,sourceType,sourceKey,note,createdByName;
        public int quantity; public BigDecimal unitPriceSnapshot,amount; public OffsetDateTime createdAt; }
    class PaymentRow { public Long id,folioId,refundOf; public long actor; public String direction,method,purpose,currency,methodReference,note,receivedByName;
        public BigDecimal amount; public OffsetDateTime receivedAt; }
    class EventRow { public Long id; public String eventType,actorName,note; public OffsetDateTime createdAt; }
    class SumRow { public BigDecimal charges,credits,receipts,refunds; }

    class OrderRow { public Long id,stayRecordId,version; public long actor; public String status,note,requestedByName,fulfilledByName,cancelledByName,cancelReason;
        public OffsetDateTime fulfilledAt,cancelledAt,createdAt; }
    class OrderItemRow { public Long id,orderId,catalogItemId; public String itemNameSnapshot,currencySnapshot;
        public BigDecimal unitPriceSnapshot; public int quantity; }

    class StayBrief { public Long id,version; public String status; public OffsetDateTime checkInAt,expectedCheckOutAt,actualCheckOutAt; }
    class SegmentRef { public Long id,roomId,roomTypeId; public String roomNumber; }

    // ---------- policies ----------
    String POLICY_SELECT="""
        select p.id,p.version,p.billing_cutoff_time,p.checkout_deadline,p.early_checkin_mode,p.late_checkout_mode,
               p.early_checkout_mode,p.room_change_price_mode,p.extension_price_mode,p.notes,p.is_active,p.created_at,
               u.display_name created_by_name
        from billing_policies p join users u on u.id=p.created_by
        """;
    @Select(POLICY_SELECT+" where p.is_active") PolicyRow activePolicy();
    // 历史只含已归档版本；当前生效版本通过 active 单独返回，避免页面重复展示。
    @Select(POLICY_SELECT+" where not p.is_active order by p.version desc") List<PolicyRow> policyHistory();
    @Select("select coalesce(max(version),0)+1 from billing_policies") int nextPolicyVersion();
    @Insert("""
        insert into billing_policies(version,billing_cutoff_time,checkout_deadline,early_checkin_mode,late_checkout_mode,
            early_checkout_mode,room_change_price_mode,extension_price_mode,notes,is_active,created_by)
        values(#{version},#{billingCutoffTime},#{checkoutDeadline},#{earlyCheckinMode},#{lateCheckoutMode},
            #{earlyCheckoutMode},#{roomChangePriceMode},#{extensionPriceMode},#{notes},#{isActive},#{actor})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insertPolicy(PolicyRow row);
    @Update("update billing_policies set is_active=false where is_active") int deactivatePolicies();

    // ---------- rate plans and day prices ----------
    String PLAN_SELECT="""
        select p.id,p.room_type_id,t.type_code room_type_code,t.type_name room_type_name,p.plan_code,p.plan_name,
               p.pricing_unit,p.nightly_price,p.enabled,p.version,p.created_at,p.updated_at
        from rate_plans p join room_types t on t.id=p.room_type_id
        """;
    @Select(PLAN_SELECT+" order by t.type_code,p.plan_code") List<RatePlanRow> ratePlanList();
    @Select(PLAN_SELECT+" where p.id=#{id}") RatePlanRow ratePlanById(long id);
    @Insert("""
        insert into rate_plans(room_type_id,plan_code,plan_name,nightly_price,created_by)
        values(#{roomTypeId},#{planCode},#{planName},#{nightlyPrice},#{actor})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insertRatePlan(RatePlanRow row);
    @Update("""
        update rate_plans set plan_name=#{planName},nightly_price=#{price},enabled=#{enabled},version=version+1
        where id=#{id} and version=#{version}
        """) int updateRatePlan(@Param("id") long id,@Param("version") long version,@Param("planName") String planName,
        @Param("price") BigDecimal price,@Param("enabled") boolean enabled);
    @Insert("""
        insert into room_rate_days(rate_plan_id,stay_date,nightly_price,updated_by)
        values(#{plan},#{date},#{price},#{actor})
        on conflict (rate_plan_id,stay_date) do update set nightly_price=excluded.nightly_price,updated_by=excluded.updated_by,updated_at=now()
        """) int upsertRateDay(@Param("plan") long plan,@Param("date") LocalDate date,@Param("price") BigDecimal price,@Param("actor") long actor);
    @Select("select rate_plan_id,stay_date,nightly_price,updated_at from room_rate_days where rate_plan_id=#{plan} and stay_date between #{from} and #{to} order by stay_date")
    List<RateDayRow> rateDayList(@Param("plan") long plan,@Param("from") LocalDate from,@Param("to") LocalDate to);
    @Select("select nightly_price from room_rate_days where rate_plan_id=#{plan} and stay_date=#{date}")
    BigDecimal rateDayPrice(@Param("plan") long plan,@Param("date") LocalDate date);

    // ---------- service catalog ----------
    String CATALOG_SELECT="select id,item_code,item_name,pricing_unit,currency,unit_price,enabled,version,created_at,updated_at from service_catalog";
    @Select(CATALOG_SELECT+" order by item_code") List<CatalogRow> catalogList();
    @Select(CATALOG_SELECT+" where id=#{id}") CatalogRow catalogById(long id);
    @Insert("""
        insert into service_catalog(item_code,item_name,pricing_unit,unit_price,created_by)
        values(#{itemCode},#{itemName},#{pricingUnit},#{unitPrice},#{actor})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insertCatalog(CatalogRow row);
    @Update("""
        update service_catalog set item_name=#{name},pricing_unit=#{unit},unit_price=#{price},enabled=#{enabled},version=version+1
        where id=#{id} and version=#{version}
        """) int updateCatalog(@Param("id") long id,@Param("version") long version,@Param("name") String name,
        @Param("unit") String unit,@Param("price") BigDecimal price,@Param("enabled") boolean enabled);

    // ---------- folios ----------
    String FOLIO_SELECT="""
        select f.id,f.stay_record_id,f.status,f.pricing_status,f.charges_effective_from,f.currency,f.version,
               f.opened_by,f.opened_at,f.closed_by,f.closed_at,ou.display_name opened_by_name,cu.display_name closed_by_name,
               s.status stay_status,s.version stay_version,s.check_in_at,s.expected_check_out_at,s.actual_check_out_at,
               seg.room_number_snapshot room_number,seg.room_type_name_snapshot room_type_name,
               (select g.display_name from stay_guests sg join guests g on g.id=sg.guest_id where sg.stay_record_id=s.id and sg.is_primary) primary_guest_name
        from folios f join stay_records s on s.id=f.stay_record_id
        join users ou on ou.id=f.opened_by left join users cu on cu.id=f.closed_by
        join lateral (select * from stay_room_segments where stay_record_id=s.id order by entered_at desc,id desc limit 1) seg on true
        """;
    @Select(FOLIO_SELECT+" where f.id=#{id}") FolioRow folioById(long id);
    @Select(FOLIO_SELECT+" where f.stay_record_id=#{stay}") FolioRow folioByStay(long stay);
    @Select("select id,stay_record_id,status,pricing_status,charges_effective_from,currency,version from folios where id=#{id} for update")
    FolioRow folioLock(long id);
    @Insert("insert into folios(stay_record_id,pricing_status,charges_effective_from,opened_by) values(#{stayRecordId},#{pricingStatus},#{chargesEffectiveFrom},#{openedBy})")
        @Options(useGeneratedKeys=true,keyProperty="id")
    int insertFolio(FolioRow row);
    @Update("update folios set status='CLOSED',closed_at=#{now},closed_by=#{actor},version=version+1 where id=#{id} and version=#{version} and status='OPEN'")
    int closeFolio(@Param("id") long id,@Param("version") long version,@Param("actor") long actor,@Param("now") OffsetDateTime now);
    @Update("update folios set status='OPEN',closed_at=NULL,closed_by=NULL,version=version+1 where id=#{id} and version=#{version} and status='CLOSED'")
    int reopenFolio(@Param("id") long id,@Param("version") long version);

    String FOLIO_LIST="""
        select f.id,f.stay_record_id,f.status,f.pricing_status,f.closed_at,s.check_in_at,
               seg.room_number_snapshot room_number,
               (select g.display_name from stay_guests sg join guests g on g.id=sg.guest_id where sg.stay_record_id=s.id and sg.is_primary) primary_guest_name
        from folios f join stay_records s on s.id=f.stay_record_id
        join lateral (select * from stay_room_segments where stay_record_id=s.id order by entered_at desc,id desc limit 1) seg on true
        """;
    @Select("<script>"+FOLIO_LIST+"<where><if test=\"status!=null and status!='ALL'\">f.status=#{status}</if></where> order by f.id desc limit #{size} offset #{offset}</script>")
    List<FolioRow> folioList(@Param("status") String status,@Param("size") int size,@Param("offset") long offset);
    @Select("<script>select count(*) from folios f <where><if test=\"status!=null and status!='ALL'\">f.status=#{status}</if></where></script>")
    long folioCount(@Param("status") String status);

    @Select("select id,version,status,check_in_at,expected_check_out_at,actual_check_out_at from stay_records where id=#{id}")
    StayBrief stayBrief(long id);
    @Select("select count(*) from room_types where id=#{id} and enabled") int roomTypeEnabled(long id);
    @Select("""
        select seg.id,seg.room_id,r.room_type_id,seg.room_number_snapshot room_number
        from stay_room_segments seg join rooms r on r.id=seg.room_id
        where seg.stay_record_id=#{stay}
          and (seg.entered_at at time zone 'Asia/Shanghai')::date<=#{date}
          and (seg.exited_at is null or (seg.exited_at at time zone 'Asia/Shanghai')::date>#{date})
        order by seg.id limit 1""")
    SegmentRef coveringSegment(@Param("stay") long stay,@Param("date") LocalDate date);

    // ---------- charges ----------
    String CHARGE_SELECT="""
        select c.id,c.folio_id,c.stay_record_id,c.room_segment_id,c.business_date,c.direction,c.category,
               c.name_snapshot,c.pricing_unit_snapshot,c.quantity,c.unit_price_snapshot,c.amount,c.currency,
               c.policy_version,c.rate_plan_id,c.catalog_item_id,c.source_type,c.source_key,c.reversal_of,c.note,c.created_at,
               u.display_name created_by_name
        from folio_charges c join users u on u.id=c.created_by
        """;
    @Select(CHARGE_SELECT+" where c.id=#{id}") ChargeRow chargeById(long id);
    @Select(CHARGE_SELECT+" where c.folio_id=#{id} order by c.id") List<ChargeRow> chargesByFolio(long id);
    @Insert("""
        insert into folio_charges(folio_id,stay_record_id,room_segment_id,business_date,direction,category,name_snapshot,
            pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,policy_version,rate_plan_id,catalog_item_id,
            source_type,source_key,reversal_of,note,created_by)
        values(#{folioId},#{stayRecordId},#{roomSegmentId},#{businessDate},#{direction},#{category},#{nameSnapshot},
               #{pricingUnitSnapshot},#{quantity},#{unitPriceSnapshot},#{amount},#{currency},#{policyVersion},#{ratePlanId},
               #{catalogItemId},#{sourceType},#{sourceKey},#{reversalOf},#{note},#{actor})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insertCharge(ChargeRow row);
    @Select("""
        select coalesce(sum(amount) filter (where direction='CHARGE'),0) charges,
               coalesce(sum(amount) filter (where direction='CREDIT'),0) credits,
               0 receipts,0 refunds
        from folio_charges where folio_id=#{id}""") SumRow chargeSums(long id);
    @Select("select coalesce(sum(amount),0) from folio_charges where reversal_of=#{original} and direction='CREDIT'")
    BigDecimal creditedTotal(long original);
    @Select("select count(*) from folio_charges where folio_id=#{folio} and source_type='ROOM_NIGHT' and source_key='STAY:'||#{stay}||':'||#{date}")
    int roomNightPosted(@Param("folio") long folio,@Param("stay") long stay,@Param("date") LocalDate date);

    // ---------- payments ----------
    String PAYMENT_SELECT="""
        select p.id,p.folio_id,p.direction,p.method,p.purpose,p.amount,p.currency,p.method_reference,p.refund_of,
               p.note,p.received_at,u.display_name received_by_name
        from payment_records p join users u on u.id=p.received_by
        """;
    @Select(PAYMENT_SELECT+" where p.id=#{id}") PaymentRow paymentById(long id);
    @Select(PAYMENT_SELECT+" where p.folio_id=#{id} order by p.id") List<PaymentRow> paymentsByFolio(long id);
    @Insert("""
        insert into payment_records(folio_id,direction,method,purpose,amount,currency,method_reference,refund_of,note,received_by)
        values(#{folioId},#{direction},#{method},#{purpose},#{amount},#{currency},#{methodReference},#{refundOf},#{note},#{actor})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insertPayment(PaymentRow row);
    @Select("select coalesce(sum(amount) filter (where direction='RECEIPT'),0) receipts,coalesce(sum(amount) filter (where direction='REFUND'),0) refunds from payment_records where folio_id=#{id}")
    SumRow paymentSums(long id);
    @Select("select coalesce(sum(amount),0) from payment_records where refund_of=#{receipt}")
    BigDecimal refundedTotal(long receipt);

    // ---------- folio events ----------
    @Insert("""
        insert into folio_events(folio_id,event_type,actor_id,note,detail)
        values(#{folioId},#{eventType},#{actor},#{note},cast(#{detail} as jsonb))
        """) int insertEvent(@Param("folioId") long folioId,@Param("eventType") String eventType,@Param("actor") long actor,
        @Param("note") String note,@Param("detail") String detail);
    @Select("""
        select e.id,e.event_type,e.note,e.created_at,u.display_name actor_name
        from folio_events e join users u on u.id=e.actor_id where e.folio_id=#{id} order by e.id""")
    List<EventRow> eventsByFolio(long id);

    // ---------- service orders ----------
    String ORDER_SELECT="""
        select o.id,o.stay_record_id,o.status,o.note,o.version,o.created_at,o.fulfilled_at,o.cancelled_at,o.cancel_reason,
               ru.display_name requested_by_name,fu.display_name fulfilled_by_name,cu.display_name cancelled_by_name
        from service_orders o join users ru on ru.id=o.requested_by
        left join users fu on fu.id=o.fulfilled_by left join users cu on cu.id=o.cancelled_by
        """;
    @Select(ORDER_SELECT+" where o.id=#{id}") OrderRow orderById(long id);
    @Select("select id,stay_record_id,status,version from service_orders where id=#{id} for update") OrderRow orderLock(long id);
    @Select(ORDER_SELECT+" where o.stay_record_id=#{stay} order by o.id desc") List<OrderRow> ordersByStay(long stay);
    @Insert("insert into service_orders(stay_record_id,note,requested_by) values(#{stayRecordId},#{note},#{actor})")
        @Options(useGeneratedKeys=true,keyProperty="id")
    int insertOrder(OrderRow row);
    @Insert("""
        insert into service_order_items(order_id,catalog_item_id,item_name_snapshot,unit_price_snapshot,currency_snapshot,quantity)
        values(#{orderId},#{catalogItemId},#{itemNameSnapshot},#{unitPriceSnapshot},#{currencySnapshot},#{quantity})
        """) int insertOrderItem(OrderItemRow item);
    @Delete("delete from service_order_items where order_id=#{id}") int deleteOrderItems(long id);
    @Select("select id,order_id,catalog_item_id,item_name_snapshot,unit_price_snapshot,currency_snapshot,quantity from service_order_items where order_id=#{id} order by id")
    List<OrderItemRow> orderItems(long id);
    @Select("select coalesce(sum(unit_price_snapshot*quantity),0) from service_order_items where order_id=#{id}")
    BigDecimal itemsTotal(long id);
    @Update("update service_orders set status='FULFILLED',fulfilled_at=#{now},fulfilled_by=#{actor},version=version+1 where id=#{id} and version=#{version} and status='REQUESTED'")
    int fulfillOrder(@Param("id") long id,@Param("version") long version,@Param("actor") long actor,@Param("now") OffsetDateTime now);
    @Update("update service_orders set status='CANCELLED',cancelled_at=#{now},cancelled_by=#{actor},cancel_reason=#{reason},version=version+1 where id=#{id} and version=#{version} and status='REQUESTED'")
    int cancelOrder(@Param("id") long id,@Param("version") long version,@Param("actor") long actor,@Param("now") OffsetDateTime now,@Param("reason") String reason);
    @Update("update service_orders set note=#{note},version=version+1 where id=#{id} and version=#{version} and status='REQUESTED'")
    int updateOrderNote(@Param("id") long id,@Param("version") long version,@Param("note") String note);
}
