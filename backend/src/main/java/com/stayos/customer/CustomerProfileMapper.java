package com.stayos.customer;

import com.stayos.customer.CustomerDtos.*;
import org.apache.ibatis.annotations.*;
import java.util.Map;

@Mapper
public interface CustomerProfileMapper {
    class Filter {
        public String query,status; public int page,pageSize;
        public long getOffset() { return (long)(page-1)*pageSize; }
    }
    @Select("""
        <script>select id,display_name,document_type,document_mask,phone_mask,notes,status,merged_into_id,version,created_at,updated_at
        from customer_profiles where 1=1
        <if test="q.query != null and q.query != ''"> and strpos(display_name,#{q.query})&gt;0</if>
        <if test="q.status != 'ALL'"> and status=#{q.status}</if>
        order by id desc limit #{q.pageSize} offset #{q.offset}</script>
        """)
    java.util.List<ProfileView> list(@Param("q") Filter q);
    @Select("""
        <script>select count(*) from customer_profiles where 1=1
        <if test="q.query != null and q.query != ''"> and strpos(display_name,#{q.query})&gt;0</if>
        <if test="q.status != 'ALL'"> and status=#{q.status}</if></script>
        """)
    long count(@Param("q") Filter q);

    @Select("select id,display_name,document_type,document_mask,phone_mask,notes,status,merged_into_id,version,created_at,updated_at from customer_profiles where id=#{id}")
    ProfileView row(@Param("id") long id);
    @Select("select id,display_name,document_mask from customer_profiles where id=#{id}")
    MergedTarget mergedInto(@Param("id") long id);

    @Insert("""
        insert into customer_profiles(display_name,document_type,document_ciphertext,document_mask,phone_ciphertext,phone_mask,notes,encryption_key_id,created_by)
        values(#{row.name},#{row.documentType},#{row.documentCiphertext},#{row.documentMask},#{row.phoneCiphertext},#{row.phoneMask},#{row.notes},'v1',#{actor})
        """) @Options(useGeneratedKeys=true,keyProperty="row.id")
    int insert(@Param("actor") long actor,@Param("row") Map<String,Object> row);

    @Update("""
        update customer_profiles set display_name=#{name},document_type=#{documentType},
        document_ciphertext=#{documentCiphertext},document_mask=#{documentMask},
        phone_ciphertext=#{phoneCiphertext},phone_mask=#{phoneMask},notes=#{notes},
        version=version+1,updated_at=clock_timestamp()
        where id=#{id} and version=#{expected} and status<>'MERGED'
        """)
    int update(@Param("id") long id,@Param("expected") long expected,@Param("name") String name,@Param("documentType") String documentType,
        @Param("documentCiphertext") String documentCiphertext,@Param("documentMask") String documentMask,
        @Param("phoneCiphertext") String phoneCiphertext,@Param("phoneMask") String phoneMask,@Param("notes") String notes);

    @Update("update customer_profiles set status=#{status},version=version+1,updated_at=clock_timestamp() where id=#{id} and status<>'MERGED'")
    int setStatus(@Param("id") long id,@Param("status") String status);

    @Update("update customer_profiles set status='MERGED',merged_into_id=#{target},version=version+1,updated_at=clock_timestamp() where id=#{id} and status<>'MERGED'")
    int merge(@Param("id") long id,@Param("target") long target);

    /** Safe because one registration snapshot belongs to at most one profile (unique guest link). */
    @Update("update customer_profile_stays set profile_id=#{target} where profile_id=#{source}")
    int moveLinks(@Param("source") long source,@Param("target") long target);

    /** Informational same-name hint only; never an automatic identity decision. */
    @Select("select id,display_name,document_mask,phone_mask,status from customer_profiles where display_name=#{name} and status='ACTIVE' order by id limit 5")
    java.util.List<SimilarProfile> similarActive(@Param("name") String name);

    @Insert("insert into customer_profile_stays(profile_id,stay_record_id,guest_id,associated_by) values(#{profile},#{stay},#{guest},#{actor})")
    @Options(useGeneratedKeys=true,keyProperty="link.id")
    int insertLink(@Param("actor") long actor,@Param("profile") long profile,@Param("stay") long stay,@Param("guest") long guest,@Param("link") Map<String,Object> link);
    @Select("select id,profile_id from customer_profile_stays where guest_id=#{guest}")
    LinkRow linkByGuest(@Param("guest") long guest);
    @Select("""
        select l.id,profile_id,stay_record_id,guest_id from customer_profile_stays l
        where l.profile_id=#{profile} and l.stay_record_id=#{stay} and (CAST(#{guest} AS bigint) is null or l.guest_id=#{guest}) limit 1
        """)
    LinkRow findLink(@Param("profile") long profile,@Param("stay") long stay,@Param("guest") Long guest);
    @Select("select id,profile_id,stay_record_id,guest_id from customer_profile_stays where id=#{id}")
    LinkRow link(@Param("id") long id);
    @Delete("delete from customer_profile_stays where id=#{id}")
    int deleteLink(@Param("id") long id);
    class LinkRow { public long id,profileId,stayRecordId,guestId; }

    @Select("select count(*) from stay_records where id=#{stay}")
    int stayExists(@Param("stay") long stay);
    @Select("""
        select sg.guest_id,g.display_name guest_name,sg.is_primary
        from stay_guests sg join guests g on g.id=sg.guest_id
        where sg.stay_record_id=#{stay} and (CAST(#{guest} AS bigint) is null or sg.guest_id=#{guest})
        order by sg.is_primary desc, sg.position limit 1
        """)
    StayGuest guestOfStay(@Param("stay") long stay,@Param("guest") Long guest);
    class StayGuest { public long guestId; public String guestName; public boolean primary; }

    @Select("""
        select l.id link_id,l.stay_record_id,l.guest_id,g.display_name guest_name,sg.is_primary,
        s.status stay_status,s.check_in_at,s.expected_check_out_at,
        (select room_number_snapshot from stay_room_segments seg where seg.stay_record_id=s.id order by seg.entered_at desc limit 1) room_number
        from customer_profile_stays l
        join stay_records s on s.id=l.stay_record_id
        join guests g on g.id=l.guest_id
        join stay_guests sg on sg.stay_record_id=l.stay_record_id and sg.guest_id=l.guest_id
        where l.id=#{id}
        """)
    StayLinkView linkView(@Param("id") long id);
    @Select("""
        select l.id link_id,l.stay_record_id,l.guest_id,g.display_name guest_name,sg.is_primary,
        s.status stay_status,s.check_in_at,s.expected_check_out_at,
        (select room_number_snapshot from stay_room_segments seg where seg.stay_record_id=s.id order by seg.entered_at desc limit 1) room_number
        from customer_profile_stays l
        join stay_records s on s.id=l.stay_record_id
        join guests g on g.id=l.guest_id
        join stay_guests sg on sg.stay_record_id=l.stay_record_id and sg.guest_id=l.guest_id
        where l.profile_id=#{id}
        order by s.check_in_at desc, l.id desc
        """)
    java.util.List<StayLinkView> staysOf(@Param("id") long id);
}
