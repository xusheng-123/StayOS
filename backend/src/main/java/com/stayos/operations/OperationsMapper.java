package com.stayos.operations;

import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface OperationsMapper {
    class Notice { public String kind,roomNumber; public Long targetId; public OffsetDateTime since; }
    String STAY="from stay_records s join stay_room_segments seg on seg.stay_record_id=s.id and seg.exited_at is null where s.status='IN_HOUSE' and s.expected_check_out_at &lt; #{now}";
    @Select("<script>select count(*) "+STAY+"</script>") long overdue(OffsetDateTime now);
    @Select("<script>select 'OVERDUE_STAY' kind,s.id target_id,seg.room_number_snapshot room_number,s.expected_check_out_at since "+STAY+" order by s.expected_check_out_at,s.id limit 20</script>") List<Notice> overdueItems(OffsetDateTime now);
    String TASK="""
        from housekeeping_tasks t join rooms r on r.id=t.room_id left join users u on u.id=t.assigned_to
        where t.status in ('PENDING','IN_PROGRESS') and (#{all} or t.assigned_to=#{actor})
        """;
    String STRANDED="""
        and t.assigned_to is not null and (not u.enabled or not exists(select 1 from user_roles ur join roles role on role.id=ur.role_id
        where ur.user_id=t.assigned_to and role.role_code in ('HOUSEKEEPING_STAFF','HOUSEKEEPING_MANAGER','HOTEL_MANAGER')))
        """;
    @Select("select count(*) "+TASK+" and t.assigned_to is null") long unassigned(@Param("actor") long actor,@Param("all") boolean all);
    @Select("<script>select count(*) "+TASK+" and ((t.status='PENDING' and t.created_at &lt; #{pendingBefore}) or (t.status='IN_PROGRESS' and t.started_at &lt; #{startedBefore}))</script>")
    long delayed(@Param("actor") long actor,@Param("all") boolean all,@Param("pendingBefore") OffsetDateTime pending,@Param("startedBefore") OffsetDateTime started);
    @Select("select count(*) "+TASK+STRANDED) long stranded(@Param("actor") long actor,@Param("all") boolean all);
    @Select("select 'STRANDED_TASK' kind,t.id target_id,r.room_number,t.assigned_at since "+TASK+STRANDED+" order by t.assigned_at,t.id limit 20")
    List<Notice> strandedItems(@Param("actor") long actor,@Param("all") boolean all);
    @Select("<script>select 'DELAYED_TASK' kind,t.id target_id,r.room_number,coalesce(t.started_at,t.created_at) since "+TASK+" and ((t.status='PENDING' and t.created_at &lt; #{pendingBefore}) or (t.status='IN_PROGRESS' and t.started_at &lt; #{startedBefore})) order by since,t.id limit 20</script>")
    List<Notice> delayedItems(@Param("actor") long actor,@Param("all") boolean all,@Param("pendingBefore") OffsetDateTime pending,@Param("startedBefore") OffsetDateTime started);
    String ISSUE="from room_issues i join rooms r on r.id=i.room_id where i.status&lt;&gt;'RESOLVED' and i.severity='CRITICAL' and (#{all} or i.reported_by=#{actor})";
    @Select("<script>select count(*) "+ISSUE+"</script>") long critical(@Param("actor") long actor,@Param("all") boolean all);
    @Select("<script>select 'CRITICAL_ISSUE' kind,i.id target_id,r.room_number,i.reported_at since "+ISSUE+" order by i.reported_at,i.id limit 20</script>")
    List<Notice> criticalItems(@Param("actor") long actor,@Param("all") boolean all);
    @Select("select count(*) from room_maintenance_stops where resumed_at is null") long stops();
}
