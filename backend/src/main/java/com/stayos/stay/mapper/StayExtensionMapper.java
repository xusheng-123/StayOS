package com.stayos.stay.mapper;

import com.stayos.stay.dto.StayExtensionDtos.Result;
import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface StayExtensionMapper {
    class LockedStay {
        public Long id, version;
        public String status;
        public OffsetDateTime checkInAt, expectedCheckOutAt;
    }
    @Select("select id,status,version,check_in_at,expected_check_out_at from stay_records where id=#{id} for update")
    LockedStay lock(long id);

    @Update("""
        update stay_records set expected_check_out_at=#{departure},version=version+1,updated_at=#{time}
        where id=#{id} and version=#{version} and status='IN_HOUSE'
        """)
    int update(@Param("id") long id, @Param("version") long version,
               @Param("departure") OffsetDateTime departure, @Param("time") OffsetDateTime time);

    @Insert("""
        insert into stay_extensions(stay_record_id,segment_id,room_id,old_expected_check_out_at,
            new_expected_check_out_at,reason,operator_id,operated_at,stay_version)
        values(#{stayRecordId},#{segmentId},#{roomId},#{oldExpectedCheckOutAt},
            #{newExpectedCheckOutAt},#{reason},#{operatorId},#{operatedAt},#{stayVersion})
        """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(Result result);

    String VIEW = """
        select e.*,seg.room_number_snapshot room_number,u.display_name operator_name
        from stay_extensions e join stay_room_segments seg on seg.id=e.segment_id
        join users u on u.id=e.operator_id
        """;
    @Select(VIEW + " where e.id=#{id}")
    Result detail(long id);
    @Select(VIEW + " where e.stay_record_id=#{stay} order by e.id desc limit #{limit} offset #{offset}")
    List<Result> history(@Param("stay") long stay, @Param("limit") int limit, @Param("offset") long offset);
    @Select("select count(*) from stay_extensions where stay_record_id=#{stay}")
    long count(long stay);
}
