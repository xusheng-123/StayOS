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
import java.util.concurrent.*;
import java.util.function.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingIntegrationTest {
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
    @LocalServerPort int port;
    final Map<String,Long> ids=new LinkedHashMap<>();
    final String password=UUID.randomUUID().toString();
    static final String OWN="select id from users where username like 'billing_test_%'";
    @BeforeAll void setup() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String hash=passwords.encode(password);purgeAll();
        for(String role:List.of("ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,
                "billing_test_"+role.toLowerCase(Locale.ROOT),hash,"虚构账务"+role);
            ids.put(role,id);users.addRole(id,role);
        }
    }
    /** Purges every trace of earlier runs; billing facts are append-only so triggers are suspended for test cleanup only. */
    private void purgeAll() { purgeBusiness();db.update("delete from user_roles where user_id in("+OWN+")");db.update("delete from users where id in("+OWN+")"); }
    private void purgeBusiness() {
        // Drop the failure-injection trigger everywhere before its function; a failed run can leave it behind.
        db.execute("drop trigger if exists bl_fail on audit_logs");
        db.execute("drop trigger if exists bl_fail on folio_events");
        db.execute("drop trigger if exists bl_fail on folio_charges");
        db.execute("drop function if exists bl_fail_fn()");
        List<Long> stays=db.queryForList("select id from stay_records where created_by in("+OWN+")",Long.class);
        String staySet=stays.isEmpty()?"(select id from stay_records where created_by in("+OWN+"))":
            "("+stays.stream().map(String::valueOf).reduce((a,b)->a+","+b).orElse("")+")";
        // Idempotency rows reference every billing fact, so they go first.
        db.update("delete from idempotency_requests where operator_id in("+OWN+")");
        db.execute("alter table folio_charges disable trigger folio_charges_append_only");
        db.execute("alter table payment_records disable trigger payment_records_append_only");
        db.execute("alter table folio_events disable trigger folio_events_append_only");
        db.execute("alter table service_order_items disable trigger service_order_item_edit");
        db.update("delete from folio_charges where stay_record_id in"+staySet);
        db.update("delete from payment_records where folio_id in(select id from folios where stay_record_id in"+staySet+")");
        db.update("delete from folio_events where folio_id in(select id from folios where stay_record_id in"+staySet+")");
        db.update("delete from folios where stay_record_id in"+staySet);
        db.update("delete from service_order_items where order_id in(select id from service_orders where stay_record_id in"+staySet+")");
        db.update("delete from service_orders where stay_record_id in"+staySet);
        db.update("delete from room_rate_days where updated_by in("+OWN+")");
        db.update("delete from rate_plans where created_by in("+OWN+")");
        db.update("delete from service_catalog where created_by in("+OWN+")");
        db.update("delete from billing_policies where created_by in("+OWN+")");
        db.update("delete from audit_logs where operator_id in("+OWN+")");
        db.execute("alter table folio_charges enable trigger folio_charges_append_only");
        db.execute("alter table payment_records enable trigger payment_records_append_only");
        db.execute("alter table folio_events enable trigger folio_events_append_only");
        db.execute("alter table service_order_items enable trigger service_order_item_edit");
        db.update("delete from guests where id in(select guest_id from stay_guests where stay_record_id in"+staySet+")");
        db.update("delete from stay_guests where stay_record_id in"+staySet);
        db.update("delete from stay_room_segments where stay_record_id in"+staySet);
        db.update("delete from stay_records where id in"+staySet);
        db.update("delete from rooms where room_number like 'BLT%'");
        db.update("delete from room_types where type_code like 'BLT%'");
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
        HttpResponse<String> put(String path,Object body)throws Exception{return request("PUT",path,body,null);}
    }
    Browser login(String role)throws Exception {
        var b=new Browser();ok(b.post("/api/auth/login",Map.of("username","billing_test_"+role.toLowerCase(Locale.ROOT),"password",password)));return b;
    }
    JsonNode ok(HttpResponse<String> response)throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");}
    String error(HttpResponse<String> response)throws Exception {var body=json.readTree(response.body());assertThat(body.path("success").asBoolean()).isFalse();return body.path("code").asText();}

    /** SQL-inserted past-dated stay with its own room, so room-night dates are deterministic. */
    record StayRef(long stayId,long segmentId,long roomId,long roomTypeId,String roomNumber) {}
    StayRef sqlStay(int checkInDaysAgo,int stayNights)throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        long typeId=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values(?,'账务测试房型',2) returning id",Long.class,"BLT"+suffix);
        String roomNumber="BLT"+suffix.substring(0,6)+String.format("%02d",Math.abs(suffix.hashCode())%90+10);
        long roomId=db.queryForObject("insert into rooms(room_number,floor,room_type_id) values(?,8,?) returning id",Long.class,roomNumber,typeId);
        long actor=ids.get("FRONT_DESK");
        long stayId=db.queryForObject("insert into stay_records(check_in_at,expected_check_out_at,created_by) values(now()-interval '"+checkInDaysAgo+" days',now()+interval '"+(stayNights-checkInDaysAgo)+" days',?) returning id",Long.class,actor);
        long segmentId=db.queryForObject("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at) values(?,?,?,?,now()-interval '"+checkInDaysAgo+" days') returning id",Long.class,stayId,roomId,roomNumber,"账务测试房型");
        return new StayRef(stayId,segmentId,roomId,typeId,roomNumber);
    }
    /** Manager-configured baseline: active policy + one plan per room type. */
    record Config(long planId,BigDecimal price) {}
    Config configure(Browser manager,long roomTypeId,String price)throws Exception {
        ok(manager.post("/api/billing/policies",Map.of("billingCutoffTime","04:00","earlyCheckinMode","FULL_NIGHT","lateCheckoutMode","HOURLY","earlyCheckoutMode","FULL_NIGHT","roomChangePriceMode","BY_NEW_SEGMENT","extensionPriceMode","CURRENT_RATE")));
        var plan=ok(manager.post("/api/billing/rate-plans",Map.of("roomTypeId",roomTypeId,"planCode","STD","planName","门市价","nightlyPrice",price)));
        return new Config(plan.path("id").asLong(),new BigDecimal(price));
    }
    String today(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();}
    String daysFromToday(int days){return LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(days).toString();}

    @Test @Order(1) void billingConfigurationBoundariesAndAudit()throws Exception {
        var hk=login("HOUSEKEEPING_MANAGER");var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        assertThat(hk.get("/api/billing/policies").statusCode()).isEqualTo(403);
        assertThat(front.post("/api/billing/policies",Map.of("billingCutoffTime","04:00","earlyCheckinMode","FULL_NIGHT","lateCheckoutMode","HOURLY","earlyCheckoutMode","FULL_NIGHT","roomChangePriceMode","BY_NEW_SEGMENT","extensionPriceMode","CURRENT_RATE")).statusCode()).isEqualTo(403);
        var overview=ok(front.get("/api/billing/policies"));assertThat(overview.path("active").isNull()).isTrue();
        // Opening a folio without an active policy is refused.
        var stay=sqlStay(1,2);
        assertThat(error(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of()))).isEqualTo("BILLING_POLICY_REQUIRED");
        var policy=ok(manager.post("/api/billing/policies",Map.of("billingCutoffTime","04:00","earlyCheckinMode","FULL_NIGHT","lateCheckoutMode","HOURLY","earlyCheckoutMode","FULL_NIGHT","roomChangePriceMode","BY_NEW_SEGMENT","extensionPriceMode","CURRENT_RATE")));
        assertThat(policy.path("version").asInt()).isEqualTo(1);assertThat(policy.path("active").asBoolean()).isTrue();
        var second=ok(manager.post("/api/billing/policies",Map.of("billingCutoffTime","05:00","earlyCheckinMode","DECLINED","lateCheckoutMode","DECLINED","earlyCheckoutMode","CREDIT_UNUSED","roomChangePriceMode","ORIGINAL_PRICE","extensionPriceMode","RATE_AT_CHECKIN")));
        assertThat(second.path("version").asInt()).isEqualTo(2);
        overview=ok(manager.get("/api/billing/policies"));
        assertThat(overview.path("active").path("version").asInt()).isEqualTo(2);// history only lists archived versions; the active one is returned separately.
        assertThat(overview.path("history").size()).isEqualTo(1);assertThat(overview.path("history").get(0).path("version").asInt()).isEqualTo(1);
        assertThat(manager.post("/api/billing/policies",Map.of("billingCutoffTime","25:00","earlyCheckinMode","FULL_NIGHT","lateCheckoutMode","HOURLY","earlyCheckoutMode","FULL_NIGHT","roomChangePriceMode","BY_NEW_SEGMENT","extensionPriceMode","CURRENT_RATE")).statusCode()).isEqualTo(400);
        // Rate plans and day prices are manager-only; plan code duplicates conflict.
        var plan=ok(manager.post("/api/billing/rate-plans",Map.of("roomTypeId",stay.roomTypeId(),"planCode","STD","planName","门市价","nightlyPrice","200")));
        assertThat(plan.path("nightlyPrice").asText()).isEqualTo("200.00");
        assertThat(error(manager.post("/api/billing/rate-plans",Map.of("roomTypeId",stay.roomTypeId(),"planCode","STD","planName","重复方案","nightlyPrice","300")))).isEqualTo("RATE_PLAN_CODE_EXISTS");
        assertThat(front.post("/api/billing/rate-plans",Map.of("roomTypeId",stay.roomTypeId(),"planCode","F2","planName","前台方案","nightlyPrice","1")).statusCode()).isEqualTo(403);
        assertThat(error(manager.post("/api/billing/rate-plans",Map.of("roomTypeId",stay.roomTypeId(),"planCode","BAD","planName","负价方案","nightlyPrice","-1")))).isEqualTo("VALIDATION_ERROR");
        var updated=ok(manager.put("/api/billing/rate-plans/"+plan.path("id").asLong(),Map.of("planName","门市价改","nightlyPrice","220","enabled",true,"version",plan.path("version").asLong())));
        assertThat(updated.path("nightlyPrice").asText()).isEqualTo("220.00");
        assertThat(manager.put("/api/billing/rate-plans/"+plan.path("id").asLong(),Map.of("planName","旧版本","nightlyPrice","1","enabled",true,"version",0)).statusCode()).isEqualTo(409);
        var day=ok(manager.post("/api/billing/rate-plans/"+plan.path("id").asLong()+"/rate-days",Map.of("stayDate",daysFromToday(7),"nightlyPrice","288")));
        assertThat(day.path("nightlyPrice").asText()).isEqualTo("288.00");
        assertThat(ok(manager.post("/api/billing/rate-plans/"+plan.path("id").asLong()+"/rate-days",Map.of("stayDate",daysFromToday(7),"nightlyPrice","288"))).path("nightlyPrice").asText()).isEqualTo("288.00");
        var days=ok(manager.get("/api/billing/rate-plans/"+plan.path("id").asLong()+"/rate-days?from="+daysFromToday(6)+"&to="+daysFromToday(8)));
        assertThat(days.size()).isEqualTo(1);
        // Catalog maintenance mirrors the same boundaries.
        var item=ok(manager.post("/api/billing/service-catalog",Map.of("itemCode","WATER","itemName","矿泉水","pricingUnit","ITEM","unitPrice","3")));
        assertThat(error(manager.post("/api/billing/service-catalog",Map.of("itemCode","WATER","itemName","重复","pricingUnit","ITEM","unitPrice","3")))).isEqualTo("ITEM_CODE_EXISTS");
        assertThat(ok(manager.put("/api/billing/service-catalog/"+item.path("id").asLong(),Map.of("itemName","矿泉水(大)","pricingUnit","ITEM","unitPrice","3.5","enabled",true,"version",item.path("version").asLong()))).path("unitPrice").asText()).isEqualTo("3.50");
        assertThat(ok(manager.get("/api/billing/service-catalog")).size()).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from audit_logs where operator_id=? and action='BILLING_POLICY_UPDATE'",Integer.class,ids.get("HOTEL_MANAGER"))).isGreaterThanOrEqualTo(2);
        assertThat(db.queryForObject("select count(*) from audit_logs where action='CHARGE_POST'",Integer.class)).isZero();
    }

    @Test @Order(2) void folio430ExampleRoomNightsServiceReceiptRefund()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(1,2);var config=configure(manager,stay.roomTypeId(),"200");
        String openKey=key();
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of(),openKey)).path("folio");
        assertThat(folio.path("pricingStatus").asText()).isEqualTo("PRICED");assertThat(folio.path("status").asText()).isEqualTo("OPEN");
        assertThat(ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of(),openKey)).path("folio").path("id").asLong()).isEqualTo(folio.path("id").asLong());
        var receipt=ok(front.get("/api/folios/result/"+openKey+"?operation=FOLIO_OPEN"));
        assertThat(receipt.path("folio").path("id").asLong()).isEqualTo(folio.path("id").asLong());
        long folioId=folio.path("id").asLong();
        // Two past nights at 200 each; the duplicate night is refused by the unique source key.
        var night1=ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",daysFromToday(-1),"ratePlanId",config.planId())));
        assertThat(night1.path("amount").asText()).isEqualTo("200.00");assertThat(night1.path("name").asText()).contains("门市价");
        assertThat(ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",today(),"ratePlanId",config.planId()))).path("amount").asText()).isEqualTo("200.00");
        assertThat(error(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",daysFromToday(-1),"ratePlanId",config.planId())))).isEqualTo("CHARGE_ALREADY_POSTED");
        // Service order 10 x water at snapshot 3.0 -> 30 on fulfilment only.
        var catalogItem=ok(manager.post("/api/billing/service-catalog",Map.of("itemCode","WATER","itemName","矿泉水","pricingUnit","ITEM","unitPrice","3")));
        var order=ok(front.post("/api/stays/"+stay.stayId()+"/service-orders",Map.of("items",List.of(Map.of("catalogItemId",catalogItem.path("id").asLong(),"quantity",10)))));
        var fulfilled=ok(front.post("/api/service-orders/"+order.path("id").asLong()+"/fulfill",Map.of(),key()));
        assertThat(fulfilled.path("status").asText()).isEqualTo("FULFILLED");assertThat(fulfilled.path("totalAmount").asText()).isEqualTo("30.00");
        var detail=ok(front.get("/api/folios/"+folioId));
        assertThat(detail.path("folio").path("summary").path("netReceivable").asText()).isEqualTo("430.00");
        assertThat(detail.path("folio").path("summary").path("balance").asText()).isEqualTo("430.00");
        // Receipt 500 then refund 70 leaves net received 430 and balance zero (guide 11.3 example).
        var payment=ok(front.post("/api/folios/"+folioId+"/payments",Map.of("method","CASH","purpose","PAYMENT","amount","500")));
        assertThat(ok(front.get("/api/folios/"+folioId)).path("folio").path("summary").path("balance").asText()).isEqualTo("-70.00");
        var refund=ok(manager.post("/api/folios/"+folioId+"/refunds",Map.of("paymentRecordId",payment.path("id").asLong(),"amount","70","reason","虚构多收退还")));
        assertThat(refund.path("direction").asText()).isEqualTo("REFUND");assertThat(refund.path("method").asText()).isEqualTo("CASH");
        var settled=ok(front.get("/api/folios/"+folioId)).path("folio").path("summary");
        assertThat(settled.path("netReceivable").asText()).isEqualTo("430.00");assertThat(settled.path("netReceived").asText()).isEqualTo("430.00");
        assertThat(settled.path("balance").asText()).isEqualTo("0.00");
        assertThat(error(manager.post("/api/folios/"+folioId+"/refunds",Map.of("paymentRecordId",payment.path("id").asLong(),"amount","1","reason","虚构再退")))).isEqualTo("REFUND_EXCEEDS_LIMIT");
        // Zero-balance close needs no acknowledgement; charge after close is refused by service and trigger.
        long version=ok(front.get("/api/folios/"+folioId)).path("folio").path("version").asLong();
        var closed=ok(front.post("/api/folios/"+folioId+"/close",Map.of("version",version),key()));
        assertThat(closed.path("folio").path("status").asText()).isEqualTo("CLOSED");
        assertThat(error(front.post("/api/folios/"+folioId+"/charges",Map.of("category","MANUAL","businessDate",today(),"title","关账后费用","unitPrice","10","quantity",1)))).isEqualTo("FOLIO_STATE");
        assertThat(db.queryForObject("select count(*) from folio_events where folio_id=? and event_type in('OPENED','CLOSED')",Integer.class,folioId)).isEqualTo(2);
        // Charge/payment audits carry their own record id as target, so count the whole flow by action.
        assertThat(db.queryForObject("select count(*) from audit_logs where action in('FOLIO_OPEN','CHARGE_POST','PAYMENT_RECEIVE','PAYMENT_REFUND','FOLIO_CLOSE')",Integer.class)).isEqualTo(6);
    }

    @Test @Order(3) void chargeValidationAdjustmentsAndLegacyAccess()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(2,3);var config=configure(manager,stay.roomTypeId(),"200");
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of())).path("folio");
        long folioId=folio.path("id").asLong();
        assertThat(error(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",daysFromToday(-3),"ratePlanId",config.planId())))).isEqualTo("NO_ROOM_SEGMENT");
        assertThat(error(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",daysFromToday(1),"ratePlanId",config.planId())))).isEqualTo("VALIDATION_ERROR");
        assertThat(error(front.post("/api/folios/"+folioId+"/charges",Map.of("category","MANUAL","businessDate",today(),"title","金额格式","unitPrice","1.999","quantity",1)))).isEqualTo("VALIDATION_ERROR");
        assertThat(error(front.post("/api/folios/"+folioId+"/charges",Map.of("category","MANUAL","businessDate",today(),"title","负数金额","unitPrice","-5","quantity",1)))).isEqualTo("VALIDATION_ERROR");
        var otherTypeStay=sqlStay(2,2);
        // A plan that exists but belongs to another room type must be indistinguishable from a missing one.
        var otherPlan=ok(manager.post("/api/billing/rate-plans",Map.of("roomTypeId",otherTypeStay.roomTypeId(),"planCode","OTH","planName","他型门市价","nightlyPrice","150")));
        assertThat(error(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",daysFromToday(-1),"ratePlanId",otherPlan.path("id").asLong())))).isEqualTo("RATE_PLAN_NOT_FOUND");
        // Manual charges allow quantity and snapshot the confirmed amount.
        var manual=ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","MANUAL","businessDate",daysFromToday(-1),"title","虚构赔偿费","unitPrice","40.5","quantity",2)));
        assertThat(manual.path("amount").asText()).isEqualTo("81.00");
        assertThat(error(front.post("/api/folios/"+folioId+"/charges",Map.of("category","MANUAL","businessDate",daysFromToday(-1),"title","零金额","unitPrice","0","quantity",1)))).isEqualTo("VALIDATION_ERROR");
        // Adjustment needs billing:adjust; partial credit then full reversal exactly consumes the original.
        assertThat(error(front.post("/api/folios/"+folioId+"/adjustments",Map.of("originalChargeId",manual.path("id").asLong(),"mode","CORRECTION","amount","10","reason","前台越权")))).isEqualTo("ACCESS_DENIED");
        assertThat(error(manager.post("/api/folios/"+folioId+"/adjustments",Map.of("originalChargeId",manual.path("id").asLong(),"mode","CORRECTION","amount","100","reason","超过原费用")))).isEqualTo("CREDIT_EXCEEDS_CHARGE");
        var credit=ok(manager.post("/api/folios/"+folioId+"/adjustments",Map.of("originalChargeId",manual.path("id").asLong(),"mode","CORRECTION","amount","20","reason","虚构部分折让")));
        assertThat(credit.path("direction").asText()).isEqualTo("CREDIT");assertThat(credit.path("category").asText()).isEqualTo("CORRECTION");
        assertThat(ok(manager.get("/api/folios/"+folioId)).path("folio").path("summary").path("credits").asText()).isEqualTo("20.00");
        var reversal=ok(manager.post("/api/folios/"+folioId+"/adjustments",Map.of("originalChargeId",manual.path("id").asLong(),"mode","REVERSAL","reason","虚构整笔冲正")));
        assertThat(reversal.path("category").asText()).isEqualTo("REVERSAL");assertThat(reversal.path("amount").asText()).isEqualTo("61.00");
        assertThat(error(manager.post("/api/folios/"+folioId+"/adjustments",Map.of("originalChargeId",manual.path("id").asLong(),"mode","REVERSAL","reason","重复冲正")))).isEqualTo("NOTHING_TO_CREDIT");
        // Legacy access: opening with an explicit effective date marks PRICING_REQUIRED and blocks back-filling.
        assertThat(ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of("chargesFrom",today()))).path("folio").path("id").asLong()).isEqualTo(folioId);
        var legacyStay=sqlStay(3,3);
        var legacyFolio=ok(front.post("/api/stays/"+legacyStay.stayId()+"/folio",Map.of("chargesFrom",today()))).path("folio");
        assertThat(legacyFolio.path("pricingStatus").asText()).isEqualTo("PRICING_REQUIRED");
        assertThat(legacyFolio.path("chargesEffectiveFrom").asText()).isEqualTo(today());
        assertThat(error(front.post("/api/folios/"+legacyFolio.path("id").asLong()+"/charges",Map.of("category","ROOM_NIGHT","businessDate",daysFromToday(-1),"ratePlanId",config.planId())))).isEqualTo("CHARGE_BEFORE_EFFECTIVE");
    }

    @Test @Order(4) void paymentRulesAndConcurrentRefunds()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(1,2);var config=configure(manager,stay.roomTypeId(),"100");
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of())).path("folio");
        long folioId=folio.path("id").asLong();
        ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",daysFromToday(-1),"ratePlanId",config.planId())));
        // Non-cash references are unique per folio/method; cash never assumes global receipt numbers.
        ok(front.post("/api/folios/"+folioId+"/payments",Map.of("method","QR_PAYMENT","purpose","PAYMENT","amount","50","methodReference","EXT-BL-001")));
        assertThat(error(front.post("/api/folios/"+folioId+"/payments",Map.of("method","QR_PAYMENT","purpose","PAYMENT","amount","60","methodReference","EXT-BL-001")))).isEqualTo("PAYMENT_REFERENCE_EXISTS");
        var cashA=ok(front.post("/api/folios/"+folioId+"/payments",Map.of("method","CASH","purpose","PAYMENT","amount","10","methodReference","NO.001")));
        var cashB=ok(front.post("/api/folios/"+folioId+"/payments",Map.of("method","CASH","purpose","PAYMENT","amount","10","methodReference","NO.001")));
        assertThat(cashA.path("id").asLong()).isNotEqualTo(cashB.path("id").asLong());
        // Refunding while the folio still has outstanding debt is refused; correct charges first.
        assertThat(error(manager.post("/api/folios/"+folioId+"/refunds",Map.of("paymentRecordId",cashA.path("id").asLong(),"amount","5","reason","虚构欠款退款")))).isEqualTo("REFUND_EXCEEDS_LIMIT");
        // Partial credit of 30 leaves receivable 70; a deposit then creates a real prepaid surplus.
        ok(manager.post("/api/folios/"+folioId+"/adjustments",Map.of("originalChargeId",firstChargeId(folioId),"mode","CORRECTION","amount","30","reason","虚构部分折让")));
        var surplusReceipt=ok(front.post("/api/folios/"+folioId+"/payments",Map.of("method","BANK_TRANSFER","purpose","DEPOSIT","amount","80","methodReference","DEP-001")));
        // Concurrent refunds of the same receipt: the folio lock serialises, so exactly one wins.
        var refundKeyA=key();var refundKeyB=key();
        var executor=Executors.newFixedThreadPool(2);
        try {
            var tasks=List.of(
                (Callable<Integer>)()->manager.request("POST","/api/folios/"+folioId+"/refunds",Map.of("paymentRecordId",surplusReceipt.path("id").asLong(),"amount","50","reason","并发退款A"),refundKeyA).statusCode(),
                (Callable<Integer>)()->manager.request("POST","/api/folios/"+folioId+"/refunds",Map.of("paymentRecordId",surplusReceipt.path("id").asLong(),"amount","50","reason","并发退款B"),refundKeyB).statusCode());
            var results=executor.invokeAll(tasks);List<Integer> codes=new ArrayList<>();
            for(var result:results)codes.add(result.get());
            Collections.sort(codes);
            assertThat(codes).isEqualTo(List.of(200,409));
        } finally {executor.shutdownNow();}
        // Receivable 70, received 150-50=100 -> balance -30.00 (prepaid remainder), never a negative refund.
        assertThat(ok(front.get("/api/folios/"+folioId)).path("folio").path("summary").path("balance").asText()).isEqualTo("-30.00");
    }
    long firstChargeId(long folioId){return db.queryForObject("select id from folio_charges where folio_id=? and direction='CHARGE' order by id limit 1",Long.class,folioId);}

    @Test @Order(5) void serviceOrderLifecycleUniqueBilling()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(1,2);configure(manager,stay.roomTypeId(),"200");
        var catalogItem=ok(manager.post("/api/billing/service-catalog",Map.of("itemCode","NOODLE","itemName","泡面","pricingUnit","ITEM","unitPrice","5")));
        var catalogItem2=ok(manager.post("/api/billing/service-catalog",Map.of("itemCode","WASH","itemName","洗衣","pricingUnit","TIME","unitPrice","15")));
        var order=ok(front.post("/api/stays/"+stay.stayId()+"/service-orders",Map.of("items",List.of(Map.of("catalogItemId",catalogItem.path("id").asLong(),"quantity",2),Map.of("catalogItemId",catalogItem2.path("id").asLong(),"quantity",1)),"note","虚构加购")));
        long orderId=order.path("id").asLong();
        assertThat(order.path("status").asText()).isEqualTo("REQUESTED");
        // Fulfilment requires an open folio first.
        assertThat(error(front.post("/api/service-orders/"+orderId+"/fulfill",Map.of(),key()))).isEqualTo("FOLIO_REQUIRED");
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of())).path("folio");
        long folioId=folio.path("id").asLong();
        String fulfillKey=key();
        var fulfilled=ok(front.post("/api/service-orders/"+orderId+"/fulfill",Map.of(),fulfillKey));
        assertThat(ok(front.get("/api/folios/result/"+fulfillKey+"?operation=SERVICE_ORDER_FULFILL")).path("id").asLong()).isEqualTo(orderId);
        assertThat(error(front.post("/api/service-orders/"+orderId+"/fulfill",Map.of(),key()))).isEqualTo("ORDER_STATE");
        assertThat(db.queryForObject("select count(*) from folio_charges where folio_id=? and source_type='SERVICE_ORDER'",Integer.class,folioId)).isEqualTo(2);
        assertThat(db.queryForObject("select sum(amount) from folio_charges where folio_id=? and source_type='SERVICE_ORDER'",BigDecimal.class,folioId).stripTrailingZeros().toPlainString()).isEqualTo("25");
        // Items freeze at fulfilment.
        assertThat(front.put("/api/service-orders/"+orderId,Map.of("items",List.of(Map.of("catalogItemId",catalogItem.path("id").asLong(),"quantity",3)),"note","改数量","version",fulfilled.path("version").asLong())).statusCode()).isEqualTo(409);
        // A cancelled order is never billed.
        var cancelled=ok(front.post("/api/stays/"+stay.stayId()+"/service-orders",Map.of("items",List.of(Map.of("catalogItemId",catalogItem.path("id").asLong(),"quantity",1)))));
        assertThat(ok(front.post("/api/service-orders/"+cancelled.path("id").asLong()+"/cancel",Map.of("reason","虚构客人取消"),key())).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(front.post("/api/service-orders/"+cancelled.path("id").asLong()+"/fulfill",Map.of(),key()).statusCode()).isEqualTo(409);
        assertThat(front.post("/api/service-orders/"+cancelled.path("id").asLong()+"/cancel",Map.of("reason","重复取消"),key()).statusCode()).isEqualTo(409);
        assertThat(db.queryForObject("select count(*) from folio_charges where source_type='SERVICE_ORDER' and source_key like 'ORDER:"+cancelled.path("id").asLong()+":%'",Integer.class)).isZero();
        // Zero-total orders cannot be billed.
        var freeItem=ok(manager.post("/api/billing/service-catalog",Map.of("itemCode","FREE","itemName","赠品","pricingUnit","ITEM","unitPrice","0")));
        var freeOrder=ok(front.post("/api/stays/"+stay.stayId()+"/service-orders",Map.of("items",List.of(Map.of("catalogItemId",freeItem.path("id").asLong(),"quantity",1)))));
        assertThat(error(front.post("/api/service-orders/"+freeOrder.path("id").asLong()+"/fulfill",Map.of(),key()))).isEqualTo("ZERO_AMOUNT_ORDER");
    }

    @Test @Order(6) void closeReopenPermissionsAndRecovery()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(1,2);var config=configure(manager,stay.roomTypeId(),"200");
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of())).path("folio");
        long folioId=folio.path("id").asLong();
        var charge=ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",daysFromToday(-1),"ratePlanId",config.planId())));
        long version=folio.path("version").asLong();
        // Closing with an outstanding balance demands explicit acknowledgement.
        assertThat(error(front.post("/api/folios/"+folioId+"/close",Map.of("version",version))).startsWith("OUTSTANDING_BALANCE")).isTrue();
        var closed=ok(front.post("/api/folios/"+folioId+"/close",Map.of("version",version,"acknowledgeOutstanding",true),key())).path("folio");
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(front.post("/api/folios/"+folioId+"/close",Map.of("version",version+1),key()).statusCode()).isEqualTo(409);
        // Payments are blocked too; reopening is manager-only and audited with a reason.
        assertThat(front.post("/api/folios/"+folioId+"/payments",Map.of("method","CASH","purpose","PAYMENT","amount","10")).statusCode()).isEqualTo(409);
        assertThat(front.post("/api/folios/"+folioId+"/reopen",Map.of("version",version+1,"reason","前台越权重开")).statusCode()).isEqualTo(403);
        var reopened=ok(manager.post("/api/folios/"+folioId+"/reopen",Map.of("version",version+1,"reason","虚构补充迷你吧费用"),key())).path("folio");
        assertThat(reopened.path("status").asText()).isEqualTo("OPEN");
        var lateCharge=ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","MANUAL","businessDate",today(),"title","虚构补充费用","unitPrice","25","quantity",1)));
        assertThat(lateCharge.path("amount").asText()).isEqualTo("25.00");
        // Receipt recovery returns the stored result of the original key.
        String adjustKey=key();
        ok(manager.post("/api/folios/"+folioId+"/adjustments",Map.of("originalChargeId",charge.path("id").asLong(),"mode","CORRECTION","amount","25","reason","虚构补收冲抵"),adjustKey));
        var receipt=ok(manager.get("/api/folios/result/"+adjustKey+"?operation=CHARGE_ADJUST"));
        assertThat(receipt.path("amount").asText()).isEqualTo("25.00");
        // ADMIN and housekeeping roles never see billing data.
        var admin=login("ADMIN");var staff=login("HOUSEKEEPING_STAFF");
        assertThat(admin.get("/api/billing/folios").statusCode()).isEqualTo(403);
        assertThat(staff.get("/api/folios/"+folioId).statusCode()).isEqualTo(403);
        assertThat(staff.post("/api/stays/"+stay.stayId()+"/service-orders",Map.of("items",List.of(Map.of("catalogItemId",1,"quantity",1)))).statusCode()).isEqualTo(403);
    }

    @Test @Order(7) void transactionFailureRollsBackReservation()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(1,2);var config=configure(manager,stay.roomTypeId(),"200");
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of())).path("folio");
        long folioId=folio.path("id").asLong();
        db.execute("create function bl_fail_fn() returns trigger language plpgsql as $$ begin raise exception 'fictional failure'; end $$");
        for(String table:List.of("audit_logs","folio_charges")) {
            db.execute("drop trigger if exists bl_fail on "+table);
            db.execute("create trigger bl_fail before insert on "+table+" for each row execute function bl_fail_fn()");
            String k=key();
            assertThat(front.request("POST","/api/folios/"+folioId+"/charges",Map.of("category","MANUAL","businessDate",today(),"title","虚构失败费用","unitPrice","10","quantity",1),k).statusCode()).isGreaterThanOrEqualTo(500);
            assertThat(db.queryForObject("select count(*) from folio_charges where folio_id=? and name_snapshot='虚构失败费用'",Integer.class,folioId)).isZero();
            assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,k)).isZero();
            db.execute("drop trigger bl_fail on "+table);
        }
        db.execute("drop function bl_fail_fn()");
        assertThat(ok(front.post("/api/folios/"+folioId+"/charges",Map.of("category","MANUAL","businessDate",today(),"title","虚构失败费用","unitPrice","10","quantity",1),key())).path("amount").asText()).isEqualTo("10.00");
    }

    @Test @Order(8) void realBrowserAcceptance()throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Browser tests explicitly enabled");
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var stay=sqlStay(1,2);
        ok(manager.post("/api/billing/policies",Map.of("billingCutoffTime","04:00","earlyCheckinMode","FULL_NIGHT","lateCheckoutMode","HOURLY","earlyCheckoutMode","FULL_NIGHT","roomChangePriceMode","BY_NEW_SEGMENT","extensionPriceMode","CURRENT_RATE")));
        var plan=ok(manager.post("/api/billing/rate-plans",Map.of("roomTypeId",stay.roomTypeId(),"planCode","STD","planName","门市价","nightlyPrice","288")));
        var water=ok(manager.post("/api/billing/service-catalog",Map.of("itemCode","WATER","itemName","矿泉水","pricingUnit","ITEM","unitPrice","3")));
        var folio=ok(front.post("/api/stays/"+stay.stayId()+"/folio",Map.of())).path("folio");
        var night=ok(front.post("/api/folios/"+folio.path("id").asLong()+"/charges",Map.of("category","ROOM_NIGHT","businessDate",daysFromToday(-1),"ratePlanId",plan.path("id").asLong())));
        var order=ok(front.post("/api/stays/"+stay.stayId()+"/service-orders",Map.of("items",List.of(Map.of("catalogItemId",water.path("id").asLong(),"quantity",1)))));
        var receiptFix=ok(front.post("/api/folios/"+folio.path("id").asLong()+"/payments",Map.of("method","CASH","purpose","PAYMENT","amount","100")));
        var fixtures=new LinkedHashMap<String,Object>();
        fixtures.put("stayId",stay.stayId());fixtures.put("folioId",folio.path("id").asLong());
        fixtures.put("planId",plan.path("id").asLong());fixtures.put("nightChargeId",night.path("id").asLong());
        fixtures.put("orderId",order.path("id").asLong());fixtures.put("receiptId",receiptFix.path("id").asLong());
        fixtures.put("closedFolioId",closedFolioFixture(front,manager,stay.roomTypeId()));
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).getParent();var log=root.resolve(".local/billing-browser.log");
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","billing.spec.ts").directory(root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_BILLING_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-billing-results.json");
        var process=builder.start();if(!process.waitFor(300,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Billing browser timeout");}
        assertThat(process.exitValue()).as("See private billing-browser.log").isZero();
    }
    long closedFolioFixture(Browser front,Browser manager,long roomTypeId)throws Exception {
        var extra=sqlStay(1,1);configure(manager,extra.roomTypeId(),"100");
        var folio=ok(front.post("/api/stays/"+extra.stayId()+"/folio",Map.of())).path("folio");
        ok(front.post("/api/folios/"+folio.path("id").asLong()+"/charges",Map.of("category","MANUAL","businessDate",today(),"title","虚构已结账费用","unitPrice","50","quantity",1)));
        long version=folio.path("version").asLong();
        return ok(front.post("/api/folios/"+folio.path("id").asLong()+"/close",Map.of("version",version,"acknowledgeOutstanding",true),key())).path("folio").path("id").asLong();
    }
}
