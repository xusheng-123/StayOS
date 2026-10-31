package com.stayos.handover;

import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface HandoverMapper {
    class Row {
        public Long id,version,handoverBy,receiverId,itemCount;
        public String scope,status,summary,handoverByName,receiverName;
        public OffsetDateTime shiftStartAt,shiftEndAt,createdAt,submittedAt,acknowledgedAt;
    }
    class ItemRow { public Long id,sourceId; public long handoverId; public int position; public String category,content; }
    class EventRow {
        public Long id,handoverId,actorId;
        public String eventType,actorName,reason,detail;
        public OffsetDateTime createdAt;
    }
    class CandidateRow { public Long id; public String displayName,username,roleCodes; }

    String SELECT="""
        select h.id,h.scope,h.status,h.version,h.handover_by,h.receiver_id,h.shift_start_at,h.shift_end_at,h.summary,
               h.created_at,h.submitted_at,h.acknowledged_at,hb.display_name handover_by_name,r.display_name receiver_name,
               (select count(*) from handover_items i where i.handover_id=h.id) item_count
        from shift_handovers h join users hb on hb.id=h.handover_by join users r on r.id=h.receiver_id
        """;
    @Select(SELECT+" where h.id=#{id}") Row byId(long id);
    // Lock by primary key only: a joined locking read can skip a row whose join key was
    // concurrently updated (EvalPlanQual re-check), so names are read via byId after locking.
    @Select("""
        select id,scope,status,version,handover_by,receiver_id,shift_start_at,shift_end_at,summary,
               created_at,submitted_at,acknowledged_at
        from shift_handovers where id=#{id} for update""") Row lock(long id);

    @Select("""
        <script>""" + SELECT + """
        where h.scope=#{scope}
        <if test="status!=null"> and h.status=#{status}</if>
        <if test="receiving"> and h.receiver_id=#{actor}</if>
        <if test="mine"> and h.handover_by=#{actor}</if>
        order by h.id desc limit #{size} offset #{offset}</script>""")
    List<Row> list(@Param("actor") long actor,@Param("scope") String scope,@Param("status") String status,
                   @Param("receiving") boolean receiving,@Param("mine") boolean mine,
                   @Param("size") int size,@Param("offset") long offset);
    @Select("""
        <script>select count(*) from shift_handovers h where h.scope=#{scope}
        <if test="status!=null"> and h.status=#{status}</if>
        <if test="receiving"> and h.receiver_id=#{actor}</if>
        <if test="mine"> and h.handover_by=#{actor}</if>
        </script>""")
    long count(@Param("actor") long actor,@Param("scope") String scope,@Param("status") String status,
               @Param("receiving") boolean receiving,@Param("mine") boolean mine);

    @Insert("""
        insert into shift_handovers(scope,handover_by,receiver_id,shift_start_at,shift_end_at,summary)
        values(#{scope},#{handoverBy},#{receiverId},#{shiftStartAt},#{shiftEndAt},#{summary})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(Row row);
    @Update("update shift_handovers set receiver_id=#{receiverId},shift_start_at=#{shiftStartAt},shift_end_at=#{shiftEndAt},summary=#{summary},version=version+1 where id=#{id} and version=#{version} and status='DRAFT'")
    int updateDraft(@Param("id") long id,@Param("version") long version,@Param("receiverId") long receiverId,
                    @Param("shiftStartAt") OffsetDateTime shiftStartAt,@Param("shiftEndAt") OffsetDateTime shiftEndAt,@Param("summary") String summary);
    @Update("update shift_handovers set status='SUBMITTED',submitted_at=#{now},version=version+1 where id=#{id} and version=#{version} and status='DRAFT'")
    int submit(@Param("id") long id,@Param("version") long version,@Param("now") OffsetDateTime now);
    @Update("update shift_handovers set receiver_id=#{receiverId},version=version+1 where id=#{id} and version=#{version} and status='SUBMITTED'")
    int reassign(@Param("id") long id,@Param("version") long version,@Param("receiverId") long receiverId);
    @Update("update shift_handovers set status='ACKNOWLEDGED',acknowledged_at=#{now},acknowledged_by=#{actor},version=version+1 where id=#{id} and version=#{version} and status='SUBMITTED'")
    int acknowledge(@Param("id") long id,@Param("version") long version,@Param("actor") long actor,@Param("now") OffsetDateTime now);

    @Insert("""
        insert into handover_items(handover_id,position,category,content,source_id)
        values(#{handoverId},#{position},#{category},#{content},#{sourceId})
        """)
    int insertItem(ItemRow item);
    @Delete("delete from handover_items where handover_id=#{id}")
    int deleteItems(long id);
    @Select("select id,handover_id,position,category,content,source_id from handover_items where handover_id=#{id} order by position,id")
    List<ItemRow> items(long id);

    @Insert("""
        insert into handover_events(handover_id,event_type,actor_id,reason,detail)
        values(#{handoverId},#{eventType},#{actorId},#{reason},cast(#{detail} as jsonb))
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insertEvent(EventRow event);
    String EVENT_SELECT="""
        select e.id,e.event_type,e.actor_id,e.reason,e.detail::text detail,e.created_at,u.display_name actor_name
        from handover_events e join users u on u.id=e.actor_id
        """;
    @Select(EVENT_SELECT+" where e.handover_id=#{id} order by e.id")
    List<EventRow> events(long id);

    @Select("""
        select u.id,u.display_name,u.username,coalesce(string_agg(r.role_code,',' order by r.role_code),'') role_codes
        from users u join user_roles ur on ur.user_id=u.id join roles r on r.id=ur.role_id
        where u.enabled group by u.id,u.display_name,u.username order by u.id
        """)
    List<CandidateRow> candidates();
}
