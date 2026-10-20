package com.stayos.room.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stayos.room.entity.Room;
import com.stayos.room.dto.*;
import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface RoomMapper extends BaseMapper<Room> {
    String COLUMNS = """
        r.*,t.type_name room_type_name,t.type_code room_type_code,t.enabled room_type_enabled
        """;
    String FILTER = """
        <where>
          <if test="q.roomNumber != null and q.roomNumber != ''">strpos(r.room_number,#{q.roomNumber}) &gt; 0</if>
          <if test="q.floor != null">and r.floor=#{q.floor}</if>
          <if test="q.roomTypeId != null">and r.room_type_id=#{q.roomTypeId}</if>
          <if test="q.availability != null">and r.availability=#{q.availability}</if>
          <if test="q.cleaningStatus != null">and r.cleaning_status=#{q.cleaningStatus}</if>
          <if test="q.occupancyStatus != null">and r.occupancy_status=#{q.occupancyStatus}</if>
          <if test="q.maintenanceStatus != null">and r.maintenance_status=#{q.maintenanceStatus}</if>
          <if test="q.enabled != null">and r.enabled=#{q.enabled}</if>
          <if test="q.canCheckIn != null">and r.can_check_in=#{q.canCheckIn}</if>
        </where>
        """;
    @Select("select * from rooms where id=#{id} for update")
    Room lock(long id);
    @Select("select " + COLUMNS + " from room_status r join room_types t on t.id=r.room_type_id where r.id=#{id}")
    RoomView detail(long id);
    @Select("<script>select " + COLUMNS + " from room_status r join room_types t on t.id=r.room_type_id " + FILTER +
        " order by r.floor,r.room_number,r.id limit #{q.pageSize} offset #{offset}</script>")
    List<RoomView> list(@Param("q") RoomFilter query, @Param("offset") long offset);
    @Select("<script>select count(*) from room_status r " + FILTER + "</script>")
    long count(@Param("q") RoomFilter query);
    @Select("<script>select count(*) total, count(*) filter(where r.can_check_in) available," +
        "count(*) filter(where r.occupied) occupied, count(*) filter(where r.cleaning_status='DIRTY') dirty," +
        "count(*) filter(where r.cleaning_status='CLEANING') cleaning," +
        "count(*) filter(where r.cleaning_status='INSPECTION') inspection," +
        "count(*) filter(where r.has_blocking_issue) out_of_service," +
        "count(*) filter(where not r.enabled) disabled from room_status r " + FILTER + "</script>")
    RoomStatistics statistics(@Param("q") RoomFilter query);
    @Select("select floor,count(*) room_count from rooms group by floor order by floor")
    List<com.stayos.room.service.RoomStatusService.Floor> floors();
    @Update("""
        update rooms set room_number=#{roomNumber},floor=#{floor},room_type_id=#{roomTypeId},notes=#{notes},
        version=version+1,updated_at=current_timestamp where id=#{id}
        """)
    void updateInfo(Room room);
    @Update("update rooms set enabled=#{enabled},version=version+1,updated_at=current_timestamp where id=#{id}")
    void setEnabled(@Param("id") long id, @Param("enabled") boolean enabled);
}
