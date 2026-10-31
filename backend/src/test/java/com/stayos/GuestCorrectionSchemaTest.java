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
class GuestCorrectionSchemaTest {
    @Autowired JdbcTemplate db;

    @Test void upgradeFromV13PreservesGuestCiphertextAndBackfillsVersion()throws Exception {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String database="stayos_restore_gcr_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database;
        String owner=System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),password=System.getenv("STAYOS_DB_PASSWORD");
        String legacyCipher=new com.stayos.stay.service.GuestProtection(Base64.getEncoder().encodeToString(new byte[32])).encrypt("LEGACY-DOCUMENT","document");
        try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("create database "+database);}
        try {
            Flyway.configure().dataSource(url,owner,password).target("13").load().migrate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement()) {
                q.execute("insert into users(id,username,password_hash,display_name) values(900001,'legacy_extension','"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("fictional-password")+"','虚构旧版员工')");
                q.execute("insert into stay_records(id,check_in_at,expected_check_out_at,created_by,notes) values(900001,'2026-01-01T01:00:00Z','2026-01-02T01:00:00Z',900001,'legacy snapshot')");
                q.execute("insert into guests(id,display_name,document_type,document_ciphertext,document_mask,phone_mask,encryption_key_id) values(900001,'legacy guest','OTHER','"+legacyCipher+"','LE****NT','','v1')");
                q.execute("insert into stay_guests(stay_record_id,guest_id,is_primary,position) values(900001,900001,true,0)");
            }
            var flyway=Flyway.configure().dataSource(url,owner,password).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);flyway.validate();
            assertThat(db.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name='shift_handovers'",Integer.class)).isEqualTo(1);
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery("select version,notes,expected_check_out_at from stay_records where id=900001")) {
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isZero();assertThat(r.getString(2)).isEqualTo("legacy snapshot");
                assertThat(r.getObject(3,java.time.OffsetDateTime.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-01-02T01:00:00Z"));
            }
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery("select version,document_ciphertext from guests where id=900001")) {
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isZero();assertThat(r.getString(2)).isEqualTo(legacyCipher);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("drop database if exists "+database+" with(force)");}}
    }
    @ParameterizedTest @ValueSource(strings={"VERSION","FIELDS","PROJECTION","TUPLE","ENDED","SHARED"}) @Transactional
    void databaseRejectsInvalidCorrectionFactsAndSharedSnapshots(String kind) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        long actor=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'虚构约束员工') returning id",Long.class,"gcr_schema_"+suffix,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(suffix));
        long stay=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now()-interval '1 day',now()+interval '1 day',?) returning id",Long.class,actor);
        long guest=db.queryForObject("insert into guests(display_name,document_type,document_ciphertext,document_mask,phone_mask,encryption_key_id,version) values('fixture','OTHER','v1.fixture.cipher','FI****RE','','v1',1) returning id",Long.class);
        db.update("insert into stay_guests(stay_record_id,guest_id,is_primary,position) values(?,?,true,0)",stay,guest);
        if(kind.equals("SHARED")) {
            long other=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now()-interval '1 day',now()+interval '1 day',?) returning id",Long.class,actor);
            assertThatThrownBy(()->db.update("insert into stay_guests(stay_record_id,guest_id,is_primary,position) values(?,?,true,0)",other,guest)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);return;
        }
        if(kind.equals("VERSION")) {assertThatThrownBy(()->db.update("update guests set version=-1 where id=?",guest)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);return;}
        if(kind.equals("ENDED")) db.update("update stay_records set status='CHECKED_OUT',actual_check_out_at=now(),checked_out_by=? where id=?",actor,stay);
        String fields=kind.equals("FIELDS")?"[\"primary\"]":"[\"name\"]";
        long version=kind.equals("PROJECTION")?2:1;
        long source=kind.equals("TUPLE")?guest+999999:guest;
        assertThatThrownBy(()->db.update("insert into guest_corrections(stay_record_id,guest_id,guest_version,operator_id,reason,operated_at,changed_fields,before_summary,after_summary,before_ciphertext,after_ciphertext) values(?,?,?,?,'fixture',now(),cast(? as jsonb),'{}','{\"documentType\":\"OTHER\",\"documentNumber\":\"FI****RE\",\"phone\":\"\"}','v1.fixture.before','v1.fixture.after')",stay,source,version,actor,fields))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
