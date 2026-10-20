package com.stayos;

import com.fasterxml.jackson.databind.*;
import com.stayos.stay.dto.StayRequests.*;
import com.stayos.stay.service.CheckInService;
import com.stayos.common.ApiException;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StayIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CheckInService service;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired UserMapper users;
    @LocalServerPort int port;
    final Map<String,Long> ids=new HashMap<>();
    final String password=UUID.randomUUID().toString();
    long type;
    @BeforeAll void setup() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        StayTestFacts.restore(jdbc);
        for(String role:List.of("ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=jdbc.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,"stay_test_"+role.toLowerCase(Locale.ROOT),passwords.encode(password),"入住测试"+role);
            ids.put(role,id);users.addRole(id,role);
        }
        type=jdbc.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('S3_TYPE','虚构入住测试房型',1) returning id",Long.class);
        jdbc.execute("create table test_stay_blocks(room_id bigint primary key references rooms(id) on delete cascade,blocking boolean not null default false,task boolean not null default false)");
        jdbc.execute("""
            create or replace view room_operational_facts as
            select r.id room_id,
              exists(select 1 from stay_room_segments s where s.room_id=r.id and s.exited_at is null) occupied,
              coalesce(b.blocking,false) has_blocking_issue,coalesce(b.task,false) has_open_cleaning_task,
              exists(select 1 from stay_room_segments s where s.room_id=r.id) has_usage_history
            from rooms r left join test_stay_blocks b on b.room_id=r.id
            """);
    }
    @BeforeEach void clean() {
        jdbc.execute("drop trigger if exists test_fail_stay_audit on audit_logs");
        jdbc.execute("drop function if exists test_fail_stay_audit_fn()");
        var guests=jdbc.queryForList("select guest_id from stay_guests where stay_record_id in(select id from stay_records where created_by in(select id from users where username like 'stay_test_%'))",Long.class);
        jdbc.update("delete from idempotency_requests where operator_id in(select id from users where username like 'stay_test_%')");
        jdbc.update("delete from stay_guests where stay_record_id in(select id from stay_records where created_by in(select id from users where username like 'stay_test_%'))");
        jdbc.update("delete from stay_room_segments where room_id in(select id from rooms where room_number like 'S3_%')");
        jdbc.update("delete from stay_records where created_by in(select id from users where username like 'stay_test_%')");
        for(long guest:guests) jdbc.update("delete from guests where id=?",guest);
        jdbc.update("delete from rooms where room_number like 'S3_%'");
        jdbc.update("delete from audit_logs where operator_id in(select id from users where username like 'stay_test_%')");
    }
    @AfterAll void cleanup() {
        clean();StayTestFacts.restore(jdbc);jdbc.execute("drop table test_stay_blocks");
        jdbc.update("delete from room_types where id=?",type);
        for(long id:ids.values()) { jdbc.update("delete from user_roles where user_id=?",id);jdbc.update("delete from users where id=?",id); }
    }
    long room(String clean,boolean enabled) {
        return jdbc.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status,enabled) values(?,8,?,?,?) returning id",Long.class,"S3_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT),type,clean,enabled);
    }
    String key() { return UUID.randomUUID().toString(); }
    CheckIn body(long room) {
        return new CheckIn(room,OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),List.of(
            new GuestInput("虚构住客甲","OTHER","FICTIONAL-ALPHA-001","000-000-0000","虚构资料",true),
            new GuestInput("虚构住客乙","PASSPORT","TESTONLY002","","",false)),"隔离测试");
    }
    long create(long room) { return service.create(ids.get("FRONT_DESK"),key(),body(room)); }
    @Test void successfulMultiGuestCheckInCreatesAtomicRecordsAndEncryptedFields() {
        long room=room("CLEAN",true),stay=create(room);
        assertThat(jdbc.queryForObject("select count(*) from stay_guests where stay_record_id=?",Integer.class,stay)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from stay_guests where stay_record_id=? and is_primary",Integer.class,stay)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select occupied,cleaning_status,has_usage_history from room_status where id=?",room)).containsEntry("occupied",true).containsEntry("cleaning_status","CLEAN").containsEntry("has_usage_history",true);
        String protectedRows=jdbc.queryForList("select document_ciphertext,phone_ciphertext from guests where id in(select guest_id from stay_guests where stay_record_id=?)",stay).toString();
        assertThat(protectedRows).contains("v1.").doesNotContain("FICTIONAL-ALPHA-001","000-000-0000","TESTONLY002");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='CHECK_IN' and target_id=? and result='SUCCESS'",Integer.class,stay)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where result_stay_id=?",Integer.class,stay)).isEqualTo(1);
    }
    @Test void unavailableRoomsAndMissingRoomAreRejectedWithoutPartialData() {
        for(String clean:List.of("DIRTY","CLEANING","INSPECTION")) {
            long room=room(clean,true);assertThatThrownBy(()->create(room)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ROOM_NOT_AVAILABLE"));
        }
        long disabled=room("CLEAN",false);assertThatThrownBy(()->create(disabled)).isInstanceOf(ApiException.class);
        long blocked=room("CLEAN",true);jdbc.update("insert into test_stay_blocks values(?,true,false)",blocked);
        assertThatThrownBy(()->create(blocked)).isInstanceOf(ApiException.class);
        long task=room("CLEAN",true);jdbc.update("insert into test_stay_blocks values(?,false,true)",task);
        assertThatThrownBy(()->create(task)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->create(999999999L)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(404));
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stay_records",Integer.class)).isZero();
    }
    @Test void invalidTimeDuplicateGuestAndMissingPrimaryRejected() {
        long room=room("CLEAN",true);var b=body(room);
        assertThatThrownBy(()->service.create(ids.get("FRONT_DESK"),key(),new CheckIn(room,OffsetDateTime.now().minusDays(1),b.guests(),""))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.create(ids.get("FRONT_DESK"),key(),new CheckIn(room,b.expectedCheckOutAt(),List.of(b.guests().getFirst(),b.guests().getFirst()),""))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.create(ids.get("FRONT_DESK"),key(),new CheckIn(room,b.expectedCheckOutAt(),List.of(b.guests().get(1)),""))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.create(ids.get("FRONT_DESK"),"bad-key",b)).isInstanceOf(ApiException.class);
    }
    @Test void repeatSameRequestReturnsOriginalMismatchAndExpiredRejected() {
        long room=room("CLEAN",true),actor=ids.get("FRONT_DESK");var b=body(room);String key=key();long first=service.create(actor,key,b);
        assertThat(service.create(actor,key,b)).isEqualTo(first);
        assertThatThrownBy(()->service.create(actor,key,new CheckIn(room,b.expectedCheckOutAt(),b.guests(),"改了内容"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_MISMATCH"));
        jdbc.update("update idempotency_requests set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where result_stay_id=?",first);
        assertThatThrownBy(()->service.create(actor,key,b)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED"));
        assertThat(jdbc.queryForObject("select count(*) from stay_records",Integer.class)).isEqualTo(1);
    }
    @Test void differentRequestsCompetingForSameRoomOnlyOneWins() throws Exception {
        long room=room("CLEAN",true);var b=body(room);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<String>> results=new ArrayList<>();
            for(String role:List.of("FRONT_DESK","HOTEL_MANAGER")) results.add(pool.submit(()->{ gate.await();try { service.create(ids.get(role),key(),b);return "SUCCESS"; }catch(ApiException e){return e.code();} }));
            gate.countDown();assertThat(List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","ROOM_NOT_AVAILABLE");
        }
        assertThat(jdbc.queryForObject("select count(*) from stay_room_segments where room_id=? and exited_at is null",Integer.class,room)).isEqualTo(1);
    }
    @Test void concurrentSameKeyCreatesOneStayAndReturnsSameId() throws Exception {
        long room=room("CLEAN",true),actor=ids.get("FRONT_DESK");String key=key();var b=body(room);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return service.create(actor,key,b);});var c=pool.submit(()->{gate.await();return service.create(actor,key,b);});
            gate.countDown();assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(c.get(20,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from stay_records",Integer.class)).isEqualTo(1);
    }
    @Test void databaseFailureDuringAuditRollsBackGuestsStaySegmentAndKeyThenRetryWorks() {
        long room=room("CLEAN",true),actor=ids.get("FRONT_DESK");String key=key();var b=body(room);
        jdbc.execute("create function test_fail_stay_audit_fn() returns trigger language plpgsql as $$ begin if NEW.action='CHECK_IN' then raise exception 'injected test failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_fail_stay_audit before insert on audit_logs for each row execute function test_fail_stay_audit_fn()");
        assertThatThrownBy(()->service.create(actor,key,b)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String table:List.of("stay_records","stay_guests","stay_room_segments","guests","idempotency_requests")) assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();
        assertThat(jdbc.queryForObject("select occupied from room_status where id=?",Boolean.class,room)).isFalse();
        jdbc.execute("drop trigger test_fail_stay_audit on audit_logs");jdbc.execute("drop function test_fail_stay_audit_fn()");
        assertThat(service.create(actor,key,b)).isPositive();
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
        HttpResponse<String> post(Object body,String key) throws Exception { return request("POST","/api/stays",body,key); }
    }
    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }
    Browser login(String role) throws Exception {
        var b=new Browser();assertThat(b.request("POST","/api/auth/login",Map.of("username","stay_test_"+role.toLowerCase(Locale.ROOT),"password",password),null).statusCode()).isEqualTo(200);return b;
    }
    @Test void allFiveRolesAndAnonymousHaveCorrectReadAndWritePermissions() throws Exception {
        long room=room("CLEAN",true);
        for(String role:List.of("ADMIN","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
            var b=login(role);
            for(String path:List.of("/api/stays","/api/stays/history","/api/stays/1","/api/stays/available-rooms")) assertThat(b.get(path).statusCode()).as(role+path).isEqualTo(403);
            assertThat(b.post(body(room),key()).statusCode()).isEqualTo(403);
            assertThat(b.get("/api/room-status").statusCode()).isEqualTo(200);
        }
        for(String role:List.of("FRONT_DESK","HOTEL_MANAGER")) {
            var b=login(role);var response=b.post(body(room("CLEAN",true)),key());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            for(String path:List.of("/api/stays","/api/stays/history","/api/stays/"+data(response).path("id").asLong(),"/api/stays/available-rooms")) assertThat(b.get(path).statusCode()).as(role+path).isEqualTo(200);
            assertThat(b.get("/api/users").statusCode()).isEqualTo(403);
        }
        var anon=new Browser();assertThat(anon.get("/api/stays").statusCode()).isEqualTo(401);assertThat(anon.post(body(room),key()).statusCode()).isEqualTo(401);
    }
    @Test void queryFilteringDetailAndHistoryKeepSensitiveFieldsMasked() throws Exception {
        long room=room("CLEAN",true);var front=login("FRONT_DESK");String key=key();var input=body(room);
        var response=front.post(input,key);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);long id=data(response).path("id").asLong();
        assertThat(data(response).path("guests").size()).isEqualTo(2);
        assertThat(response.body()).contains("FI****01","00****00").doesNotContain("FICTIONAL-ALPHA-001","000-000-0000","documentCiphertext","phoneCiphertext");
        assertThat(data(front.post(input,key)).path("id").asLong()).isEqualTo(id);
        String number=jdbc.queryForObject("select room_number from rooms where id=?",String.class,room);
        String encoded=URLEncoder.encode("虚构住客乙",java.nio.charset.StandardCharsets.UTF_8);
        assertThat(data(front.get("/api/stays?roomNumber="+number+"&guestName="+encoded+"&pageSize=1")).path("total").asInt()).isEqualTo(1);
        assertThat(data(front.get("/api/stays?guestName=NOBODY")).path("total").asInt()).isZero();
        assertThat(data(front.get("/api/stays/history")).path("total").asInt()).isZero();
        assertThat(data(front.get("/api/stays/available-rooms?canCheckIn=false")).path("total").asInt()).isZero();
        assertThat(front.get("/api/room-status").body()).doesNotContain("虚构住客甲","FICTIONAL","document","phone");
        assertThat(jdbc.queryForList("select * from audit_logs where operator_id=?",ids.get("FRONT_DESK")).toString()).doesNotContain("FICTIONAL-ALPHA-001","000-000-0000","TESTONLY002");
        assertThat(data(front.get("/api/stays/"+id)).path("guestCount").asInt()).isEqualTo(2);
    }
    @Test void historicalRecordsAreQueryableWithoutImplementingCheckout() throws Exception {
        // Test-only fixture, not a checkout endpoint and not written into the developer database.
        long room=room("CLEAN",true),id=create(room);
        jdbc.update("update stay_room_segments set exited_at=entered_at,end_reason='CHECK_OUT' where stay_record_id=?",id);
        jdbc.update("update stay_records set status='CHECKED_OUT',actual_check_out_at=check_in_at,checked_out_by=created_by where id=?",id);
        var front=login("FRONT_DESK");
        assertThat(data(front.get("/api/stays")).path("total").asInt()).isZero();
        assertThat(data(front.get("/api/stays/history")).path("items").get(0).path("id").asLong()).isEqualTo(id);
        assertThat(data(front.get("/api/stays/"+id)).path("status").asText()).isEqualTo("CHECKED_OUT");
        assertThat(front.request("POST","/api/stays/"+id+"/check-out",Map.of(),key()).statusCode()).isEqualTo(404);
    }
    @Test void invalidParametersAndMissingRecordsReturnSafeErrors() throws Exception {
        var front=login("FRONT_DESK");
        for(String q:List.of("page=0","pageSize=101","status=HACK","checkInFrom=not-a-time","checkInFrom=2026-10-21T00:00:00Z&checkInTo=2026-10-20T00:00:00Z")) assertThat(front.get("/api/stays?"+q).statusCode()).as(q).isEqualTo(400);
        for(String q:List.of("floor=0","pageSize=101","floor=abc")) assertThat(front.get("/api/stays/available-rooms?"+q).statusCode()).isEqualTo(400);
        assertThat(front.get("/api/stays/999999999").statusCode()).isEqualTo(404);
        assertThat(front.post(body(room("CLEAN",true)),null).statusCode()).isEqualTo(400);
        var b=body(room("CLEAN",true));var raw=json.valueToTree(b);((com.fasterxml.jackson.databind.node.ObjectNode)raw).put("expectedCheckOutAt","2026-12-01T12:00:00");
        assertThat(front.post(raw,key()).statusCode()).isEqualTo(400);
    }
    @Test void actualStayPreventsRoomRenumberAndDisableButAllowsNotes() throws Exception {
        long room=room("CLEAN",true);create(room);var admin=login("ADMIN");
        var original=jdbc.queryForMap("select room_number,version from rooms where id=?",room);
        var edit=new HashMap<String,Object>(Map.of("roomNumber","S3_CHANGED","roomTypeId",type,"floor",8,"notes","历史后的备注","version",original.get("version")));
        var rename=admin.request("PUT","/api/rooms/"+room,edit,null);
        assertThat(rename.statusCode()).isEqualTo(409);assertThat(rename.body()).contains("ROOM_NUMBER_HAS_HISTORY");
        assertThat(admin.request("POST","/api/rooms/"+room+"/set-enabled",Map.of("enabled",false,"version",original.get("version")),null).statusCode()).isEqualTo(409);
        edit.put("roomNumber",original.get("room_number"));assertThat(admin.request("PUT","/api/rooms/"+room,edit,null).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select notes from rooms where id=?",String.class,room)).isEqualTo("历史后的备注");
    }
    @Test void twoAuthenticatedHttpClientsCompeteForRoomAndOnlyOneSucceeds() throws Exception {
        long room=room("CLEAN",true);var a=login("FRONT_DESK");var b=login("HOTEL_MANAGER");var input=body(room);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var f1=pool.submit(()->{gate.await();return a.post(input,key()).statusCode();});var f2=pool.submit(()->{gate.await();return b.post(input,key()).statusCode();});gate.countDown();
            assertThat(List.of(f1.get(25,TimeUnit.SECONDS),f2.get(25,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
    @Test void stayBrowserEndToEndWithRealPostgres() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Enable with scripts/test.ps1 -Browser");
        var fixtures=new LinkedHashMap<String,Map<String,Object>>();
        for(String name:List.of("multi","network","mobile","dirty")) {
            long id=room(name.equals("dirty")?"DIRTY":"CLEAN",true);
            fixtures.put(name,Map.of("id",id,"number",jdbc.queryForObject("select room_number from rooms where id=?",String.class,id)));
        }
        var directory=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("frontend").toFile();
        var log=directory.toPath().getParent().resolve(".local/stay-browser-run.log");java.nio.file.Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","stays.spec.ts").directory(directory).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_STAY_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-stays-results.json");
        var process=builder.start();
        if(!process.waitFor(240,TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Stay browsers timed out"); }
        assertThat(process.exitValue()).as("Playwright details: "+log).isZero();
        long multi=jdbc.queryForObject("select stay_record_id from stay_room_segments where room_id=?",Long.class,fixtures.get("multi").get("id"));
        assertThat(jdbc.queryForObject("select count(*) from stay_guests where stay_record_id=?",Integer.class,multi)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from stay_room_segments where room_id=?",Integer.class,fixtures.get("network").get("id"))).isEqualTo(1);
    }
}
