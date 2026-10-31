package com.stayos;

import com.fasterxml.jackson.databind.*;
import com.stayos.staff.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class StayExtensionIntegrationTest {
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
    @LocalServerPort int port;
    final Map<String,Long> ids=new LinkedHashMap<>();
    final String password=UUID.randomUUID().toString();
    static final String OWN="select id from users where username like 'extension_test_%'";
    long type; String hash;
    @BeforeAll void setup() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        hash=passwords.encode(password); clean();
        db.update("delete from user_roles where user_id in("+OWN+")");db.update("delete from users where id in("+OWN+")");
        db.update("delete from room_types where type_code='EXT_TYPE'");
        for(String role:List.of("ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,
                "extension_test_"+role.toLowerCase(Locale.ROOT),hash,"虚构续住"+role);
            ids.put(role,id); users.addRole(id,role);
        }
        type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('EXT_TYPE','虚构续住房型',2) returning id",Long.class);
    }
    @BeforeEach void clean() {
        db.execute("drop trigger if exists ext_fail on audit_logs");db.execute("drop trigger if exists ext_fail on stay_extensions");
        db.execute("drop trigger if exists ext_pause on idempotency_requests");
        db.execute("drop function if exists ext_fail_fn()");db.execute("drop function if exists ext_pause_fn()");
        var guests=db.queryForList("select guest_id from stay_guests where stay_record_id in(select id from stay_records where created_by in("+OWN+"))",Long.class);
        db.update("delete from idempotency_requests where operator_id in("+OWN+")");
        db.update("delete from stay_extensions where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
        db.update("delete from housekeeping_task_logs where task_id in(select id from housekeeping_tasks where created_by in("+OWN+"))");
        db.update("delete from housekeeping_tasks where created_by in("+OWN+")");db.update("delete from room_changes where operated_by in("+OWN+")");
        db.update("delete from stay_guests where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
        db.update("delete from stay_room_segments where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
        db.update("delete from stay_records where created_by in("+OWN+")");for(long id:guests)db.update("delete from guests where id=?",id);
        db.update("delete from rooms where room_number like 'EXT_%'");db.update("delete from audit_logs where operator_id in("+OWN+")");
        for(var entry:ids.entrySet()) {
            db.update("update users set enabled=true,password_hash=?,failed_login_attempts=0,locked_until=null where id=?",hash,entry.getValue());
            db.update("delete from user_roles where user_id=?",entry.getValue());users.addRole(entry.getValue(),entry.getKey());
        }
    }
    @AfterAll void after() {
        clean();db.update("delete from room_types where id=?",type);
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
    }
    Browser login(String role)throws Exception {
        var b=new Browser();ok(b.post("/api/auth/login",Map.of("username","extension_test_"+role.toLowerCase(Locale.ROOT),"password",password)));return b;
    }
    JsonNode ok(HttpResponse<String> response)throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");}
    long room(){return db.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,1,?,'CLEAN') returning id",Long.class,"EXT_"+key().substring(0,8).toUpperCase(Locale.ROOT),type);}
    JsonNode checkIn(Browser b)throws Exception {return ok(b.post("/api/stays",Map.of("roomId",room(),"expectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString(),"guests",List.of(Map.of("name","虚构续住住客","documentType","OTHER","documentNumber","EXT-FICTIONAL-001","primary",true)))));}
    String path(JsonNode stay){return "/api/stays/"+stay.path("id").asLong()+"/extensions";}
    JsonNode detail(Browser b,JsonNode stay)throws Exception{return ok(b.get("/api/stays/"+stay.path("id").asLong()));}
    Map<String,Object> body(JsonNode stay){return new HashMap<>(Map.of("segmentId",stay.path("segmentId").asLong(),"version",stay.path("version").asLong(),"newExpectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0).toString(),"reason","虚构调整离店计划"));}

    @Test void extensionAndShorteningPreserveOccupancyGuestsAndOriginalReceipts()throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);long id=s.path("id").asLong();
        var segment=db.queryForMap("select * from stay_room_segments where stay_record_id=?",id);
        var guests=db.queryForList("select * from stay_guests where stay_record_id=?",id);
        var room=db.queryForMap("select * from rooms where id=?",s.path("roomId").asLong());
        var input=body(s);String key=key();var first=ok(b.request("POST",path(s),input,key));
        assertThat(first.path("stayVersion").asLong()).isEqualTo(1);
        assertThat(first.path("oldExpectedCheckOutAt")).isEqualTo(s.path("expectedCheckOutAt"));
        var next=detail(b,s);var shorter=body(next);shorter.put("newExpectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusHours(5).withNano(0).toString());
        ok(b.post(path(s),shorter));assertThat(detail(b,s).path("version").asLong()).isEqualTo(2);
        assertThat(ok(b.request("POST",path(s),input,key))).isEqualTo(first);
        assertThat(ok(b.get("/api/stays/extensions/result/"+key))).isEqualTo(first);
        assertThat(db.queryForMap("select * from stay_room_segments where stay_record_id=?",id)).isEqualTo(segment);
        assertThat(db.queryForList("select * from stay_guests where stay_record_id=?",id)).isEqualTo(guests);
        assertThat(db.queryForMap("select * from rooms where id=?",s.path("roomId").asLong())).isEqualTo(room);
        assertThat(db.queryForObject("select count(*) from housekeeping_tasks where stay_record_id=?",Integer.class,id)).isZero();
        assertThat(first.toString()).doesNotContain("document","phone","ciphertext");
        assertThat(ok(b.get(path(s)+"?page=1&pageSize=1")).path("total").asInt()).isEqualTo(2);
        assertThat(ok(b.get(path(s)+"?page=2&pageSize=1")).path("items").get(0)).isEqualTo(first);
        assertThat(b.get(path(s)+"?page=0").statusCode()).isEqualTo(400);
        assertThat(b.get(path(s)+"?pageSize=101").statusCode()).isEqualTo(400);
        var manager=login("HOTEL_MANAGER");
        var audit=ok(manager.get("/api/audit-logs?action=STAY_EXTEND"));
        assertThat(audit.path("total").asInt()).isEqualTo(2);
        assertThat(audit.path("items").get(0).path("targetType").asText()).isEqualTo("STAY_EXTENSION");
        assertThat(audit.toString()).doesNotContain("documentNumber","phone","虚构调整离店计划");
        assertThat(ok(manager.get("/api/audit-logs/options")).path("modules").path("STAY").toString()).contains("STAY_EXTEND");
        assertThat(login("ADMIN").get("/api/audit-logs?action=STAY_EXTEND").statusCode()).isEqualTo(403);
        assertThat(login("HOUSEKEEPING_MANAGER").get("/api/audit-logs?action=STAY_EXTEND").statusCode()).isEqualTo(403);
        assertThat(b.get("/api/audit-logs?action=STAY_EXTEND").statusCode()).isEqualTo(403);
    }
    @ParameterizedTest @ValueSource(strings={"ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF"})
    void fiveRolesAndActorScopedReceipts(String role)throws Exception {
        var front=login("FRONT_DESK");var s=checkIn(front);String key=key();ok(front.request("POST",path(s),body(s),key));
        var b=login(role);boolean allowed=Set.of("FRONT_DESK","HOTEL_MANAGER").contains(role);
        assertThat(b.get(path(s)).statusCode()).isEqualTo(allowed?200:403);
        var next=body(detail(front,s));next.put("newExpectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0).toString());
        assertThat(b.post(path(s),next).statusCode()).isEqualTo(allowed?200:403);
        assertThat(b.get("/api/stays/extensions/result/"+key).statusCode()).isEqualTo(role.equals("FRONT_DESK")?200:allowed?404:403);
    }
    @Test void unauthenticatedMissingCsrfAndUnknownStayAreRejected()throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);
        assertThat(new Browser().post(path(s),body(s)).statusCode()).isEqualTo(401);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path(s))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(s)))).build();
        assertThat(b.http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(b.post("/api/stays/9223372036854775806/extensions",body(s)).statusCode()).isEqualTo(404);
    }
    @ParameterizedTest @ValueSource(strings={"PAST","SAME","NO_REASON","LONG_REASON","CONTROL","NO_VERSION","NO_SEGMENT","NO_TIME"})
    void invalidInputDoesNotReserveAnIntent(String kind)throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);var input=body(s);
        switch(kind){
            case "PAST"->input.put("newExpectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString());
            case "SAME"->input.put("newExpectedCheckOutAt",s.path("expectedCheckOutAt").asText());
            case "NO_REASON"->input.put("reason","  ");case "LONG_REASON"->input.put("reason","a".repeat(1001));case "CONTROL"->input.put("reason","a\u0001");
            case "NO_VERSION"->input.remove("version");case "NO_SEGMENT"->input.remove("segmentId");case "NO_TIME"->input.remove("newExpectedCheckOutAt");
        }
        String key=key();assertThat(b.request("POST",path(s),input,key).statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,key)).isZero();
        assertThat(detail(b,s).path("version").asLong()).isZero();
    }
    @Test void staleVersionsSegmentsCheckedOutAndIdempotencyRules()throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);var input=body(s);String key=key();
        assertThat(b.request("POST",path(s),input,null).statusCode()).isEqualTo(400);
        var wrong=body(s);wrong.put("segmentId",s.path("segmentId").asLong()+999999);
        assertThat(b.post(path(s),wrong).statusCode()).isEqualTo(409);
        var result=ok(b.request("POST",path(s),input,key));
        assertThat(b.post(path(s),input).statusCode()).isEqualTo(409);
        var changed=new HashMap<>(input);changed.put("reason","另一次意图");
        assertThat(json.readTree(b.request("POST",path(s),changed,key).body()).path("code").asText()).isEqualTo("IDEMPOTENCY_MISMATCH");
        ok(b.post("/api/stays/"+s.path("id").asLong()+"/check-out",Map.of("segmentId",s.path("segmentId").asLong())));
        assertThat(b.post(path(s),body(detail(b,s))).statusCode()).isEqualTo(409);
        assertThat(ok(b.request("POST",path(s),input,key))).isEqualTo(result);
        db.update("update idempotency_requests set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where request_key=?::uuid",key);
        assertThat(json.readTree(b.request("POST",path(s),input,key).body()).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
        assertThat(ok(b.get("/api/stays/extensions/result/"+key))).isEqualTo(result);
    }
    @Test void overdueReminderClearsWithoutCreatingTasks()throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);
        db.update("update stay_records set check_in_at=now()-interval '2 days',expected_check_out_at=now()-interval '1 hour' where id=?",s.path("id").asLong());
        long before=ok(b.get("/api/operations/summary")).path("overdueStays").asLong();assertThat(before).isPositive();
        ok(b.post(path(s),body(s)));assertThat(ok(b.get("/api/operations/summary")).path("overdueStays").asLong()).isEqualTo(before-1);
    }
    @ParameterizedTest @ValueSource(strings={"audit_logs","stay_extensions"})
    void failedHistoryOrAuditRollsBackEverythingAndAllowsSameKeyRetry(String table)throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);var input=body(s);String key=key();
        db.execute("create function ext_fail_fn() returns trigger language plpgsql as $$ begin raise exception 'fictional failure'; end $$");
        db.execute("create trigger ext_fail before insert on "+table+" for each row execute function ext_fail_fn()");
        assertThat(b.request("POST",path(s),input,key).statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(detail(b,s).path("expectedCheckOutAt")).isEqualTo(s.path("expectedCheckOutAt"));
        assertThat(detail(b,s).path("version").asLong()).isZero();
        assertThat(db.queryForObject("select count(*) from stay_extensions",Integer.class)).isZero();
        assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,key)).isZero();
        db.execute("drop trigger ext_fail on "+table);ok(b.request("POST",path(s),input,key));
    }
    @Test void twoSimultaneousExtensionsCannotOverwriteEachOther()throws Exception {
        var b=login("FRONT_DESK");var m=login("HOTEL_MANAGER");var s=checkIn(b);var input=body(s);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return b.post(path(s),input).statusCode();});
            var c=pool.submit(()->{gate.await();return m.post(path(s),input).statusCode();});gate.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),c.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(detail(b,s).path("version").asLong()).isEqualTo(1);
        assertThat(ok(b.get(path(s))).path("total").asInt()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"CHECK_OUT","ROOM_CHANGE"})
    void concurrentExistingOperationsKeepVersionAndHistoryConsistent(String action)throws Exception {
        var b=login("FRONT_DESK");var m=login("HOTEL_MANAGER");var s=checkIn(b);
        String other="/api/stays/"+s.path("id").asLong()+(action.equals("CHECK_OUT")?"/check-out":"/room-changes");
        Object input=action.equals("CHECK_OUT")?Map.of("segmentId",s.path("segmentId").asLong()):Map.of("segmentId",s.path("segmentId").asLong(),"targetRoomId",room(),"reasonType","OTHER","reason","虚构并发换房");
        int extended;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return b.post(path(s),body(s)).statusCode();});
            var c=pool.submit(()->{gate.await();return m.post(other,input).statusCode();});gate.countDown();
            extended=a.get(20,TimeUnit.SECONDS);assertThat(extended).isIn(200,409);assertThat(c.get(20,TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(detail(b,s).path("version").asLong()).isEqualTo(extended==200?2:1);
        assertThat(ok(b.get(path(s))).path("total").asInt()).isEqualTo(extended==200?1:0);
        assertThat(db.queryForObject("select count(*) from housekeeping_tasks where stay_record_id=?",Integer.class,s.path("id").asLong())).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"DISABLE","ROLES","PASSWORD"})
    void revokedActorCannotCommitAcceptedExtension(String change)throws Exception {
        var b=login("FRONT_DESK");var admin=login("ADMIN");var s=checkIn(b);long actor=ids.get("FRONT_DESK");String key=key();
        db.execute("create function ext_pause_fn() returns trigger language plpgsql as $$ begin if NEW.operation='STAY_EXTEND' then perform pg_advisory_xact_lock(9234111); end if; return NEW; end $$");
        db.execute("create trigger ext_pause before insert on idempotency_requests for each row execute function ext_pause_fn()");
        try(var pool=Executors.newSingleThreadExecutor();var holder=db.getDataSource().getConnection()) {
            holder.setAutoCommit(false);try(var q=holder.createStatement()){q.execute("select pg_advisory_xact_lock(9234111)");}
            var future=pool.submit(()->b.request("POST",path(s),body(s),key));
            boolean waiting=false;
            for(int i=0;i<100;i++){waiting=db.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%insert into idempotency_requests%')",Boolean.class);if(waiting)break;Thread.sleep(30);}
            assertThat(waiting).isTrue();
            switch(change){
                case "DISABLE"->ok(admin.post("/api/users/"+actor+"/set-enabled",Map.of("enabled",false)));
                case "ROLES"->ok(admin.request("PUT","/api/users/"+actor+"/roles",Map.of("roles",List.of("HOUSEKEEPING_STAFF")),null));
                case "PASSWORD"->ok(admin.post("/api/users/"+actor+"/reset-password",Map.of("password",UUID.randomUUID().toString())));
            }
            holder.commit();assertThat(future.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(403);
            assertThat(db.queryForObject("select version from stay_records where id=?",Long.class,s.path("id").asLong())).isZero();
            assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,key)).isZero();
        }
    }
    @Test void realBrowserAcceptance()throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Browser tests explicitly enabled");
        var front=login("FRONT_DESK");var fixtures=new LinkedHashMap<String,JsonNode>();
        for(String name:List.of("normal","shorter","lost","reload","stale","mobile","validation","ended"))fixtures.put(name,checkIn(front));
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).getParent();var log=root.resolve(".local/extensions-browser.log");
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","stay-extensions.spec.ts").directory(root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_EXTENSION_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-extensions-results.json");
        var process=builder.start();if(!process.waitFor(300,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Extension browser timeout");}
        assertThat(process.exitValue()).as("See private extensions-browser.log").isZero();
    }
}
