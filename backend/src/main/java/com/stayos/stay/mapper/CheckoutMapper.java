package com.stayos.stay.mapper;
import com.stayos.stay.dto.CheckoutResult;
import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;

@Mapper
public interface CheckoutMapper {
    class LockedStay { public Long id; public String status; }
    class Segment { public Long id,roomId; public OffsetDateTime enteredAt; }
    @Select("select id,status from stay_records where id=#{id} for update")
    LockedStay lockStay(long id);
    @Select("select id,room_id,entered_at from stay_room_segments where stay_record_id=#{id} and exited_at is null")
    Segment current(long id);
    @Select("select id,room_id,entered_at from stay_room_segments where stay_record_id=#{id} and exited_at is null for update")
    Segment lockCurrent(long id);
    @Update("update stay_records set status='CHECKED_OUT',actual_check_out_at=#{time},checked_out_by=#{actor},updated_at=#{time},version=version+1 where id=#{id} and status='IN_HOUSE'")
    int closeStay(@Param("id") long id,@Param("actor") long actor,@Param("time") OffsetDateTime time);
    @Update("update stay_records set version=version+1,updated_at=#{time} where id=#{id} and status='IN_HOUSE'")
    int changed(@Param("id") long id,@Param("time") OffsetDateTime time);
    @Update("update stay_room_segments set exited_at=#{time},end_reason='CHECK_OUT' where id=#{id} and exited_at is null")
    int closeSegment(@Param("id") long id,@Param("time") OffsetDateTime time);
    @Update("update rooms set cleaning_status='DIRTY',version=version+1,updated_at=#{time} where id=#{id}")
    int dirty(@Param("id") long id,@Param("time") OffsetDateTime time);
    @Select("""
        select s.id stay_id,s.actual_check_out_at,s.checked_out_by,t.id task_id,
          seg.id segment_id,seg.room_id,seg.room_number_snapshot room_number,seg.room_type_name_snapshot room_type_name
        from stay_records s join housekeeping_tasks t on t.stay_record_id=s.id and t.source='CHECKOUT'
        join stay_room_segments seg on seg.id=t.room_segment_id
        where s.id=#{id} and s.status='CHECKED_OUT' and seg.end_reason='CHECK_OUT'
        """)
    CheckoutResult result(long id);
}
