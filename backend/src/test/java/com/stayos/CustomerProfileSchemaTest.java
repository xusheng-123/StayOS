package com.stayos;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE) @ActiveProfiles("test")
class CustomerProfileSchemaTest {
    @Autowired JdbcTemplate db;
    @Autowired Flyway flyway;
    static final org.springframework.security.crypto.password.PasswordEncoder ENCODER=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);

    @Test void upgradeFromV19CreatesCustomerProfileModelAndPreservesHistory()throws Exception {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String database="stayos_restore_cp_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database;
        String owner=System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),password=System.getenv("STAYOS_DB_PASSWORD");
        try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("create database "+database);}
        try {
            Flyway.configure().dataSource(url,owner,password).target("19").load().migrate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement()){
                q.execute("insert into users(id,username,password_hash,display_name) values(900021,'legacy_profile','"+ENCODER.encode("fictional-password")+"','虚构旧版员工')");
                q.execute("insert into stay_records(id,check_in_at,expected_check_out_at,created_by) values(900021,now(),now()+interval '1 day',900021)");
                q.execute("insert into guests(id,display_name,document_type,document_ciphertext,document_mask,encryption_key_id)"+
                    " values(900021,'虚构旧版住客','ID_CARD','v1.legacy.fictional','11****22','v1')");
                q.execute("insert into stay_guests(stay_record_id,guest_id,is_primary,position) values(900021,900021,true,0)");
            }
            var flyway=Flyway.configure().dataSource(url,owner,password).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);flyway.validate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.tables where table_schema='public' and table_name in "+
                "('customer_profiles','customer_profile_stays')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(2);
            }
            // The profile module must not touch per-stay history: the legacy registration survives intact.
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from stay_guests where stay_record_id=900021 and guest_id=900021 and is_primary")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(1);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("drop database if exists "+database+" with(force)");}}
    }

    @Test void migrationContainsExpectedCustomerProfileConstraints() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        // Status whitelist and merge consistency are database-enforced, not only service-enforced.
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='customer_profiles'::regclass and conname in "+
            "('customer_profiles_status_check','customer_profiles_merge_target_check','customer_profiles_merge_state_check')",Integer.class)).isEqualTo(3);
        // Merge is terminal and profiles are never deleted: both facts live in triggers.
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='customer_profiles'::regclass "+
            "and not tgisinternal and tgname in ('customer_profile_transition','customer_profiles_no_delete')",Integer.class)).isEqualTo(2);
        // A registration snapshot belongs to at most one profile.
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='customer_profile_stays_guest_id_key'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='customer_profiles'::regclass and contype='f'",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='customer_profile_stays'::regclass and contype='f'",Integer.class)).isEqualTo(4);
        // Six profile write operations joined the idempotency protocol with their own result column.
        String operationCheck=db.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid='idempotency_requests'::regclass and conname='idempotency_requests_operation_check'",String.class);
        for(String operation:new String[]{"CUSTOMER_PROFILE_CREATE","CUSTOMER_PROFILE_UPDATE","CUSTOMER_PROFILE_SET_STATUS","CUSTOMER_PROFILE_MERGE","CUSTOMER_PROFILE_STAY_LINK","CUSTOMER_PROFILE_STAY_UNLINK"})
            assertThat(operationCheck).contains(operation);
        assertThat(db.queryForObject("select count(*) from information_schema.columns where table_name='idempotency_requests' and column_name='result_customer_profile_id'",Integer.class)).isEqualTo(1);
        String kindCheck=db.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid='idempotency_requests'::regclass and conname='ck_request_result_kind'",String.class);
        assertThat(kindCheck).contains("result_customer_profile_id");
    }

    @Test @Transactional void statusWhitelistRejectsUnknownValues() {
        long actor=actor();
        assertThatThrownBy(()->db.update("insert into customer_profiles(display_name,document_type,document_ciphertext,document_mask,encryption_key_id,status,created_by)"+
            " values('虚构住客','ID_CARD','v1.x.fictional','11****22','v1','ARCHIVED',?)",actor)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional void mergedRowWithoutTargetRejected() {
        long actor=actor();
        assertThatThrownBy(()->db.update("insert into customer_profiles(display_name,document_type,document_ciphertext,document_mask,encryption_key_id,status,merged_into_id,created_by)"+
            " values('虚构住客','ID_CARD','v1.x.fictional','11****22','v1','MERGED',NULL,?)",actor)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional void selfMergeRejected() {
        long id=profile(actor());
        assertThatThrownBy(()->db.update("update customer_profiles set status='MERGED',merged_into_id=? where id=?",id,id))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional void mergedProfileCannotLeaveMergedState() {
        long actor=actor(),source=profile(actor),target=profile(actor);
        db.update("update customer_profiles set status='MERGED',merged_into_id=? where id=?",target,source);
        assertThatThrownBy(()->db.update("update customer_profiles set status='ACTIVE' where id=?",source))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional void mergedProfileTargetIsImmutable() {
        long actor=actor(),source=profile(actor),target=profile(actor),other=profile(actor);
        db.update("update customer_profiles set status='MERGED',merged_into_id=? where id=?",target,source);
        assertThatThrownBy(()->db.update("update customer_profiles set merged_into_id=? where id=?",other,source))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional void profilesAreNeverDeleted() {
        long id=profile(actor());
        assertThatThrownBy(()->db.update("delete from customer_profiles where id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional void oneRegistrationSnapshotBelongsToAtMostOneProfile() {
        long actor=actor(),stay=stay(actor),guest=guest(),linker=actor();
        db.update("insert into stay_guests(stay_record_id,guest_id,is_primary,position) values(?,?,true,0)",stay,guest);
        long first=profile(actor);
        db.update("insert into customer_profile_stays(profile_id,stay_record_id,guest_id,associated_by) values(?,?,?,?)",first,stay,guest,linker);
        long second=profile(actor);
        assertThatThrownBy(()->db.update("insert into customer_profile_stays(profile_id,stay_record_id,guest_id,associated_by) values(?,?,?,?)",second,stay,guest,linker))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long actor() { return db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'测试员工') returning id",Long.class,"cp_"+UUID.randomUUID(),"$2a$12$"+"a".repeat(53)); }
    private long profile(long actor) {
        return db.queryForObject("insert into customer_profiles(display_name,document_type,document_ciphertext,document_mask,encryption_key_id,created_by)"+
            " values('虚构住客','ID_CARD','v1.x.fictional','11****22','v1',?) returning id",Long.class,actor);
    }
    private long stay(long actor) { return db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now(),now()+interval '1 day',?) returning id",Long.class,actor); }
    private long guest() {
        return db.queryForObject("insert into guests(display_name,document_type,document_ciphertext,document_mask,encryption_key_id)"+
            " values('虚构住客','ID_CARD','v1.x.fictional','11****22','v1') returning id",Long.class);
    }
}
