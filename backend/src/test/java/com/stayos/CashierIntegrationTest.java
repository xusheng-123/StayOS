package com.stayos;

import com.fasterxml.jackson.databind.*;
import com.stayos.staff.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CashierIntegrationTest {
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
    @LocalServerPort int port;
    final Map<String,Long> ids=new LinkedHashMap<>();
    final String password=UUID.randomUUID().toString();
    static final String OWN="select id from users where username like 'cashier_test_%'";
    @BeforeAll void setup() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String hash=passwords.encode(password);purgeAll();
        for(String role:List.of("FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,
                "cashier_test_"+role.toLowerCase(Locale.ROOT),hash,"虚构收银"+role);
            ids.put(role,id);users.addRole(id,role);
        }
    }
    /** Purges every trace of earlier runs; cashier facts are append-only so triggers are suspended for test cleanup only. */
    private void purgeAll() { purgeBusiness();db.update("delete from user_roles where user_id in("+OWN+")");db.update("delete from users where id in("+OWN+")"); }
    private void purgeBusiness() {
        List<Long> stays=db.queryForList("select id from stay_records where created_by in("+OWN+")",Long.class);
        String staySet=stays.isEmpty()?"(select id from stay_records where created_by in("+OWN+"))":
            "("+stays.stream().map(String::valueOf).reduce((a,b)->a+","+b).orElse("")+")";
        db.update("delete from idempotency_requests where operator_id in("+OWN+")");
        db.execute("alter table folio_charges disable trigger folio_charges_append_only");
        // V18 replaced payment_records_append_only with guard (UPDATE) + no_delete (DELETE).
        db.execute("alter table payment_records disable trigger payment_records_guard");
        db.execute("alter table payment_records disable trigger payment_records_no_delete");
        db.execute("alter table folio_events disable trigger folio_events_append_only");
        db.update("delete from folio_charges where stay_record_id in"+staySet);
        db.update("delete from payment_records where folio_id in(select id from folios where stay_record_id in"+staySet+")");
        db.update("delete from folio_events where folio_id in(select id from folios where stay_record_id in"+staySet+")");
        db.execute("alter table folio_charges enable trigger folio_charges_append_only");
        db.execute("alter table payment_records enable trigger payment_records_guard");
        db.execute("alter table payment_records enable trigger payment_records_no_delete");
        db.execute("alter table folio_events enable trigger folio_events_append_only");
        db.execute("alter table daily_reports disable trigger daily_reports_append_only");
        db.execute("alter table daily_close_runs disable trigger daily_close_runs_append_only");
        db.execute("alter table cash_counts disable trigger cash_counts_append_only");
        db.update("delete from daily_reports where business_date in(select business_date from business_days where policy_id in(select id from billing_policies where created_by in("+OWN+")))");
        db.update("delete from daily_close_runs where business_date in(select business_date from business_days where policy_id in(select id from billing_policies where created_by in("+OWN+")))");
        db.update("delete from business_days where policy_id in(select id from billing_policies where created_by in("+OWN+"))");
        db.update("delete from cash_counts where cashier_shift_id in(select id from cashier_shifts where operator_id in("+OWN+"))");
        db.update("delete from cashier_shifts where operator_id in("+OWN+")");
        db.execute("alter table daily_reports enable trigger daily_reports_append_only");
        db.execute("alter table daily_close_runs enable trigger daily_close_runs_append_only");
        db.execute("alter table cash_counts enable trigger cash_counts_append_only");
        db.update("delete from folios where stay_record_id in"+staySet);
        db.update("delete from room_rate_days where updated_by in("+OWN+")");
        db.update("delete from rate_plans where created_by in("+OWN+")");
        db.update("delete from service_catalog where created_by in("+OWN+")");
        db.update("delete from billing_policies where created_by in("+OWN+")");
        db.update("delete from audit_logs where operator_id in("+OWN+")");
        db.update("delete from stay_room_segments where stay_record_id in"+staySet);
        db.update("delete from stay_records where id in"+staySet);
        db.update("delete from rooms where room_number like 'CAT%'");
        db.update("delete from room_types where type_code like 'CAT%'");
    }
    @BeforeEach void clean() {
        if(!ids.isEmpty()) purgeBusiness();
        for(var entry:ids.entrySet())
            db.update("update users set enabled=true,password_hash=?,failed_login_attempts=0,locked_until=null where id=?",passwords.encode(password),entry.getValue());
    }
    @AfterAll void after() {
        if(!ids.isEmpty()) purgeBusiness();
        for(long id:ids.values()){db.update("delete from user_roles where user_id=?",id);db.update("delete from users where id=?",id);}
    }
    String key(){return UUID.randomUUID().toString();}
    final class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> request(String method,String path,Object body,String key)throws Exception {
            var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
            if(!method.equals("GET")){var csrf=ok(get("/api/auth/csrf"));r.header(csrf.path("headerName").asText(),csrf.path("token").asText());}
            if(key!=null)r.header("Idempotency-Key",key);
            return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path)throws Exception{return request("GET",path,null,null);}
        HttpResponse<String> post(String path,Object body)throws Exception{return request("POST",path,body,key());}
        HttpResponse<String> post(String path,Object body,String idempotencyKey)throws Exception{return request("POST",path,body,idempotencyKey);}
    }
    Browser login(String role)throws Exception {
        var b=new Browser();ok(b.post("/api/auth/login",Map.of("username","cashier_test_"+role.toLowerCase(Locale.ROOT),"password",password)));return b;
    }
    JsonNode ok(HttpResponse<String> response)throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");}
    String error(HttpResponse<String> response)throws Exception {var body=json.readTree(response.body());assertThat(body.path("success").asBoolean()).isFalse();return body.path("code").asText();}

    record StayRef(long stayId,long segmentId,long roomId,long roomTypeId,String roomNumber) {}
    StayRef sqlStay(int checkInDaysAgo,int stayNights)throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        long typeId=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values(?,'收银测试房型',2) returning id",Long.class,"CAT"+suffix);
        String roomNumber="CAT"+suffix.substring(0,6)+String.format("%02d",Math.abs(suffix.hashCode())%90+10);
        long roomId=db.queryForObject("insert into rooms(room_number,floor,room_type_id) values(?,8,?) returning id",Long.class,roomNumber,typeId);
        long actor=ids.get("FRONT_DESK");
        long stayId=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now()-interval '"+checkInDaysAgo+" days',now()+interval '"+(stayNights-checkInDaysAgo)+" days',?) returning id",Long.class,actor);
        long segmentId=db.queryForObject("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at) values(?,?,?,?,now()-interval '"+checkInDaysAgo+" days') returning id",Long.class,stayId,roomId,roomNumber,"收银测试房型");
        return new StayRef(stayId,segmentId,roomId,typeId,roomNumber);
    }
    /** Manager-configured baseline: active policy (cutoff 04:00) + one plan per room type; returns plan id. */
    long configure(Browser manager,long roomTypeId,String price)throws Exception {
        ok(manager.post("/api/billing/policies",Map.of("billingCutoffTime","04:00","earlyCheckinMode","FULL_NIGHT","lateCheckoutMode","HOURLY","earlyCheckoutMode","FULL_NIGHT","roomChangePriceMode","BY_NEW_SEGMENT","extensionPriceMode","CURRENT_RATE")));
        var plan=ok(manager.post("/api/billing/rate-plans",Map.of("roomTypeId",roomTypeId,"planCode","STD","planName","门市价","nightlyPrice",price)));
        return plan.path("id").asLong();
    }
    /** Opens a folio, posts the room night for the given date and receives cash; returns the folio id. */
    long folioWithNightAndCash(Browser front,long stayId,long planId,String nightDate,String cash)throws Exception {
        var folio=ok(front.post("/api/stays/"+stayId+"/folio",Map.of()));
        long folioId=folio.path("folio").path("id").asLong();
        ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",nightDate,"ratePlanId",planId)));
        ok(front.post("/api/folios/"+folioId+"/payments",Map.of("method","CASH","purpose","PAYMENT","amount",cash)));
        return folioId;
    }
    String day(int offset){return LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(offset).toString();}

    @Test @Order(1) void shiftLifecyclePermissionsAndAttribution()throws Exception {
        var hk=login("HOUSEKEEPING_STAFF");var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        assertThat(hk.get("/api/cashier-shifts").statusCode()).isEqualTo(403);
        var stay=sqlStay(1,2);long planId=configure(manager,stay.roomTypeId(),"288");
        // Shift needs an active policy: it pins today's business day. The original open uses a fixed key for replay.
        String openKey=key();
        var shift=ok(front.post("/api/cashier-shifts",Map.of("openedFloat","100"),openKey));
        long shiftId=shift.path("id").asLong();
        assertThat(shift.path("status").asText()).isEqualTo("OPEN");assertThat(shift.path("openedFloat").asText()).isEqualTo("100.00");
        assertThat(error(front.post("/api/cashier-shifts",Map.of("openedFloat","50")))).isEqualTo("SHIFT_ALREADY_OPEN");
        // Replay with the same key returns the same shift; a different key is a new (refused) open.
        var replay=ok(front.post("/api/cashier-shifts",Map.of("openedFloat","100"),openKey));
        assertThat(replay.path("id").asLong()).isEqualTo(shiftId);
        assertThat(error(front.post("/api/cashier-shifts",Map.of("openedFloat","60"),key()))).isEqualTo("SHIFT_ALREADY_OPEN");
        // Manager sees all shifts; a second front cashier would only see their own.
        var all=ok(manager.get("/api/cashier-shifts?scope=all"));
        assertThat(all.path("total").asLong()).isEqualTo(1);
        // Cash receipt during the shift attaches to it; the day cut of the policy applies for day attribution.
        long folioId=folioWithNightAndCash(front,stay.stayId(),planId,day(0),"206");
        long attached=db.queryForObject("select cashier_shift_id from payment_records where folio_id=? and direction='RECEIPT'",Long.class,folioId);
        assertThat(attached).isEqualTo(shiftId);
        var detail=ok(front.get("/api/cashier-shifts/"+shiftId));
        assertThat(detail.path("money").path("receipts").asText()).isEqualTo("206.00");
        assertThat(detail.path("money").path("expectedCash").asText()).isEqualTo("306.00");
        // Manager (no open shift) receives a payment: it stays shiftless but still hits the business day.
        var managerFolio=ok(manager.post("/api/folios/"+folioId+"/payments",Map.of("method","CASH","purpose","PAYMENT","amount","20")));
        Long managerShift=db.queryForObject("select cashier_shift_id from payment_records where id=?",Long.class,managerFolio.path("id").asLong());
        assertThat(managerShift).isNull();
        // Submit: counted != expected requires a reason; wrong version conflicts.
        assertThat(error(front.post("/api/cashier-shifts/"+shiftId+"/submit",Map.of("countedCash","280","version",0)))).isEqualTo("VALIDATION_ERROR");
        assertThat(error(front.post("/api/cashier-shifts/"+shiftId+"/submit",Map.of("countedCash","280","differenceReason","找零误差","version",99)))).isEqualTo("STALE_VERSION");
        var submitted=ok(front.post("/api/cashier-shifts/"+shiftId+"/submit",Map.of("countedCash","280","differenceReason","虚构找零误差","version",0)));
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.path("differenceReason").asText()).isEqualTo("虚构找零误差");
        // Only a manager confirms; confirm is one-way.
        assertThat(front.post("/api/cashier-shifts/"+shiftId+"/confirm",Map.of("version",1)).statusCode()).isEqualTo(403);
        var confirmed=ok(manager.post("/api/cashier-shifts/"+shiftId+"/confirm",Map.of("version",1)));
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("confirmedByName").asText()).isEqualTo("虚构收银HOTEL_MANAGER");
        assertThat(error(manager.post("/api/cashier-shifts/"+shiftId+"/confirm",Map.of("version",2)))).isEqualTo("SHIFT_STATE");
    }

    @Test @Order(2) void closeHappyPathFreezesReportAndBlocksReopen()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(1,2);long planId=configure(manager,stay.roomTypeId(),"288");
        folioWithNightAndCash(front,stay.stayId(),planId,day(0),"288");
        assertThat(front.post("/api/business-days/"+day(0)+"/close",Map.of()).statusCode()).isEqualTo(403);
        var hk=login("HOUSEKEEPING_STAFF");
        assertThat(hk.get("/api/business-days").statusCode()).isEqualTo(403);
        var days=ok(front.get("/api/business-days"));
        assertThat(days.isArray()).isTrue();
        var todayRow=days.get(0);
        assertThat(todayRow.path("businessDate").asText()).isEqualTo(day(0));
        assertThat(todayRow.path("status").asText()).isEqualTo("OPEN");
        assertThat(todayRow.path("cutoff").asText()).isEqualTo("04:00");
        var result=ok(manager.post("/api/business-days/"+day(0)+"/close",Map.of()));
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("stepsDone").asInt()).isEqualTo(4);
        // The day is CLOSED, frozen report matches the source sums, and another close is refused.
        assertThat(ok(front.get("/api/business-days")).get(0).path("status").asText()).isEqualTo("CLOSED");
        assertThat(error(manager.post("/api/business-days/"+day(0)+"/close",Map.of()))).isEqualTo("DAY_ALREADY_CLOSED");
        var reports=ok(manager.get("/api/reports/daily?page=1&pageSize=10"));
        assertThat(reports.path("total").asLong()).isGreaterThanOrEqualTo(1);
        var snapshot=reports.path("items").get(0).path("snapshot");
        // Snapshot money fields are JSON numbers; compare by value so the parser's scale choice is irrelevant.
        assertThat(snapshot.path("charges").path("netReceivable").decimalValue()).isEqualByComparingTo("288.00");
        assertThat(snapshot.path("payments").path("netReceived").decimalValue()).isEqualByComparingTo("288.00");
        assertThat(snapshot.path("occupancy").path("roomNightsSold").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.path("occupancy").path("availableRooms").asInt()).isGreaterThanOrEqualTo(0);
        // Report reads are manager-only finance data.
        assertThat(front.get("/api/reports/daily").statusCode()).isEqualTo(403);
        // Close, submit and confirm all leave audit trails in the CASHIER module.
        assertThat(db.queryForObject("select count(*) from audit_logs where action='BUSINESS_DAY_CLOSE'",Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test @Order(3) void closeFailsOnMissingRoomFeeThenResumes()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(1,3);long planId=configure(manager,stay.roomTypeId(),"288");
        // Yesterday has a covering stay with no folio at all.
        var first=manager.post("/api/business-days/"+day(-1)+"/close",Map.of());
        assertThat(error(first)).isEqualTo("CLOSE_FAILED");
        assertThat(json.readTree(first.body()).path("message").asText()).contains("未开立客账");
        // After opening the folio but before posting the night, the gap is named precisely.
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of()));
        long folioId=folio.path("folio").path("id").asLong();
        var second=manager.post("/api/business-days/"+day(-1)+"/close",Map.of());
        assertThat(error(second)).isEqualTo("CLOSE_FAILED");
        assertThat(json.readTree(second.body()).path("message").asText()).contains("缺少房费");
        // The failures are durable and the day stays OPEN for retry.
        assertThat(db.queryForObject("select status from business_days where business_date=?",String.class,java.time.LocalDate.parse(day(-1)))).isEqualTo("OPEN");
        assertThat(db.queryForObject("select count(*) from daily_close_runs where business_date=? and status='FAILED'",Integer.class,java.time.LocalDate.parse(day(-1)))).isEqualTo(2);
        // Fix the gap, then the retry completes (idempotent re-verification, new run).
        ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",day(-1),"ratePlanId",planId)));
        var retry=ok(manager.post("/api/business-days/"+day(-1)+"/close",Map.of()));
        assertThat(retry.path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test @Order(4) void dayCutAttributionUsesPinnedCutoff()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(3,4);long planId=configure(manager,stay.roomTypeId(),"288");
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of()));
        long folioId=folio.path("folio").path("id").asLong();
        ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",day(-2),"ratePlanId",planId)));
        // A receipt at 01:00 Beijing time (before the 04:00 cutoff) belongs to the previous business day.
        db.update("insert into payment_records(folio_id,direction,method,purpose,amount,currency,received_by,received_at) values(?,'RECEIPT','CASH','PAYMENT',50.00,'CNY',?,(now() at time zone 'Asia/Shanghai')::date - interval '1 day' + time '01:00')",folioId,ids.get("FRONT_DESK"));
        var result=ok(manager.post("/api/business-days/"+day(-2)+"/close",Map.of()));
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        var reports=ok(manager.get("/api/reports/daily?from="+day(-2)+"&to="+day(-2)));
        var snapshot=reports.path("items").get(0).path("snapshot");
        assertThat(snapshot.path("payments").path("cashIn").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(snapshot.path("payments").path("netReceived").decimalValue()).isEqualByComparingTo("50.00");
    }

    @Test @Order(5) void concurrentCloseIsBlockedAtDatabaseLevel()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(2,3);long planId=configure(manager,stay.roomTypeId(),"288");
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of()));
        ok(front.post("/api/folios/"+folio.path("folio").path("id").asLong()+"/charges",Map.of("category","ROOM_NIGHT","businessDate",day(-1),"ratePlanId",planId)));
        // Simulate another instance holding the day's RUNNING batch; the unique index must refuse a second one.
        db.update("insert into business_days(business_date,policy_id) select ?,id from billing_policies where is_active on conflict (business_date) do nothing",java.time.LocalDate.parse(day(-1)));
        long policy=db.queryForObject("select policy_id from business_days where business_date=?",Long.class,java.time.LocalDate.parse(day(-1)));
        db.update("delete from daily_close_runs where business_date=? and status='RUNNING'",java.time.LocalDate.parse(day(-1)));
        db.update("insert into daily_close_runs(business_date,status,started_by) values(?,'RUNNING',?)",java.time.LocalDate.parse(day(-1)),ids.get("HOTEL_MANAGER"));
        var second=manager.post("/api/business-days/"+day(-1)+"/close",Map.of());
        assertThat(second.statusCode()).isEqualTo(409);
    }

    @Test @Order(6) void realBrowserAcceptance()throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Browser tests explicitly enabled");
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(1,2);long planId=configure(manager,stay.roomTypeId(),"288");
        // The desk cashier's shift exists before the browser run; the browser performs submit -> confirm -> close -> report.
        String openKey=key();
        var shift=ok(front.post("/api/cashier-shifts",Map.of("openedFloat","100"),openKey));
        long shiftId=shift.path("id").asLong();
        long folioId=folioWithNightAndCash(front,stay.stayId(),planId,day(0),"206");
        var attached=db.queryForObject("select cashier_shift_id from payment_records where folio_id=?",Long.class,folioId);
        assertThat(attached).isEqualTo(shiftId);
        var fixtures=new LinkedHashMap<String,Object>();
        fixtures.put("shiftId",shiftId);fixtures.put("dayDate",day(0));fixtures.put("stayId",stay.stayId());fixtures.put("folioId",folioId);
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).getParent();var log=root.resolve(".local/cashier-browser.log");
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","cashier.spec.ts").directory(root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_CASHIER_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-cashier-results.json");
        var process=builder.start();if(!process.waitFor(300,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Cashier browser timeout");}
        assertThat(process.exitValue()).as("See private cashier-browser.log").isZero();
    }
}
