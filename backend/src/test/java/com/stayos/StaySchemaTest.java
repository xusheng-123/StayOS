package com.stayos;

import com.stayos.stay.service.GuestProtection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test") @Transactional
class StaySchemaTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired GuestProtection protection;
    long room() {
        long type=jdbc.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values(?, '虚构测试房型',2) returning id",Long.class,("SS"+UUID.randomUUID().toString().replace("-","")).toUpperCase(Locale.ROOT));
        return jdbc.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,1,?,'CLEAN') returning id",Long.class,("SS"+UUID.randomUUID().toString().substring(0,8)).toUpperCase(Locale.ROOT),type);
    }
    long actor() { return jdbc.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'测试员工') returning id",Long.class,"ss_"+UUID.randomUUID(),"$2a$12$"+"a".repeat(53)); }
    long stay() { return jdbc.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now(),now()+interval '1 day',?) returning id",Long.class,actor()); }
    void segment(long stay,long room) { jdbc.update("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at) values(?,?,'TEST','测试房型',now())",stay,room); }
    @Test void migrationRepeatableAndExistingRolesPreserved() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from roles",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select success from flyway_schema_history where version='4'",Boolean.class)).isTrue();
    }
    @Test void occupancyAndHistoryComeFromSegmentsWithoutChangingCleaning() {
        long room=room(); assertThat(jdbc.queryForObject("select can_check_in from room_status where id=?",Boolean.class,room)).isTrue();
        segment(stay(),room);
        assertThat(jdbc.queryForMap("select occupied,has_usage_history,can_check_in,cleaning_status from room_status where id=?",room))
            .containsEntry("occupied",true).containsEntry("has_usage_history",true).containsEntry("can_check_in",false).containsEntry("cleaning_status","CLEAN");
        jdbc.update("update stay_room_segments set exited_at=entered_at,end_reason='CHECK_OUT' where room_id=?",room);
        assertThat(jdbc.queryForMap("select occupied,has_usage_history from room_status where id=?",room)).containsEntry("occupied",false).containsEntry("has_usage_history",true);
    }
    @Test void sameRoomCannotHaveTwoOpenSegments() {
        long room=room();segment(stay(),room);
        assertThatThrownBy(()->segment(stay(),room)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void sameStayCannotHaveTwoOpenSegments() {
        long stay=stay();segment(stay,room());
        assertThatThrownBy(()->segment(stay,room())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void expectedCheckoutMustBeLater() {
        long actor=actor();
        assertThatThrownBy(()->jdbc.update("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now(),now(),?)",actor)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void danglingRoomForeignKeyRejected() {
        long stay=stay();assertThatThrownBy(()->segment(stay,-1)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void endReasonWithoutExitTimeIsRejected() {
        long stay=stay(),room=room();segment(stay,room);
        assertThatThrownBy(()->jdbc.update("update stay_room_segments set end_reason='CHECK_OUT' where stay_record_id=?",stay)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void encryptionIsRandomizedMaskedAndKeyRequired() {
        String first=protection.encrypt("FICTIONAL-DOCUMENT-001","document");
        assertThat(first).startsWith("v1.").doesNotContain("FICTIONAL").isNotEqualTo(protection.encrypt("FICTIONAL-DOCUMENT-001","document"));
        try {
            var parts=first.split("\\.");var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"),new javax.crypto.spec.GCMParameterSpec(128,Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD("StayOS/v1/document".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("FICTIONAL-DOCUMENT-001");
        } catch(java.security.GeneralSecurityException failure) { throw new AssertionError(failure); }
        assertThat(protection.mask("FICTIONAL-DOCUMENT-001")).isEqualTo("FI****01");
        assertThat(protection.mask("ABC")).isEqualTo("****");
        assertThat(protection.fingerprint("sample".getBytes())).hasSize(64).isEqualTo(protection.fingerprint("sample".getBytes()));
        assertThatThrownBy(()->new GuestProtection("")).isInstanceOf(IllegalStateException.class);
    }
}
