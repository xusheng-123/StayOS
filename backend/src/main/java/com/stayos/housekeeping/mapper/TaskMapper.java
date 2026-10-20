package com.stayos.housekeeping.mapper;
import com.stayos.housekeeping.dto.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface TaskMapper {
    @Insert("""
        insert into housekeeping_tasks(room_id,task_type,source,source_id,stay_record_id,room_segment_id,created_by,created_at)
        values(#{room},'CHECKOUT_CLEANING','CHECKOUT',#{stay},#{stay},#{segment},#{actor},#{time})
        """)
    int createCheckout(@Param("room") long room,@Param("stay") long stay,@Param("segment") long segment,@Param("actor") long actor,@Param("time") java.time.OffsetDateTime time);
    String VIEW="""
        select t.id,t.room_id,r.room_number,r.floor,rt.type_name room_type_name,
          t.task_type,t.source,t.status,t.created_at,t.notes
        from housekeeping_tasks t join rooms r on r.id=t.room_id join room_types rt on rt.id=r.room_type_id
        """;
    String FILTER="""
        <where>
          <if test="q.roomNumber != ''">strpos(r.room_number,#{q.roomNumber}) &gt; 0</if>
          <if test="q.floor != null">and r.floor=#{q.floor}</if>
          <if test="q.status != null">and t.status=#{q.status}</if>
        </where>
        """;
    @Select("<script>"+VIEW+FILTER+" order by t.created_at desc,t.id desc limit #{q.pageSize} offset #{offset}</script>")
    List<TaskView> list(@Param("q") TaskFilter q,@Param("offset") long offset);
    @Select("<script>select count(*) from housekeeping_tasks t join rooms r on r.id=t.room_id "+FILTER+"</script>")
    long count(@Param("q") TaskFilter q);
    @Select(VIEW+" where t.id=#{id}") TaskView detail(long id);
}
