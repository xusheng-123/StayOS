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
class BillingSchemaTest {
    @Autowired JdbcTemplate db;

    @Test void upgradeFromV15CreatesBillingModelAndPreservesData()throws Exception {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String database="stayos_restore_bl_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database;
        String owner=System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),password=System.getenv("STAYOS_DB_PASSWORD");
        try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("create database "+database);}
        try {
            Flyway.configure().dataSource(url,owner,password).target("15").load().migrate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement()) {
                q.execute("insert into users(id,username,password_hash,display_name) values(900001,'legacy_billing','"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("fictional-password")+"','虚构旧版员工')");
            }
            // Three migrations apply since V2.1: V16 (billing foundation), V17 (cashier/business days) and V18 (reservations/inventory).
            var flyway=Flyway.configure().dataSource(url,owner,password).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);flyway.validate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.tables where table_schema='public' and table_name in "+
                "('billing_policies','rate_plans','room_rate_days','service_catalog','service_orders','service_order_items','folios','folio_charges','payment_records','folio_events')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(10);
            }
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.columns where table_name='idempotency_requests' and column_name in "+
                "('result_folio_id','result_folio_charge_id','result_service_order_id','result_payment_record_id')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(4);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("drop database if exists "+database+" with(force)");}}
    }

    @Test void migrationContainsExpectedBillingConstraints() {
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='uq_billing_policy_active'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='uq_folio_charge_source'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='uq_payment_reference'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='uq_payment_reference' and indexdef like '%WHERE%'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_constraint where conrelid='folios'::regclass and conname='folios_stay_record_id_key' and contype='u'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='folio_charges'::regclass and not tgisinternal",Integer.class)).isEqualTo(2);
        // V3.0 replaces the append-only trigger with migration guard + delete guard beside the insert validator.
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='payment_records'::regclass and not tgisinternal",Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='folio_events'::regclass and not tgisinternal",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='service_order_items'::regclass and not tgisinternal",Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={
        "CHARGE_NEGATIVE","CHARGE_MISMATCH","CHARGE_OVERFLOW","CHARGE_CURRENCY","CHARGE_FOLIO_STAY","CHARGE_CLOSED_FOLIO",
        "CHARGE_MUTATE","CHARGE_DELETE","CREDIT_NO_ORIGINAL","CREDIT_FOREIGN_FOLIO","ROOM_NIGHT_NO_SEGMENT","ROOM_NIGHT_NO_POLICY",
        "SERVICE_NO_CATALOG","PAYMENT_MUTATE","PAYMENT_REFUND_ORIGINAL","REFUND_WITH_PURPOSE","NONCASH_REF_UNIQUE",
        "FOLIO_IMMUTABLE_COVERAGE","FOLIO_TRANSITION","FOLIO_VERSION_BACK","POLICY_MULTI_ACTIVE","ORDER_TRANSITION","ORDER_ITEM_FROZEN","ORDER_LONG_AMOUNT"}) @Transactional
    void databaseRejectsInvalidBillingFacts(String kind) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        long actor=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'虚构账务员工') returning id",Long.class,"bl_schema_"+suffix,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(suffix));
        long policy=db.queryForObject("insert into billing_policies(version,billing_cutoff_time,early_checkin_mode,late_checkout_mode,early_checkout_mode,room_change_price_mode,extension_price_mode,is_active,created_by) values(1,time '04:00','FULL_NIGHT','HOURLY','FULL_NIGHT','BY_NEW_SEGMENT','CURRENT_RATE',true,?) returning id",Long.class,actor);
        long stay=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now()-interval '2 days',now()+interval '2 days',?) returning id",Long.class,actor);
        long folio=db.queryForObject("insert into folios(stay_record_id,pricing_status,opened_by) values(?,'PRICED',?) returning id",Long.class,stay,actor);
        long otherStay=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now()-interval '1 day',now()+interval '1 day',?) returning id",Long.class,actor);
        long otherFolio=db.queryForObject("insert into folios(stay_record_id,pricing_status,opened_by) values(?,'PRICED',?) returning id",Long.class,otherStay,actor);
        long charge=db.queryForObject("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,created_by) values(?,?,current_date,'CHARGE','MANUAL','手工测试费','UNIT',1,100.00,100.00,'CNY','MANUAL',?,?) returning id",Long.class,folio,stay,"MANUAL:"+UUID.randomUUID(),actor);
        long receipt=db.queryForObject("insert into payment_records(folio_id,direction,method,purpose,amount,currency,received_by) values(?,'RECEIPT','CASH','PAYMENT',50.00,'CNY',?) returning id",Long.class,folio,actor);
        long order=db.queryForObject("insert into service_orders(stay_record_id,requested_by) values(?,?) returning id",Long.class,stay,actor);
        switch(kind) {
            case "CHARGE_NEGATIVE"->assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,created_by) values(?,?,current_date,'CHARGE','MANUAL','负数费用','UNIT',1,-100.00,-100.00,'CNY','MANUAL','MANUAL:x1',?)",folio,stay,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "CHARGE_MISMATCH"->assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,created_by) values(?,?,current_date,'CHARGE','MANUAL','金额不符','UNIT',2,100.00,150.00,'CNY','MANUAL','MANUAL:x2',?)",folio,stay,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "CHARGE_OVERFLOW"->assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,created_by) values(?,?,current_date,'CHARGE','MANUAL','金额超限','UNIT',999,999999.99,999899900.01,'CNY','MANUAL','MANUAL:x3',?)",folio,stay,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "CHARGE_CURRENCY"->assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,created_by) values(?,?,current_date,'CHARGE','MANUAL','外币费用','UNIT',1,100.00,100.00,'USD','MANUAL','MANUAL:x4',?)",folio,stay,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "CHARGE_FOLIO_STAY"->assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,created_by) values(?,?,current_date,'CHARGE','MANUAL','串账费用','UNIT',1,100.00,100.00,'CNY','MANUAL','MANUAL:x5',?)",folio,otherStay,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "CHARGE_CLOSED_FOLIO"->{
                db.update("update folios set status='CLOSED',closed_at=now(),closed_by=?,version=1 where id=?",actor,folio);
                assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,created_by) values(?,?,current_date,'CHARGE','MANUAL','关账后费用','UNIT',1,100.00,100.00,'CNY','MANUAL','MANUAL:x6',?)",folio,stay,actor)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "CHARGE_MUTATE"->assertThatThrownBy(()->db.update("update folio_charges set amount=999 where id=?",charge)).isInstanceOf(DataIntegrityViolationException.class);
            case "CHARGE_DELETE"->assertThatThrownBy(()->db.update("delete from folio_charges where id=?",charge)).isInstanceOf(DataIntegrityViolationException.class);
            case "CREDIT_NO_ORIGINAL"->assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,reversal_of,created_by) values(?,?,current_date,'CREDIT','CORRECTION','无原账贷项','UNIT',1,30.00,30.00,'CNY','CREDIT',gen_random_uuid()::text,NULL,?)",folio,stay,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "CREDIT_FOREIGN_FOLIO"->assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,reversal_of,created_by) values(?,?,current_date,'CREDIT','CORRECTION','跨账贷项','UNIT',1,30.00,30.00,'CNY','CREDIT',gen_random_uuid()::text,?,?)",otherFolio,otherStay,charge,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "ROOM_NIGHT_NO_SEGMENT"->assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,policy_version,source_type,source_key,created_by) values(?,?,current_date,'CHARGE','ROOM_NIGHT','无使用段房费','NIGHT',1,200.00,200.00,'CNY',?,'ROOM_NIGHT','RN:x1',?)",folio,stay,1,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "ROOM_NIGHT_NO_POLICY"->{
                long type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values(?,'测试房型',2) returning id",Long.class,("BL"+suffix).toUpperCase(Locale.ROOT));
                long room=db.queryForObject("insert into rooms(room_number,floor,room_type_id) values('9901',9,?) returning id",Long.class,type);
                long segment=db.queryForObject("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at) values(?,?,'9901','测试房型',now()-interval '2 days') returning id",Long.class,stay,room);
                assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,room_segment_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,created_by) values(?,?,?,?,current_date,'CHARGE','ROOM_NIGHT','测试房费','NIGHT',1,200.00,200.00,'CNY','ROOM_NIGHT','RN:x2',?)",folio,stay,segment,actor)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "SERVICE_NO_CATALOG"->assertThatThrownBy(()->db.update("insert into folio_charges(folio_id,stay_record_id,business_date,direction,category,name_snapshot,pricing_unit_snapshot,quantity,unit_price_snapshot,amount,currency,source_type,source_key,created_by) values(?,?,current_date,'CHARGE','SERVICE','无价目服务','ITEM',1,30.00,30.00,'CNY','SERVICE_ORDER','SERVICE_ORDER:x1',?)",folio,stay,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "PAYMENT_MUTATE"->assertThatThrownBy(()->db.update("update payment_records set amount=1 where id=?",receipt)).isInstanceOf(DataIntegrityViolationException.class);
            case "PAYMENT_REFUND_ORIGINAL"->assertThatThrownBy(()->db.update("insert into payment_records(folio_id,direction,method,amount,currency,refund_of,received_by) values(?,'REFUND','CASH',10.00,'CNY',?,?)",otherFolio,receipt,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "REFUND_WITH_PURPOSE"->assertThatThrownBy(()->db.update("insert into payment_records(folio_id,direction,method,purpose,amount,currency,refund_of,received_by) values(?,'REFUND','CASH','PAYMENT',10.00,'CNY',?,?)",folio,receipt,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "NONCASH_REF_UNIQUE"->{
                db.update("insert into payment_records(folio_id,direction,method,purpose,amount,currency,method_reference,received_by) values(?,'RECEIPT','QR_PAYMENT','PAYMENT',10.00,'CNY','EXT-001',?)",folio,actor);
                assertThatThrownBy(()->db.update("insert into payment_records(folio_id,direction,method,purpose,amount,currency,method_reference,received_by) values(?,'RECEIPT','QR_PAYMENT','PAYMENT',20.00,'CNY','EXT-001',?)",folio,actor)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "FOLIO_IMMUTABLE_COVERAGE"->assertThatThrownBy(()->db.update("update folios set pricing_status='PRICING_REQUIRED',charges_effective_from=current_date where id=?",folio)).isInstanceOf(DataIntegrityViolationException.class);
            case "FOLIO_TRANSITION"->assertThatThrownBy(()->db.update("update folios set closed_at=now(),closed_by=? where id=?",actor,otherFolio)).isInstanceOf(DataIntegrityViolationException.class);
            case "FOLIO_VERSION_BACK"->{
                db.update("update folios set version=2 where id=?",folio);
                assertThatThrownBy(()->db.update("update folios set version=1 where id=?",folio)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "POLICY_MULTI_ACTIVE"->assertThatThrownBy(()->db.update("insert into billing_policies(version,billing_cutoff_time,early_checkin_mode,late_checkout_mode,early_checkout_mode,room_change_price_mode,extension_price_mode,is_active,created_by) values(2,time '05:00','FULL_NIGHT','HOURLY','FULL_NIGHT','BY_NEW_SEGMENT','CURRENT_RATE',true,?)",actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "ORDER_TRANSITION"->{
                db.update("update service_orders set status='CANCELLED',cancelled_at=now(),cancelled_by=?,version=1 where id=?",actor,order);
                assertThatThrownBy(()->db.update("update service_orders set status='REQUESTED',cancelled_at=NULL,cancelled_by=NULL,version=2 where id=?",order)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "ORDER_ITEM_FROZEN"->{
                long catalog=db.queryForObject("insert into service_catalog(item_code,item_name,unit_price,created_by) values(?,'测试矿泉水',3.00,?) returning id",Long.class,"BL_ITEM"+suffix,actor);
                db.update("update service_orders set status='FULFILLED',fulfilled_at=now(),fulfilled_by=?,version=1 where id=?",actor,order);
                assertThatThrownBy(()->db.update("insert into service_order_items(order_id,catalog_item_id,item_name_snapshot,unit_price_snapshot,currency_snapshot,quantity) values(?,?,?,?, 'CNY',1)",order,catalog,"测试矿泉水",3.00)).isInstanceOf(DataIntegrityViolationException.class);
            }
            case "ORDER_LONG_AMOUNT"->{
                long catalog=db.queryForObject("insert into service_catalog(item_code,item_name,unit_price,created_by) values(?,'测试泡面',5.00,?) returning id",Long.class,"BL_ITEM2"+suffix,actor);
                assertThatThrownBy(()->db.update("insert into service_order_items(order_id,catalog_item_id,item_name_snapshot,unit_price_snapshot,currency_snapshot,quantity) values(?,?,?,?, 'CNY',1000)",order,catalog,"测试泡面",5.00)).isInstanceOf(DataIntegrityViolationException.class);
            }
            default->{}
        }
    }
}
