package com.stayos.stay.mapper;

import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.*;

@Mapper
public interface GuestCorrectionMapper {
    class Guest {
        public Long id,version;
        public String displayName,documentType,documentCiphertext,documentMask,phoneCiphertext,phoneMask,encryptionKeyId;
    }
    class Fact {
        public Long id,stayRecordId,guestId,guestVersion,operatorId;
        public String operatorName,reason,changedFields,beforeSummary,afterSummary,beforeCiphertext,afterCiphertext;
        public OffsetDateTime operatedAt;
    }
    @Select("select g.* from guests g join stay_guests sg on sg.guest_id=g.id where sg.stay_record_id=#{stay} order by g.id for update of g")
    List<Guest> lockGuests(long stay);
    @Select("select count(*) from stay_guests where guest_id=#{guest}")
    long registrations(long guest);
    @Update("""
        update guests set display_name=#{name},document_type=#{type},document_ciphertext=#{document},document_mask=#{documentMask},
            phone_ciphertext=#{phone},phone_mask=#{phoneMask},version=version+1 where id=#{id} and version=#{version}
        """)
    int update(@Param("id") long id,@Param("version") long version,@Param("name") String name,@Param("type") String type,
               @Param("document") String document,@Param("documentMask") String documentMask,@Param("phone") String phone,@Param("phoneMask") String phoneMask);
    @Insert("""
        insert into guest_corrections(stay_record_id,guest_id,guest_version,operator_id,reason,operated_at,changed_fields,before_summary,after_summary,before_ciphertext,after_ciphertext)
        values(#{stayRecordId},#{guestId},#{guestVersion},#{operatorId},#{reason},#{operatedAt},cast(#{changedFields} as jsonb),cast(#{beforeSummary} as jsonb),cast(#{afterSummary} as jsonb),#{beforeCiphertext},#{afterCiphertext})
        """) @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(Fact fact);
    // Receipt reads intentionally never select encrypted snapshots.
    String VIEW="select c.id,c.stay_record_id,c.guest_id,c.guest_version,c.operator_id,u.display_name operator_name,c.reason,c.operated_at,c.changed_fields::text,c.before_summary::text,c.after_summary::text from guest_corrections c join users u on u.id=c.operator_id";
    @Select(VIEW+" where c.id=#{id}") Fact detail(long id);
    @Select(VIEW+" where c.stay_record_id=#{stay} and c.guest_id=#{guest} order by c.id desc limit #{size} offset #{offset}")
    List<Fact> history(@Param("stay") long stay,@Param("guest") long guest,@Param("size") int size,@Param("offset") long offset);
    @Select("select count(*) from guest_corrections where stay_record_id=#{stay} and guest_id=#{guest}")
    long count(@Param("stay") long stay,@Param("guest") long guest);
    @Select("select exists(select 1 from stay_guests where stay_record_id=#{stay} and guest_id=#{guest})")
    boolean belongs(@Param("stay") long stay,@Param("guest") long guest);
}
