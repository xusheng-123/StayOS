package com.stayos;

import com.fasterxml.jackson.databind.*;
import com.stayos.staff.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class OperationalReliabilityTest {
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
    @LocalServerPort int port;
    final Map<String,Long> ids=new LinkedHashMap<>();final String password=UUID.randomUUID().toString();long type;
    static final String OWN="select id from users where username like 'ops_test_%'";
    @BeforeAll void setup() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        clean();db.update("delete from user_roles where user_id in("+OWN+")");db.update("delete from users where id in("+OWN+")");db.update("delete from room_types where type_code='OPS_TYPE'");
        for(String role:List.of("ADMIN","HOTEL_MANAGER","FRONT_DESK","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,"ops_test_"+role.toLowerCase(Locale.ROOT),passwords.encode(password),"虚构运维"+role);
            ids.put(role,id);users.addRole(id,role);
        }
        type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('OPS_TYPE','虚构运维房型',2) returning id",Long.class);
    }
    @BeforeEach void clean() {
        db.execute("drop trigger if exists ops_fault on audit_logs");db.execute("drop trigger if exists ops_pause on idempotency_requests");
        db.execute("drop function if exists ops_fault_fn()");db.execute("drop function if exists ops_pause_fn()");
        var guests=db.queryForList("select guest_id from stay_guests where stay_record_id in(select id from stay_records where created_by in("+OWN+"))",Long.class);
        db.update("delete from idempotency_requests where operator_id in("+OWN+")");
        db.update("delete from housekeeping_assignment_items where batch_id in(select id from housekeeping_assignment_batches where operator_id in("+OWN+"))");
        db.update("delete from housekeeping_assignment_batches where operator_id in("+OWN+")");
        db.update("delete from housekeeping_task_logs where task_id in(select id from housekeeping_tasks where created_by in("+OWN+"))");
        db.update("delete from housekeeping_tasks where created_by in("+OWN+")");
        db.update("delete from room_changes where operated_by in("+OWN+")");
        db.update("delete from room_issue_logs where issue_id in(select id from room_issues where reported_by in("+OWN+"))");
        db.update("delete from room_issues where reported_by in("+OWN+")");
        db.update("delete from stay_guests where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
        db.update("delete from stay_room_segments where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
        db.update("delete from stay_records where created_by in("+OWN+")");for(long id:guests)db.update("delete from guests where id=?",id);
        db.update("delete from rooms where room_number like 'OPS_%'");db.update("delete from audit_logs where operator_id in("+OWN+")");
        db.update("delete from user_roles where user_id in(select id from users where username like 'ops_test_created_%')");
        db.update("delete from users where username like 'ops_test_created_%'");
        for(var e:ids.entrySet()) {
            db.update("update users set enabled=true,password_hash=?,failed_login_attempts=0,locked_until=null where id=?",passwords.encode(password),e.getValue());
            db.update("delete from user_roles where user_id=?",e.getValue());users.addRole(e.getValue(),e.getKey());
        }
    }
    @AfterAll void after() {
        clean();db.update("delete from room_types where id=?",type);
        for(long id:ids.values()){db.update("delete from user_roles where user_id=?",id);db.update("delete from users where id=?",id);}
    }
    final class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> request(String method,String path,Object body,String key) throws Exception {
            var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
            if(!method.equals("GET")){var c=ok(get("/api/auth/csrf"));r.header(c.path("headerName").asText(),c.path("token").asText());}
            if(key!=null)r.header("Idempotency-Key",key);
            return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception{return request("GET",path,null,null);}
        HttpResponse<String> post(String path,Object body) throws Exception{return request("POST",path,body,UUID.randomUUID().toString());}
    }
    Browser login(String role) throws Exception {
        var b=new Browser();ok(b.post("/api/auth/login",Map.of("username","ops_test_"+role.toLowerCase(Locale.ROOT),"password",password)));return b;
    }
    JsonNode ok(HttpResponse<String> r) throws Exception {assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json.readTree(r.body()).path("data");}
    long room() {return db.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,1,?,'CLEAN') returning id",Long.class,"OPS_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT),type);}
    Map<String,Object> checkIn(long room){return Map.of("roomId",room,"expectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString(),"guests",List.of(Map.of("name","虚构运维住客","documentType","OTHER","documentNumber","OPS-FICTIONAL-001","primary",true)),"notes","");}
    @Test void logoutRevokesOriginalSessionEvenWhenAuditInsertFails() throws Exception {
        var b=login("FRONT_DESK");String cookie=b.cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("STAYOS_SESSION")).map(c->c.getName()+"="+c.getValue()).findFirst().orElseThrow();
        db.execute("create function ops_fault_fn() returns trigger language plpgsql as $$ begin if NEW.action='LOGOUT' then raise exception 'fictional audit outage'; end if; return NEW; end $$");
        db.execute("create trigger ops_fault before insert on audit_logs for each row execute function ops_fault_fn()");
        try {
            assertThat(b.post("/api/auth/logout",null).statusCode()).isEqualTo(200);
            assertThat(b.get("/api/auth/me").statusCode()).isEqualTo(401);
            var old=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/me")).header("Cookie",cookie).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(old.statusCode()).isEqualTo(401);
            assertThat(db.queryForObject("select count(*) from audit_logs where operator_id=? and action='LOGOUT'",Integer.class,ids.get("FRONT_DESK"))).isZero();
        } finally {db.execute("drop trigger ops_fault on audit_logs");db.execute("drop function ops_fault_fn()");}
    }
    @ParameterizedTest @CsvSource({"CHECK_IN,DISABLE","CHECK_IN,ROLES","CHECK_IN,PASSWORD","CHECK_OUT,DISABLE","CHECK_OUT,ROLES","CHECK_OUT,PASSWORD","ROOM_CHANGE,PASSWORD"})
    void revokedActorCannotCommitAnAlreadyAcceptedBusinessRequest(String operation,String change) throws Exception {
        var b=login("FRONT_DESK");var admin=login("ADMIN");long actor=ids.get("FRONT_DESK"),room=room();String key=UUID.randomUUID().toString();
        var body=checkIn(room);String path="/api/stays";
        if(operation.equals("CHECK_OUT")){var stay=ok(b.post(path,body));path+="/"+stay.path("id").asLong()+"/check-out";body=Map.of("segmentId",stay.path("segmentId").asLong());}
        if(operation.equals("ROOM_CHANGE")){var stay=ok(b.post(path,body));path+="/"+stay.path("id").asLong()+"/room-changes";body=Map.of("segmentId",stay.path("segmentId").asLong(),"targetRoomId",room(),"reasonType","GUEST_REQUEST","reason","虚构账号撤销竞态验证");}
        db.execute("create function ops_pause_fn() returns trigger language plpgsql as $$ begin if NEW.operator_id="+actor+" and NEW.operation='"+operation+"' then perform pg_advisory_xact_lock(9234102); end if; return NEW; end $$");
        db.execute("create trigger ops_pause before insert on idempotency_requests for each row execute function ops_pause_fn()");
        var pool=Executors.newSingleThreadExecutor();
        try(var holder=Objects.requireNonNull(db.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);try(var s=holder.createStatement()){s.execute("select pg_advisory_xact_lock(9234102)");}
            final String target=path;final Object input=body;var future=pool.submit(()->b.request("POST",target,input,key));
            boolean waiting=false;
            for(int i=0;i<100;i++){waiting=db.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%insert into idempotency_requests%')",Boolean.class);if(waiting)break;Thread.sleep(30);}
            assertThat(waiting).isTrue();
            switch(change) {
                case "DISABLE" -> ok(admin.post("/api/users/"+actor+"/set-enabled",Map.of("enabled",false)));
                case "ROLES" -> ok(admin.request("PUT","/api/users/"+actor+"/roles",Map.of("roles",List.of("HOUSEKEEPING_STAFF")),null));
                case "PASSWORD" -> ok(admin.post("/api/users/"+actor+"/reset-password",Map.of("password",UUID.randomUUID().toString())));
            }
            holder.commit();assertThat(future.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(403);
            assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,key)).isZero();
            assertThat(db.queryForObject("select count(*) from stay_records where created_by=? and status='CHECKED_OUT'",Integer.class,actor)).isZero();
            assertThat(db.queryForObject("select count(*) from stay_records where created_by=?",Integer.class,actor)).isEqualTo(operation.equals("CHECK_IN")?0:1);
            ok(admin.post("/api/users/"+actor+"/set-enabled",Map.of("enabled",true)));
            ok(admin.request("PUT","/api/users/"+actor+"/roles",Map.of("roles",List.of("FRONT_DESK")),null));
            ok(admin.post("/api/users/"+actor+"/reset-password",Map.of("password",password)));
            assertThat(login("FRONT_DESK").request("POST",target,input,key).statusCode()).isEqualTo(200);
        } finally {pool.shutdownNow();db.execute("drop trigger if exists ops_pause on idempotency_requests");db.execute("drop function if exists ops_pause_fn()");}
    }
    @Test void readinessAndRequestIdsDoNotExposeDatabaseOrClientSuppliedTrackingData() throws Exception {
        var b=new Browser();assertThat(b.get("/api/health/ready").statusCode()).isEqualTo(200);
        var response=b.get("/api/users");assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("X-Request-ID").orElseThrow()).matches("[a-f0-9-]{36}");
        assertThat(response.body()).doesNotContain("jdbc:","password_hash","org.postgresql");
    }
    @Test void operationalSummaryHasRealCountsAndEnforcesFiveRoleDataScopes() throws Exception {
        var manager=login("HOTEL_MANAGER");var front=login("FRONT_DESK");long stayRoom=room(),issueRoom=room(),taskRoom=room();
        ok(manager.post("/api/room-issues",Map.of("roomId",issueRoom,"issueType","SAFETY","severity","CRITICAL","description","虚构安全异常")));
        db.update("update rooms set cleaning_status='DIRTY' where id=?",taskRoom);
        ok(manager.post("/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",ids.get("HOUSEKEEPING_STAFF"),"roomIds",List.of(taskRoom))));
        db.update("update housekeeping_tasks set created_at=now()-interval '7 hours' where room_id=?",taskRoom);
        var body=new HashMap<String,Object>(checkIn(stayRoom));body.put("expectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(4).toString());ok(front.post("/api/stays",body));
        var staff=login("HOUSEKEEPING_STAFF");var staffSummary=ok(staff.get("/api/operations/summary"));
        assertThat(staffSummary.path("overdueStays").isNull()).isTrue();assertThat(staffSummary.path("criticalIssues").asLong()).isZero();
        assertThat(staffSummary.path("delayedTasks").asLong()).isEqualTo(1);assertThat(staffSummary.toString()).doesNotContain("documentNumber","phone","虚构运维住客");
        ok(login("ADMIN").post("/api/users/"+ids.get("HOUSEKEEPING_STAFF")+"/set-enabled",Map.of("enabled",false)));
        Thread.sleep(4200);
        var all=ok(manager.get("/api/operations/summary"));assertThat(all.path("overdueStays").asLong()).isGreaterThanOrEqualTo(1);assertThat(all.path("strandedTasks").asLong()).isEqualTo(1);
        assertThat(all.path("criticalIssues").asLong()).isGreaterThanOrEqualTo(1);
        var hk=ok(login("HOUSEKEEPING_MANAGER").get("/api/operations/summary"));assertThat(hk.path("overdueStays").isNull()).isTrue();assertThat(hk.path("strandedTasks").asLong()).isEqualTo(1);
        var desk=ok(front.get("/api/operations/summary"));assertThat(desk.path("strandedTasks").isNull()).isTrue();assertThat(desk.path("overdueStays").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(login("ADMIN").get("/api/operations/summary").statusCode()).isEqualTo(403);assertThat(new Browser().get("/api/operations/summary").statusCode()).isEqualTo(401);
    }
    @Test void accountSearchIsServerSidePagedAndReturnsNoPasswordFields() throws Exception {
        var admin=login("ADMIN");var matches=ok(admin.get("/api/users?search=ops_test_front_desk&pageSize=1"));
        assertThat(matches.path("total").asLong()).isEqualTo(1);assertThat(matches.path("items").size()).isEqualTo(1);
        assertThat(matches.toString()).doesNotContain("passwordHash","$2a$","securityVersion");
        assertThat(login("HOTEL_MANAGER").get("/api/users?search=ops_test_").statusCode()).isEqualTo(403);
        assertThat(ok(admin.get("/api/users?search=no_such_ops_employee")).path("total").asLong()).isZero();
    }
    @Test void operationsBrowserAcceptance() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Browser tests explicitly enabled");
        var directory=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("frontend").toFile();
        var log=directory.toPath().getParent().resolve(".local/operations-browser.log");java.nio.file.Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","operations.spec.ts").directory(directory).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);builder.environment().put("STAYOS_E2E_IDS",json.writeValueAsString(ids));
        builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-operations-results.json");
        var process=builder.start();if(!process.waitFor(240,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Operations browser timeout");}
        assertThat(process.exitValue()).as("See .local/operations-browser.log").isZero();
    }
}
