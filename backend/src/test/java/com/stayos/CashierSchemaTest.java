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
class CashierSchemaTest {
    @Autowired JdbcTemplate db;

    @Test void upgradeFromV16CreatesCashierModelAndPreservesData()throws Exception {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String database="stayos_restore_ca_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database;
        String owner=System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),password=System.getenv("STAYOS_DB_PASSWORD");
        try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("create database "+database);}
        try {
            Flyway.configure().dataSource(url,owner,password).target("16").load().migrate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement()){
                q.execute("insert into users(id,username,password_hash,display_name) values(900002,'legacy_cashier','"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("fictional-password")+"','虚构旧版收银')");
            }
            var flyway=Flyway.configure().dataSource(url,owner,password).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);flyway.validate();
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.tables where table_schema='public' and table_name in "+
                "('business_days','cashier_shifts','cash_counts','daily_close_runs','daily_reports')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(5);
            }
            try(var c=DriverManager.getConnection(url,owner,password);var q=c.createStatement();var r=q.executeQuery(
                "select count(*) from information_schema.columns where table_name in ('idempotency_requests','payment_records') and column_name in "+
                "('result_cashier_shift_id','cashier_shift_id')")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(2);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {try(var c=db.getDataSource().getConnection();var q=c.createStatement()){q.execute("drop database if exists "+database+" with(force)");}}
    }

    @Test void migrationContainsExpectedCashierConstraints() {
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='uq_cashier_shift_open' and indexdef like '%WHERE%'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_indexes where indexname='uq_daily_close_running' and indexdef like '%WHERE%'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='cashier_shifts'::regclass and not tgisinternal",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='cash_counts'::regclass and not tgisinternal",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='daily_reports'::regclass and not tgisinternal",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='daily_close_runs'::regclass and not tgisinternal",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from pg_trigger where tgrelid='business_days'::regclass and not tgisinternal",Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={
        "SHIFT_DOUBLE_OPEN","SHIFT_BACK_TRANSITION","SHIFT_CONFIRM_IMMUTABLE","SHIFT_COUNT_PRESET","SHIFT_NEGATIVE_FLOAT","SHIFT_STATE_MISMATCH",
        "COUNT_DIFF_REASON","COUNT_MISMATCH","COUNT_NEGATIVE","COUNT_MUTATE","COUNT_DELETE",
        "REPORT_MUTATE","REPORT_DELETE","REPORT_DOUBLE",
        "DAY_REOPEN","DAY_RUNNING_UNIQUE","RUN_BACK","RUN_DELETE","RUN_STATE_MISMATCH"}) @Transactional
    void databaseRejectsInvalidCashierFacts(String kind) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        long actor=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'虚构收银员工') returning id",Long.class,"ca_schema_"+suffix,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(suffix));
        long manager=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,'虚构收银经理') returning id",Long.class,"ca_mgr_"+suffix,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(suffix));
        long policy=db.queryForObject("insert into billing_policies(version,billing_cutoff_time,early_checkin_mode,late_checkout_mode,early_checkout_mode,room_change_price_mode,extension_price_mode,is_active,created_by) values(1,time '04:00','FULL_NIGHT','HOURLY','FULL_NIGHT','BY_NEW_SEGMENT','CURRENT_RATE',true,?) returning id",Long.class,actor);
        long day=db.queryForObject("insert into business_days(business_date,policy_id) values(current_date,?) returning id",Long.class,policy);
        long day2=db.queryForObject("insert into business_days(business_date,policy_id,status,closed_by,closed_at) values(current_date-1,?,'CLOSED',?,now()) returning id",Long.class,policy,manager);
        long open=db.queryForObject("insert into cashier_shifts(business_date,operator_id,opened_float) values(current_date,?,100.00) returning id",Long.class,actor);
        long submitted=db.queryForObject("insert into cashier_shifts(business_date,operator_id,opened_float,status,counted_cash,difference_reason,submitted_at) values(current_date,?,100.00,'SUBMITTED',80.00,'现金找零误差',now()) returning id",Long.class,manager);
        long confirmed=db.queryForObject("insert into cashier_shifts(business_date,operator_id,opened_float,status,counted_cash,difference_reason,submitted_at,confirmed_by,confirmed_at) values(current_date,?,100.00,'CONFIRMED',100.00,NULL,now(),?,now()) returning id",Long.class,actor,manager);
        db.update("insert into cash_counts(cashier_shift_id,expected_cash,counted_cash,difference,difference_reason,counted_by) values(?,180.00,80.00,-100.00,'现金找零误差',?)",submitted,actor);
        long running=db.queryForObject("insert into daily_close_runs(business_date,status,started_by) values(current_date,'RUNNING',?) returning id",Long.class,manager);
        long done=db.queryForObject("insert into daily_close_runs(business_date,status,started_by,finished_at,steps_done,last_step) values(current_date-1,'COMPLETED',?,now(),4,'REPORT_FROZEN') returning id",Long.class,manager);
        db.update("insert into daily_reports(business_date,run_id,snapshot,created_by) values(current_date-1,?,cast(? as jsonb),?)",done,"{\"businessDate\":\"x\"}",manager);
        switch(kind) {
            case "SHIFT_DOUBLE_OPEN"->assertThatThrownBy(()->db.update("insert into cashier_shifts(business_date,operator_id,opened_float) values(current_date,?,100.00)",actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "SHIFT_BACK_TRANSITION"->assertThatThrownBy(()->db.update("update cashier_shifts set status='OPEN',submitted_at=NULL,counted_cash=NULL where id=?",submitted)).isInstanceOf(DataIntegrityViolationException.class);
            case "SHIFT_CONFIRM_IMMUTABLE"->assertThatThrownBy(()->db.update("update cashier_shifts set counted_cash=1 where id=?",confirmed)).isInstanceOf(DataIntegrityViolationException.class);
            case "SHIFT_COUNT_PRESET"->assertThatThrownBy(()->db.update("update cashier_shifts set counted_cash=1 where id=?",open)).isInstanceOf(DataIntegrityViolationException.class);
            case "SHIFT_NEGATIVE_FLOAT"->assertThatThrownBy(()->db.update("insert into cashier_shifts(business_date,operator_id,opened_float) values(current_date,?,-1.00)",manager)).isInstanceOf(DataIntegrityViolationException.class);
            case "SHIFT_STATE_MISMATCH"->assertThatThrownBy(()->db.update("insert into cashier_shifts(business_date,operator_id,opened_float,status,confirmed_by,confirmed_at) values(current_date,?,100.00,'CONFIRMED',NULL,now())",manager)).isInstanceOf(DataIntegrityViolationException.class);
            case "COUNT_DIFF_REASON"->assertThatThrownBy(()->db.update("insert into cash_counts(cashier_shift_id,expected_cash,counted_cash,difference,counted_by) values(?,100.00,90.00,-10.00,?)",open,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "COUNT_MISMATCH"->assertThatThrownBy(()->db.update("insert into cash_counts(cashier_shift_id,expected_cash,counted_cash,difference,difference_reason,counted_by) values(?,100.00,90.00,-5.00,'差异说明',?)",open,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "COUNT_NEGATIVE"->assertThatThrownBy(()->db.update("insert into cash_counts(cashier_shift_id,expected_cash,counted_cash,difference,difference_reason,counted_by) values(?,100.00,-5.00,-105.00,'负数实盘',?)",open,actor)).isInstanceOf(DataIntegrityViolationException.class);
            case "COUNT_MUTATE"->assertThatThrownBy(()->db.update("update cash_counts set counted_cash=1 where cashier_shift_id=?",submitted)).isInstanceOf(DataIntegrityViolationException.class);
            case "COUNT_DELETE"->assertThatThrownBy(()->db.update("delete from cash_counts where cashier_shift_id=?",submitted)).isInstanceOf(DataIntegrityViolationException.class);
            case "REPORT_MUTATE"->assertThatThrownBy(()->db.update("update daily_reports set snapshot=cast('{}' as jsonb) where run_id=?",done)).isInstanceOf(DataIntegrityViolationException.class);
            case "REPORT_DELETE"->assertThatThrownBy(()->db.update("delete from daily_reports where run_id=?",done)).isInstanceOf(DataIntegrityViolationException.class);
            case "REPORT_DOUBLE"->assertThatThrownBy(()->db.update("insert into daily_reports(business_date,run_id,snapshot,created_by) values(current_date-1,?,cast('{}' as jsonb),?)",done,manager)).isInstanceOf(DataIntegrityViolationException.class);
            case "DAY_REOPEN"->assertThatThrownBy(()->db.update("update business_days set status='OPEN',closed_at=NULL,closed_by=NULL where id=?",day2)).isInstanceOf(DataIntegrityViolationException.class);
            case "DAY_RUNNING_UNIQUE"->assertThatThrownBy(()->db.update("insert into daily_close_runs(business_date,status,started_by) values(current_date,'RUNNING',?)",manager)).isInstanceOf(DataIntegrityViolationException.class);
            case "RUN_BACK"->assertThatThrownBy(()->db.update("update daily_close_runs set status='RUNNING',finished_at=NULL where id=?",done)).isInstanceOf(DataIntegrityViolationException.class);
            case "RUN_DELETE"->assertThatThrownBy(()->db.update("delete from daily_close_runs where id=?",done)).isInstanceOf(DataIntegrityViolationException.class);
            case "RUN_STATE_MISMATCH"->assertThatThrownBy(()->db.update("insert into daily_close_runs(business_date,status,started_by,finished_at) values(current_date-1,'FAILED',?,now())",manager)).isInstanceOf(DataIntegrityViolationException.class);
            default->{}
        }
    }
}
