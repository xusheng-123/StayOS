package com.stayos.housekeeping.mapper;
import com.stayos.housekeeping.dto.*;
import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.*;
@Mapper
public interface AssignmentMapper {
    @Select("""
        select u.id,u.username,u.display_name,u.enabled,
          count(t.id) filter(where t.status='PENDING') pending,
          count(t.id) filter(where t.status='IN_PROGRESS') in_progress,
          count(t.id) filter(where t.status='COMPLETED' and t.completed_at>=#{today} and t.completed_at<#{tomorrow}) completed_today
        from users u left join housekeeping_tasks t on t.assigned_to=u.id
        where u.enabled and exists(select 1 from user_roles ur join roles r on r.id=ur.role_id
          where ur.user_id=u.id and r.role_code in ('HOUSEKEEPING_STAFF','HOUSEKEEPING_MANAGER','HOTEL_MANAGER'))
        group by u.id order by u.display_name,u.id
        """) List<AssignmentDtos.Employee> employees(@Param("today") OffsetDateTime today,@Param("tomorrow") OffsetDateTime tomorrow);
    @Select(TaskMapper.VIEW+" where t.assigned_to=#{employee} and t.status in ('PENDING','IN_PROGRESS') order by r.floor,r.room_number,t.id")
    List<TaskView> currentTasks(long employee);
    @Select("""
        <script>select r.id room_id,r.room_number,r.floor,r.room_type_id,rt.type_name room_type_name,
        r.cleaning_status,r.occupied,r.enabled,r.has_blocking_issue,t.id task_id,t.status task_status,
        t.assigned_to,t.version task_version,u.display_name assignee_name
        from room_status r join room_types rt on rt.id=r.room_type_id
        left join housekeeping_tasks t on t.room_id=r.id and t.status in ('PENDING','IN_PROGRESS')
        left join users u on u.id=t.assigned_to
        <where>
          <if test="q.roomNumber != null and q.roomNumber != ''">strpos(r.room_number,#{q.roomNumber}) &gt; 0</if>
          <if test="q.floor != null">and r.floor=#{q.floor}</if>
          <if test="q.roomTypeId != null">and r.room_type_id=#{q.roomTypeId}</if>
        </where></script>
        """) List<AssignmentDtos.Room> rooms(@Param("q") AssignmentDtos.Filter filter);
    @Select("select id from housekeeping_tasks where room_id=#{room} and status in ('PENDING','IN_PROGRESS')") Long openTask(long room);
    @Insert("insert into housekeeping_assignment_batches(operator_id,assignee_id,created_at,notes) values(#{operatorId},#{assigneeId},#{createdAt},#{notes})")
    @Options(useGeneratedKeys=true,keyProperty="id") int create(AssignmentDtos.Receipt receipt);
    @Insert("""
        insert into housekeeping_assignment_items(batch_id,room_id,task_id,room_number_snapshot,creation_log_id,assignment_log_id)
        values(#{batch},#{room},#{task},#{number},#{created},#{assigned})
        """) int item(@Param("batch") long batch,@Param("room") long room,@Param("task") long task,@Param("number") String number,@Param("created") Long created,@Param("assigned") long assigned);
    @Select("select * from housekeeping_assignment_batches where id=#{id}") AssignmentDtos.Receipt receipt(long id);
    @Select("select room_id,task_id,room_number_snapshot room_number,creation_log_id is not null manual_created,assignment_log_id from housekeeping_assignment_items where batch_id=#{id} order by room_id")
    List<AssignmentDtos.Item> items(long id);
}
