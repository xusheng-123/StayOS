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
class RoomChangeSchemaTest {
 @Autowired JdbcTemplate db; @Autowired Flyway flyway;
 long actor,stay,a,b,old,next,change;
 void fixture() {
  actor=db.queryForObject("insert into users(username,password_hash,display_name) values('s7_schema',?,'约束员工') returning id",Long.class,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(java.util.UUID.randomUUID().toString()));
  long type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('S7_SCHEMA','约束房型',2) returning id",Long.class);
  a=db.queryForObject("insert into rooms(room_number,floor,room_type_id) values('S7_SCHEMA_A',7,?) returning id",Long.class,type);
  b=db.queryForObject("insert into rooms(room_number,floor,room_type_id) values('S7_SCHEMA_B',7,?) returning id",Long.class,type);
  stay=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now()-interval '1 day',now()+interval '1 day',?) returning id",Long.class,actor);
  old=db.queryForObject("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at,exited_at,end_reason) values(?,?,'A','类型',now()-interval '1 day',now(),'ROOM_CHANGE') returning id",Long.class,stay,a);
  next=db.queryForObject("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at) values(?,?,'B','类型',now()) returning id",Long.class,stay,b);
 }
 long insert(long from,long to,long segment,long target,String time) {
  return db.queryForObject("insert into room_changes(stay_record_id,old_room_id,new_room_id,old_segment_id,new_segment_id,reason_type,reason,changed_at,operated_by,old_room_number,new_room_number,old_room_type_name,new_room_type_name) values(?,?,?,?,?,'GUEST_REQUEST','虚构约束测试',"+time+",?,'A','B','类型','类型') returning id",Long.class,stay,from,to,segment,target,actor);
 }
 @Test void migrationAndExistingActiveSegmentIndexesRemain() {
  assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
  assertThat(db.queryForList("select version from flyway_schema_history where success",String.class)).contains("11","12");
  assertThat(db.queryForList("select indexname from pg_indexes where tablename='stay_room_segments'",String.class)).contains("uq_stay_active_segment","uq_room_active_segment");
 }
 @Test void validChangeAndTaskRequireRealTuple() { fixture();change=insert(a,b,old,next,"now()");
  db.update("insert into housekeeping_tasks(room_id,task_type,source,source_id,room_change_id,stay_record_id,room_segment_id,created_by) values(?,'ROOM_CHANGE_CLEANING','ROOM_CHANGE',?,?,?,?,?)",a,change,change,stay,old,actor);
  assertThat(db.queryForObject("select count(*) from housekeeping_tasks where room_change_id=?",Integer.class,change)).isEqualTo(1);
 }
 @Test void sameRoomRejected() { fixture();assertThatThrownBy(()->insert(a,a,old,next,"now()")).isInstanceOf(DataIntegrityViolationException.class); }
 @Test void mismatchedSegmentRoomRejected() { fixture();assertThatThrownBy(()->insert(b,a,old,next,"now()")).isInstanceOf(DataIntegrityViolationException.class); }
 @Test void timeGapRejected() { fixture();assertThatThrownBy(()->insert(a,b,old,next,"now()+interval '1 second'")).isInstanceOf(DataIntegrityViolationException.class); }
 @Test void sameOldSegmentCannotProduceTwoChanges() { fixture();insert(a,b,old,next,"now()");assertThatThrownBy(()->insert(a,b,old,next,"now()")).isInstanceOf(DataIntegrityViolationException.class); }
 @Test void roomChangeTaskCannotFakeSource() { fixture();change=insert(a,b,old,next,"now()");assertThatThrownBy(()->db.update("insert into housekeeping_tasks(room_id,task_type,source,source_id,room_change_id,stay_record_id,room_segment_id,created_by) values(?,'ROOM_CHANGE_CLEANING','ROOM_CHANGE',?,?,?,?,?)",a,change+999,change,stay,old,actor)).isInstanceOf(DataIntegrityViolationException.class); }
 @Test void roomChangeTaskCannotOmitRealChange() { fixture();assertThatThrownBy(()->db.update("insert into housekeeping_tasks(room_id,task_type,source,source_id,stay_record_id,room_segment_id,created_by) values(?,'ROOM_CHANGE_CLEANING','ROOM_CHANGE',999,?,?,?)",a,stay,old,actor)).isInstanceOf(DataIntegrityViolationException.class); }
}
