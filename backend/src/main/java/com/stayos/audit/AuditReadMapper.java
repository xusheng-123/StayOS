package com.stayos.audit;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AuditReadMapper {
    String COLUMNS="a.id,a.operator_id,u.display_name operator_name,u.username operator_username,a.action,a.target_type,a.target_id,a.result,a.created_at";
    String SCOPE="a.action in <foreach collection='actions' item='action' open='(' separator=',' close=')'>#{action}</foreach>";
    String FILTER="""
        where a.action in <foreach collection='q.actions' item='action' open='(' separator=',' close=')'>#{action}</foreach>
        and a.created_at &gt;= #{q.from} and a.created_at &lt; #{q.to}
        <if test='q.operatorId != null'>and a.operator_id=#{q.operatorId}</if>
        <if test='q.action != null'>and a.action=#{q.action}</if>
        <if test='q.result != null'>and a.result=#{q.result}</if>
        """;
    @Select("<script>select "+COLUMNS+" from audit_logs a left join users u on u.id=a.operator_id "+FILTER+" order by a.created_at desc,a.id desc limit #{q.pageSize} offset #{offset}</script>")
    List<AuditQuery.Entry> list(@Param("q") AuditQuery.Criteria q,@Param("offset") long offset);
    @Select("<script>select count(*) from audit_logs a "+FILTER+"</script>") long count(@Param("q") AuditQuery.Criteria q);
    @Select("<script>select "+COLUMNS+" from audit_logs a left join users u on u.id=a.operator_id where a.id=#{id} and "+SCOPE+"</script>")
    AuditQuery.Entry detail(@Param("id") long id,@Param("actions") List<String> actions);
    @Select("<script>select u.id,u.display_name,u.username from users u where exists(select 1 from audit_logs a where a.operator_id=u.id and "+SCOPE+") order by u.display_name,u.id</script>")
    List<AuditQuery.Operator> operators(@Param("actions") List<String> actions);
}
