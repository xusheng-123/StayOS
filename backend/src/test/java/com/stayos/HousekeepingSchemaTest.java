package com.stayos;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test") @Transactional
class HousekeepingSchemaTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Test void migrationValidatesAndPreservesExistingReferenceConstraints() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid='housekeeping_tasks'::regclass",String.class))
            .contains("fk_task_segment_business","uq_housekeeping_source","uq_housekeeping_checkout_segment","ck_task_source","ck_task_workflow");
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname='uq_room_open_cleaning_task'",String.class)).contains("PENDING","IN_PROGRESS");
        assertThat(jdbc.queryForObject("select definition from pg_views where viewname='room_operational_facts'",String.class)).contains("PENDING","IN_PROGRESS");
        assertThat(jdbc.queryForObject("select count(*) from roles",Integer.class)).isEqualTo(5);
    }

    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void upgradeFromV8PreservesExistingCheckoutTaskAndBackfillsRealHistory() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String schema="s5_upgrade_"+java.util.UUID.randomUUID().toString().replace("-","");
        var datasource=jdbc.getDataSource();assertThat(datasource).isNotNull();
        jdbc.execute("create schema "+schema);
        try {
            var initial=Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).target("8").load();initial.migrate();
            try(var connection=datasource.getConnection()) {
                String previousSchema=connection.getSchema();
                try {
                connection.setSchema(schema);var db=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
                String hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(java.util.UUID.randomUUID().toString());
                long actor=db.queryForObject("insert into users(username,password_hash,display_name) values('upgrade_test',?,'虚构迁移员工') returning id",Long.class,hash);
                long type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('UPGRADE','迁移房型',2) returning id",Long.class);
                long room=db.queryForObject("insert into rooms(room_number,floor,room_type_id) values('0801',8,?) returning id",Long.class,type);
                long stay=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,actual_check_out_at,status,created_by,checked_out_by) values(now()-interval '2 days',now()+interval '1 day',now()-interval '1 day','CHECKED_OUT',?,?) returning id",Long.class,actor,actor);
                long segment=db.queryForObject("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at,exited_at,end_reason) values(?,?,'0801','迁移房型',now()-interval '2 days',now()-interval '1 day','CHECK_OUT') returning id",Long.class,stay,room);
                long task=db.queryForObject("insert into housekeeping_tasks(room_id,task_type,source,source_id,stay_record_id,room_segment_id,created_by) values(?,'CHECKOUT_CLEANING','CHECKOUT',?,?,?,?) returning id",Long.class,room,stay,stay,segment,actor);
                db.update("insert into idempotency_requests(operator_id,operation,request_key,request_hash,result_stay_id) values(?,'CHECK_OUT',gen_random_uuid(),repeat('a',64),?)",actor,stay);
                var before=db.queryForMap("select * from housekeeping_tasks where id=?",task);
                var upgrade=Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).load();assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(8);upgrade.validate();
                assertThat(db.queryForMap("select * from housekeeping_tasks where id=?",task)).containsAllEntriesOf(before);
                assertThat(db.queryForMap("select action,to_status,operator_id,task_version from housekeeping_task_logs where task_id=?",task)).containsEntry("action","AUTO_CREATE").containsEntry("to_status","PENDING").containsEntry("operator_id",actor).containsEntry("task_version",0L);
                assertThat(db.queryForObject("select l.operated_at=t.created_at from housekeeping_task_logs l join housekeeping_tasks t on t.id=l.task_id",Boolean.class)).isTrue();
                assertThat(db.queryForObject("select result_stay_id from idempotency_requests",Long.class)).isEqualTo(stay);
                assertThat(db.queryForObject("select room_number from rooms where id=?",String.class,room)).isEqualTo("0801");
                } finally { connection.setSchema(previousSchema); }
            }
        } finally {
            // Only this freshly generated schema inside the isolated test database is removed.
            if(!schema.matches("s5_upgrade_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test schema");
            jdbc.execute("drop schema "+schema+" cascade");
        }
    }
}
