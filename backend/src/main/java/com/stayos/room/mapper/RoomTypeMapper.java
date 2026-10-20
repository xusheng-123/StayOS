package com.stayos.room.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stayos.room.entity.RoomType;
import com.stayos.room.dto.RoomTypeView;
import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface RoomTypeMapper extends BaseMapper<RoomType> {
    @Select("select * from room_types where id=#{id} for update")
    RoomType lock(long id);
    @Select("""
        <script>
        select t.*, (select count(*) from rooms r where r.room_type_id=t.id) room_count
        from room_types t
        <where>
          <if test="enabled != null">t.enabled=#{enabled}</if>
          <if test="search != null and search != ''">
            and (strpos(upper(t.type_name),#{search}) &gt; 0 or strpos(t.type_code,#{search}) &gt; 0)
          </if>
        </where>
        order by t.id limit #{limit} offset #{offset}
        </script>
        """)
    List<RoomTypeView> list(@Param("enabled") Boolean enabled, @Param("search") String search,
        @Param("limit") int limit, @Param("offset") long offset);
    @Select("""
        <script>
        select count(*) from room_types t <where>
          <if test="enabled != null">t.enabled=#{enabled}</if>
          <if test="search != null and search != ''">
            and (strpos(upper(t.type_name),#{search}) &gt; 0 or strpos(t.type_code,#{search}) &gt; 0)
          </if>
        </where>
        </script>
        """)
    long count(@Param("enabled") Boolean enabled, @Param("search") String search);
    @Update("""
        update room_types set type_code=#{typeCode},type_name=#{typeName},description=#{description},
        bed_description=#{bedDescription},suggested_occupancy=#{suggestedOccupancy},
        version=version+1,updated_at=current_timestamp where id=#{id}
        """)
    void updateInfo(RoomType type);
    @Update("update room_types set enabled=#{enabled},version=version+1,updated_at=current_timestamp where id=#{id}")
    void setEnabled(@Param("id") long id, @Param("enabled") boolean enabled);
}
