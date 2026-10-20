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
class HousekeepingIntegrationTest {
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
            long id=jdbc.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,"hk_test_"+role.toLowerCase(Locale.ROOT),passwords.encode(password),"清洁测试"+role);
            ids.put(role,id);users.addRole(id,role.equals("STAFF2")?"HOUSEKEEPING_STAFF":role);
        }
        type=jdbc.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('S5_TYPE','虚构清洁测试房型',2) returning id",Long.class);
    }
    @BeforeEach void clean() {
        for(String table:List.of("housekeeping_tasks","rooms","housekeeping_task_logs","audit_logs","idempotency_requests","housekeeping_assignment_items","housekeeping_assignment_batches")) jdbc.execute("drop trigger if exists test_fail_hk on "+table);
        jdbc.execute("drop function if exists test_fail_hk_fn()");
        jdbc.update("update users set enabled=true where username like 'hk_test_%'");
        var guests=jdbc.queryForList("select guest_id from stay_guests where stay_record_id in(select id from stay_records where created_by in(select id from users where username like 'hk_test_%'))",Long.class);
        jdbc.update("delete from idempotency_requests where operator_id in(select id from users where username like 'hk_test_%')");
        jdbc.update("delete from housekeeping_assignment_items where batch_id in(select id from housekeeping_assignment_batches where operator_id in(select id from users where username like 'hk_test_%'))");
        jdbc.update("delete from housekeeping_assignment_batches where operator_id in(select id from users where username like 'hk_test_%')");
        jdbc.update("delete from housekeeping_task_logs where task_id in(select id from housekeeping_tasks where created_by in(select id from users where username like 'hk_test_%'))");
        jdbc.update("delete from housekeeping_tasks where created_by in(select id from users where username like 'hk_test_%')");
        jdbc.update("delete from stay_guests where stay_record_id in(select id from stay_records where created_by in(select id from users where username like 'hk_test_%'))");
        jdbc.update("delete from stay_room_segments where room_id in(select id from rooms where room_number like 'S5_%')");
        jdbc.update("delete from stay_records where created_by in(select id from users where username like 'hk_test_%')");
        for(long guest:guests) jdbc.update("delete from guests where id=?",guest);
        jdbc.update("delete from rooms where room_number like 'S5_%'");
        jdbc.update("delete from audit_logs where operator_id in(select id from users where username like 'hk_test_%')");
        StayTestFacts.restore(jdbc);
    }
    @AfterAll void cleanup() {
        clean();jdbc.update("delete from room_types where id=?",type);
        for(long id:ids.values()) { jdbc.update("delete from user_roles where user_id=?",id);jdbc.update("delete from users where id=?",id); }
    }
    String key() { return UUID.randomUUID().toString(); }
    long room() {
        return jdbc.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,5,?,'DIRTY') returning id",Long.class,"S5_"+key().substring(0,8).toUpperCase(Locale.ROOT),type);
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
        var b=new Browser();b.role=role;assertThat(b.request("POST","/api/auth/login",Map.of("username","hk_test_"+role.toLowerCase(Locale.ROOT),"password",password),null).statusCode()).isEqualTo(200);ok(b.get("/api/auth/csrf"));return b;
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
    @Test void manualCreationHasRealRoomCreatorReasonAndNoInventedStay() throws Exception {
        long room=room();var manager=login("HOUSEKEEPING_MANAGER");String key=key();var body=Map.of("roomId",room,"reason","初始化清洁");
        var a=ok(manager.request("POST","/api/housekeeping-tasks/manual",body,key));var b=ok(manager.request("POST","/api/housekeeping-tasks/manual",body,key));assertThat(a).isEqualTo(b);
        var row=jdbc.queryForMap("select * from housekeeping_tasks where id=?",a.path("taskId").asLong());
        assertThat(row).containsEntry("source","MANUAL").containsEntry("task_type","MANUAL_CLEANING").containsEntry("stay_record_id",null).containsEntry("source_id",null).containsEntry("room_segment_id",null).containsEntry("created_by",ids.get("HOUSEKEEPING_MANAGER")).containsEntry("notes","初始化清洁");
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs",Integer.class)).isEqualTo(1);
        assertThat(manager.request("POST","/api/housekeeping-tasks/manual",body,key()).statusCode()).isEqualTo(409);
        assertThat(manager.request("POST","/api/housekeeping-tasks/manual",Map.of("roomId",room,"reason","另一原因"),key).statusCode()).isEqualTo(409);
    }
    @Test void manualRejectsMissingRoomCleanRoomAndUnauthorizedRoles() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");long room=room();
        assertThat(manager.request("POST","/api/housekeeping-tasks/manual",Map.of("roomId",999999999,"reason","测试"),key()).statusCode()).isEqualTo(404);
        assertThat(manager.request("POST","/api/housekeeping-tasks/manual",Map.of("roomId",room,"reason"," "),key()).statusCode()).isEqualTo(400);
        for(String role:List.of("HOUSEKEEPING_STAFF","FRONT_DESK","ADMIN")) assertThat(login(role).request("POST","/api/housekeeping-tasks/manual",Map.of("roomId",room,"reason","测试"),key()).statusCode()).isEqualTo(403);
        long task=manual(manager,room);finish(login("HOUSEKEEPING_STAFF"),task);
        assertThat(manager.request("POST","/api/housekeeping-tasks/manual",Map.of("roomId",room,"reason","已清洁不应建单"),key()).statusCode()).isEqualTo(409);
    }
    @Test void allRolesCannotClaimAndStaffCannotSeeUnassignedTasks() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");long task=manual(manager,room());
        for(String role:List.of("ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF","STAFF2"))
            assertThat(act(login(role),task,"claim").statusCode()).isEqualTo(403);
        for(String role:List.of("HOUSEKEEPING_STAFF","STAFF2")) {
            var employee=login(role);
            assertThat(employee.get("/api/housekeeping-tasks/"+task).statusCode()).isEqualTo(404);
            assertThat(employee.get("/api/housekeeping-tasks/"+task+"/history").statusCode()).isEqualTo(404);
            assertThat(ok(employee.get("/api/housekeeping-tasks?scope=ALL")).path("total").asInt()).isZero();
        }
        var a=login("HOUSEKEEPING_STAFF");give(a,task);state(task,"PENDING","DIRTY");
        assertThat(ok(a.get("/api/housekeeping-tasks?scope=MINE")).path("total").asInt()).isEqualTo(1);
        assertThat(login("STAFF2").get("/api/housekeeping-tasks?assigneeId="+ids.get("HOUSEKEEPING_STAFF")).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select claimed_at is null and assigned_by<>assigned_to from housekeeping_tasks where id=?",Boolean.class,task)).isTrue();
    }
    @Test void simultaneousManagerAssignmentsOnlyOneWins() throws Exception {
        long task=manual(login("HOUSEKEEPING_MANAGER"),room());var a=login("HOUSEKEEPING_MANAGER");var b=login("HOTEL_MANAGER");long v=version(task);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var fa=pool.submit(()->{gate.await();return a.request("POST","/api/housekeeping-tasks/"+task+"/assign",Map.of("version",v,"assigneeId",ids.get("HOUSEKEEPING_STAFF")),key()).statusCode();});
            var fb=pool.submit(()->{gate.await();return b.request("POST","/api/housekeeping-tasks/"+task+"/assign",Map.of("version",v,"assigneeId",ids.get("STAFF2")),key()).statusCode();});gate.countDown();
            assertThat(List.of(fa.get(20,TimeUnit.SECONDS),fb.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where action='ASSIGN'",Integer.class)).isEqualTo(1);
    }
    @Test void managerAssignsAndReassignsPendingWithReasonAndEligibleEmployee() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");long task=manual(manager,room());
        assertThat(assign(login("HOUSEKEEPING_STAFF"),task,"assign","STAFF2","").statusCode()).isEqualTo(403);
        for(String role:List.of("ADMIN","FRONT_DESK")) assertThat(assign(manager,task,"assign",role,"").statusCode()).isEqualTo(409);
        jdbc.update("update users set enabled=false where id=?",ids.get("STAFF2"));assertThat(assign(manager,task,"assign","STAFF2","").statusCode()).isEqualTo(409);
        ok(assign(manager,task,"assign","HOUSEKEEPING_STAFF","主管分配"));state(task,"PENDING","DIRTY");
        assertThat(assign(manager,task,"reassign","HOUSEKEEPING_MANAGER"," ").statusCode()).isEqualTo(400);
        ok(assign(manager,task,"reassign","HOUSEKEEPING_MANAGER","原员工请假"));
        assertThat(jdbc.queryForObject("select assigned_to from housekeeping_tasks where id=?",Long.class,task)).isEqualTo(ids.get("HOUSEKEEPING_MANAGER"));
        var roster=ok(manager.get("/api/housekeeping-tasks/employees"));assertThat(roster.findValues("id").stream().map(JsonNode::asLong).toList()).doesNotContain(ids.get("STAFF2"));
    }
    @Test void startCompleteAndHistoryUseServerTimeAndRoomBecomesAvailable() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long room=room(),task=manual(manager,room);give(staff,task);
        assertThat(act(manager,task,"start").statusCode()).isEqualTo(403);
        assertThat(act(staff,task,"complete").statusCode()).isEqualTo(409);
        var before=Instant.now().minusSeconds(1);ok(act(staff,task,"start"));state(task,"IN_PROGRESS","CLEANING");
        assertThat(jdbc.queryForObject("select has_open_cleaning_task from room_status where id=?",Boolean.class,room)).isTrue();
        assertThat(act(staff,task,"start").statusCode()).isEqualTo(409);
        var done=ok(staff.request("POST","/api/housekeeping-tasks/"+task+"/complete",Map.of("version",version(task),"reason","清洁已完成"),key()));state(task,"COMPLETED","CLEAN");
        assertThat(OffsetDateTime.parse(done.path("operatedAt").asText()).toInstant()).isBetween(before,Instant.now().plusSeconds(1));
        assertThat(act(staff,task,"complete").statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForMap("select occupied,has_open_cleaning_task,can_check_in from room_status where id=?",room)).containsEntry("occupied",false).containsEntry("has_open_cleaning_task",false).containsEntry("can_check_in",true);
        assertThat(jdbc.queryForObject("select started_by=completed_by and completed_by=assigned_to and completed_at>=started_at and completion_notes='清洁已完成' from housekeeping_tasks where id=?",Boolean.class,task)).isTrue();
        var history=ok(staff.get("/api/housekeeping-tasks/"+task+"/history"));assertThat(history.size()).isEqualTo(4);
        assertThat(history.findValuesAsText("action")).containsExactly("MANUAL_CREATE","ASSIGN","START","COMPLETE");
    }
    @Test void inProgressTakeoverPreservesStartTimeAndFormerOwnerLosesRights() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var old=login("HOUSEKEEPING_STAFF");var next=login("STAFF2");long task=manual(manager,room());give(old,task);ok(act(old,task,"start"));
        var start=jdbc.queryForObject("select started_at from housekeeping_tasks where id=?",OffsetDateTime.class,task);
        jdbc.update("update users set enabled=false where id=?",ids.get("HOUSEKEEPING_STAFF"));assertThat(act(old,task,"complete").statusCode()).isEqualTo(401);
        ok(assign(manager,task,"reassign","STAFF2","原员工无法继续"));state(task,"IN_PROGRESS","CLEANING");
        assertThat(jdbc.queryForObject("select started_at from housekeeping_tasks where id=?",OffsetDateTime.class,task)).isEqualTo(start);
        jdbc.update("update users set enabled=true where id=?",ids.get("HOUSEKEEPING_STAFF"));assertThat(act(login("HOUSEKEEPING_STAFF"),task,"complete").statusCode()).isEqualTo(404);
        ok(act(next,task,"complete"));state(task,"COMPLETED","CLEAN");
        assertThat(jdbc.queryForMap("select started_by,completed_by from housekeeping_tasks where id=?",task)).containsEntry("started_by",ids.get("HOUSEKEEPING_STAFF")).containsEntry("completed_by",ids.get("STAFF2"));
    }
    @Test void hotelManagerCanExecuteWholeWorkflowButHasNoAdminPermission() throws Exception {
        var manager=login("HOTEL_MANAGER");long task=manual(manager,room());finish(manager,task);
        assertThat(manager.get("/api/users").statusCode()).isEqualTo(403);assertThat(manager.get("/api/stays").statusCode()).isEqualTo(200);
    }
    @Test void adminFrontAnonymousCannotExecuteAndNoArbitraryRoomStateEndpoint() throws Exception {
        long task=manual(login("HOUSEKEEPING_MANAGER"),room());
        for(String role:List.of("ADMIN","FRONT_DESK")) { var b=login(role);assertThat(act(b,task,"claim").statusCode()).isEqualTo(403);assertThat(b.get("/api/housekeeping-tasks/"+task).statusCode()).isEqualTo(403); }
        assertThat(act(new Browser(),task,"claim").statusCode()).isEqualTo(401);
        assertThat(login("HOUSEKEEPING_STAFF").request("PUT","/api/rooms/1/cleaning-status",Map.of("status","CLEAN"),null).statusCode()).isIn(403,404);
    }
    @Test void sameKeyReplayMismatchExpiryAndConcurrentDuplicateProduceOneEvent() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long task=manual(manager,room());give(staff,task);ok(act(staff,task,"start"));
        String key=key();var body=actionBody(task);var a=ok(staff.request("POST","/api/housekeeping-tasks/"+task+"/complete",body,key));
        assertThat(ok(staff.request("POST","/api/housekeeping-tasks/"+task+"/complete",body,key))).isEqualTo(a);
        assertThat(staff.request("POST","/api/housekeeping-tasks/"+task+"/complete",Map.of("version",2,"reason","不同内容"),key).statusCode()).isEqualTo(409);
        jdbc.update("update idempotency_requests set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where operation='HK_COMPLETE'");
        assertThat(staff.request("POST","/api/housekeeping-tasks/"+task+"/complete",body,key).body()).contains("IDEMPOTENCY_KEY_EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where action='COMPLETE'",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentSameKeyCompletionReturnsOneReceipt() throws Exception {
        long task=manual(login("HOUSEKEEPING_MANAGER"),room());var staff=login("HOUSEKEEPING_STAFF");give(staff,task);ok(act(staff,task,"start"));String key=key();var gate=new CountDownLatch(1);
        var input=new com.stayos.housekeeping.dto.TaskRequests.Action(version(task),null,"");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return workflow.act(ids.get("HOUSEKEEPING_STAFF"),key,task,"COMPLETE",input);});var b=pool.submit(()->{gate.await();return workflow.act(ids.get("HOUSEKEEPING_STAFF"),key,task,"COMPLETE",input);});gate.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS).id).isEqualTo(b.get(20,TimeUnit.SECONDS).id);
        }
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where action='COMPLETE'",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentCompleteAndTakeoverProduceConsistentSingleWinner() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long task=manual(manager,room());give(staff,task);ok(act(staff,task,"start"));long v=version(task);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return staff.request("POST","/api/housekeeping-tasks/"+task+"/complete",Map.of("version",v),key()).statusCode();});
            var b=pool.submit(()->{gate.await();return manager.request("POST","/api/housekeeping-tasks/"+task+"/reassign",Map.of("version",v,"assigneeId",ids.get("STAFF2"),"reason","接管测试"),key()).statusCode();});gate.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).contains(200).containsAnyOf(404,409).doesNotContainSequence(200,200);
        }
        var row=jdbc.queryForMap("select status,assigned_to from housekeeping_tasks where id=?",task);
        if(row.get("status").equals("COMPLETED")) {state(task,"COMPLETED","CLEAN");assertThat(row.get("assigned_to")).isEqualTo(ids.get("HOUSEKEEPING_STAFF"));}
        else {state(task,"IN_PROGRESS","CLEANING");assertThat(row.get("assigned_to")).isEqualTo(ids.get("STAFF2"));}
    }
    @Test void staleVersionCannotReassignAgainOrChangeOwner() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");long task=manual(manager,room());var stale=Map.of("version",0,"assigneeId",ids.get("STAFF2"));
        ok(assign(manager,task,"assign","HOUSEKEEPING_STAFF",""));
        assertThat(manager.request("POST","/api/housekeeping-tasks/"+task+"/assign",stale,key()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select assigned_to from housekeeping_tasks where id=?",Long.class,task)).isEqualTo(ids.get("HOUSEKEEPING_STAFF"));
    }
    void fault(String table,String event,String condition) {
        jdbc.execute("create function test_fail_hk_fn() returns trigger language plpgsql as $$ begin if "+condition+" then raise exception 'injected housekeeping failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_fail_hk before "+event+" on "+table+" for each row execute function test_fail_hk_fn()");
    }
    void rollbackComplete(String table,String event,String condition) throws Exception {
        var staff=login("HOUSEKEEPING_STAFF");long task=manual(login("HOUSEKEEPING_MANAGER"),room());give(staff,task);ok(act(staff,task,"start"));long actor=ids.get("HOUSEKEEPING_STAFF");String key=key();var input=new com.stayos.housekeeping.dto.TaskRequests.Action(version(task),null,"完成备注");
        fault(table,event,condition);assertThatThrownBy(()->workflow.act(actor,key,task,"COMPLETE",input)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        state(task,"IN_PROGRESS","CLEANING");assertThat(version(task)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where action='COMPLETE'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='HK_COMPLETE' and result='SUCCESS'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where operation='HK_COMPLETE'",Integer.class)).isZero();
        jdbc.execute("drop trigger test_fail_hk on "+table);jdbc.execute("drop function test_fail_hk_fn()");workflow.act(actor,key,task,"COMPLETE",input);state(task,"COMPLETED","CLEAN");
    }
    @Test void taskUpdateFailureRollsBackRoomHistoryAuditAndRequest() throws Exception {rollbackComplete("housekeeping_tasks","update","NEW.status='COMPLETED'");}
    @Test void roomUpdateFailureCannotCompleteTask() throws Exception {rollbackComplete("rooms","update","NEW.cleaning_status='CLEAN'");}
    @Test void historyFailureRollsBackCompleteAndRetrySucceeds() throws Exception {rollbackComplete("housekeeping_task_logs","insert","NEW.action='COMPLETE'");}
    @Test void auditFailureRollsBackCompleteAndRetrySucceeds() throws Exception {rollbackComplete("audit_logs","insert","NEW.action='HK_COMPLETE'");}
    @Test void idempotencyResultFailureRollsBackEntireOperation() throws Exception {rollbackComplete("idempotency_requests","update","NEW.operation='HK_COMPLETE'");}
    @Test void databaseRejectsSecondPendingOrInProgressTaskWithoutFakeReferences() throws Exception {
        long room=room(),task=manual(login("HOUSEKEEPING_MANAGER"),room()); // first task on another valid room
        manual(login("HOUSEKEEPING_MANAGER"),room);
        assertThatThrownBy(()->jdbc.update("insert into housekeeping_tasks(room_id,task_type,source,created_by,notes) values(?,'MANUAL_CLEANING','MANUAL',?,'重复任务')",room,ids.get("HOUSEKEEPING_MANAGER"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var staff=login("HOUSEKEEPING_STAFF");give(staff,task);ok(act(staff,task,"start"));
        long busyRoom=jdbc.queryForObject("select room_id from housekeeping_tasks where id=?",Long.class,task);
        assertThatThrownBy(()->jdbc.update("insert into housekeeping_tasks(room_id,task_type,source,created_by,notes) values(?,'MANUAL_CLEANING','MANUAL',?,'清洁中重复任务')",busyRoom,ids.get("HOUSEKEEPING_MANAGER"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("insert into housekeeping_tasks(room_id,task_type,source,created_by,notes,stay_record_id) values(?,'MANUAL_CLEANING','MANUAL',?,'虚假来源',-1)",room(),ids.get("HOUSEKEEPING_MANAGER"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void completionPreservesRepairAndDisabledFlagsAndDoesNotChangeOccupancy() throws Exception {
        long room=room();var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long task=manual(manager,room);
        // Test-only issue fact: no production repair or direct status setter is introduced.
        jdbc.execute("create or replace view room_operational_facts as select r.id room_id,exists(select 1 from stay_room_segments s where s.room_id=r.id and s.exited_at is null) occupied,true has_blocking_issue,exists(select 1 from housekeeping_tasks t where t.room_id=r.id and t.status in ('PENDING','IN_PROGRESS')) has_open_cleaning_task,exists(select 1 from stay_room_segments s where s.room_id=r.id) has_usage_history from rooms r");
        jdbc.update("update rooms set enabled=false where id=?",room);finish(staff,task);
        assertThat(jdbc.queryForMap("select enabled,occupied,cleaning_status,maintenance_status,can_check_in from room_status where id=?",room)).containsEntry("enabled",false).containsEntry("occupied",false).containsEntry("cleaning_status","CLEAN").containsEntry("maintenance_status","OUT_OF_SERVICE").containsEntry("can_check_in",false);
    }
    @Test void completeBusinessCycleUsesOnlyPublicBusinessOperations() throws Exception {
        long room=room();var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");var front=login("FRONT_DESK");
        long initial=manual(manager,room);finish(staff,initial);var first=checkIn(room);
        assertThat(manager.request("POST","/api/housekeeping-tasks/manual",Map.of("roomId",room,"reason","占用不能建单"),key()).statusCode()).isEqualTo(409);
        var out=ok(front.out(first.path("id").asLong(),first.path("segmentId").asLong(),key()));long second=out.path("taskId").asLong();
        assertThat(second).isNotEqualTo(initial);finish(staff,second);var next=checkIn(room);
        assertThat(next.path("id").asLong()).isNotEqualTo(first.path("id").asLong());
        assertThat(act(staff,initial,"complete").statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select occupied from room_status where id=?",Boolean.class,room)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks where room_id=? and status='COMPLETED'",Integer.class,room)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where task_id in(?,?)",Integer.class,initial,second)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from stay_records",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from stay_records where status='IN_HOUSE'",Integer.class)).isEqualTo(1);
        assertThat(ok(manager.get("/api/housekeeping-tasks/"+second+"/history")).get(0).path("action").asText()).isEqualTo("AUTO_CREATE");
    }
    @Test void statisticsFiltersPrivacyAndValidationAreServerEnforced() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long done=manual(manager,room()),pending=manual(manager,room());finish(staff,done);give(staff,pending);
        var stats=ok(manager.get("/api/housekeeping-tasks/statistics"));assertThat(stats.path("pending").asInt()).isEqualTo(1);assertThat(stats.path("completedToday").asInt()).isEqualTo(1);assertThat(stats.path("unassigned").asInt()).isZero();
        assertThat(ok(staff.get("/api/housekeeping-tasks/statistics")).path("mine").asInt()).isEqualTo(1);
        assertThat(ok(manager.get("/api/housekeeping-tasks?status=COMPLETED&floor=5&sort=OLDEST")).path("total").asInt()).isEqualTo(1);
        assertThat(ok(manager.get("/api/housekeeping-tasks?scope=UNASSIGNED")).path("total").asInt()).isZero();
        for(String q:List.of("status=INVALID","scope=OTHER","sort=BAD","page=0","floor=0","assigneeId=-1")) assertThat(manager.get("/api/housekeeping-tasks?"+q).statusCode()).isEqualTo(400);
        assertThat(ok(staff.get("/api/housekeeping-tasks/"+done)).toString()).doesNotContain("document","phone","guest","ciphertext");
        assertThat(manager.get("/api/housekeeping-tasks/employees").body()).doesNotContain("password","securityVersion","lockedUntil");
        assertThat(staff.get("/api/housekeeping-tasks/employees").statusCode()).isEqualTo(403);
    }

    @Test void housekeepingBrowserEndToEndUsesRealPostgres() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Enable with scripts/test.ps1 -Browser");
        var fixtures=new LinkedHashMap<String,Map<String,Object>>();var manager=login("HOUSEKEEPING_MANAGER");
        for(String name:List.of("manual","cycle","claim","takeover","network","refresh","mobile","scope","scopeOther","manager","malformed","assigned")) {
            long room=room();var fixture=new LinkedHashMap<String,Object>();fixture.put("id",room);fixture.put("number",jdbc.queryForObject("select room_number from rooms where id=?",String.class,room));
            if(!List.of("manual","cycle").contains(name)) {
                long task=manual(manager,room);fixture.put("taskId",task);
                if(List.of("claim","network","refresh","mobile","malformed").contains(name)) ok(assign(manager,task,"assign","HOUSEKEEPING_STAFF","浏览器测试预分配"));
                if(name.equals("manager")) ok(assign(manager,task,"assign","HOTEL_MANAGER","浏览器测试预分配"));
            }
            fixtures.put(name,fixture);
        }
        int batchNumber=0;
        for(String name:List.of("batchA","batchB","batchExisting","batchNetwork","batchRefresh","batchMobileA","batchMobileB","batchConflictA","batchConflictB")) {
            long room=room();var fixture=new LinkedHashMap<String,Object>();fixture.put("id",room);fixture.put("number",jdbc.queryForObject("select room_number from rooms where id=?",String.class,room));
            String number="S5_BATCH"+String.format("%02d",++batchNumber);jdbc.update("update rooms set room_number=? where id=?",number,room);fixture.put("number",number);
            if(name.equals("batchExisting")) fixture.put("taskId",manual(manager,room));
            fixtures.put(name,fixture);
        }
        var directory=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("frontend").toFile();
        var log=directory.toPath().getParent().resolve(".local/housekeeping-browser-run.log");java.nio.file.Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","housekeeping.spec.ts","assignment.spec.ts").directory(directory).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_HK_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_HK_EMPLOYEES",json.writeValueAsString(ids));builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-housekeeping-results.json");
        var process=builder.start();
        if(!process.waitFor(420,TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Housekeeping browsers timed out"); }
        assertThat(process.exitValue()).as("Playwright details: "+log).isZero();
        long cycle=((Number)fixtures.get("cycle").get("id")).longValue();
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks where room_id=? and status='COMPLETED'",Integer.class,cycle)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from stay_records where status='IN_HOUSE'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where task_id in(select id from housekeeping_tasks where room_id=?)",Integer.class,cycle)).isEqualTo(8);
    }

    @Test void concurrentManualCreationIsUniqueAndDuplicateAssignmentStartRequestsReplay() throws Exception {
        long room=room();var manager=login("HOUSEKEEPING_MANAGER");var other=login("HOTEL_MANAGER");var body=Map.of("roomId",room,"reason","并发建单测试");var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return manager.request("POST","/api/housekeeping-tasks/manual",body,key()).statusCode();});
            var b=pool.submit(()->{gate.await();return other.request("POST","/api/housekeeping-tasks/manual",body,key()).statusCode();});gate.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        long task=jdbc.queryForObject("select id from housekeeping_tasks where room_id=?",Long.class,room);var staff=login("HOUSEKEEPING_STAFF");
        for(String action:List.of("assign","start")) {
            String requestKey=key();var input=new LinkedHashMap<String,Object>(actionBody(task));
            Browser actor=action.equals("assign")?manager:staff;
            if(action.equals("assign")) input.put("assigneeId",ids.get("HOUSEKEEPING_STAFF"));
            var first=ok(actor.request("POST","/api/housekeeping-tasks/"+task+"/"+action,input,requestKey));
            assertThat(ok(actor.request("POST","/api/housekeeping-tasks/"+task+"/"+action,input,requestKey))).isEqualTo(first);
        }
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs",Integer.class)).isEqualTo(3);
    }
    @Test void manualAndAssignmentHistoryFailuresRollBackWholeOperation() throws Exception {
        long room=room(),actor=ids.get("HOUSEKEEPING_MANAGER");String key=key();
        var input=new com.stayos.housekeeping.dto.TaskRequests.Manual(room,"回滚测试");
        fault("housekeeping_task_logs","insert","NEW.action='MANUAL_CREATE'");
        assertThatThrownBy(()->workflow.manual(actor,key,input)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where operation='HK_MANUAL_CREATE'",Integer.class)).isZero();
        jdbc.execute("drop trigger test_fail_hk on housekeeping_task_logs");jdbc.execute("drop function test_fail_hk_fn()");
        long task=workflow.manual(actor,key,input).taskId;
        fault("housekeeping_task_logs","insert","NEW.action='ASSIGN'");
        var assignment=new com.stayos.housekeeping.dto.TaskRequests.Action(0L,ids.get("HOUSEKEEPING_STAFF"),"分配测试");String assignKey=key();
        assertThatThrownBy(()->workflow.act(actor,assignKey,task,"ASSIGN",assignment)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select assigned_to is null and version=0 from housekeeping_tasks where id=?",Boolean.class,task)).isTrue();
        jdbc.execute("drop trigger test_fail_hk on housekeeping_task_logs");jdbc.execute("drop function test_fail_hk_fn()");workflow.act(actor,assignKey,task,"ASSIGN",assignment);
        assertThat(version(task)).isEqualTo(1);
    }
    @Test void invalidWorkflowAndSourceRowsAreRejectedByDatabase() throws Exception {
        long room=room(),actor=ids.get("HOUSEKEEPING_MANAGER");
        assertThatThrownBy(()->jdbc.update("insert into housekeeping_tasks(room_id,task_type,source,created_by,notes) values(?,'MANUAL_CLEANING','MANUAL',?,' ')",room,actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("insert into housekeeping_tasks(room_id,task_type,source,created_by,notes) values(?,'CHECKOUT_CLEANING','CHECKOUT',?,'缺少真实来源')",room,actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("insert into housekeeping_tasks(room_id,task_type,source,created_by,notes,status) values(?,'MANUAL_CLEANING','MANUAL',?,'非法执行信息','IN_PROGRESS')",room,actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        long task=manual(login("HOUSEKEEPING_MANAGER"),room);
        assertThatThrownBy(()->jdbc.update("update housekeeping_tasks set assigned_to=? where id=?",actor,task)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void disabledAndRoleRevokedOwnersRemainFilterableForTakeoverButNotAssignable() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long task=manual(manager,room());give(staff,task);
        jdbc.update("update users set enabled=false where id=?",ids.get("HOUSEKEEPING_STAFF"));
        assertThat(ok(manager.get("/api/housekeeping-tasks/assignees")).findValues("id").stream().map(JsonNode::asLong).toList()).contains(ids.get("HOUSEKEEPING_STAFF"));
        assertThat(ok(manager.get("/api/housekeeping-tasks/employees")).findValues("id").stream().map(JsonNode::asLong).toList()).doesNotContain(ids.get("HOUSEKEEPING_STAFF"));
        assertThat(ok(manager.get("/api/housekeeping-tasks?assigneeId="+ids.get("HOUSEKEEPING_STAFF"))).path("total").asInt()).isEqualTo(1);
        jdbc.update("delete from user_roles where user_id=?",ids.get("STAFF2"));users.addRole(ids.get("STAFF2"),"FRONT_DESK");
        try { assertThat(assign(manager,task,"reassign","STAFF2","员工角色已经改变").statusCode()).isEqualTo(409); }
        finally { jdbc.update("delete from user_roles where user_id=?",ids.get("STAFF2"));users.addRole(ids.get("STAFF2"),"HOUSEKEEPING_STAFF"); }
        ok(assign(manager,task,"reassign","HOUSEKEEPING_MANAGER","原员工禁用"));
    }

    Map<String,Object> batchBody(String target,List<Long> rooms) { return Map.of("assigneeId",ids.get(target),"roomIds",rooms,"reason","虚构批量清洁安排"); }
    HttpResponse<String> batch(Browser manager,String target,List<Long> rooms,String key) throws Exception { return manager.request("POST","/api/housekeeping-tasks/batch-assign",batchBody(target,rooms),key); }
    @Test void batchMixesExistingCheckoutAndNewManualWithoutInventedReferences() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long existingRoom=room(),manualRoom=room();
        finish(staff,manual(manager,existingRoom));var stay=checkIn(existingRoom);var out=ok(login("FRONT_DESK").out(stay.path("id").asLong(),stay.path("segmentId").asLong(),key()));long task=out.path("taskId").asLong();
        var before=jdbc.queryForMap("select source,source_id,stay_record_id,room_segment_id,room_id from housekeeping_tasks where id=?",task);
        var result=ok(batch(manager,"HOUSEKEEPING_STAFF",List.of(manualRoom,existingRoom),key()));assertThat(result.path("items").size()).isEqualTo(2);
        assertThat(jdbc.queryForMap("select source,source_id,stay_record_id,room_segment_id,room_id from housekeeping_tasks where id=?",task)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks where room_id=? and status='PENDING'",Integer.class,existingRoom)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select source,stay_record_id,room_segment_id,source_id,assigned_to from housekeeping_tasks where room_id=?",manualRoom)).containsEntry("source","MANUAL").containsEntry("stay_record_id",null).containsEntry("room_segment_id",null).containsEntry("source_id",null).containsEntry("assigned_to",ids.get("HOUSEKEEPING_STAFF"));
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_assignment_items where batch_id=? and creation_log_id is not null",Integer.class,result.path("id").asLong())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where task_id=? and action='ASSIGN'",Integer.class,task)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cleaning_status from rooms where id=?",String.class,manualRoom)).isEqualTo("DIRTY");
    }
    @Test void batchPermissionsOnlyTwoManagersAndOldSingleAssignmentAlsoRejectsAdmin() throws Exception {
        long room=room(),task=manual(login("HOUSEKEEPING_MANAGER"),room);
        for(String role:List.of("ADMIN","FRONT_DESK","HOUSEKEEPING_STAFF")) {
            var b=login(role);
            assertThat(batch(b,"HOUSEKEEPING_STAFF",List.of(room),key()).statusCode()).isEqualTo(403);
            assertThat(assign(b,task,"assign","STAFF2","").statusCode()).isEqualTo(403);
            assertThat(assign(b,task,"reassign","STAFF2","不得改派").statusCode()).isEqualTo(403);
            for(String path:List.of("/assignment-employees","/assignment-rooms","/assignment-employees/"+ids.get("STAFF2")+"/tasks","/assignment-batches/1","/assignment-results/"+key()))
                assertThat(b.get("/api/housekeeping-tasks"+path).statusCode()).isEqualTo(403);
        }
        assertThat(batch(new Browser(),"HOUSEKEEPING_STAFF",List.of(room),key()).statusCode()).isEqualTo(401);
        for(String role:List.of("HOTEL_MANAGER","HOUSEKEEPING_MANAGER")) {
            var b=login(role);ok(batch(b,"HOUSEKEEPING_STAFF",List.of(room()),key()));
            assertThat(b.get("/api/users").statusCode()).isEqualTo(403);
        }
        var admin=login("ADMIN");assertThat(admin.get("/api/users").statusCode()).isEqualTo(200);assertThat(admin.get("/api/stays").statusCode()).isEqualTo(403);
    }
    @Test void batchInputValidationRejectsEmptyDuplicateInvalidAndOverLimit() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");long room=room();
        for(Object rooms:List.of(List.of(),List.of(room,room),List.of(-1L),java.util.stream.LongStream.rangeClosed(1,101).boxed().toList(),Arrays.asList(room,null))) {
            assertThat(manager.request("POST","/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",ids.get("HOUSEKEEPING_STAFF"),"roomIds",rooms),key()).statusCode()).isEqualTo(400);
        }
        assertThat(manager.request("POST","/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",-1,"roomIds",List.of(room)),key()).statusCode()).isEqualTo(400);
        assertThat(batch(manager,"HOUSEKEEPING_STAFF",List.of(room),null).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_assignment_batches",Integer.class)).isZero();
    }
    @Test void batchInvalidRoomOrEmployeeRollsBackEarlierRoomsAndReservation() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");long room=room();
        assertThat(batch(manager,"HOUSEKEEPING_STAFF",List.of(room,999999999L),key()).statusCode()).isEqualTo(409);
        for(String target:List.of("ADMIN","FRONT_DESK")) assertThat(batch(manager,target,List.of(room),key()).statusCode()).isEqualTo(409);
        jdbc.update("update users set enabled=false where id=?",ids.get("STAFF2"));assertThat(batch(manager,"STAFF2",List.of(room),key()).statusCode()).isEqualTo(409);
        assertThat(manager.request("POST","/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",999999999L,"roomIds",List.of(room)),key()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where operation='HK_BATCH_ASSIGN'",Integer.class)).isZero();
    }
    @Test void batchDoesNotStealAssignedOrInProgressTasksAndAllRoomsRollBack() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long first=room(),second=room(),task=manual(manager,second);give(staff,task);
        for(boolean started:List.of(false,true)) {
            if(started) ok(act(staff,task,"start"));
            assertThat(batch(manager,"STAFF2",List.of(first,second),key()).statusCode()).isEqualTo(409);
            assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks where room_id=?",Integer.class,first)).isZero();
            assertThat(jdbc.queryForObject("select assigned_to from housekeeping_tasks where id=?",Long.class,task)).isEqualTo(ids.get("HOUSEKEEPING_STAFF"));
        }
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_assignment_batches",Integer.class)).isZero();
    }
    @Test void batchCleanOccupiedAndInspectionRoomsRejectWholeOperation() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long first=room(),second=room();finish(staff,manual(manager,second));
        assertThat(batch(manager,"STAFF2",List.of(first,second),key()).statusCode()).isEqualTo(409);
        checkIn(second);assertThat(batch(manager,"STAFF2",List.of(first,second),key()).statusCode()).isEqualTo(409);
        long inspection=room();jdbc.update("update rooms set cleaning_status='INSPECTION' where id=?",inspection);
        assertThat(batch(manager,"STAFF2",List.of(first,inspection),key()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks where room_id=?",Integer.class,first)).isZero();
    }
    @Test void batchCanonicalOrderReplayExpiryMismatchAndReceiptRecovery() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");long a=room(),b=room();String requestKey=key();
        var result=ok(batch(manager,"HOUSEKEEPING_STAFF",List.of(b,a),requestKey));
        assertThat(ok(batch(manager,"HOUSEKEEPING_STAFF",List.of(a,b),requestKey))).isEqualTo(result);
        assertThat(ok(manager.get("/api/housekeeping-tasks/assignment-results/"+requestKey))).isEqualTo(result);
        assertThat(login("HOTEL_MANAGER").get("/api/housekeeping-tasks/assignment-results/"+requestKey).statusCode()).isEqualTo(404);
        assertThat(batch(manager,"STAFF2",List.of(a,b),requestKey).body()).contains("IDEMPOTENCY_MISMATCH");
        assertThat(batch(manager,"HOUSEKEEPING_STAFF",List.of(a),requestKey).body()).contains("IDEMPOTENCY_MISMATCH");
        assertThat(manager.request("POST","/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",ids.get("HOUSEKEEPING_STAFF"),"roomIds",List.of(a,b),"reason","不同备注"),requestKey).body()).contains("IDEMPOTENCY_MISMATCH");
        jdbc.update("update idempotency_requests set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where operation='HK_BATCH_ASSIGN'");
        assertThat(batch(manager,"HOUSEKEEPING_STAFF",List.of(a,b),requestKey).body()).contains("IDEMPOTENCY_KEY_EXPIRED");
        assertThat(ok(manager.get("/api/housekeeping-tasks/assignment-results/"+requestKey))).isEqualTo(result);
        jdbc.update("update rooms set room_number='S5_RENAMED' where id=?",a);
        assertThat(ok(manager.get("/api/housekeeping-tasks/assignment-batches/"+result.path("id").asLong()))).isEqualTo(result);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where action='ASSIGN'",Integer.class)).isEqualTo(2);
    }
    @Test void batchConcurrentOverlappingManagersOnlyOneWinsAndNoPartialAssignments() throws Exception {
        long a=room(),b=room(),c=room();var manager=login("HOUSEKEEPING_MANAGER");var other=login("HOTEL_MANAGER");var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->{gate.await();return batch(manager,"HOUSEKEEPING_STAFF",List.of(a,b),key()).statusCode();});
            var y=pool.submit(()->{gate.await();return batch(other,"STAFF2",List.of(c,b),key()).statusCode();});gate.countDown();
            assertThat(List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_assignment_batches",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(distinct assigned_to) from housekeeping_tasks",Integer.class)).isEqualTo(1);
    }
    @Test void batchConcurrentSameKeyDifferentOrderReplaysOneBatch() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");long a=room(),b=room();String requestKey=key();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->{gate.await();return batch(manager,"HOUSEKEEPING_STAFF",List.of(a,b),requestKey);});
            var y=pool.submit(()->{gate.await();return batch(manager,"HOUSEKEEPING_STAFF",List.of(b,a),requestKey);});gate.countDown();
            assertThat(ok(x.get(20,TimeUnit.SECONDS))).isEqualTo(ok(y.get(20,TimeUnit.SECONDS)));
        }
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_assignment_batches",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where action='ASSIGN'",Integer.class)).isEqualTo(2);
    }
    void batchFault(String table,String condition) throws Exception {
        long a=room(),b=room();String requestKey=key();long actor=ids.get("HOUSEKEEPING_MANAGER");
        var input=new com.stayos.housekeeping.dto.AssignmentDtos.Request(ids.get("HOUSEKEEPING_STAFF"),List.of(a,b),"回滚测试");
        String event=table.equals("idempotency_requests")?"update":"insert";
        if(table.equals("housekeeping_tasks")) event="update";
        fault(table,event,condition.replace("ROOM_ID",Long.toString(b)));
        assertThatThrownBy(()->assignment.assign(actor,requestKey,input)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_assignment_batches",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action like 'HK_%'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where operation='HK_BATCH_ASSIGN'",Integer.class)).isZero();
        jdbc.execute("drop trigger test_fail_hk on "+table);jdbc.execute("drop function test_fail_hk_fn()");
        assertThat(assignment.assign(actor,requestKey,input).items).hasSize(2);
    }
    @Test void batchTaskUpdateFailureRollsBackEarlierRoom() throws Exception { batchFault("housekeeping_tasks","NEW.room_id=ROOM_ID"); }
    @Test void batchHistoryFailureRollsBackEarlierRoom() throws Exception { batchFault("housekeeping_task_logs","NEW.action='ASSIGN' AND NEW.task_version=1 AND NEW.task_id=(select id from housekeeping_tasks where room_id=ROOM_ID)"); }
    @Test void batchAuditFailureRollsBackWholeOperation() throws Exception { batchFault("audit_logs","NEW.action='HK_ASSIGN'"); }
    @Test void batchResultFailureRollsBackWholeOperation() throws Exception { batchFault("idempotency_requests","NEW.operation='HK_BATCH_ASSIGN'"); }
    @Test void batchItemFailureRollsBackWholeOperation() throws Exception { batchFault("housekeeping_assignment_items","NEW.room_id=ROOM_ID"); }
    @Test void batchEmployeeWorkloadCurrentTasksScopeAndNaturalRoomOrder() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long a=room(),b=room(),c=room();
        jdbc.update("update rooms set room_number='S5_A10' where id=?",a);jdbc.update("update rooms set room_number='S5_A2' where id=?",b);jdbc.update("update rooms set room_number='S5_A1' where id=?",c);
        ok(batch(manager,"HOUSEKEEPING_STAFF",List.of(a,b,c),key()));
        long taskA=jdbc.queryForObject("select id from housekeeping_tasks where room_id=?",Long.class,a),taskB=jdbc.queryForObject("select id from housekeeping_tasks where room_id=?",Long.class,b);
        ok(act(staff,taskA,"start"));ok(act(staff,taskB,"start"));ok(act(staff,taskB,"complete"));
        var employees=ok(manager.get("/api/housekeeping-tasks/assignment-employees"));var employee=java.util.stream.StreamSupport.stream(employees.spliterator(),false).filter(x->x.path("id").asLong()==ids.get("HOUSEKEEPING_STAFF")).findFirst().orElseThrow();
        assertThat(employee.path("pending").asInt()).isEqualTo(1);assertThat(employee.path("inProgress").asInt()).isEqualTo(1);assertThat(employee.path("completedToday").asInt()).isEqualTo(1);
        assertThat(ok(manager.get("/api/housekeeping-tasks/assignment-employees/"+ids.get("HOUSEKEEPING_STAFF")+"/tasks")).size()).isEqualTo(2);
        var rooms=ok(manager.get("/api/housekeeping-tasks/assignment-rooms?floor=5&roomTypeId="+type+"&roomNumber=S5_A&assigneeId="+ids.get("HOUSEKEEPING_STAFF")));
        assertThat(rooms.path("items").findValuesAsText("roomNumber")).containsExactly("S5_A1","S5_A2","S5_A10");
        assertThat(rooms.path("items").findValuesAsText("assignmentState")).containsExactly("ASSIGNED_SELF","CLEAN","IN_PROGRESS");
        assertThat(staff.get("/api/housekeeping-tasks/assignment-employees/"+ids.get("STAFF2")+"/tasks").statusCode()).isEqualTo(403);
        jdbc.update("update users set enabled=false where id=?",ids.get("STAFF2"));assertThat(ok(manager.get("/api/housekeeping-tasks/assignment-employees")).findValues("id").stream().map(JsonNode::asLong)).doesNotContain(ids.get("STAFF2"));
        assertThat(employees.toString()).doesNotContain("password","document","phone","securityVersion");
    }
    @Test void batchPreservesDisabledAndRepairRulesAndAssignedStaffCanComplete() throws Exception {
        var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long room=room();jdbc.update("update rooms set enabled=false where id=?",room);
        jdbc.execute("create or replace view room_operational_facts as select r.id room_id,exists(select 1 from stay_room_segments s where s.room_id=r.id and s.exited_at is null) occupied,true has_blocking_issue,exists(select 1 from housekeeping_tasks t where t.room_id=r.id and t.status in ('PENDING','IN_PROGRESS')) has_open_cleaning_task,exists(select 1 from stay_room_segments s where s.room_id=r.id) has_usage_history from rooms r");
        long task=ok(batch(manager,"HOUSEKEEPING_STAFF",List.of(room),key())).path("items").get(0).path("taskId").asLong();ok(act(staff,task,"start"));ok(act(staff,task,"complete"));
        assertThat(jdbc.queryForMap("select enabled,cleaning_status,maintenance_status,can_check_in from room_status where id=?",room)).containsEntry("enabled",false).containsEntry("cleaning_status","CLEAN").containsEntry("maintenance_status","OUT_OF_SERVICE").containsEntry("can_check_in",false);
    }


    @Test void batchSupportsOneHundredRoomsAndNoMore() throws Exception {
        var list=new ArrayList<Long>();for(int i=0;i<100;i++) list.add(room());
        var result=ok(batch(login("HOUSEKEEPING_MANAGER"),"HOUSEKEEPING_STAFF",list,key()));
        assertThat(result.path("items").size()).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks where assigned_to=?",Integer.class,ids.get("HOUSEKEEPING_STAFF"))).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where action='MANUAL_CREATE'",Integer.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_task_logs where action='ASSIGN'",Integer.class)).isEqualTo(100);
    }
    @Test void batchAssignedRoomsCompleteNormalCheckinCheckoutAndRecleanCycle() throws Exception {
        var manager=login("HOTEL_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long room=room();
        long firstTask=ok(batch(manager,"HOUSEKEEPING_STAFF",List.of(room),key())).path("items").get(0).path("taskId").asLong();
        ok(act(staff,firstTask,"start"));ok(act(staff,firstTask,"complete"));
        assertThat(jdbc.queryForObject("select can_check_in from room_status where id=?",Boolean.class,room)).isTrue();
        var first=checkIn(room);long secondTask=ok(manager.out(first.path("id").asLong(),first.path("segmentId").asLong(),key())).path("taskId").asLong();
        var next=ok(batch(manager,"HOUSEKEEPING_STAFF",List.of(room),key()));assertThat(next.path("items").get(0).path("taskId").asLong()).isEqualTo(secondTask);assertThat(next.path("items").get(0).path("manualCreated").asBoolean()).isFalse();
        ok(act(staff,secondTask,"start"));ok(act(staff,secondTask,"complete"));var second=checkIn(room);assertThat(second.path("id").asLong()).isNotEqualTo(first.path("id").asLong());
        assertThat(jdbc.queryForObject("select count(*) from housekeeping_tasks where room_id=?",Integer.class,room)).isEqualTo(2);
        assertThat(ok(staff.get("/api/housekeeping-tasks/"+firstTask+"/history")).findValuesAsText("action")).containsExactly("MANUAL_CREATE","ASSIGN","START","COMPLETE");
    }

}
