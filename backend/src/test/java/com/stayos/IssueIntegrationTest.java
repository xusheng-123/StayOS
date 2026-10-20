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
class IssueIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CheckoutService checkout;
    @Autowired com.stayos.housekeeping.service.TaskWorkflowService workflow;
    @Autowired com.stayos.housekeeping.service.AssignmentService assignment;
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
        for(String role:List.of("ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF","STAFF2")) {
            long id=jdbc.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,"issue_test_"+role.toLowerCase(Locale.ROOT),passwords.encode(password),"清洁测试"+role);
            ids.put(role,id);users.addRole(id,role.equals("STAFF2")?"HOUSEKEEPING_STAFF":role);
        }
        type=jdbc.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('S6_TYPE','虚构清洁测试房型',2) returning id",Long.class);
    }
    @BeforeEach void clean() {
        for(String table:List.of("room_issues","room_issue_logs","room_maintenance_stops","room_maintenance_logs","audit_logs","idempotency_requests")) jdbc.execute("drop trigger if exists test_fail_issue on "+table);
        jdbc.execute("drop function if exists test_fail_issue_fn()");
        jdbc.update("delete from idempotency_requests where operator_id in(select id from users where username like 'issue_test_%')");
        jdbc.update("delete from room_issue_logs where issue_id in(select id from room_issues where room_id in(select id from rooms where room_number like 'S6_%'))");
        jdbc.update("delete from room_maintenance_logs where stop_id in(select id from room_maintenance_stops where room_id in(select id from rooms where room_number like 'S6_%'))");
        jdbc.update("delete from room_maintenance_stops where room_id in(select id from rooms where room_number like 'S6_%')");
        jdbc.update("delete from room_issues where room_id in(select id from rooms where room_number like 'S6_%')");
        for(String table:List.of("housekeeping_tasks","rooms","housekeeping_task_logs","audit_logs","idempotency_requests","housekeeping_assignment_items","housekeeping_assignment_batches")) jdbc.execute("drop trigger if exists test_fail_hk on "+table);
        jdbc.execute("drop function if exists test_fail_hk_fn()");
        jdbc.update("update users set enabled=true where username like 'issue_test_%'");
        var guests=jdbc.queryForList("select guest_id from stay_guests where stay_record_id in(select id from stay_records where created_by in(select id from users where username like 'issue_test_%'))",Long.class);
        jdbc.update("delete from idempotency_requests where operator_id in(select id from users where username like 'issue_test_%')");
        jdbc.update("delete from housekeeping_assignment_items where batch_id in(select id from housekeeping_assignment_batches where operator_id in(select id from users where username like 'issue_test_%'))");
        jdbc.update("delete from housekeeping_assignment_batches where operator_id in(select id from users where username like 'issue_test_%')");
        jdbc.update("delete from housekeeping_task_logs where task_id in(select id from housekeeping_tasks where created_by in(select id from users where username like 'issue_test_%'))");
        jdbc.update("delete from housekeeping_tasks where created_by in(select id from users where username like 'issue_test_%')");
        jdbc.update("delete from stay_guests where stay_record_id in(select id from stay_records where created_by in(select id from users where username like 'issue_test_%'))");
        jdbc.update("delete from stay_room_segments where room_id in(select id from rooms where room_number like 'S6_%')");
        jdbc.update("delete from stay_records where created_by in(select id from users where username like 'issue_test_%')");
        for(long guest:guests) jdbc.update("delete from guests where id=?",guest);
        jdbc.update("delete from rooms where room_number like 'S6_%'");
        jdbc.update("delete from audit_logs where operator_id in(select id from users where username like 'issue_test_%')");
        StayTestFacts.restore(jdbc);
    }
    @AfterAll void cleanup() {
        clean();jdbc.update("delete from room_types where id=?",type);
        for(long id:ids.values()) { jdbc.update("delete from user_roles where user_id=?",id);jdbc.update("delete from users where id=?",id); }
    }
    String key() { return UUID.randomUUID().toString(); }
    long room() {
        return jdbc.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,5,?,'DIRTY') returning id",Long.class,"S6_"+key().substring(0,8).toUpperCase(Locale.ROOT),type);
    }
    Map<String,Object> checkInBody(long room) {
        return Map.of("roomId",room,"expectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString(),"guests",List.of(Map.of("name","虚构退房住客","documentType","OTHER","documentNumber","S5-FICTIONAL-001","phone","000-000-0000","primary",true)),"notes","");
    }
    final class Browser {
        String role;
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
        var b=new Browser();b.role=role;assertThat(b.request("POST","/api/auth/login",Map.of("username","issue_test_"+role.toLowerCase(Locale.ROOT),"password",password),null).statusCode()).isEqualTo(200);ok(b.get("/api/auth/csrf"));return b;
    }
    JsonNode checkIn(long room) throws Exception {
        var r=login("FRONT_DESK").request("POST","/api/stays",checkInBody(room),key());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return data(r);
    }
    CheckoutRequest input(JsonNode stay) { return new CheckoutRequest(stay.path("segmentId").asLong()); }

    JsonNode ok(HttpResponse<String> r) throws Exception { assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return data(r); }
    long manual(Browser manager,long room) throws Exception {
        return ok(manager.request("POST","/api/housekeeping-tasks/manual",Map.of("roomId",room,"reason","虚构测试：初始待打扫"),key())).path("taskId").asLong();
    }
    long version(long task) { return jdbc.queryForObject("select version from housekeeping_tasks where id=?",Long.class,task); }
    Map<String,Object> actionBody(long task) { return Map.of("version",version(task)); }
    HttpResponse<String> act(Browser b,long task,String action) throws Exception { return b.request("POST","/api/housekeeping-tasks/"+task+"/"+action,actionBody(task),key()); }
    HttpResponse<String> assign(Browser b,long task,String action,String target,String reason) throws Exception {
        return b.request("POST","/api/housekeeping-tasks/"+task+"/"+action,Map.of("version",version(task),"assigneeId",ids.get(target),"reason",reason),key());
    }
    void state(long task,String status,String clean) {
        assertThat(jdbc.queryForObject("select status from housekeeping_tasks where id=?",String.class,task)).isEqualTo(status);
        assertThat(jdbc.queryForObject("select cleaning_status from rooms where id=(select room_id from housekeeping_tasks where id=?)",String.class,task)).isEqualTo(clean);
    }
    void give(Browser employee,long task) throws Exception { ok(assign(login("HOUSEKEEPING_MANAGER"),task,"assign",employee.role,"主管分房")); }
    void finish(Browser staff,long task) throws Exception { give(staff,task);ok(act(staff,task,"start"));ok(act(staff,task,"complete")); }

    Map<String,Object> reportBody(long room,String severity) { return Map.of("roomId",room,"issueType","PLUMBING","severity",severity,"description","虚构测试：管道渗水","notes",""); }
    long issue(Browser b,long room,String severity) throws Exception { return ok(b.request("POST","/api/room-issues",reportBody(room,severity),key())).path("issueId").asLong(); }
    Map<String,Object> issueAction(long id,String reason) { return Map.of("version",jdbc.queryForObject("select version from room_issues where id=?",Long.class,id),"reason",reason); }
    HttpResponse<String> change(Browser b,long id,String action) throws Exception { return b.request("POST","/api/room-issues/"+id+"/"+action,issueAction(id,"测试处理结论"),key()); }
    JsonNode roomView(Browser b,long room) throws Exception { return ok(b.get("/api/rooms/"+room)); }
    long cleanRoom() throws Exception { long r=room();long t=manual(login("HOUSEKEEPING_MANAGER"),r);finish(login("HOUSEKEEPING_STAFF"),t);return r; }
    long stop(Browser b,long room) throws Exception { return ok(b.request("POST","/api/rooms/"+room+"/maintenance-stop",Map.of("reason","测试维修停用"),key())).path("stopId").asLong(); }
    HttpResponse<String> resume(Browser b,long room,long stop,String key) throws Exception { return b.request("POST","/api/rooms/"+room+"/maintenance-resume",Map.of("stopId",stop,"reason","修复完毕恢复"),key); }
    @Test void reportsSupportFourBusinessRolesAndServerIdentity() throws Exception {
        long room=room();for(String role:List.of("FRONT_DESK","HOUSEKEEPING_STAFF","HOUSEKEEPING_MANAGER","HOTEL_MANAGER")) {
            long id=issue(login(role),room,"LOW");var row=jdbc.queryForMap("select * from room_issues where id=?",id);
            assertThat(row).containsEntry("reported_by",ids.get(role)).containsEntry("status","OPEN");assertThat(row.get("reported_at")).isNotNull();
        }
        assertThat(jdbc.queryForObject("select count(*) from room_issues where room_id=?",Integer.class,room)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from room_issue_logs",Integer.class)).isEqualTo(4);
    }
    @Test void adminAnonymousAndDisabledAccountsAreRejected() throws Exception {
        long room=room();assertThat(new Browser().get("/api/room-issues").statusCode()).isEqualTo(401);
        var admin=login("ADMIN");assertThat(admin.get("/api/room-issues").statusCode()).isEqualTo(403);
        assertThat(admin.request("POST","/api/room-issues",reportBody(room,"LOW"),key()).statusCode()).isEqualTo(403);
        var staff=login("HOUSEKEEPING_STAFF");jdbc.update("update users set enabled=false where id=?",ids.get("HOUSEKEEPING_STAFF"));
        assertThat(staff.request("POST","/api/room-issues",reportBody(room,"LOW"),key()).statusCode()).isIn(401,403);
    }
    @Test void invalidInputsMissingRoomsAndForgedActorDoNotCreateFacts() throws Exception {
        var b=login("FRONT_DESK");long room=room();assertThat(b.request("POST","/api/room-issues",reportBody(999999999,"LOW"),key()).statusCode()).isEqualTo(404);
        for(var body:List.of(Map.of("roomId",room,"issueType","FAKE","severity","LOW","description","测试"),Map.of("roomId",room,"issueType","OTHER","severity","BAD","description","测试"),Map.of("roomId",room,"issueType","OTHER","severity","LOW","description"," "),Map.of("roomId",room,"issueType","OTHER","severity","LOW","description","test\u0001")))
            assertThat(b.request("POST","/api/room-issues",body,key()).statusCode()).isEqualTo(400);
        var body=new HashMap<String,Object>(reportBody(room,"LOW"));body.put("reportedBy",ids.get("ADMIN"));body.put("reportedAt","2000-01-01T00:00:00Z");
        var response=b.request("POST","/api/room-issues",body,key());assertThat(response.statusCode()).isEqualTo(200);
        if(response.statusCode()==200) assertThat(jdbc.queryForObject("select reported_by from room_issues where id=?",Long.class,data(response).path("issueId").asLong())).isEqualTo(ids.get("FRONT_DESK"));
    }
    @Test void staffScopeCannotBeBypassedThroughFiltersDetailsOrHistory() throws Exception {
        long room=room();long own=issue(login("HOUSEKEEPING_STAFF"),room,"LOW"),other=issue(login("STAFF2"),room,"CRITICAL");var staff=login("HOUSEKEEPING_STAFF");
        assertThat(ok(staff.get("/api/room-issues?roomId="+room)).path("total").asInt()).isEqualTo(1);
        assertThat(staff.get("/api/room-issues/"+other).statusCode()).isEqualTo(404);assertThat(staff.get("/api/room-issues/"+other+"/history").statusCode()).isEqualTo(404);
        assertThat(ok(staff.get("/api/room-issues/"+own)).toString()).doesNotContain("document","phone","ciphertext");
        assertThat(ok(staff.get("/api/room-issues/statistics")).path("critical").asInt()).isZero();
        assertThat(roomView(staff,room).path("hasCriticalIssue").asBoolean()).isTrue();
        assertThat(ok(login("FRONT_DESK").get("/api/room-issues")).path("total").asInt()).isEqualTo(2);
    }
    @Test void filtersPaginationStatisticsAndChineseErrors() throws Exception {
        long room=room();var manager=login("HOUSEKEEPING_MANAGER");long id=issue(manager,room,"CRITICAL");issue(manager,room,"LOW");ok(change(manager,id,"start"));
        String number=roomView(manager,room).path("roomNumber").asText();
        var result=ok(manager.get("/api/room-issues?roomNumber="+number+"&floor=5&issueType=PLUMBING&severity=CRITICAL&status=IN_PROGRESS&pageSize=1"));assertThat(result.path("total").asInt()).isEqualTo(1);
        var stats=ok(manager.get("/api/room-issues/statistics"));assertThat(stats.path("open").asInt()).isEqualTo(1);assertThat(stats.path("inProgress").asInt()).isEqualTo(1);assertThat(stats.path("critical").asInt()).isEqualTo(1);assertThat(stats.path("maintenanceRooms").asInt()).isEqualTo(1);
        for(String query:List.of("floor=0","page=0","pageSize=101","issueType=BAD","severity=BAD","status=BAD")) assertThat(manager.get("/api/room-issues?"+query).statusCode()).isEqualTo(400);
    }
    @Test void normalAndDirectResolutionPreserveAppendOnlyHistory() throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");long i=issue(m,r,"CRITICAL");ok(change(m,i,"start"));ok(change(m,i,"resolve"));
        var d=ok(m.get("/api/room-issues/"+i));assertThat(d.path("status").asText()).isEqualTo("RESOLVED");assertThat(d.path("startedAt").isTextual()).isTrue();assertThat(d.path("resolvedAt").isTextual()).isTrue();
        var history=ok(m.get("/api/room-issues/"+i+"/history"));assertThat(history).hasSize(3);assertThat(history.get(2).path("operatorId").asLong()).isEqualTo(ids.get("HOUSEKEEPING_MANAGER"));
        assertThat(change(m,i,"resolve").statusCode()).isEqualTo(409);assertThat(m.request("DELETE","/api/room-issues/"+i,null,null).statusCode()).isEqualTo(405);
        long second=issue(m,r,"LOW");ok(change(m,second,"resolve"));assertThat(ok(m.get("/api/room-issues/"+second+"/history"))).hasSize(2);
    }
    @Test void onlyTwoManagersCanManageIssuesAndMaintenance() throws Exception {
        long r=room(),id=issue(login("HOUSEKEEPING_MANAGER"),r,"CRITICAL");
        for(String role:List.of("ADMIN","FRONT_DESK","HOUSEKEEPING_STAFF")) {
            var b=login(role);assertThat(change(b,id,"start").statusCode()).isEqualTo(403);assertThat(change(b,id,"resolve").statusCode()).isEqualTo(403);
            assertThat(b.request("POST","/api/rooms/"+r+"/maintenance-stop",Map.of("reason","测试"),key()).statusCode()).isEqualTo(403);
            assertThat(resume(b,r,1,key()).statusCode()).isEqualTo(403);
        }
        ok(change(login("HOTEL_MANAGER"),id,"resolve"));long stop=stop(login("HOTEL_MANAGER"),r);ok(resume(login("HOUSEKEEPING_MANAGER"),r,stop,key()));
    }
    @Test void severityChangeRequiresReasonVersionAndRecomputesBlocking() throws Exception {
        long r=cleanRoom();var m=login("HOUSEKEEPING_MANAGER");long id=issue(m,r,"LOW");assertThat(roomView(m,r).path("canCheckIn").asBoolean()).isTrue();
        var body=Map.of("version",0,"severity","CRITICAL","reason","升级：确认严重漏水");ok(m.request("POST","/api/room-issues/"+id+"/severity",body,key()));
        assertThat(roomView(m,r).path("canCheckIn").asBoolean()).isFalse();assertThat(m.request("POST","/api/room-issues/"+id+"/severity",body,key()).statusCode()).isEqualTo(409);
        assertThat(m.request("POST","/api/room-issues/"+id+"/severity",Map.of("version",1,"severity","LOW","reason",""),key()).statusCode()).isEqualTo(400);
        assertThat(login("FRONT_DESK").request("POST","/api/room-issues/"+id+"/severity",Map.of("version",1,"severity","LOW","reason","测试"),key()).statusCode()).isEqualTo(403);
        ok(m.request("POST","/api/room-issues/"+id+"/severity",Map.of("version",1,"severity","MEDIUM","reason","复核降低"),key()));assertThat(roomView(m,r).path("canCheckIn").asBoolean()).isTrue();
        assertThat(ok(m.get("/api/room-issues/"+id+"/history"))).hasSize(3);
    }
    @Test void criticalAndManualStopsRemainIndependentAndCannotBypassResume() throws Exception {
        long r=cleanRoom();var m=login("HOUSEKEEPING_MANAGER");long id=issue(m,r,"CRITICAL"),stop=stop(m,r);
        assertThat(resume(m,r,stop,key()).statusCode()).isEqualTo(409);ok(change(m,id,"resolve"));assertThat(roomView(m,r).path("maintenanceStatus").asText()).isEqualTo("OUT_OF_SERVICE");
        ok(resume(m,r,stop,key()));assertThat(roomView(m,r).path("canCheckIn").asBoolean()).isTrue();assertThat(resume(m,r,stop,key()).statusCode()).isEqualTo(409);
        assertThat(ok(m.get("/api/rooms/"+r+"/maintenance-history"))).hasSize(1);assertThat(jdbc.queryForObject("select count(*) from room_maintenance_logs",Integer.class)).isEqualTo(2);
    }
    @Test void allCriticalIssuesMustBeResolvedButLowAndMediumDoNotBlock() throws Exception {
        long r=cleanRoom();var m=login("HOUSEKEEPING_MANAGER");issue(m,r,"LOW");issue(m,r,"MEDIUM");assertThat(roomView(m,r).path("canCheckIn").asBoolean()).isTrue();
        long a=issue(m,r,"CRITICAL"),b=issue(m,r,"CRITICAL");ok(change(m,a,"resolve"));assertThat(roomView(m,r).path("canCheckIn").asBoolean()).isFalse();ok(change(m,b,"resolve"));assertThat(roomView(m,r).path("canCheckIn").asBoolean()).isTrue();
    }
    @Test void manualStopDoesNotAlterDirtyRoomAndResumeDoesNotCleanIt() throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");long stop=stop(m,r);ok(resume(m,r,stop,key()));var room=roomView(m,r);
        assertThat(room.path("cleaningStatus").asText()).isEqualTo("DIRTY");assertThat(room.path("canCheckIn").asBoolean()).isFalse();assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isZero();
    }
    @Test void legitimateCleaningCompletesWhileMaintenanceRemainsBlocking() throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");long stop=stop(m,r),task=manual(m,r);finish(login("HOUSEKEEPING_STAFF"),task);
        var view=roomView(m,r);assertThat(view.path("cleaningStatus").asText()).isEqualTo("CLEAN");assertThat(view.path("maintenanceStatus").asText()).isEqualTo("OUT_OF_SERVICE");assertThat(view.path("occupied").asBoolean()).isFalse();
        assertThat(login("FRONT_DESK").request("POST","/api/stays",checkInBody(r),key()).statusCode()).isEqualTo(409);ok(resume(m,r,stop,key()));assertThat(checkIn(r).path("id").asLong()).isPositive();
    }
    @Test void occupiedRoomCanReportStopResolveResumeWithoutEndingStay() throws Exception {
        long r=cleanRoom();var stay=checkIn(r);var m=login("HOUSEKEEPING_MANAGER");long issue=issue(login("FRONT_DESK"),r,"CRITICAL"),stop=stop(m,r);ok(change(m,issue,"resolve"));ok(resume(m,r,stop,key()));
        var view=roomView(m,r);assertThat(view.path("occupied").asBoolean()).isTrue();assertThat(view.path("canCheckIn").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select status from stay_records where id=?",String.class,stay.path("id").asLong())).isEqualTo("IN_HOUSE");assertThat(jdbc.queryForObject("select exited_at is null from stay_room_segments where id=?",Boolean.class,stay.path("segmentId").asLong())).isTrue();
    }
    @Test void reportIdempotencyMismatchExpiryAndSnapshotReplay() throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");String key=key();var body=reportBody(r,"CRITICAL");var first=ok(m.request("POST","/api/room-issues",body,key));long id=first.path("issueId").asLong();ok(change(m,id,"resolve"));assertThat(ok(m.request("POST","/api/room-issues",body,key))).isEqualTo(first);
        assertThat(m.request("POST","/api/room-issues",reportBody(r,"LOW"),key).statusCode()).isEqualTo(409);
        jdbc.update("update idempotency_requests set created_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 day' where request_key=cast(? as uuid)",key);assertThat(m.request("POST","/api/room-issues",body,key).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from room_issues",Integer.class)).isEqualTo(1);
    }
    @Test void allIssueActionsAndMaintenanceActionsReplayWithoutDuplicateHistory() throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");long id=issue(m,r,"CRITICAL");
        for(String action:List.of("start","resolve")) { var body=issueAction(id,"修复说明");String key=key();var result=ok(m.request("POST","/api/room-issues/"+id+"/"+action,body,key));assertThat(ok(m.request("POST","/api/room-issues/"+id+"/"+action,body,key))).isEqualTo(result); }
        String key=key();var body=Map.of("reason","测试停用");var result=ok(m.request("POST","/api/rooms/"+r+"/maintenance-stop",body,key));assertThat(ok(m.request("POST","/api/rooms/"+r+"/maintenance-stop",body,key))).isEqualTo(result);
        long stop=result.path("stopId").asLong();String resumeKey=key();var resumed=ok(resume(m,r,stop,resumeKey));assertThat(ok(resume(m,r,stop,resumeKey))).isEqualTo(resumed);
        assertThat(jdbc.queryForObject("select count(*) from room_issue_logs",Integer.class)).isEqualTo(3);assertThat(jdbc.queryForObject("select count(*) from room_maintenance_logs",Integer.class)).isEqualTo(2);
    }

    List<Integer> race(Callable<Integer> first,Callable<Integer> second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try { var a=pool.submit(()->{start.await();return first.call();});var b=pool.submit(()->{start.await();return second.call();});start.countDown();return List.of(a.get(25,TimeUnit.SECONDS),b.get(25,TimeUnit.SECONDS)); }
        finally { pool.shutdownNow(); }
    }
    @Test void concurrentIdenticalReportCreatesOneIssueAndHistory() throws Exception {
        long r=room();String key=key();Browser a=login("FRONT_DESK"),b=login("FRONT_DESK");var body=reportBody(r,"CRITICAL");
        assertThat(race(()->a.request("POST","/api/room-issues",body,key).statusCode(),()->b.request("POST","/api/room-issues",body,key).statusCode())).containsExactlyInAnyOrder(200,200);
        assertThat(jdbc.queryForObject("select count(*) from room_issues",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from room_issue_logs",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentDifferentStopsAndResumesEachHaveOneWinner() throws Exception {
        long r=room();Browser a=login("HOUSEKEEPING_MANAGER"),b=login("HOTEL_MANAGER");String path="/api/rooms/"+r+"/maintenance-stop";var body=Map.of("reason","并发停用测试");
        assertThat(race(()->a.request("POST",path,body,key()).statusCode(),()->b.request("POST",path,body,key()).statusCode())).containsExactlyInAnyOrder(200,409);
        long stop=jdbc.queryForObject("select id from room_maintenance_stops where room_id=?",Long.class,r);
        assertThat(race(()->resume(a,r,stop,key()).statusCode(),()->resume(b,r,stop,key()).statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("select count(*) from room_maintenance_logs",Integer.class)).isEqualTo(2);
    }
    @Test void concurrentResolutionsAndSeverityChangesRespectVersions() throws Exception {
        long r=room();Browser a=login("HOUSEKEEPING_MANAGER"),b=login("HOTEL_MANAGER");long id=issue(a,r,"CRITICAL");var body=issueAction(id,"并发解决");String path="/api/room-issues/"+id+"/resolve";
        assertThat(race(()->a.request("POST",path,body,key()).statusCode(),()->b.request("POST",path,body,key()).statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("select count(*) from room_issue_logs where issue_id=? and action='RESOLVE'",Integer.class,id)).isEqualTo(1);
    }
    @Test void criticalBeforeCheckInRejectsButCheckInBeforeCriticalRetainsStay() throws Exception {
        long r=cleanRoom();Browser m=login("HOUSEKEEPING_MANAGER"),front=login("FRONT_DESK");long id=issue(m,r,"CRITICAL");
        assertThat(front.request("POST","/api/stays",checkInBody(r),key()).statusCode()).isEqualTo(409);ok(change(m,id,"resolve"));var stay=checkIn(r);issue(m,r,"CRITICAL");
        assertThat(jdbc.queryForObject("select status from stay_records where id=?",String.class,stay.path("id").asLong())).isEqualTo("IN_HOUSE");
    }
    @Test void checkInAndCriticalReportCompeteOnSameRoomLockWithoutDeadlock() throws Exception {
        long r=cleanRoom();Browser m=login("HOUSEKEEPING_MANAGER"),front=login("FRONT_DESK");
        var results=race(()->m.request("POST","/api/room-issues",reportBody(r,"CRITICAL"),key()).statusCode(),()->front.request("POST","/api/stays",checkInBody(r),key()).statusCode());
        assertThat(results.get(0)).isEqualTo(200);assertThat(results.get(1)).isIn(200,409);var view=roomView(m,r);assertThat(view.path("hasCriticalIssue").asBoolean()).isTrue();assertThat(view.path("canCheckIn").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from stay_room_segments where room_id=? and exited_at is null",Integer.class,r)).isEqualTo(results.get(1)==200?1:0);
    }
    @Test void committedCriticalWhileCheckInWaitsIsReadAfterLockAcquisition() throws Exception {
        long r=cleanRoom();Browser m=login("HOUSEKEEPING_MANAGER"),front=login("FRONT_DESK");var pool=Executors.newFixedThreadPool(2);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            var reporting=pool.submit(()->tx.execute(status->{jdbc.queryForObject("select id from rooms where id=? for update",Long.class,r);entered.countDown();try { release.await(15,TimeUnit.SECONDS); } catch(InterruptedException e) { throw new RuntimeException(e); }
                return issueService.report(ids.get("HOUSEKEEPING_MANAGER"),key(),new com.stayos.issue.IssueDtos.Report(r,"SAFETY","CRITICAL","虚构并发安全问题","")).issueId; }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();var checking=pool.submit(()->front.request("POST","/api/stays",checkInBody(r),key()).statusCode());
            // Detect a real PostgreSQL lock wait instead of trusting scheduler timing.
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean waiting=false;
            while(System.nanoTime()<until) { if(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%rooms%'",Integer.class)>0) { waiting=true;break; } Thread.sleep(20); }
            assertThat(waiting).isTrue();release.countDown();assertThat(reporting.get(15,TimeUnit.SECONDS)).isPositive();assertThat(checking.get(15,TimeUnit.SECONDS)).isEqualTo(409);
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Autowired com.stayos.issue.IssueService issueService;
    void fault(String table,String event,String predicate) {
        jdbc.execute("create function test_fail_issue_fn() returns trigger language plpgsql as $$ begin if "+predicate+" then raise exception 'Injected phase6 failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_fail_issue before "+event+" on "+table+" for each row execute function test_fail_issue_fn()");
    }
    void clearFault(String table) { jdbc.execute("drop trigger test_fail_issue on "+table);jdbc.execute("drop function test_fail_issue_fn()"); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"room_issues","room_issue_logs","audit_logs","idempotency_requests"})
    void reportFailureRollsBackIssueHistoryAuditAndRequestThenSameKeyRetries(String table) throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");String key=key();var body=reportBody(r,"CRITICAL");
        fault(table,table.equals("idempotency_requests")?"update":"insert",table.equals("audit_logs")?"NEW.action='ISSUE_REPORT'":table.equals("idempotency_requests")?"NEW.operation='ISSUE_REPORT'":"true");
        assertThat(m.request("POST","/api/room-issues",body,key).statusCode()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from room_issues",Integer.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from room_issue_logs",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where operation='ISSUE_REPORT'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='ISSUE_REPORT' and result='SUCCESS'",Integer.class)).isZero();
        clearFault(table);ok(m.request("POST","/api/room-issues",body,key));assertThat(roomView(m,r).path("hasCriticalIssue").asBoolean()).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"room_issues","room_issue_logs","audit_logs","idempotency_requests"})
    void resolveFailureRetainsBlockingAndCanRetryAfterRepair(String table) throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");long id=issue(m,r,"CRITICAL");String key=key();var body=issueAction(id,"修复说明");
        fault(table,table.equals("room_issues") || table.equals("idempotency_requests")?"update":"insert",table.equals("audit_logs")?"NEW.action='ISSUE_RESOLVE'":table.equals("idempotency_requests")?"NEW.operation='ISSUE_RESOLVE'":"true");
        assertThat(m.request("POST","/api/room-issues/"+id+"/resolve",body,key).statusCode()).isEqualTo(500);assertThat(jdbc.queryForObject("select status from room_issues where id=?",String.class,id)).isEqualTo("OPEN");assertThat(jdbc.queryForObject("select count(*) from room_issue_logs",Integer.class)).isEqualTo(1);assertThat(roomView(m,r).path("hasCriticalIssue").asBoolean()).isTrue();
        clearFault(table);ok(m.request("POST","/api/room-issues/"+id+"/resolve",body,key));assertThat(roomView(m,r).path("hasCriticalIssue").asBoolean()).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"room_maintenance_stops","room_maintenance_logs","audit_logs","idempotency_requests"})
    void maintenanceStopFailureRollsBackAllFacts(String table) throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");String key=key();var body=Map.of("reason","回滚停用");
        fault(table,table.equals("idempotency_requests")?"update":"insert",table.equals("audit_logs")?"NEW.action='MAINTENANCE_STOP'":table.equals("idempotency_requests")?"NEW.operation='MAINTENANCE_STOP'":"true");
        assertThat(m.request("POST","/api/rooms/"+r+"/maintenance-stop",body,key).statusCode()).isEqualTo(500);assertThat(jdbc.queryForObject("select count(*) from room_maintenance_stops",Integer.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from room_maintenance_logs",Integer.class)).isZero();
        clearFault(table);ok(m.request("POST","/api/rooms/"+r+"/maintenance-stop",body,key));assertThat(roomView(m,r).path("hasManualMaintenanceStop").asBoolean()).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"room_maintenance_stops","room_maintenance_logs","audit_logs","idempotency_requests"})
    void maintenanceResumeFailureKeepsStopActive(String table) throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");long stop=stop(m,r);String key=key();
        fault(table,table.equals("room_maintenance_stops") || table.equals("idempotency_requests")?"update":"insert",table.equals("audit_logs")?"NEW.action='MAINTENANCE_RESUME'":table.equals("idempotency_requests")?"NEW.operation='MAINTENANCE_RESUME'":"true");
        assertThat(resume(m,r,stop,key).statusCode()).isEqualTo(500);assertThat(jdbc.queryForObject("select resumed_at is null from room_maintenance_stops where id=?",Boolean.class,stop)).isTrue();assertThat(jdbc.queryForObject("select count(*) from room_maintenance_logs",Integer.class)).isEqualTo(1);
        clearFault(table);ok(resume(m,r,stop,key));assertThat(roomView(m,r).path("maintenanceStatus").asText()).isEqualTo("NORMAL");
    }
    @Test void databaseForeignKeysDuplicateStopsAndTransitionsRejectInvalidFacts() throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");long id=issue(m,r,"CRITICAL"),stop=stop(m,r);
        assertThatThrownBy(()->jdbc.update("insert into room_maintenance_stops(room_id,reason,stopped_by,stopped_at) values(?,'重复',?,now())",r,ids.get("HOUSEKEEPING_MANAGER"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update room_issues set status='RESOLVED',version=version+1 where id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update room_maintenance_stops set resumed_at=now() where id=?",stop)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from room_issues where id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("insert into room_issue_logs(issue_id,action,to_status,to_severity,operator_id,operated_at,issue_version) values(99999999,'REPORT','OPEN','LOW',?,now(),0)",ids.get("HOUSEKEEPING_MANAGER"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void resultQueryIsActorScopedAndSurvivesRoomChanges() throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");String key=key();var result=ok(m.request("POST","/api/room-issues",reportBody(r,"LOW"),key));
        assertThat(ok(m.get("/api/room-operations/ISSUE_REPORT/"+key))).isEqualTo(result);
        assertThat(login("HOTEL_MANAGER").get("/api/room-operations/ISSUE_REPORT/"+key).statusCode()).isEqualTo(404);
        assertThat(login("ADMIN").get("/api/room-operations/ISSUE_REPORT/"+key).statusCode()).isEqualTo(403);
        assertThat(m.get("/api/room-operations/ISSUE_REPORT/not-a-key").statusCode()).isEqualTo(400);
        assertThat(m.get("/api/room-operations/OTHER/"+key).statusCode()).isEqualTo(400);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ISSUE_START","ISSUE_RESOLVE","ISSUE_SEVERITY","MAINTENANCE_STOP","MAINTENANCE_RESUME"})
    void everyOperationRetainsTwentyFourHourMismatchAndExpiryRules(String operation) throws Exception {
        long r=room();var m=login("HOUSEKEEPING_MANAGER");long id=issue(m,r,"LOW");String path;Map<String,Object> body;
        if(operation.equals("MAINTENANCE_STOP")) { path="/api/rooms/"+r+"/maintenance-stop";body=Map.of("reason","停用测试"); }
        else if(operation.equals("MAINTENANCE_RESUME")) { long stop=stop(m,r);path="/api/rooms/"+r+"/maintenance-resume";body=Map.of("stopId",stop,"reason","恢复测试"); }
        else { path="/api/room-issues/"+id+"/"+operation.substring(6).toLowerCase(Locale.ROOT);body=operation.equals("ISSUE_SEVERITY")?Map.of("version",0,"reason","升级测试","severity","CRITICAL"):issueAction(id,"处理测试"); }
        String key=key();var original=ok(m.request("POST",path,body,key));assertThat(ok(m.request("POST",path,body,key))).isEqualTo(original);
        var changed=new HashMap<String,Object>(body);changed.put("reason","其他请求内容");assertThat(m.request("POST",path,changed,key).statusCode()).isEqualTo(409);
        jdbc.update("update idempotency_requests set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where request_key=cast(? as uuid)",key);
        assertThat(m.request("POST",path,body,key).statusCode()).isEqualTo(409);
        assertThat(ok(m.get("/api/room-operations/"+operation+"/"+key))).isEqualTo(original);
    }
    @Test void linkedStopRequiresIssueFromSameRoomAndCsrfCannotBeSkipped() throws Exception {
        long a=room(),b=room();var m=login("HOUSEKEEPING_MANAGER");long id=issue(m,a,"LOW");
        assertThat(m.request("POST","/api/rooms/"+b+"/maintenance-stop",Map.of("issueId",id,"reason","测试"),key()).statusCode()).isEqualTo(409);
        var noCsrf=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/room-issues")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(reportBody(a,"LOW")))).build();
        assertThat(m.http.send(noCsrf,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
    @Test void issueBrowserEndToEndUsesIsolatedPostgres() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Enable with -Dstayos.e2e=true");
        var fixtures=new LinkedHashMap<String,Map<String,Object>>();
        for(String name:List.of("report","workflow","standalone","mobile","network","refresh","scope","scopeOther","roomDetail")) {
            long room=room();String number="S6_UI_"+name.toUpperCase(Locale.ROOT);jdbc.update("update rooms set room_number=? where id=?",number,room);
            fixtures.put(name,Map.of("id",room,"number",number));
        }
        long own=issue(login("HOUSEKEEPING_STAFF"),((Number)fixtures.get("scope").get("id")).longValue(),"LOW");
        long other=issue(login("STAFF2"),((Number)fixtures.get("scopeOther").get("id")).longValue(),"LOW");
        var directory=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("frontend").toFile();
        var log=directory.toPath().getParent().resolve(".local/issues-browser-run.log");java.nio.file.Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","issues.spec.ts").directory(directory).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_ISSUE_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_ISSUE_SCOPE",own+","+other);
        builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-issues-results.json");
        var process=builder.start();if(!process.waitFor(420,TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Issue browser tests timed out"); }
        assertThat(process.exitValue()).as("Playwright details: "+log).isZero();
    }
}
