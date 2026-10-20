package com.stayos.housekeeping.mapper;
import com.stayos.housekeeping.dto.*;
import org.apache.ibatis.annotations.*;
import java.util.*;
import java.time.OffsetDateTime;
@Mapper
public interface TaskMapper {
    @Insert("""
        insert into housekeeping_tasks(room_id,task_type,source,source_id,stay_record_id,room_segment_id,created_by,created_at)
        values(#{room},'CHECKOUT_CLEANING','CHECKOUT',#{stay},#{stay},#{segment},#{actor},#{time})
        """)
    int createCheckout(@Param("room") long room,@Param("stay") long stay,@Param("segment") long segment,@Param("actor") long actor,@Param("time") OffsetDateTime time);
    @Select("select id from housekeeping_tasks where source='CHECKOUT' and source_id=#{stay}") Long checkoutId(long stay);
    @Insert("insert into housekeeping_tasks(room_id,task_type,source,created_by,created_at,notes) values(#{roomId},'MANUAL_CLEANING','MANUAL',#{createdBy},#{createdAt},#{notes})")
    @Options(useGeneratedKeys=true,keyProperty="id") int manual(TaskView task);
    String VIEW="""
        select t.*,r.room_number,r.floor,rt.type_name room_type_name,u.display_name assignee_name
        from housekeeping_tasks t join rooms r on r.id=t.room_id join room_types rt on rt.id=r.room_type_id
        left join users u on u.id=t.assigned_to
        """;
    String FILTER="""
        <where>
          (#{manager} or t.assigned_to=#{actor})
          <if test="q.roomNumber != ''">and strpos(r.room_number,#{q.roomNumber}) &gt; 0</if>
          <if test="q.floor != null">and r.floor=#{q.floor}</if>
          <if test="q.status != null">and t.status=#{q.status}</if>
          <if test="q.assigneeId != null">and t.assigned_to=#{q.assigneeId}</if>
          <if test="q.scope == 'MINE'">and t.assigned_to=#{actor}</if>
          <if test="q.scope == 'UNASSIGNED'">and t.assigned_to is null and t.status='PENDING'</if>
        </where>
        """;
    @Select("<script>"+VIEW+FILTER+"""
         order by t.created_at <choose><when test="q.sort == 'OLDEST'">asc,t.id asc</when><otherwise>desc,t.id desc</otherwise></choose>
         limit #{q.pageSize} offset #{offset}</script>
        """)
    List<TaskView> list(@Param("q") TaskFilter q,@Param("offset") long offset,@Param("actor") long actor,@Param("manager") boolean manager);
    @Select("<script>select count(*) from housekeeping_tasks t join rooms r on r.id=t.room_id "+FILTER+"</script>")
    long count(@Param("q") TaskFilter q,@Param("actor") long actor,@Param("manager") boolean manager);
    @Select(VIEW+" where t.id=#{id}") TaskView detail(long id);
    @Select("select * from housekeeping_tasks where id=#{id} for update") TaskView lock(long id);
    @Update("""
        update housekeeping_tasks set status=#{status},assigned_to=#{assignedTo},assigned_by=#{assignedBy},
          assigned_at=#{assignedAt},claimed_at=#{claimedAt},started_by=#{startedBy},started_at=#{startedAt},
          completed_by=#{completedBy},completed_at=#{completedAt},completion_notes=#{completionNotes},version=version+1
        where id=#{id} and version=#{version}
        """) int update(TaskView task);
    @Update("update rooms set cleaning_status=#{state},updated_at=#{time},version=version+1 where id=#{room}")
    int cleanState(@Param("room") long room,@Param("state") String state,@Param("time") OffsetDateTime time);
    @Insert("""
        insert into housekeeping_task_logs(task_id,action,from_status,to_status,from_assignee,to_assignee,operator_id,operated_at,reason,task_version)
        values(#{taskId},#{action},#{fromStatus},#{toStatus},#{fromAssignee},#{toAssignee},#{operatorId},#{operatedAt},#{reason},#{taskVersion})
        """) @Options(useGeneratedKeys=true,keyProperty="id") int log(TaskLog log);
    String LOG_VIEW="""
        select l.*,a.display_name from_assignee_name,b.display_name to_assignee_name,u.display_name operator_name
        from housekeeping_task_logs l left join users a on a.id=l.from_assignee left join users b on b.id=l.to_assignee
        join users u on u.id=l.operator_id
        """;
    @Select(LOG_VIEW+" where l.id=#{id}") TaskLog receipt(long id);
    @Select(LOG_VIEW+" where l.task_id=#{id} order by l.id") List<TaskLog> history(long id);
    @Select("select id,display_name from users where id=#{id} and enabled for share") Map<String,Object> lockEmployee(long id);
    record Employee(long id,String displayName) {}
    @Select("""
        select u.id,u.display_name from users u where u.enabled and exists
          (select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id
           and r.role_code in ('HOUSEKEEPING_STAFF','HOUSEKEEPING_MANAGER','HOTEL_MANAGER')) order by u.display_name,u.id
        """) List<Employee> employees();
    @Select("""
        select u.id,u.display_name from users u where exists(select 1 from housekeeping_tasks t where t.assigned_to=u.id)
        or (u.enabled and exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id
            and r.role_code in ('HOUSEKEEPING_STAFF','HOUSEKEEPING_MANAGER','HOTEL_MANAGER')))
        order by u.display_name,u.id
        """) List<Employee> assignees();
    @Select("""
        select count(*) filter(where t.status='PENDING') pending,
          count(*) filter(where t.status='IN_PROGRESS') in_progress,
          count(*) filter(where t.status='COMPLETED' and t.completed_at>=#{today} and t.completed_at<#{tomorrow}) completed_today,
          count(*) filter(where t.assigned_to=#{actor} and t.status in ('PENDING','IN_PROGRESS')) mine,
          count(*) filter(where t.assigned_to is null and t.status='PENDING') unassigned
        from housekeeping_tasks t where #{manager} or t.assigned_to=#{actor}
        """) TaskStatistics statistics(@Param("actor") long actor,@Param("manager") boolean manager,@Param("today") OffsetDateTime today,@Param("tomorrow") OffsetDateTime tomorrow);
}
