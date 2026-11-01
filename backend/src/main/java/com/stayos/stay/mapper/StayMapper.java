package com.stayos.stay.mapper;

import com.stayos.stay.dto.StayView;
import com.stayos.stay.dto.StayFilter;
import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface StayMapper {
    @Insert("""
        insert into idempotency_requests(operator_id,operation,request_key,request_hash)
        values(#{actor},#{operation},CAST(#{key} AS uuid),#{hash})
        on conflict(operator_id,operation,request_key) do nothing
        """)
    int reserve(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("hash") String hash);
    class RequestResult { public String requestHash; public Long resultStayId,resultTaskLogId,resultAssignmentBatchId,resultIssueLogId,resultMaintenanceLogId,resultRoomChangeId,resultStayExtensionId,resultGuestCorrectionId,resultHandoverId,resultFolioId,resultFolioChargeId,resultServiceOrderId,resultPaymentRecordId,resultCashierShiftId,resultReservationId,resultInventoryBlockId,resultCustomerProfileId; public Boolean expired; }
    @Select("""
        select request_hash,result_stay_id,result_task_log_id,result_assignment_batch_id,result_issue_log_id,result_maintenance_log_id,result_room_change_id,result_stay_extension_id,result_guest_correction_id,result_handover_id,result_folio_id,result_folio_charge_id,result_service_order_id,result_payment_record_id,result_cashier_shift_id,result_reservation_id,result_inventory_block_id,result_customer_profile_id,expires_at <= clock_timestamp() expired
        from idempotency_requests where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)
        """)
    RequestResult request(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key);
    @Update("update idempotency_requests set result_stay_id=#{stay} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int complete(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("stay") long stay);
    @Update("update idempotency_requests set result_task_log_id=#{log} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeTask(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("log") long log);
    @Update("update idempotency_requests set result_assignment_batch_id=#{batch} where operator_id=#{actor} and operation='HK_BATCH_ASSIGN' and request_key=CAST(#{key} AS uuid)")
    int completeBatch(@Param("actor") long actor,@Param("key") String key,@Param("batch") long batch);
    @Update("update idempotency_requests set result_issue_log_id=#{log} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeIssue(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("log") long log);
    @Update("update idempotency_requests set result_maintenance_log_id=#{log} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeMaintenance(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("log") long log);
    @Update("update idempotency_requests set result_room_change_id=#{change} where operator_id=#{actor} and operation='ROOM_CHANGE' and request_key=CAST(#{key} AS uuid)")
    int completeChange(@Param("actor") long actor,@Param("key") String key,@Param("change") long change);
    @Update("update idempotency_requests set result_stay_extension_id=#{extension} where operator_id=#{actor} and operation='STAY_EXTEND' and request_key=CAST(#{key} AS uuid)")
    int completeExtension(@Param("actor") long actor,@Param("key") String key,@Param("extension") long extension);
    @Update("update idempotency_requests set result_guest_correction_id=#{correction} where operator_id=#{actor} and operation='GUEST_CORRECT' and request_key=CAST(#{key} AS uuid)")
    int completeCorrection(@Param("actor") long actor,@Param("key") String key,@Param("correction") long correction);
    @Update("update idempotency_requests set result_handover_id=#{handover} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeHandover(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("handover") long handover);
    @Update("update idempotency_requests set result_folio_id=#{folio} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeFolio(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("folio") long folio);
    @Update("update idempotency_requests set result_folio_charge_id=#{charge} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeFolioCharge(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("charge") long charge);
    @Update("update idempotency_requests set result_service_order_id=#{order} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeServiceOrder(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("order") long order);
    @Update("update idempotency_requests set result_payment_record_id=#{payment} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completePaymentRecord(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("payment") long payment);
    @Update("update idempotency_requests set result_cashier_shift_id=#{shift} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeCashierShift(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("shift") long shift);
    @Update("update idempotency_requests set result_reservation_id=#{reservation} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeReservation(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("reservation") long reservation);
    @Update("update idempotency_requests set result_inventory_block_id=#{block} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeBlock(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("block") long block);
    @Update("update idempotency_requests set result_customer_profile_id=#{profile} where operator_id=#{actor} and operation=#{operation} and request_key=CAST(#{key} AS uuid)")
    int completeProfile(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("profile") long profile);
    @Insert("""
        insert into stay_records(check_in_at,expected_check_out_at,created_by,notes)
        values(#{checkInAt},#{expectedCheckOutAt},#{actor},#{notes})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insertStay(Map<String,Object> row);
    @Insert("""
        insert into guests(display_name,document_type,document_ciphertext,document_mask,phone_ciphertext,phone_mask,encryption_key_id,notes)
        values(#{name},#{documentType},#{documentCiphertext},#{documentMask},#{phoneCiphertext},#{phoneMask},'v1',#{notes})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insertGuest(Map<String,Object> row);
    @Insert("insert into stay_guests(stay_record_id,guest_id,is_primary,position) values(#{stay},#{guest},#{primary},#{position})")
    int link(@Param("stay") long stay,@Param("guest") long guest,@Param("primary") boolean primary,@Param("position") int position);
    @Insert("""
        insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at)
        values(#{stay},#{room},#{number},#{type},#{time})
        """)
    int segment(@Param("stay") long stay,@Param("room") long room,@Param("number") String number,@Param("type") String type,@Param("time") java.time.OffsetDateTime time);
    String VIEW="""
        select s.*, seg.id segment_id,seg.room_id,seg.room_number_snapshot room_number,
          seg.room_type_name_snapshot room_type_name,u.display_name operator_name,
          (select count(*) from stay_guests sg where sg.stay_record_id=s.id) guest_count,
          (select g.display_name from stay_guests sg join guests g on g.id=sg.guest_id where sg.stay_record_id=s.id and sg.is_primary) primary_guest_name
        from stay_records s join users u on u.id=s.created_by
        join lateral (select * from stay_room_segments where stay_record_id=s.id order by entered_at desc,id desc limit 1) seg on true
        """;
    @Select(VIEW+" where s.id=#{id}")
    StayView detail(long id);
    @Select("""
        select g.id,g.version,g.display_name name,g.document_type,g.document_mask document_number,g.phone_mask phone,g.notes,sg.is_primary as "primary"
        from stay_guests sg join guests g on g.id=sg.guest_id where sg.stay_record_id=#{id} order by sg.position
        """)
    List<StayView.Guest> guests(long id);
    String FILTER="""
        <where>
          <if test="q.status != 'ALL'">s.status=#{q.status}</if>
          <if test="q.roomNumber != ''">and exists(select 1 from stay_room_segments x where x.stay_record_id=s.id and strpos(x.room_number_snapshot,#{q.roomNumber}) &gt; 0)</if>
          <if test="q.guestName != ''">and exists(select 1 from stay_guests x join guests g on g.id=x.guest_id where x.stay_record_id=s.id and strpos(g.display_name,#{q.guestName}) &gt; 0)</if>
          <if test="q.checkInFrom != null">and s.check_in_at &gt;= #{q.checkInFrom}</if>
          <if test="q.checkInTo != null">and s.check_in_at &lt; #{q.checkInTo}</if>
        </where>
        """;
    @Select("<script>"+VIEW+FILTER+" order by s.check_in_at desc,s.id desc limit #{q.pageSize} offset #{offset}</script>")
    List<StayView> list(@Param("q") StayFilter query,@Param("offset") long offset);
    @Select("<script>select count(*) from stay_records s "+FILTER+"</script>")
    long count(@Param("q") StayFilter query);
}
