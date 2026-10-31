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
class GuestCorrectionIntegrationTest {
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
    @LocalServerPort int port;
    final Map<String,Long> ids=new LinkedHashMap<>();
    final String password=UUID.randomUUID().toString();
    static final String OWN="select id from users where username like 'correction_test_%'";
    long type; String hash;
    @BeforeAll void setup() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        hash=passwords.encode(password); clean();
        db.update("delete from user_roles where user_id in("+OWN+")");db.update("delete from users where id in("+OWN+")");
        db.update("delete from room_types where type_code='GCR_TYPE'");
        for(String role:List.of("ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,
                "correction_test_"+role.toLowerCase(Locale.ROOT),hash,"虚构更正"+role);
            ids.put(role,id); users.addRole(id,role);
        }
        type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('GCR_TYPE','虚构更正房型',2) returning id",Long.class);
    }
    @BeforeEach void clean() {
        db.execute("drop trigger if exists gcr_fail on audit_logs");db.execute("drop trigger if exists gcr_fail on guest_corrections");
        db.execute("drop trigger if exists gcr_pause on idempotency_requests");
        db.execute("drop function if exists gcr_fail_fn()");db.execute("drop function if exists gcr_pause_fn()");
        var guests=db.queryForList("select guest_id from stay_guests where stay_record_id in(select id from stay_records where created_by in("+OWN+"))",Long.class);
        db.update("delete from idempotency_requests where operator_id in("+OWN+")");
        db.update("delete from guest_corrections where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
        db.update("delete from stay_extensions where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
        db.update("delete from housekeeping_task_logs where task_id in(select id from housekeeping_tasks where created_by in("+OWN+"))");
        db.update("delete from housekeeping_tasks where created_by in("+OWN+")");db.update("delete from room_changes where operated_by in("+OWN+")");
        db.update("delete from stay_guests where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
        db.update("delete from stay_room_segments where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
        db.update("delete from stay_records where created_by in("+OWN+")");for(long id:guests)db.update("delete from guests where id=?",id);
        db.update("delete from rooms where room_number like 'GCR_%'");db.update("delete from audit_logs where operator_id in("+OWN+")");
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
        var b=new Browser();ok(b.post("/api/auth/login",Map.of("username","correction_test_"+role.toLowerCase(Locale.ROOT),"password",password)));return b;
    }
    JsonNode ok(HttpResponse<String> response)throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");}
    long room(){return db.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,1,?,'CLEAN') returning id",Long.class,"GCR_"+key().substring(0,8).toUpperCase(Locale.ROOT),type);}
    JsonNode checkIn(Browser b)throws Exception {return ok(b.post("/api/stays",Map.of("roomId",room(),"expectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString(),"guests",List.of(Map.of("name","虚构更正住客","documentType","OTHER","documentNumber","EXT-FICTIONAL-001","primary",true)))));}
    String path(JsonNode stay){return "/api/stays/"+stay.path("id").asLong()+"/guests/"+stay.path("guests").get(0).path("id").asLong()+"/corrections";}
    JsonNode detail(Browser b,JsonNode stay)throws Exception{return ok(b.get("/api/stays/"+stay.path("id").asLong()));}
    Map<String,Object> body(JsonNode stay){return new HashMap<>(Map.of("version",stay.path("guests").get(0).path("version").asLong(),"changes",Map.of("name","虚构更正姓名"),"reason","虚构笔误核对"));}

    @Test void correctionKeepsRegistrationAndOccupancyWithEncryptedImmutableHistory()throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);long id=s.path("id").asLong(),g=s.path("guests").get(0).path("id").asLong();
        var registration=db.queryForList("select * from stay_guests where stay_record_id=?",id);
        var segment=db.queryForList("select * from stay_room_segments where stay_record_id=?",id);
        var input=body(s);input.put("changes",Map.of("name","虚构更正名字","documentType","PASSPORT","documentNumber","pa9876543","phone","13900000991"));String k=key();
        var first=ok(b.request("POST",path(s),input,k));
        assertThat(first.path("changedFields").size()).isEqualTo(4);assertThat(first.path("guestVersion").asLong()).isEqualTo(1);
        assertThat(first.toString()).doesNotContain("PA9876543","13900000991","ciphertext","虚构更正名字");
        var encrypted=db.queryForMap("select * from guest_corrections where id=?",first.path("id").asLong());
        String before=(String)encrypted.get("before_ciphertext"),after=(String)encrypted.get("after_ciphertext");
        assertThat(before).startsWith("v1.").isNotEqualTo(after);assertThat(after).doesNotContain("PA9876543","13900000991");
        assertThat(decrypt(after,"guest-correction")).contains("PA9876543","13900000991","虚构更正名字");
        var current=detail(b,s);assertThat(current.path("guests").get(0).path("name").asText()).isEqualTo("虚构更正名字");
        assertThat(current.path("guests").get(0).path("documentNumber").asText()).isEqualTo("PA****43");
        var next=body(current);next.put("changes",Map.of("phone",""));ok(b.post(path(s),next));
        assertThat(detail(b,s).path("guests").get(0).path("phone").asText()).isEmpty();
        assertThat(db.queryForObject("select phone_ciphertext from guests where id=?",String.class,g)).isNull();
        assertThat(ok(b.request("POST",path(s),input,k))).isEqualTo(first);
        assertThat(ok(b.get("/api/stays/guest-corrections/result/"+k))).isEqualTo(first);
        assertThat(ok(b.get(path(s)+"?pageSize=1&page=2")).path("items").get(0)).isEqualTo(first);
        assertThat(db.queryForList("select * from stay_guests where stay_record_id=?",id)).isEqualTo(registration);
        assertThat(db.queryForList("select * from stay_room_segments where stay_record_id=?",id)).isEqualTo(segment);
        assertThat(current.path("version")).isEqualTo(s.path("version"));
        assertThat(db.queryForObject("select count(*) from housekeeping_tasks where stay_record_id=?",Integer.class,id)).isZero();
        var manager=login("HOTEL_MANAGER");var audit=ok(manager.get("/api/audit-logs?action=GUEST_CORRECT"));
        assertThat(audit.path("total").asInt()).isEqualTo(2);assertThat(audit.toString()).doesNotContain("13900000991","PA9876543","虚构笔误核对");
        assertThat(ok(manager.get("/api/audit-logs/options")).path("modules").path("STAY").toString()).contains("GUEST_CORRECT");
    }
    String decrypt(String value,String field)throws Exception {
        var parts=value.split("\\.");var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"),new javax.crypto.spec.GCMParameterSpec(128,Base64.getDecoder().decode(parts[1])));
        cipher.updateAAD(("StayOS/v1/"+field).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),java.nio.charset.StandardCharsets.UTF_8);
    }
    @ParameterizedTest @ValueSource(strings={"ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF"})
    void fiveRolesAndReceiptIsolation(String role)throws Exception {
        var front=login("FRONT_DESK");var s=checkIn(front);String k=key();ok(front.request("POST",path(s),body(s),k));
        var b=login(role);boolean allowed=Set.of("FRONT_DESK","HOTEL_MANAGER").contains(role);
        assertThat(b.get(path(s)).statusCode()).isEqualTo(allowed?200:403);
        var input=body(detail(front,s));input.put("changes",Map.of("name","虚构第二次更正"));
        assertThat(b.post(path(s),input).statusCode()).isEqualTo(allowed?200:403);
        assertThat(b.get("/api/stays/guest-corrections/result/"+k).statusCode()).isEqualTo(role.equals("FRONT_DESK")?200:allowed?404:403);
    }
    @ParameterizedTest @ValueSource(strings={"EMPTY","NAME","DOCUMENT","TYPE","PHONE","NULL","UNKNOWN","NUMBER","SAME","CONTROL","REASON","LONG_REASON","VERSION"})
    void invalidPatchesLeaveNoReservation(String kind)throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);var input=body(s);
        switch(kind) {
            case "EMPTY"->input.put("changes",Map.of());case "NAME"->input.put("changes",Map.of("name"," "));
            case "DOCUMENT"->input.put("changes",Map.of("documentNumber","GC****01"));case "TYPE"->input.put("changes",Map.of("documentType","UNKNOWN"));
            case "PHONE"->input.put("changes",Map.of("phone","**991"));case "NULL"->{var patch=new HashMap<String,Object>();patch.put("phone",null);input.put("changes",patch);}
            case "UNKNOWN"->input.put("changes",Map.of("primary",false));case "NUMBER"->input.put("changes",Map.of("name",42));
            case "SAME"->input.put("changes",Map.of("name",s.path("guests").get(0).path("name").asText()));
            case "CONTROL"->input.put("changes",Map.of("name","a\u0001"));case "REASON"->input.put("reason"," ");
            case "LONG_REASON"->input.put("reason","a".repeat(1001));case "VERSION"->input.remove("version");
        }
        String k=key();assertThat(b.request("POST",path(s),input,k).statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,k)).isZero();
        assertThat(detail(b,s).path("guests").get(0).path("version").asLong()).isZero();
    }
    @Test void duplicateDocumentsForeignGuestNoAuthCsrfAndPagingAreRejected()throws Exception {
        var b=login("FRONT_DESK");var input=Map.of("roomId",room(),"expectedCheckOutAt",OffsetDateTime.now().plusDays(1).toString(),"guests",List.of(
            Map.of("name","虚构甲","documentType","OTHER","documentNumber","CORRECT-001","primary",true),
            Map.of("name","虚构乙","documentType","OTHER","documentNumber","CORRECT-002","primary",false)));
        var s=ok(b.post("/api/stays",input));var patch=body(s);patch.put("changes",Map.of("documentNumber","correct-002"));
        assertThat(b.post(path(s),patch).statusCode()).isEqualTo(400);
        assertThat(b.post(path(s).replace("/guests/","/guests/99999"),body(s)).statusCode()).isEqualTo(404);
        assertThat(b.get(path(s)+"?page=0").statusCode()).isEqualTo(400);assertThat(b.get(path(s)+"?pageSize=101").statusCode()).isEqualTo(400);
        assertThat(new Browser().post(path(s),body(s)).statusCode()).isEqualTo(401);
        var raw=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path(s))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(s)))).build();
        assertThat(b.http.send(raw,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(b.request("POST",path(s),body(s),null).statusCode()).isEqualTo(400);
    }
    @Test void versionIdempotencyExpiryCheckoutAndCipherRandomness()throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);var input=body(s);String k=key();var first=ok(b.request("POST",path(s),input,k));
        assertThat(b.post(path(s),input).statusCode()).isEqualTo(409);
        var changed=new HashMap<>(input);changed.put("reason","另一意图");assertThat(json.readTree(b.request("POST",path(s),changed,k).body()).path("code").asText()).isEqualTo("IDEMPOTENCY_MISMATCH");
        var current=detail(b,s);var second=body(current);second.put("changes",Map.of("name",s.path("guests").get(0).path("name").asText()));var original=ok(b.post(path(s),second));
        String snapshot=db.queryForObject("select before_ciphertext from guest_corrections where id=?",String.class,first.path("id").asLong());
        String snapshot2=db.queryForObject("select after_ciphertext from guest_corrections where id=?",String.class,original.path("id").asLong());
        assertThat(snapshot).isNotEqualTo(snapshot2);assertThat(decrypt(snapshot,"guest-correction")).isEqualTo(decrypt(snapshot2,"guest-correction"));
        ok(b.post("/api/stays/"+s.path("id").asLong()+"/check-out",Map.of("segmentId",s.path("segmentId").asLong())));
        assertThat(b.post(path(s),body(detail(b,s))).statusCode()).isEqualTo(409);assertThat(ok(b.request("POST",path(s),input,k))).isEqualTo(first);
        db.update("update idempotency_requests set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where request_key=?::uuid",k);
        assertThat(json.readTree(b.request("POST",path(s),input,k).body()).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
        assertThat(ok(b.get("/api/stays/guest-corrections/result/"+k))).isEqualTo(first);
    }
    @ParameterizedTest @ValueSource(strings={"audit_logs","guest_corrections"})
    void historyAndAuditFailureRollBackProjectionAndIdempotency(String table)throws Exception {
        var b=login("FRONT_DESK");var s=checkIn(b);String k=key();var before=detail(b,s);
        db.execute("create function gcr_fail_fn() returns trigger language plpgsql as $$ begin raise exception 'fictional failure'; end $$");
        db.execute("create trigger gcr_fail before insert on "+table+" for each row execute function gcr_fail_fn()");
        assertThat(b.request("POST",path(s),body(s),k).statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(detail(b,s)).isEqualTo(before);assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,k)).isZero();
        db.execute("drop trigger gcr_fail on "+table);ok(b.request("POST",path(s),body(s),k));
    }
    @ParameterizedTest @ValueSource(strings={"CORRECT","CHECK_OUT","ROOM_CHANGE"})
    void concurrentOperationsSerializeAtStay(String action)throws Exception {
        var b=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");var s=checkIn(b);
        long target=action.equals("ROOM_CHANGE")?room():0;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return b.post(path(s),body(s)).statusCode();});
            var c=pool.submit(()->{gate.await();return switch(action) {
                case "CORRECT"->manager.post(path(s),body(s)).statusCode();
                case "ROOM_CHANGE"->manager.post("/api/stays/"+s.path("id").asLong()+"/room-changes",Map.of("segmentId",s.path("segmentId").asLong(),"targetRoomId",target,"reasonType","OTHER","reason","虚构并发换房")).statusCode();
                default->manager.post("/api/stays/"+s.path("id").asLong()+"/check-out",Map.of("segmentId",s.path("segmentId").asLong())).statusCode();
            };});gate.countDown();
            int first=a.get(20,TimeUnit.SECONDS),other=c.get(20,TimeUnit.SECONDS);
            if(action.equals("CORRECT")) assertThat(List.of(first,other)).containsExactlyInAnyOrder(200,409);
            else {assertThat(first).isIn(200,409);assertThat(other).isEqualTo(200);if(action.equals("ROOM_CHANGE"))assertThat(first).isEqualTo(200);}
            assertThat(ok(b.get(path(s))).path("total").asInt()).isEqualTo(action.equals("CORRECT") || first==200?1:0);
        }
    }
    @ParameterizedTest @ValueSource(strings={"DISABLE","ROLES","PASSWORD"})
    void revokedActorCannotCommitCorrection(String change)throws Exception {
        var b=login("FRONT_DESK");var admin=login("ADMIN");var s=checkIn(b);long actor=ids.get("FRONT_DESK");String k=key();
        db.execute("create function gcr_pause_fn() returns trigger language plpgsql as $$ begin if NEW.operation='GUEST_CORRECT' then perform pg_advisory_xact_lock(9234112); end if; return NEW; end $$");
        db.execute("create trigger gcr_pause before insert on idempotency_requests for each row execute function gcr_pause_fn()");
        try(var pool=Executors.newSingleThreadExecutor();var holder=db.getDataSource().getConnection()) {
            holder.setAutoCommit(false);try(var q=holder.createStatement()){q.execute("select pg_advisory_xact_lock(9234112)");}
            var future=pool.submit(()->b.request("POST",path(s),body(s),k));boolean waiting=false;
            for(int i=0;i<100;i++){waiting=db.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%insert into idempotency_requests%')",Boolean.class);if(waiting)break;Thread.sleep(30);}
            assertThat(waiting).isTrue();
            switch(change){case "DISABLE"->ok(admin.post("/api/users/"+actor+"/set-enabled",Map.of("enabled",false)));case "ROLES"->ok(admin.request("PUT","/api/users/"+actor+"/roles",Map.of("roles",List.of("HOUSEKEEPING_STAFF")),null));case "PASSWORD"->ok(admin.post("/api/users/"+actor+"/reset-password",Map.of("password",UUID.randomUUID().toString())));}
            holder.commit();assertThat(future.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(403);
            assertThat(detail(login("HOTEL_MANAGER"),s).path("guests").get(0).path("version").asLong()).isZero();
        }
    }
    @Test void realBrowserAcceptance()throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Browser tests explicitly enabled");
        var front=login("FRONT_DESK");var fixtures=new LinkedHashMap<String,JsonNode>();
        for(String name:List.of("normal","phone","lost","reload","stale","mobile","validation","ended","history"))fixtures.put(name,checkIn(front));
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).getParent();var log=root.resolve(".local/corrections-browser.log");
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","guest-corrections.spec.ts").directory(root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_CORRECTION_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-corrections-results.json");
        var process=builder.start();if(!process.waitFor(300,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Correction browser timeout");}
        assertThat(process.exitValue()).as("See private corrections-browser.log").isZero();
    }
}
