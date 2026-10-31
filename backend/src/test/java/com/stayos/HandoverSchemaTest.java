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
class HandoverSchemaTest {
    @Autowired JdbcTemplate db;

    @Test void upgradeFromV14CreatesHandoverModelAndPreservesData()throws Exception {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String database="stayos_restore_hv_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database;
        String owner=System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),password=System.getenv("STAYOS_DB_PASSWORD");
        try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("create database "+database);}
        try {
            Flyway.configure().dataSource(url,owner,password).target("14").load().migrate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement()) {
                q.execute("insert into users(id,username,password_hash,display_name) values(900001,'legacy_handover','"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("fictional-password")+"','虚构旧版员工')");
            }
            // From V14 two migrations apply since V2.0: V15 (handovers) and V16 (billing foundation).
            var flyway=Flyway.configure().dataSource(url,owner,password).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);flyway.validate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.tables where table_schema='public' and table_name in ('shift_handovers','handover_items','handover_events')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(3);
            }
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.columns where table_name='idempotency_requests' and column_name='result_handover_id'")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(1);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("drop database if exists "+database+" with(force)");}}
    }
    @Test void migrationContainsExpectedHandoverConstraints() {
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='shift_handovers'::regclass and pg_get_constraintdef(oid) like '%receiver_id <> handover_by%'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='shift_handovers'::regclass and pg_get_constraintdef(oid) like '%shift_end_at > shift_start_at%'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='handover_items'::regclass and not tgisinternal",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"STATUS","SELF","TIME","TIMESTAMPS","FROZEN","TRANSITION","ACK_IMMUTABLE","ITEM_FROZEN","ITEM_DELETE_FROZEN","CATEGORY","ITEM_LONG","ORPHAN","ITEM_POSITION","ITEM_POSITION_RANGE"}) @Transactional
    void databaseRejectsInvalidHandoverFacts(String kind) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        long actor=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'虚构约束员工') returning id",Long.class,"hv_schema_"+suffix,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(suffix));
        if(kind.equals("SELF")) {
            assertThatThrownBy(()->db.update("insert into shift_handovers(scope,handover_by,receiver_id,shift_start_at,shift_end_at,status) values('FRONT_DESK',?,?,now(),now()+interval '8 hours','DRAFT')",actor,actor))
                .isInstanceOf(DataIntegrityViolationException.class);
            return;
        }
        if(kind.equals("TIME")) {
            assertThatThrownBy(()->db.update("insert into shift_handovers(scope,handover_by,receiver_id,shift_start_at,shift_end_at,status) values('FRONT_DESK',?,?,now(),now(),'DRAFT')",actor,actor+1))
                .isInstanceOf(DataIntegrityViolationException.class);
            return;
        }
        long receiver=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'虚构接班员工') returning id",Long.class,"hv_recv_"+suffix,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(suffix));
        if(kind.equals("STATUS")) {
            assertThatThrownBy(()->db.update("insert into shift_handovers(scope,handover_by,receiver_id,shift_start_at,shift_end_at,status) values('FRONT_DESK',?,?,now(),now()+interval '8 hours','WEIRD')",actor,receiver))
                .isInstanceOf(DataIntegrityViolationException.class);
            return;
        }
        long id=db.queryForObject("insert into shift_handovers(scope,handover_by,receiver_id,shift_start_at,shift_end_at,status) values('FRONT_DESK',?,?,now(),now()+interval '8 hours','DRAFT') returning id",Long.class,actor,receiver);
        switch(kind) {
            case "FROZEN"->{
                db.update("update shift_handovers set status='SUBMITTED',submitted_at=now(),version=1 where id=?",id);
                assertThatThrownBy(()->db.update("update shift_handovers set summary='rewritten',version=2 where id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "TRANSITION"->assertThatThrownBy(()->db.update("update shift_handovers set status='ACKNOWLEDGED',acknowledged_at=now(),acknowledged_by=?,version=1 where id=?",actor,id)).isInstanceOf(DataIntegrityViolationException.class);
            case "ACK_IMMUTABLE"->{
                db.update("update shift_handovers set status='SUBMITTED',submitted_at=now(),version=1 where id=?",id);
                db.update("update shift_handovers set status='ACKNOWLEDGED',acknowledged_at=now(),acknowledged_by=?,version=2 where id=?",receiver,id);
                assertThatThrownBy(()->db.update("update shift_handovers set version=3 where id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "ITEM_FROZEN"->{
                db.update("update shift_handovers set status='SUBMITTED',submitted_at=now(),version=1 where id=?",id);
                assertThatThrownBy(()->db.update("insert into handover_items(handover_id,position,category,content) values(?,0,'CUSTOM','late item')",id)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "ITEM_DELETE_FROZEN"->{
                db.update("insert into handover_items(handover_id,position,category,content) values(?,0,'CUSTOM','draft item')",id);
                db.update("update shift_handovers set status='SUBMITTED',submitted_at=now(),version=1 where id=?",id);
                assertThatThrownBy(()->db.update("delete from handover_items where handover_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "CATEGORY"->assertThatThrownBy(()->db.update("insert into handover_items(handover_id,position,category,content) values(?,0,'GUEST_SECRET','fixture')",id)).isInstanceOf(DataIntegrityViolationException.class);
            case "ITEM_LONG"->assertThatThrownBy(()->db.update("insert into handover_items(handover_id,position,category,content) values(?,0,'CUSTOM',?)",id,"a".repeat(501))).isInstanceOf(DataIntegrityViolationException.class);
            case "ORPHAN"->assertThatThrownBy(()->db.update("insert into handover_items(handover_id,position,category,content) values(?,0,'CUSTOM','fixture')",id+999999)).isInstanceOf(DataIntegrityViolationException.class);
            case "ITEM_POSITION"->{
                db.update("insert into handover_items(handover_id,position,category,content) values(?,0,'CUSTOM','fixture')",id);
                assertThatThrownBy(()->db.update("insert into handover_items(handover_id,position,category,content) values(?,0,'CUSTOM','fixture')",id)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "ITEM_POSITION_RANGE"->assertThatThrownBy(()->db.update("insert into handover_items(handover_id,position,category,content) values(?,50,'CUSTOM','fixture')",id)).isInstanceOf(DataIntegrityViolationException.class);
            case "TIMESTAMPS"->assertThatThrownBy(()->db.update("update shift_handovers set status='SUBMITTED',version=1 where id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
            default->{}
        }
    }
}
