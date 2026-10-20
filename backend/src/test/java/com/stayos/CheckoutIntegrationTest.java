package com.stayos;

import com.fasterxml.jackson.databind.*;
import com.stayos.common.ApiException;
import com.stayos.staff.mapper.UserMapper;
import com.stayos.stay.dto.*;
import com.stayos.stay.service.CheckoutService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CheckoutService checkout;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired UserMapper users;
    @LocalServerPort int port;
    final Map<String,Long> ids=new LinkedHashMap<>();
    final String password=UUID.randomUUID().toString();
    long type;
    @BeforeAll void setup() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        StayTestFacts.restore(jdbc);
        for(String role:List.of("ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=jdbc.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,"checkout_test_"+role.toLowerCase(Locale.ROOT),passwords.encode(password),"退房测试"+role);
            ids.put(role,id);users.addRole(id,role);
        }
        type=jdbc.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('S4_TYPE','虚构退房测试房型',2) returning id",Long.class);
    }
    @BeforeEach void clean() {
        jdbc.execute("drop trigger if exists test_fail_checkout_key on idempotency_requests");
        jdbc.execute("drop function if exists test_fail_checkout_key_fn()");
        for(String target:List.of("task","audit")) {
            jdbc.execute("drop trigger if exists test_fail_checkout_"+target+" on "+(target.equals("task")?"housekeeping_tasks":"audit_logs"));
            jdbc.execute("drop function if exists test_fail_checkout_"+target+"_fn()");
        }
        var guests=jdbc.queryForList("select guest_id from stay_guests where stay_record_id in(select id from stay_records where created_by in(select id from users where username like 'checkout_test_%'))",Long.class);
        jdbc.update("delete from housekeeping_tasks where created_by in(select id from users where username like 'checkout_test_%')");
        jdbc.update("delete from idempotency_requests where operator_id in(select id from users where username like 'checkout_test_%')");
        jdbc.update("delete from stay_guests where stay_record_id in(select id from stay_records where created_by in(select id from users where username like 'checkout_test_%'))");
        jdbc.update("delete from stay_room_segments where room_id in(select id from rooms where room_number like 'S4_%')");
        jdbc.update("delete from stay_records where created_by in(select id from users where username like 'checkout_test_%')");
        for(long guest:guests) jdbc.update("delete from guests where id=?",guest);
        jdbc.update("delete from rooms where room_number like 'S4_%'");
        jdbc.update("delete from audit_logs where operator_id in(select id from users where username like 'checkout_test_%')");
        StayTestFacts.restore(jdbc);
    }
    @AfterAll void cleanup() {
        clean();jdbc.update("delete from room_types where id=?",type);
        for(long id:ids.values()) { jdbc.update("delete from user_roles where user_id=?",id);jdbc.update("delete from users where id=?",id); }
    }
    String key() { return UUID.randomUUID().toString(); }
    long room() {
        return jdbc.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,4,?,'CLEAN') returning id",Long.class,"S4_"+key().substring(0,8).toUpperCase(Locale.ROOT),type);
    }
    Map<String,Object> checkInBody(long room) {
        return Map.of("roomId",room,"expectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString(),"guests",List.of(Map.of("name","虚构退房住客","documentType","OTHER","documentNumber","S4-FICTIONAL-001","phone","000-000-0000","primary",true)),"notes","");
    }
    final class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> request(String method,String path,Object body,String key) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
            if(!method.equals("GET")) {
                var csrf=data(request("GET","/api/auth/csrf",null,null));request.header(csrf.path("headerName").asText(),csrf.path("token").asText());
            }
            if(key!=null) request.header("Idempotency-Key",key);
            return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET",path,null,null); }
        HttpResponse<String> out(long id,long segment,String key) throws Exception { return request("POST","/api/stays/"+id+"/check-out",Map.of("segmentId",segment),key); }
    }
    JsonNode data(HttpResponse<String> r) throws Exception { return json.readTree(r.body()).path("data"); }
    Browser login(String role) throws Exception {
        var b=new Browser();assertThat(b.request("POST","/api/auth/login",Map.of("username","checkout_test_"+role.toLowerCase(Locale.ROOT),"password",password),null).statusCode()).isEqualTo(200);return b;
    }
    JsonNode checkIn(long room) throws Exception {
        var r=login("FRONT_DESK").request("POST","/api/stays",checkInBody(room),key());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return data(r);
    }
    CheckoutRequest input(JsonNode stay) { return new CheckoutRequest(stay.path("segmentId").asLong()); }
    void intact(JsonNode stay) {
        assertThat(jdbc.queryForObject("select status from stay_records where id=?",String.class,stay.path("id").asLong())).isEqualTo("IN_HOUSE");
        assertThat(jdbc.queryForMap("select occupied,cleaning_status,can_check_in from room_status where id=?",stay.path("roomId").asLong())).containsEntry("occupied",true).containsEntry("cleaning_status","CLEAN").containsEntry("can_check_in",false);
        assertThat(jdbc.queryForObject("select exited_at is null from stay_room_segments where id=?",Boolean.class,stay.path("segmentId").asLong())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where operation='CHECK_OUT'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='CHECK_OUT'",Integer.class)).isZero();
    }
    @Test void realHttpCheckInThenCheckoutClosesHistoryReleasesRoomAndCreatesExactlyOneTask() throws Exception {
        long room=room();var stay=checkIn(room);var front=login("FRONT_DESK");long id=stay.path("id").asLong();
        var before=Instant.now().minusSeconds(1);
        var response=front.out(id,stay.path("segmentId").asLong(),key());assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var result=data(response);var time=OffsetDateTime.parse(result.path("actualCheckOutAt").asText());
        assertThat(time.toInstant()).isBetween(before,Instant.now().plusSeconds(1));
        assertThat(jdbc.queryForMap("select status,checked_out_by,actual_check_out_at from stay_records where id=?",id)).containsEntry("status","CHECKED_OUT").containsEntry("checked_out_by",ids.get("FRONT_DESK"));
        assertThat(jdbc.queryForObject("select exited_at= ? and end_reason='CHECK_OUT' from stay_room_segments where stay_record_id=?",Boolean.class,time,id)).isTrue();
        assertThat(jdbc.queryForMap("select occupied,cleaning_status,has_open_cleaning_task,can_check_in from room_status where id=?",room)).containsEntry("occupied",false).containsEntry("cleaning_status","DIRTY").containsEntry("has_open_cleaning_task",true).containsEntry("can_check_in",false);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks where source='CHECKOUT' and source_id=? and stay_record_id=? and room_id=? and status='PENDING'",Integer.class,id,id,room)).isEqualTo(1);
        assertThat(data(front.get("/api/stays")).path("total").asInt()).isZero();
        assertThat(data(front.get("/api/stays/history")).path("items").get(0).path("id").asLong()).isEqualTo(id);
        assertThat(data(front.get("/api/stays/"+id)).path("guests").get(0).path("documentNumber").asText()).isEqualTo("S4****01");
        assertThat(front.request("POST","/api/stays",checkInBody(room),key()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from stay_guests where stay_record_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='CHECK_OUT' and target_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void repeatedKeyReturnsOriginalResultAndNewKeyCannotCheckoutAgain() throws Exception {
        var stay=checkIn(room());var front=login("FRONT_DESK");String key=key();long id=stay.path("id").asLong(),segment=stay.path("segmentId").asLong();
        var first=front.out(id,segment,key);assertThat(first.statusCode()).isEqualTo(200);
        assertThat(front.out(id,segment,key).body()).isEqualTo(first.body());
        assertThat(front.out(id,segment,key()).statusCode()).isEqualTo(409);
        assertThat(front.out(id,segment+1,key).body()).contains("IDEMPOTENCY_MISMATCH");
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where operation='CHECK_OUT'",Integer.class)).isEqualTo(1);
    }
    @Test void expiredKeyRejectedAndOperationScopeIsIndependent() throws Exception {
        var front=login("FRONT_DESK");String key=key();long room=room();
        var created=front.request("POST","/api/stays",checkInBody(room),key);var stay=data(created);long id=stay.path("id").asLong();
        assertThat(front.out(id,stay.path("segmentId").asLong(),key).statusCode()).isEqualTo(200);
        jdbc.update("update idempotency_requests set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where operation='CHECK_OUT'");
        assertThat(front.out(id,stay.path("segmentId").asLong(),key).body()).contains("IDEMPOTENCY_KEY_EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests",Integer.class)).isEqualTo(2);
    }
    @Test void staleSegmentAndOtherStaySegmentRejectedWithoutChanges() throws Exception {
        var first=checkIn(room());var second=checkIn(room());
        var front=login("FRONT_DESK");
        assertThat(front.out(first.path("id").asLong(),second.path("segmentId").asLong(),key()).body()).contains("STALE_ROOM_SEGMENT");
        intact(first);
        // Simulate a future room-change history in the isolated database; no public change-room API.
        long id=first.path("id").asLong(),old=first.path("segmentId").asLong(),next=room();
        jdbc.update("update stay_room_segments set exited_at=now(),end_reason='ROOM_CHANGE' where id=?",old);
        jdbc.update("insert into stay_room_segments(stay_record_id,room_id,room_number_snapshot,room_type_name_snapshot,entered_at) values(?,?,'TEST','测试',now())",id,next);
        assertThat(front.out(id,old,key()).body()).contains("STALE_ROOM_SEGMENT");
        assertThat(jdbc.queryForObject("select occupied from room_status where id=?",Boolean.class,next)).isTrue();
    }
    @Test void concurrentDifferentRequestsAndActorsProduceOneSuccessOneConflict() throws Exception {
        var stay=checkIn(room());var a=login("FRONT_DESK");var b=login("HOTEL_MANAGER");long id=stay.path("id").asLong(),seg=stay.path("segmentId").asLong();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var f1=pool.submit(()->{gate.await();return a.out(id,seg,key()).statusCode();});var f2=pool.submit(()->{gate.await();return b.out(id,seg,key()).statusCode();});gate.countDown();
            assertThat(List.of(f1.get(25,TimeUnit.SECONDS),f2.get(25,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentSameKeyReturnsSameCheckoutAndOneTask() throws Exception {
        var stay=checkIn(room());String key=key();var gate=new CountDownLatch(1);long id=stay.path("id").asLong(),actor=ids.get("FRONT_DESK");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return checkout.checkout(actor,key,id,input(stay));});var b=pool.submit(()->{gate.await();return checkout.checkout(actor,key,id,input(stay));});gate.countDown();
            JsonNode first=json.valueToTree(a.get(20,TimeUnit.SECONDS)),second=json.valueToTree(b.get(20,TimeUnit.SECONDS));
            assertThat(first).isEqualTo(second);
        }
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='CHECK_OUT'",Integer.class)).isEqualTo(1);
    }
    void injectFailure(String target) {
        String condition=target.equals("audit")?"if NEW.action='CHECK_OUT' then raise exception 'injected checkout failure'; end if;":"raise exception 'injected task failure';";
        jdbc.execute("create function test_fail_checkout_"+target+"_fn() returns trigger language plpgsql as $$ begin "+condition+" return NEW; end $$");
        jdbc.execute("create trigger test_fail_checkout_"+target+" before insert on "+(target.equals("task")?"housekeeping_tasks":"audit_logs")+" for each row execute function test_fail_checkout_"+target+"_fn()");
    }
    void rollbackAndRetry(String target) throws Exception {
        var stay=checkIn(room());String key=key();long actor=ids.get("FRONT_DESK"),id=stay.path("id").asLong();
        injectFailure(target);assertThatThrownBy(()->checkout.checkout(actor,key,id,input(stay))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        intact(stay);
        jdbc.execute("drop trigger test_fail_checkout_"+target+" on "+(target.equals("task")?"housekeeping_tasks":"audit_logs"));
        jdbc.execute("drop function test_fail_checkout_"+target+"_fn()");
        assertThat(checkout.checkout(actor,key,id,input(stay)).taskId).isPositive();
    }
    @Test void taskFailureRollsBackEntireCheckoutAndSameKeyRetrySucceeds() throws Exception { rollbackAndRetry("task"); }
    @Test void auditFailureRollsBackEntireCheckoutAndSameKeyRetrySucceeds() throws Exception { rollbackAndRetry("audit"); }
    @Test void checkoutPreservesDisabledFlagAndMaintenanceFacts() throws Exception {
        var stay=checkIn(room());long room=stay.path("roomId").asLong();
        // No repair module yet. Inject a derived blocking fact only in the isolated test view.
        jdbc.execute("create or replace view room_operational_facts as select r.id room_id,exists(select 1 from stay_room_segments s where s.room_id=r.id and s.exited_at is null) occupied,true has_blocking_issue,exists(select 1 from housekeeping_tasks t where t.room_id=r.id) has_open_cleaning_task,true has_usage_history from rooms r");
        jdbc.update("update rooms set enabled=false where id=?",room);
        checkout.checkout(ids.get("FRONT_DESK"),key(),stay.path("id").asLong(),input(stay));
        assertThat(jdbc.queryForMap("select enabled,maintenance_status,cleaning_status from room_status where id=?",room)).containsEntry("enabled",false).containsEntry("maintenance_status","OUT_OF_SERVICE").containsEntry("cleaning_status","DIRTY");
    }
    @Test void databaseRejectsDuplicateTaskSource() throws Exception {
        var stay=checkIn(room());checkout.checkout(ids.get("FRONT_DESK"),key(),stay.path("id").asLong(),input(stay));
        assertThatThrownBy(()->jdbc.update("insert into housekeeping_tasks(room_id,task_type,source,source_id,stay_record_id,room_segment_id,created_by) select room_id,task_type,source,source_id,stay_record_id,room_segment_id,created_by from housekeeping_tasks"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void fiveRolesAndAnonymousCheckoutAuthorization() throws Exception {
        var stay=checkIn(room());long id=stay.path("id").asLong(),seg=stay.path("segmentId").asLong();
        for(String role:List.of("ADMIN","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) assertThat(login(role).out(id,seg,key()).statusCode()).as(role).isEqualTo(403);
        assertThat(new Browser().out(id,seg,key()).statusCode()).isEqualTo(401);
        assertThat(login("HOTEL_MANAGER").out(id,seg,key()).statusCode()).isEqualTo(200);
        assertThat(login("HOTEL_MANAGER").get("/api/users").statusCode()).isEqualTo(403);
    }
    @Test void invalidInputsMissingStayAndMissingCsrfAreRejected() throws Exception {
        var stay=checkIn(room());var front=login("FRONT_DESK");long id=stay.path("id").asLong(),seg=stay.path("segmentId").asLong();
        assertThat(front.out(id,seg,null).statusCode()).isEqualTo(400);
        assertThat(front.out(id,seg,"invalid").statusCode()).isEqualTo(400);
        assertThat(front.request("POST","/api/stays/"+id+"/check-out",Map.of(),key()).statusCode()).isEqualTo(400);
        assertThat(front.out(id,0,key()).statusCode()).isEqualTo(400);
        assertThat(front.out(999999999,seg,key()).statusCode()).isEqualTo(404);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/stays/"+id+"/check-out")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"segmentId\":"+seg+"}")).build();
        assertThat(front.http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);intact(stay);
    }
    @Test void taskQueryAuthorizationFilteringDetailsAndPrivacy() throws Exception {
        var stay=checkIn(room());var result=checkout.checkout(ids.get("FRONT_DESK"),key(),stay.path("id").asLong(),input(stay));
        for(String role:ids.keySet()) {
            var b=login(role);boolean allowed=List.of("HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF").contains(role);
            assertThat(b.get("/api/housekeeping-tasks").statusCode()).as(role).isEqualTo(allowed?200:403);
            var detail=b.get("/api/housekeeping-tasks/"+result.taskId);assertThat(detail.statusCode()).isEqualTo(allowed?200:403);
            if(allowed) {
                assertThat(detail.body()).doesNotContain("虚构退房住客","S4-FICTIONAL-001","000-000-0000","document","phone","ciphertext","primaryGuest");
                assertThat(data(b.get("/api/housekeeping-tasks?floor=4&status=PENDING&roomNumber="+result.roomNumber)).path("total").asInt()).isEqualTo(1);
                assertThat(data(b.get("/api/housekeeping-tasks?floor=5")).path("total").asInt()).isZero();
                assertThat(b.request("POST","/api/housekeeping-tasks/"+result.taskId+"/start",Map.of(),null).statusCode()).isEqualTo(404);
            }
        }
        assertThat(new Browser().get("/api/housekeeping-tasks").statusCode()).isEqualTo(401);
        var hk=login("HOUSEKEEPING_STAFF");
        for(String q:List.of("page=0","pageSize=101","floor=0","floor=bad","status=COMPLETED")) assertThat(hk.get("/api/housekeeping-tasks?"+q).statusCode()).isEqualTo(400);
        assertThat(hk.get("/api/housekeeping-tasks/999999999").statusCode()).isEqualTo(404);
    }
    @Test void idempotencyResultFailureAlsoRollsBackRoomStayTaskAndAudit() throws Exception {
        var stay=checkIn(room());String key=key();long actor=ids.get("FRONT_DESK"),id=stay.path("id").asLong();
        jdbc.execute("create function test_fail_checkout_key_fn() returns trigger language plpgsql as $$ begin if NEW.operation='CHECK_OUT' then raise exception 'injected result failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_fail_checkout_key before update on idempotency_requests for each row execute function test_fail_checkout_key_fn()");
        assertThatThrownBy(()->checkout.checkout(actor,key,id,input(stay))).isInstanceOf(org.springframework.dao.DataAccessException.class);intact(stay);
        jdbc.execute("drop trigger test_fail_checkout_key on idempotency_requests");jdbc.execute("drop function test_fail_checkout_key_fn()");
        assertThat(checkout.checkout(actor,key,id,input(stay)).taskId).isPositive();
    }
    @Test void pendingTaskBlocksAvailabilityEvenIfCleaningFlagIsIncorrectlyChanged() throws Exception {
        var stay=checkIn(room());String key=key();long id=stay.path("id").asLong(),room=stay.path("roomId").asLong(),actor=ids.get("FRONT_DESK");
        var original=checkout.checkout(actor,key,id,input(stay));
        // Test-only corruption: no production endpoint permits arbitrary CLEAN updates.
        jdbc.update("update rooms set cleaning_status='CLEAN' where id=?",room);
        assertThat(jdbc.queryForMap("select can_check_in,has_open_cleaning_task from room_status where id=?",room)).containsEntry("can_check_in",false).containsEntry("has_open_cleaning_task",true);
        JsonNode replay=json.valueToTree(checkout.checkout(actor,key,id,input(stay)));
        assertThat(replay).isEqualTo(json.valueToTree(original));
        assertThat(login("FRONT_DESK").request("POST","/api/stays",checkInBody(room),key()).statusCode()).isEqualTo(409);
    }
    @Test void taskCannotReferenceAnotherRoomsSegment() throws Exception {
        var a=checkIn(room());var b=checkIn(room());
        assertThatThrownBy(()->jdbc.update("insert into housekeeping_tasks(room_id,task_type,source,source_id,stay_record_id,room_segment_id,created_by) values(?,'CHECKOUT_CLEANING','CHECKOUT',?,?,?,?)",
            b.path("roomId").asLong(),a.path("id").asLong(),a.path("id").asLong(),a.path("segmentId").asLong(),ids.get("FRONT_DESK")))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void checkoutBrowserEndToEndWithRealPostgres() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Enable with scripts/test.ps1 -Browser");
        var fixtures=new LinkedHashMap<String,Map<String,Object>>();
        for(String name:List.of("normal","network","refresh","mobile","manager","task","malformed")) {
            long id=room();fixtures.put(name,Map.of("id",id,"number",jdbc.queryForObject("select room_number from rooms where id=?",String.class,id)));
        }
        var initial=checkIn(((Number)fixtures.get("task").get("id")).longValue());
        checkout.checkout(ids.get("FRONT_DESK"),key(),initial.path("id").asLong(),input(initial));
        var directory=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("frontend").toFile();
        var log=directory.toPath().getParent().resolve(".local/checkout-browser-run.log");java.nio.file.Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","checkout.spec.ts").directory(directory).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_CHECKOUT_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-checkout-results.json");
        var process=builder.start();
        if(!process.waitFor(300,TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Checkout browsers timed out"); }
        assertThat(process.exitValue()).as("Playwright details: "+log).isZero();
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from stay_records where status='IN_HOUSE'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stay_room_segments where exited_at is null",Integer.class)).isZero();
    }
}
