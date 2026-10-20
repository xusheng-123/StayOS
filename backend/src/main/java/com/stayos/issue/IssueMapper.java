package com.stayos.issue;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.stayos.issue.IssueDtos.*;
@Mapper
public interface IssueMapper {
 String VIEW="""
 select i.*,r.room_number,r.floor,t.type_name room_type_name,u.display_name reporter_name,h.display_name handler_name
 from room_issues i join rooms r on r.id=i.room_id join room_types t on t.id=r.room_type_id
 join users u on u.id=i.reported_by left join users h on h.id=coalesce(i.resolved_by,i.started_by)
 """;
 String FILTER="""
 <where>(#{all} or i.reported_by=#{actor})
 <if test="q.roomNumber != null and q.roomNumber != ''">and strpos(r.room_number,#{q.roomNumber}) &gt; 0</if>
 <if test="q.roomId != null">and i.room_id=#{q.roomId}</if>
 <if test="q.floor != null">and r.floor=#{q.floor}</if>
 <if test="q.issueType != null">and i.issue_type=#{q.issueType}</if>
 <if test="q.severity != null">and i.severity=#{q.severity}</if>
 <if test="q.status != null">and i.status=#{q.status}</if>
 </where>
 """;
 @Select("<script>"+VIEW+FILTER+" order by i.reported_at desc,i.id desc limit #{q.pageSize} offset #{offset}</script>")
 List<Issue> list(@Param("actor") long actor,@Param("all") boolean all,@Param("q") Filter q,@Param("offset") long offset);
 @Select("<script>select count(*) from room_issues i join rooms r on r.id=i.room_id "+FILTER+"</script>")
 long count(@Param("actor") long actor,@Param("all") boolean all,@Param("q") Filter q);
 @Select(VIEW+" where i.id=#{id}") Issue detail(long id);
 @Select("select * from room_issues where id=#{id} for update") Issue lock(long id);
 @Insert("""
 insert into room_issues(room_id,issue_type,severity,description,notes,reported_by,reported_at)
 values(#{roomId},#{issueType},#{severity},#{description},#{notes},#{reportedBy},#{reportedAt})
 """) @Options(useGeneratedKeys=true,keyProperty="id") int insert(Issue issue);
 @Update("""
 update room_issues set status=#{status},severity=#{severity},started_by=#{startedBy},started_at=#{startedAt},
 resolved_by=#{resolvedBy},resolved_at=#{resolvedAt},resolution=#{resolution},version=version+1 where id=#{id} and version=#{version}
 """) int update(Issue issue);
 @Insert("""
 insert into room_issue_logs(issue_id,action,from_status,to_status,from_severity,to_severity,operator_id,operated_at,reason,issue_version)
 values(#{issueId},#{action},#{fromStatus},#{toStatus},#{fromSeverity},#{toSeverity},#{operatorId},#{operatedAt},#{reason},#{issueVersion})
 """) @Options(useGeneratedKeys=true,keyProperty="id") int log(Log log);
 String LOG="select l.*,u.display_name operator_name from room_issue_logs l join users u on u.id=l.operator_id";
 @Select(LOG+" where l.id=#{id}") Log receipt(long id);
 @Select(LOG+" where l.issue_id=#{id} order by l.id") List<Log> history(long id);
 @Select("select * from room_maintenance_stops where room_id=#{room} and resumed_at is null for update") Maintenance active(long room);
 @Insert("""
 insert into room_maintenance_stops(room_id,issue_id,reason,stopped_by,stopped_at)
 values(#{roomId},#{issueId},#{reason},#{stoppedBy},#{stoppedAt})
 """) @Options(useGeneratedKeys=true,keyProperty="id") int stop(Maintenance stop);
 @Update("update room_maintenance_stops set resumed_by=#{resumedBy},resumed_at=#{resumedAt},resume_reason=#{resumeReason} where id=#{id} and resumed_at is null") int resume(Maintenance stop);
 @Select("""
 select m.*,u.display_name stopped_by_name,v.display_name resumed_by_name from room_maintenance_stops m
 join users u on u.id=m.stopped_by left join users v on v.id=m.resumed_by where m.room_id=#{room} order by m.id desc
 """) List<Maintenance> maintenanceHistory(long room);
 @Insert("insert into room_maintenance_logs(stop_id,action,operator_id,operated_at,reason) values(#{stopId},#{action},#{operatorId},#{operatedAt},#{reason})")
 @Options(useGeneratedKeys=true,keyProperty="id") int maintenanceLog(MaintenanceLog log);
 @Select("select l.*,m.room_id,u.display_name operator_name from room_maintenance_logs l join room_maintenance_stops m on m.id=l.stop_id join users u on u.id=l.operator_id where l.id=#{id}") MaintenanceLog maintenanceReceipt(long id);
 @Select("select count(*) from room_issues where room_id=#{room} and severity='CRITICAL' and status<>'RESOLVED'") long critical(long room);
 @Select("select count(*) from room_issues where (#{all} or reported_by=#{actor}) and status=#{status}") long stateCount(@Param("actor") long actor,@Param("all") boolean all,@Param("status") String status);
 @Select("select count(*) from room_issues where (#{all} or reported_by=#{actor}) and severity='CRITICAL' and status<>'RESOLVED'") long criticalCount(@Param("actor") long actor,@Param("all") boolean all);
 @Select("select count(*) from room_status where maintenance_status='OUT_OF_SERVICE'") long maintenanceCount();
}
