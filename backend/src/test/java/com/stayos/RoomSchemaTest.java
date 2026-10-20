package com.stayos;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class RoomSchemaTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    long type() {
        return jdbc.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('SCHEMA_ROOM','测试房型',2) returning id", Long.class);
    }
    @Test void incrementalMigrationIsRepeatableAndAuthenticationSurvives() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("stayos_test");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from roles", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class))
            .contains("1", "2", "3");
    }
    @Test void newRoomIsVacantDirtyAndUnavailableAndLeadingZeroIsPreserved() {
        long type = type();
        jdbc.update("insert into rooms(room_number,floor,room_type_id) values('0801',8,?)", type);
        var room = jdbc.queryForMap("select * from room_status where room_number='0801'");
        assertThat(room).containsEntry("room_number", "0801").containsEntry("occupied", false)
            .containsEntry("cleaning_status", "DIRTY").containsEntry("maintenance_status", "NORMAL")
            .containsEntry("can_check_in", false).containsEntry("availability", "DIRTY");
        jdbc.update("insert into rooms(room_number,floor,room_type_id) values('801',8,?)", type);
        assertThat(jdbc.queryForObject("select count(*) from rooms where room_type_id=?", Integer.class, type)).isEqualTo(2);
    }
    @Test void cleanEnabledRoomIsAvailableAndDisabledRoomIsNot() {
        long type = type();
        jdbc.update("insert into rooms(room_number,floor,room_type_id,cleaning_status) values('SCHEMA_A',-1,?,'CLEAN')", type);
        assertThat(jdbc.queryForObject("select can_check_in from room_status where room_number='SCHEMA_A'", Boolean.class)).isTrue();
        jdbc.update("update rooms set enabled=false where room_number='SCHEMA_A'");
        assertThat(jdbc.queryForMap("select can_check_in,availability from room_status where room_number='SCHEMA_A'"))
            .containsEntry("can_check_in", false).containsEntry("availability", "DISABLED");
    }
    // A failed PostgreSQL statement aborts its transaction, so each violation has its own test.
    @Test void duplicateTypeCodeIsRejected() {
        type();
        assertThatThrownBy(this::type).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void duplicateRoomNumberIsRejected() {
        long type = type();
        jdbc.update("insert into rooms(room_number,floor,room_type_id) values('SCHEMA_B',1,?)", type);
        assertThatThrownBy(() -> jdbc.update("insert into rooms(room_number,floor,room_type_id) values('SCHEMA_B',2,?)", type))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void referencedTypeCannotBeDeleted() {
        long type = type();
        jdbc.update("insert into rooms(room_number,floor,room_type_id) values('SCHEMA_C',1,?)", type);
        assertThatThrownBy(() -> jdbc.update("delete from room_types where id=?", type)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void zeroFloorIsRejected() {
        long type = type();
        assertThatThrownBy(() -> jdbc.update("insert into rooms(room_number,floor,room_type_id) values('SCHEMA_D',0,?)", type))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void unknownCleaningStatusIsRejected() {
        long type = type();
        assertThatThrownBy(() -> jdbc.update("insert into rooms(room_number,floor,room_type_id,cleaning_status) values('SCHEMA_E',1,?,'UNKNOWN')", type))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
