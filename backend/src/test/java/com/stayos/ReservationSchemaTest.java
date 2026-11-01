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
class ReservationSchemaTest {
    @Autowired JdbcTemplate db;

    @Test void upgradeFromV17CreatesReservationModelAndPreservesData()throws Exception {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String database="stayos_restore_rs_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database;
        String owner=System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),password=System.getenv("STAYOS_DB_PASSWORD");
        try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("create database "+database);}
        try {
            Flyway.configure().dataSource(url,owner,password).target("17").load().migrate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement()){
                q.execute("insert into users(id,username,password_hash,display_name) values(900003,'legacy_reservation','"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("fictional-password")+"','虚构旧版预订')");
            }
            var flyway=Flyway.configure().dataSource(url,owner,password).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);flyway.validate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.tables where table_schema='public' and table_name in "+
                "('reservations','reservation_events','inventory_days','inventory_blocks')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(4);
            }
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.columns where table_name in ('idempotency_requests','payment_records') and column_name in "+
                "('result_reservation_id','result_inventory_block_id','reservation_id')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(3);
            }
            // folio_id became nullable only together with the attachment check: folio XOR reservation.
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.columns where table_name='payment_records' and column_name='folio_id' and is_nullable='YES'")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(1);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("drop database if exists "+database+" with(force)");}}
    }

    @Test void migrationContainsExpectedReservationConstraints() {
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='uq_reservation_external_ref' and indexdef like '%WHERE%'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='reservations'::regclass and not tgisinternal",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='reservation_events'::regclass and not tgisinternal",Integer.class)).isEqualTo(1);
        // The old shared append-only trigger is replaced by migration guard + delete guard beside the V2.0 insert validator.
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='payment_records'::regclass and not tgisinternal",Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='payment_records'::regclass and conname='payment_records_attachment_check'",Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={
        "RES_BACK_TRANSITION","RES_IMMUTABLE","RES_TERMINAL_PRESET","RES_BAD_TRANSITION","RES_STATE_MISMATCH","RES_DEP_ORDER",
        "RES_BAD_REF","RES_DUP_REF","RES_PHONE_KEY","RES_ASSIGN_INCOMPLETE",
        "EVENT_MUTATE","EVENT_DELETE",
        "INV_NEGATIVE_SOLD","INV_DOUBLE",
        "BLOCK_BAD_RANGE","BLOCK_STATE_MISMATCH",
        "PAY_BOTH_ATTACH","PAY_MIGRATE_IMMUTABLE","PAY_MIGRATE_FILL_OK","PAY_MIGRATE_TWICE","PAY_MUTATE","PAY_DELETE","PAY_NEEDS_ATTACHMENT"}) @Transactional
    void databaseRejectsInvalidReservationFacts(String kind) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        long actor=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'虚构预订员工') returning id",Long.class,"rs_schema_"+suffix,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(suffix));
        long typeId=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values(?,'预订测试房型',2) returning id",Long.class,"RS"+suffix.toUpperCase());
        long roomId=db.queryForObject("insert into rooms(room_number,floor,room_type_id) values(?,8,?) returning id",Long.class,"RS"+suffix.substring(0,6).toUpperCase(),typeId);
        long held=db.queryForObject("insert into reservations(arrival_date,departure_date,room_type_id,status,source,external_ref,contact_name,held_until,created_by) values(current_date+1,current_date+3,?,'HELD','PHONE','OTA-REF-'||?, '虚构联系人',now()+interval '1 day',?) returning id",Long.class,typeId,suffix,actor);
        long confirmed=db.queryForObject("insert into reservations(arrival_date,departure_date,room_type_id,status,source,contact_name,confirmed_by,confirmed_at,created_by) values(current_date+1,current_date+3,?,'CONFIRMED','OTA','虚构联系人',?,now(),?) returning id",Long.class,typeId,actor,actor);
        // A minimal in-house stay backs the CHECKED_IN reservation and the folio used by deposit-migration cases.
        long stay=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now(),now()+interval '2 days',?) returning id",Long.class,actor);
        long checked=db.queryForObject("insert into reservations(arrival_date,departure_date,room_type_id,status,source,contact_name,confirmed_by,confirmed_at,checked_in_stay_id,created_by) values(current_date-1,current_date+1,?,'CHECKED_IN','WALK_IN','虚构联系人',?,now(),?,?) returning id",Long.class,typeId,actor,stay,actor);
        long policy=db.queryForObject("insert into billing_policies(version,billing_cutoff_time,early_checkin_mode,late_checkout_mode,early_checkout_mode,room_change_price_mode,extension_price_mode,is_active,created_by) values(1,time '04:00','FULL_NIGHT','HOURLY','FULL_NIGHT','BY_NEW_SEGMENT','CURRENT_RATE',true,?) returning id",Long.class,actor);
        long folio=db.queryForObject("insert into folios(stay_record_id,pricing_status,opened_by) values(?,'PRICED',?) returning id",Long.class,stay,actor);
        db.update("insert into inventory_days(room_type_id,stay_date,sold) values(?,current_date+1,1)",typeId);
        long block=db.queryForObject("insert into inventory_blocks(room_type_id,start_date,end_date,reason,created_by) values(?,current_date+5,current_date+7,'计划装修停售',?) returning id",Long.class,typeId,actor);
        long folioPayment=db.queryForObject("insert into payment_records(folio_id,direction,method,purpose,amount,currency,received_by) values(?,'RECEIPT','CASH','PAYMENT',100.00,'CNY',?) returning id",Long.class,folio,actor);
        long deposit=db.queryForObject("insert into payment_records(reservation_id,direction,method,purpose,amount,currency,received_by) values(?,'RECEIPT','CASH','DEPOSIT',100.00,'CNY',?) returning id",Long.class,confirmed,actor);
        db.update("insert into reservation_events(reservation_id,action,actor,detail) values(?,'CREATE',?,'虚构预订创建')",held,actor);
        switch(kind) {
            case "RES_BACK_TRANSITION"->assertThatThrownBy(()->db.update("update reservations set status='HELD',confirmed_at=NULL,confirmed_by=NULL where id=?",confirmed)).isInstanceOf(DataIntegrityViolationException.class);
            case "RES_IMMUTABLE"->assertThatThrownBy(()->db.update("update reservations set notes='改不动' where id=?",checked)).isInstanceOf(DataIntegrityViolationException.class);
            case "RES_TERMINAL_PRESET"->assertThatThrownBy(()->db.update("update reservations set confirmed_at=now() where id=?",held)).isInstanceOf(DataIntegrityViolationException.class);
            case "RES_BAD_TRANSITION"->assertThatThrownBy(()->db.update("update reservations set status='NO_SHOW',no_show_at=now(),no_show_by=? where id=?",actor,held)).isInstanceOf(DataIntegrityViolationException.class);
            case "RES_STATE_MISMATCH"->assertThatThrownBy(()->db.update("insert into reservations(arrival_date,departure_date,room_type_id,status,source,contact_name,created_by) values(current_date+1,current_date+3,?,'CONFIRMED','OTA','虚构联系人',?)",typeId,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "RES_DEP_ORDER"->assertThatThrownBy(()->db.update("insert into reservations(arrival_date,departure_date,room_type_id,status,source,contact_name,held_until,created_by) values(current_date+3,current_date+3,?,'HELD','PHONE','虚构联系人',now(),?)",typeId,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "RES_BAD_REF"->assertThatThrownBy(()->db.update("insert into reservations(arrival_date,departure_date,room_type_id,status,source,external_ref,contact_name,held_until,created_by) values(current_date+1,current_date+3,?,'HELD','PHONE','  空白单号  ','虚构联系人',now(),?)",typeId,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "RES_DUP_REF"->assertThatThrownBy(()->db.update("insert into reservations(arrival_date,departure_date,room_type_id,status,source,external_ref,contact_name,held_until,created_by) values(current_date+1,current_date+3,?,'HELD','PHONE','OTA-REF-'||?,'虚构联系人',now(),?)",typeId,suffix,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "RES_PHONE_KEY"->assertThatThrownBy(()->db.update("update reservations set contact_phone_ciphertext='x' where id=?",held)).isInstanceOf(DataIntegrityViolationException.class);
            case "RES_ASSIGN_INCOMPLETE"->assertThatThrownBy(()->db.update("update reservations set assigned_room_id=? where id=?",roomId,held)).isInstanceOf(DataIntegrityViolationException.class);
            case "EVENT_MUTATE"->assertThatThrownBy(()->db.update("update reservation_events set detail='改历史' where reservation_id=?",held)).isInstanceOf(DataIntegrityViolationException.class);
            case "EVENT_DELETE"->assertThatThrownBy(()->db.update("delete from reservation_events where reservation_id=?",held)).isInstanceOf(DataIntegrityViolationException.class);
            case "INV_NEGATIVE_SOLD"->assertThatThrownBy(()->db.update("update inventory_days set sold=-1 where room_type_id=? and stay_date=current_date+1",typeId)).isInstanceOf(DataIntegrityViolationException.class);
            case "INV_DOUBLE"->assertThatThrownBy(()->db.update("insert into inventory_days(room_type_id,stay_date,sold) values(?,current_date+1,0)",typeId)).isInstanceOf(DataIntegrityViolationException.class);
            case "BLOCK_BAD_RANGE"->assertThatThrownBy(()->db.update("insert into inventory_blocks(room_type_id,start_date,end_date,reason,created_by) values(?,current_date+9,current_date+9,'范围颠倒',?)",typeId,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "BLOCK_STATE_MISMATCH"->assertThatThrownBy(()->db.update("update inventory_blocks set released_at=now() where id=?",block)).isInstanceOf(DataIntegrityViolationException.class);
            case "PAY_BOTH_ATTACH"->assertThatThrownBy(()->db.update("update payment_records set reservation_id=? where id=?",confirmed,folioPayment)).isInstanceOf(DataIntegrityViolationException.class);
            case "PAY_MIGRATE_FILL_OK"->assertThatCode(()->db.update("update payment_records set folio_id=? where id=?",folio,deposit)).doesNotThrowAnyException();
            case "PAY_MIGRATE_IMMUTABLE"->assertThatThrownBy(()->db.update("update payment_records set folio_id=?,amount=1.00 where id=?",folio,deposit)).isInstanceOf(DataIntegrityViolationException.class);
            case "PAY_MIGRATE_TWICE"->{
                db.update("update payment_records set folio_id=? where id=?",folio,deposit);
                assertThatThrownBy(()->db.update("update payment_records set folio_id=? where id=?",folio,deposit)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "PAY_MUTATE"->assertThatThrownBy(()->db.update("update payment_records set amount=2.00 where id=?",deposit)).isInstanceOf(DataIntegrityViolationException.class);
            case "PAY_DELETE"->assertThatThrownBy(()->db.update("delete from payment_records where id=?",deposit)).isInstanceOf(DataIntegrityViolationException.class);
            case "PAY_NEEDS_ATTACHMENT"->assertThatThrownBy(()->db.update("insert into payment_records(direction,method,purpose,amount,received_by) values('RECEIPT','CASH','DEPOSIT',100.00,?)",actor)).isInstanceOf(DataIntegrityViolationException.class);
            default->{}
        }
    }
}
