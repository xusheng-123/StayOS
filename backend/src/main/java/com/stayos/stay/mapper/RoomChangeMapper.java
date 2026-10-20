package com.stayos.stay.mapper;
import com.stayos.stay.dto.RoomChangeDtos.*;
import com.stayos.stay.dto.RoomChangeDtos.Result;
import org.apache.ibatis.annotations.*;
import java.util.*;
import java.time.OffsetDateTime;
@Mapper
public interface RoomChangeMapper {
 @Update("update stay_room_segments set exited_at=#{time},end_reason='ROOM_CHANGE' where id=#{id} and exited_at is null")
 int close(@Param("id") long id,@Param("time") OffsetDateTime time);
 @Insert("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at) values(#{stay},#{room},#{number},#{type},#{time})")
 @Options(useGeneratedKeys=true,keyProperty="id") int newSegment(Map<String,Object> row);
 @Insert("""
 insert into room_changes(stay_record_id,old_room_id,new_room_id,old_segment_id,new_segment_id,reason_type,reason,issue_id,
 changed_at,operated_by,old_room_number,new_room_number,old_room_type_name,new_room_type_name)
 values(#{stayRecordId},#{oldRoomId},#{newRoomId},#{oldSegmentId},#{newSegmentId},#{reasonType},#{reason},#{issueId},
 #{changedAt},#{operatedBy},#{oldRoomNumber},#{newRoomNumber},#{oldRoomTypeName},#{newRoomTypeName})
 """) @Options(useGeneratedKeys=true,keyProperty="id") int insert(Result row);
 String VIEW="select c.*,u.display_name operator_name,t.id task_id from room_changes c join users u on u.id=c.operated_by left join housekeeping_tasks t on t.room_change_id=c.id and t.source='ROOM_CHANGE'";
 @Select(VIEW+" where c.id=#{id}") Result detail(long id);
 @Select(VIEW+" where c.stay_record_id=#{id} order by c.changed_at,c.id") List<Result> history(long id);
 @Select("select id,room_id,room_number_snapshot room_number,room_type_name_snapshot room_type_name,entered_at,exited_at,end_reason from stay_room_segments where stay_record_id=#{id} order by entered_at,id") List<Segment> segments(long id);
 @Select("select exists(select 1 from room_issues where id=#{id} and room_id=#{room})") boolean issueMatches(@Param("id") long id,@Param("room") long room);
}
