package com.stayos;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE) @ActiveProfiles("test") @Transactional
class IssueSchemaTest {
 @Autowired JdbcTemplate jdbc; @Autowired Flyway flyway;
 long actor() { return jdbc.queryForObject("insert into users(username,password_hash,display_name) values('issue_schema',?,'虚构约束员工') returning id",Long.class,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(java.util.UUID.randomUUID().toString())); }
 long room() { long t=jdbc.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('ISSUE_SCHEMA','约束房型',2) returning id",Long.class);return jdbc.queryForObject("insert into rooms(room_number,floor,room_type_id) values('S6_SCHEMA',6,?) returning id",Long.class,t); }
 @Test void migrationAndDerivedFactsHaveOneMaintenanceSource() {
  assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
  assertThat(jdbc.queryForList("select version from flyway_schema_history where success",String.class)).contains("10","11");
  assertThat(jdbc.queryForObject("select definition from pg_views where viewname='room_operational_facts' and schemaname='public'",String.class)).contains("room_issues","room_maintenance_stops","CRITICAL","RESOLVED");
  assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and table_name='rooms' and column_name='maintenance_status'",Integer.class)).isZero();
 }
 @Test void unknownTypeRejectedByDatabase() { long r=room(),a=actor();assertThatThrownBy(()->jdbc.update("insert into room_issues(room_id,issue_type,severity,description,reported_by,reported_at) values(?,'BAD','LOW','测试',?,now())",r,a)).isInstanceOf(DataIntegrityViolationException.class); }
 @Test void unknownSeverityRejectedByDatabase() { long r=room(),a=actor();assertThatThrownBy(()->jdbc.update("insert into room_issues(room_id,issue_type,severity,description,reported_by,reported_at) values(?,'SAFETY','BAD','测试',?,now())",r,a)).isInstanceOf(DataIntegrityViolationException.class); }
 @Test void unknownRoomRejectedByDatabase() { long a=actor();assertThatThrownBy(()->jdbc.update("insert into room_issues(room_id,issue_type,severity,description,reported_by,reported_at) values(999999999,'SAFETY','LOW','测试',?,now())",a)).isInstanceOf(DataIntegrityViolationException.class); }
 @Test void crossRoomLinkedStopRejectedByDatabase() {
  long r=room(),a=actor(),t=jdbc.queryForObject("select room_type_id from rooms where id=?",Long.class,r);
  long second=jdbc.queryForObject("insert into rooms(room_number,floor,room_type_id) values('S6_SCHEMA_B',6,?) returning id",Long.class,t);
  long i=jdbc.queryForObject("insert into room_issues(room_id,issue_type,severity,description,reported_by,reported_at) values(?,'SAFETY','LOW','测试',?,now()) returning id",Long.class,r,a);
  assertThatThrownBy(()->jdbc.update("insert into room_maintenance_stops(room_id,issue_id,reason,stopped_by,stopped_at) values(?,?,'不能跨房关联',?,now())",second,i,a)).isInstanceOf(DataIntegrityViolationException.class);
 }
}
