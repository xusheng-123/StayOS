package com.stayos;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE) @ActiveProfiles("test")
class StayExtensionSchemaTest {
    @Autowired JdbcTemplate db;

    @Test void upgradeFromV12PreservesRealLegacyRowsAndBackfillsVersion()throws Exception {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String database="stayos_restore_ext_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database;
        String owner=System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),password=System.getenv("STAYOS_DB_PASSWORD");
        try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("create database "+database);}
        try {
            Flyway.configure().dataSource(url,owner,password).target("12").load().migrate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement()) {
                q.execute("insert into users(id,username,password_hash,display_name) values(900001,'legacy_extension','"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("fictional-password")+"','虚构旧版员工')");
                q.execute("insert into stay_records(id,check_in_at,expected_check_out_at,created_by,notes) values(900001,'2026-01-01T01:00:00Z','2026-01-02T01:00:00Z',900001,'legacy snapshot')");
            }
            var flyway=Flyway.configure().dataSource(url,owner,password).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);flyway.validate();
            assertThat(db.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name in ('shift_handovers','handover_items','handover_events')",Integer.class)).isEqualTo(3);
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery("select version,notes,expected_check_out_at from stay_records where id=900001")) {
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isZero();assertThat(r.getString(2)).isEqualTo("legacy snapshot");
                assertThat(r.getObject(3,java.time.OffsetDateTime.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-01-02T01:00:00Z"));
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("drop database if exists "+database+" with(force)");}}
    }
    @Test void migrationContainsExpectedVersionAndReceiptConstraints() {
        assertThatThrownBy(()->db.update("insert into stay_extensions(stay_record_id,segment_id,room_id,old_expected_check_out_at,new_expected_check_out_at,reason,operator_id,operated_at,stay_version) values(-1,-1,-1,now(),now()+interval '1 day','fixture',-1,now(),1)"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='stay_records'::regclass and pg_get_constraintdef(oid) like '%version >= 0%'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForList("select column_name from information_schema.columns where table_name='idempotency_requests'",String.class)).contains("result_stay_extension_id");
    }
    @ParameterizedTest @ValueSource(strings={"VERSION","TIME","PROJECTION","TUPLE"}) @Transactional
    void databaseRejectsInconsistentExtensionFacts(String kind) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        long actor=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'虚构约束员工') returning id",Long.class,"ext_schema_"+suffix,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(suffix));
        String code="EXS_"+suffix.toUpperCase(Locale.ROOT);
        long type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values(?,'虚构约束房型',2) returning id",Long.class,code);
        long room=db.queryForObject("insert into rooms(room_number,floor,room_type_id) values(?,1,?) returning id",Long.class,code,type);
        long stay=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by,version) values(now()-interval '1 day',now()+interval '2 days',?,1) returning id",Long.class,actor);
        long segment=db.queryForObject("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at) values(?,?,'EXS','fixture',now()-interval '1 day') returning id",Long.class,stay,room);
        if(kind.equals("VERSION")) {
            assertThatThrownBy(()->db.update("update stay_records set version=-1 where id=?",stay)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            return;
        }
        String next=kind.equals("TIME")?"now()-interval '1 hour'":"now()+interval '2 days'";
        long version=kind.equals("PROJECTION")?2:1;
        long source=kind.equals("TUPLE")?room+999999:room;
        assertThatThrownBy(()->db.update("insert into stay_extensions(stay_record_id,segment_id,room_id,old_expected_check_out_at,new_expected_check_out_at,reason,operator_id,operated_at,stay_version) values(?,?,?,now()+interval '1 day',"+next+",'fictional',?,now(),?)",stay,segment,source,actor,version))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
