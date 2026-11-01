package com.stayos;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class ChannelSchemaTest {
    @Autowired JdbcTemplate db;
    static final org.springframework.security.crypto.password.PasswordEncoder ENCODER=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);

    @Test void upgradeFromV18CreatesChannelModelAndPreservesData()throws Exception {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String database="stayos_restore_ch_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database;
        String owner=System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),password=System.getenv("STAYOS_DB_PASSWORD");
        try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("create database "+database);}
        try {
            Flyway.configure().dataSource(url,owner,password).target("18").load().migrate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement()){
                q.execute("insert into users(id,username,password_hash,display_name) values(900004,'legacy_channel','"+ENCODER.encode("fictional-password")+"','虚构旧版渠道')");
            }
            var flyway=Flyway.configure().dataSource(url,owner,password).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);flyway.validate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.tables where table_schema='public' and table_name in "+
                "('channel_connections','channel_mappings','inbound_events','outbox_events','sync_attempts','reconciliation_runs')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(6);
            }
            // Credentials are references only: the secret itself must not have a home in these tables.
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.columns where table_name='channel_connections' and column_name in "+
                "('credential_ref','callback_secret_ref','last_inbound_at','last_outbound_at','last_sync_at','last_error')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(6);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("drop database if exists "+database+" with(force)");}}
    }

    @Test void migrationContainsExpectedChannelConstraints() {
        // Inbound facts: exactly one row per (connection, external event); worker polls only PENDING.
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='inbound_events_connection_id_external_event_id_key'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='ix_inbound_events_pending' and indexdef like '%WHERE%'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='ix_outbox_events_pending' and indexdef like '%WHERE%'",Integer.class)).isEqualTo(1);
        // Outbox: SENT fixes its fact once; the pending index keeps the worker claim surface partial.
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='outbox_events'::regclass and conname='outbox_events_sent_check'",Integer.class)).isEqualTo(1);
        // Reconciliation runs cannot claim completion without a finish timestamp.
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='reconciliation_runs'::regclass and conname='reconciliation_runs_finish_check'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='uq_channel_connections_name'",Integer.class)).isEqualTo(1);
    }

    long connectionRow(String name) {
        long actor=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'虚构渠道员工') returning id",Long.class,"ch_schema_"+name,ENCODER.encode(name));
        return db.queryForObject("insert into channel_connections(supplier_code,name,credential_ref,callback_secret_ref,created_by) values('SIMULATOR',?,?,'CH_CALLBACK_SECRET',?) returning id",Long.class,name,"CH_CREDENTIAL",actor);
    }

    @ParameterizedTest @ValueSource(strings={"DUPLICATE_EVENT","NEGATIVE_SEQUENCE","UNKNOWN_SUPPLIER","SENT_WITHOUT_TIME","RUNNING_WITH_FINISH","MAPPING_DUP_CODE"}) @Transactional
    void databaseRejectsInvalidChannelFacts(String kind) {
        long connection=connectionRow(UUID.randomUUID().toString().substring(0,8));
        long event=db.queryForObject("insert into inbound_events(connection_id,external_event_id,event_type,sequence_no,payload) values(?,'CH-EVT-1','ORDER_CREATE',1,'{}'::jsonb) returning id",Long.class,connection);
        switch(kind) {
            case "DUPLICATE_EVENT"->assertThatThrownBy(()->db.update("insert into inbound_events(connection_id,external_event_id,event_type,sequence_no,payload) values(?,'CH-EVT-1','ORDER_CREATE',2,'{}'::jsonb)",connection)).isInstanceOf(DataIntegrityViolationException.class);
            case "NEGATIVE_SEQUENCE"->assertThatThrownBy(()->db.update("insert into inbound_events(connection_id,external_event_id,event_type,sequence_no,payload) values(?,'CH-EVT-2','ORDER_CREATE',-1,'{}'::jsonb)",connection)).isInstanceOf(DataIntegrityViolationException.class);
            case "UNKNOWN_SUPPLIER"->assertThatThrownBy(()->db.update("insert into channel_connections(supplier_code,name,credential_ref,callback_secret_ref,created_by) select 'CTRIP',name||'-x',credential_ref,callback_secret_ref,created_by from channel_connections where id=?",connection)).isInstanceOf(DataIntegrityViolationException.class);
            case "SENT_WITHOUT_TIME"->{
                long outbox=db.queryForObject("insert into outbox_events(connection_id,aggregate_type,aggregate_id,event_type,payload) values(?,'RESERVATION',1,'RESERVATION_CONFIRMED','{}'::jsonb) returning id",Long.class,connection);
                assertThatThrownBy(()->db.update("update outbox_events set status='SENT' where id=?",outbox)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "RUNNING_WITH_FINISH"->{
                long run=db.queryForObject("insert into reconciliation_runs(connection_id,started_by) values(?,?) returning id",Long.class,connection,connectionRowOwner(connection));
                assertThatThrownBy(()->db.update("update reconciliation_runs set finished_at=now() where id=?",run)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "MAPPING_DUP_CODE"->{
                long type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values(?,'测试房型',2) returning id",Long.class,("CH"+UUID.randomUUID().toString().substring(0,6)).toUpperCase(Locale.ROOT));
                db.update("insert into channel_mappings(connection_id,kind,local_id,external_code,created_by) values(?,'ROOM_TYPE',?,'HTL-STD',?)",connection,type,connectionRowOwner(connection));
                assertThatThrownBy(()->db.update("insert into channel_mappings(connection_id,kind,local_id,external_code,created_by) values(?,'ROOM_TYPE',?,'HTL-STD',?)",connection,type,connectionRowOwner(connection))).isInstanceOf(DataIntegrityViolationException.class);
            }
        }
    }

    private long connectionRowOwner(long connection){return db.queryForObject("select created_by from channel_connections where id=?",Long.class,connection);}
}
