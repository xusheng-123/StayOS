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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HandoverIntegrationTest {
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
    @LocalServerPort int port;
    final Map<String,Long> ids=new LinkedHashMap<>();
    final String password=UUID.randomUUID().toString();
    static final String OWN="select id from users where username like 'handover_test_%'";
    @BeforeAll void setup() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String hash=passwords.encode(password);purgeAll();
        for(String role:List.of("ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,
                "handover_test_"+role.toLowerCase(Locale.ROOT),hash,"虚构交班"+role);
            ids.put(role,id);users.addRole(id,role);
        }
        long secondManager=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,
            "handover_test_hotel_manager2",hash,"虚构交班HOTEL_MANAGER");
        ids.put("HOTEL_MANAGER2",secondManager);users.addRole(secondManager,"HOTEL_MANAGER");
    }
    /** Purges every trace of earlier runs, including users, so setup is idempotent after a crashed run. */
    private void purgeAll() {
        purgeBusiness();
        db.update("delete from user_roles where user_id in("+OWN+")");
        db.update("delete from users where id in("+OWN+")");
    }
    /** Resets business facts but keeps the fixture users; the freeze trigger is suspended for test cleanup only. */
    private void purgeBusiness() {
        db.execute("drop trigger if exists hv_fail on audit_logs");db.execute("drop trigger if exists hv_fail on handover_events");
        db.execute("drop function if exists hv_fail_fn()");
        db.update("delete from idempotency_requests where operator_id in("+OWN+")");
        db.execute("alter table handover_items disable trigger handover_item_edit");
        db.update("delete from handover_items where handover_id in(select id from shift_handovers where handover_by in("+OWN+"))");
        db.execute("alter table handover_items enable trigger handover_item_edit");
        db.update("delete from handover_events where handover_id in(select id from shift_handovers where handover_by in("+OWN+"))");
        db.update("delete from shift_handovers where handover_by in("+OWN+")");
        db.update("delete from audit_logs where operator_id in("+OWN+")");
    }
    @BeforeEach void clean() {
        if(!ids.isEmpty()) purgeBusiness();
        for(var entry:ids.entrySet()) {
            db.update("update users set enabled=true,password_hash=?,failed_login_attempts=0,locked_until=null where id=?",passwords.encode(password),entry.getValue());
            String roleCode=entry.getKey().equals("HOTEL_MANAGER2")?"HOTEL_MANAGER":entry.getKey();
            db.update("delete from user_roles where user_id=?",entry.getValue());users.addRole(entry.getValue(),roleCode);
        }
    }
    @AfterAll void after() {
        if(!ids.isEmpty()) purgeBusiness();
        for(long id:ids.values()){db.update("delete from user_roles where user_id=?",id);db.update("delete from users where id=?",id);}
    }
    String key(){return UUID.randomUUID().toString();}
    OffsetDateTime inHours(long hours){return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).plusHours(hours);}
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
        HttpResponse<String> put(String path,Object body)throws Exception{return request("PUT",path,body,null);}
    }
    Browser login(String role)throws Exception {
        var b=new Browser();ok(b.post("/api/auth/login",Map.of("username","handover_test_"+role.toLowerCase(Locale.ROOT),"password",password)));return b;
    }
    JsonNode ok(HttpResponse<String> response)throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");}
    Map<String,Object> draft(long receiverId,String scope,String summary)throws Exception {
        var body=new LinkedHashMap<String,Object>();
        body.put("scope",scope);body.put("receiverId",receiverId);body.put("shiftStartAt",inHours(0).toString());body.put("shiftEndAt",inHours(8).toString());
        body.put("summary",summary);body.put("items",List.of(
            Map.of("category","CRITICAL_ISSUE","content","虚构严重异常未解决，已报修","sourceId",501),
            Map.of("category","CUSTOM","content","虚构交接提醒：检查备用钥匙")));
        return body;
    }
    long submit(Browser owner,long id,long version)throws Exception{return ok(owner.request("POST","/api/handovers/"+id+"/submit",Map.of("version",version),key())).path("version").asLong();}
    Map<String,Object> editBody(long version,String summary)throws Exception {
        var body=draft(ids.get("HOTEL_MANAGER"),"FRONT_DESK",summary);body.put("version",version);return body;
    }

    @Test void lifecycleFreezesSnapshotKeepsEventsAndAudits()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");long receiver=ids.get("HOTEL_MANAGER");
        String createKey=key();var created=ok(front.request("POST","/api/handovers",draft(receiver,"FRONT_DESK","虚构前台交班摘要"),createKey));
        long id=created.path("id").asLong();
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");assertThat(created.path("version").asLong()).isZero();
        assertThat(created.path("items").size()).isEqualTo(2);assertThat(created.path("events").get(0).path("eventType").asText()).isEqualTo("CREATED");
        assertThat(ok(front.request("POST","/api/handovers",draft(receiver,"FRONT_DESK","虚构前台交班摘要"),createKey))).isEqualTo(created);
        assertThat(ok(front.get("/api/handovers/result/"+createKey+"?operation=HANDOVER_CREATE"))).isEqualTo(created);
        // Draft edit bumps version and records an UPDATED event.
        var edited=new LinkedHashMap<>(draft(receiver,"FRONT_DESK","虚构修改后的摘要"));edited.put("version",0);
        var update=ok(front.put("/api/handovers/"+id,edited));assertThat(update.path("version").asLong()).isEqualTo(1);
        assertThat(update.path("summary").asText()).isEqualTo("虚构修改后的摘要");
        assertThat(front.put("/api/handovers/"+id,edited).statusCode()).isEqualTo(409);
        // Submission freezes content; acknowledgement by the receiver completes the handover.
        long version=submit(front,id,1);
        assertThat(ok(front.get("/api/handovers/"+id)).path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(front.put("/api/handovers/"+id,edited).statusCode()).isEqualTo(409);
        assertThat(ok(manager.request("POST","/api/handovers/"+id+"/acknowledge",Map.of("version",version),key())).path("status").asText()).isEqualTo("ACKNOWLEDGED");
        var detail=ok(front.get("/api/handovers/"+id));
        assertThat(detail.path("version").asLong()).isEqualTo(3);
        assertThat(detail.path("events").size()).isEqualTo(4);
        assertThat(detail.path("acknowledgedAt").asText()).isNotBlank();
        // Acknowledged handovers cannot be changed, reassigned or re-acknowledged.
        assertThat(front.put("/api/handovers/"+id,edited).statusCode()).isEqualTo(409);
        assertThat(front.post("/api/handovers/"+id+"/reassign",Map.of("version",3,"receiverId",ids.get("FRONT_DESK"),"reason","虚构改派")).statusCode()).isEqualTo(409);
        assertThat(manager.post("/api/handovers/"+id+"/acknowledge",Map.of("version",3)).statusCode()).isEqualTo(409);
        // Snapshot rows stay untouched; events and audit facts exist.
        assertThat(db.queryForObject("select count(*) from handover_items where handover_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from handover_events where handover_id=? and event_type='ACKNOWLEDGED'",Integer.class,id)).isEqualTo(1);
        var audit=ok(manager.get("/api/audit-logs?action=HANDOVER_ACKNOWLEDGE"));
        assertThat(audit.path("total").asInt()).isEqualTo(1);
        assertThat(ok(manager.get("/api/audit-logs/options")).path("modules").path("HANDOVER").toString()).contains("HANDOVER_SUBMIT","HANDOVER_REASSIGN");
    }
    @Test void idempotentReplaysMismatchExpiryAndReceiptIsolation()throws Exception {
        var front=login("FRONT_DESK");var other=login("HOUSEKEEPING_MANAGER");long receiver=ids.get("HOTEL_MANAGER");
        String k=key();var body=draft(receiver,"FRONT_DESK","虚构幂等摘要");
        var first=ok(front.request("POST","/api/handovers",body,k));
        assertThat(ok(front.request("POST","/api/handovers",body,k))).isEqualTo(first);
        var different=new LinkedHashMap<>(body);different.put("summary","虚构不同摘要");
        assertThat(json.readTree(front.request("POST","/api/handovers",different,k).body()).path("code").asText()).isEqualTo("IDEMPOTENCY_MISMATCH");
        assertThat(other.get("/api/handovers/result/"+k+"?operation=HANDOVER_CREATE").statusCode()).isEqualTo(404);
        assertThat(front.get("/api/handovers/result/"+k+"?operation=HANDOVER_SUBMIT").statusCode()).isEqualTo(404);
        assertThat(front.get("/api/handovers/result/not-a-key?operation=HANDOVER_CREATE").statusCode()).isEqualTo(400);
        assertThat(front.get("/api/handovers/result/"+k+"?operation=CHECK_OUT").statusCode()).isEqualTo(400);
        long id=first.path("id").asLong();String submitKey=key();
        var submitted=ok(front.request("POST","/api/handovers/"+id+"/submit",Map.of("version",0),submitKey));
        long version=submitted.path("version").asLong();
        assertThat(ok(front.request("POST","/api/handovers/"+id+"/submit",Map.of("version",0),submitKey))).isEqualTo(submitted);
        var manager=login("HOTEL_MANAGER");String ackKey=key();
        var acknowledged=ok(manager.request("POST","/api/handovers/"+id+"/acknowledge",Map.of("version",version),ackKey));
        assertThat(ok(manager.request("POST","/api/handovers/"+id+"/acknowledge",Map.of("version",version),ackKey))).isEqualTo(acknowledged);
        db.update("update idempotency_requests set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where request_key=?::uuid",ackKey);
        assertThat(json.readTree(manager.request("POST","/api/handovers/"+id+"/acknowledge",Map.of("version",version),ackKey).body()).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
        assertThat(ok(manager.get("/api/handovers/result/"+ackKey+"?operation=HANDOVER_ACKNOWLEDGE"))).isEqualTo(acknowledged);
    }
    @ParameterizedTest @ValueSource(strings={"ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF"})
    void scopeMatrixSeparatesFrontDeskAndHousekeeping(String role)throws Exception {
        var front=login("FRONT_DESK");var hk=login("HOUSEKEEPING_MANAGER");
        var frontHandover=ok(front.post("/api/handovers",draft(ids.get("HOTEL_MANAGER"),"FRONT_DESK","虚构前台范围")));
        var hkHandover=ok(hk.post("/api/handovers",draft(ids.get("HOTEL_MANAGER"),"HOUSEKEEPING","虚构客房范围")));
        var b=login(role);boolean frontAllowed=Set.of("FRONT_DESK","HOTEL_MANAGER").contains(role),hkAllowed=Set.of("HOUSEKEEPING_MANAGER","HOTEL_MANAGER").contains(role);
        long receiver=role.equals("HOTEL_MANAGER")?ids.get("HOTEL_MANAGER2"):ids.get("HOTEL_MANAGER");
        assertThat(b.get("/api/handovers?scope=FRONT_DESK").statusCode()).isEqualTo(frontAllowed?200:403);
        assertThat(b.get("/api/handovers?scope=HOUSEKEEPING").statusCode()).isEqualTo(hkAllowed?200:403);
        assertThat(b.get("/api/handovers/"+frontHandover.path("id").asLong()).statusCode()).isEqualTo(frontAllowed?200:403);
        assertThat(b.get("/api/handovers/"+hkHandover.path("id").asLong()).statusCode()).isEqualTo(hkAllowed?200:403);
        var frontCreate=b.post("/api/handovers",draft(receiver,"FRONT_DESK","虚构越权"));
        assertThat(frontCreate.statusCode()).as(frontCreate.body()).isEqualTo(frontAllowed?200:403);
        var hkCreate=b.post("/api/handovers",draft(receiver,"HOUSEKEEPING","虚构越权"));
        assertThat(hkCreate.statusCode()).as(hkCreate.body()).isEqualTo(hkAllowed?200:403);
        assertThat(b.get("/api/handovers/candidates?scope=FRONT_DESK").statusCode()).isEqualTo(frontAllowed?200:403);
        // Users without any handover permission are refused at the entry; scoped users get field validation.
        assertThat(b.get("/api/handovers?scope=SCHEDULERS").statusCode()).isEqualTo(frontAllowed||hkAllowed?400:403);
        // A front-desk handover is invisible to the housekeeping scope even in listings (structural id check,
        // not a substring test: numeric ids like 112 contain "12" as text).
        var hkList=ok(hk.get("/api/handovers?scope=HOUSEKEEPING"));
        for(var item:hkList.path("items")) assertThat(item.path("id").asLong()).isNotEqualTo(frontHandover.path("id").asLong());
    }
    @Test void receiverValidationRejectsDisabledMissingPermissionAndSelf()throws Exception {
        var front=login("FRONT_DESK");long staff=ids.get("HOUSEKEEPING_STAFF"),admin=ids.get("ADMIN"),self=ids.get("FRONT_DESK");
        assertThat(front.post("/api/handovers",draft(self,"FRONT_DESK","虚构自交自接")).statusCode()).isEqualTo(400);
        assertThat(front.post("/api/handovers",draft(staff,"FRONT_DESK","虚构无权限接班人")).statusCode()).isEqualTo(409);
        assertThat(front.post("/api/handovers",draft(admin,"FRONT_DESK","虚构管理员接班")).statusCode()).isEqualTo(409);
        assertThat(front.post("/api/handovers",draft(999999,"FRONT_DESK","虚构不存在接班人")).statusCode()).isEqualTo(409);
        // A disabled receiver cannot be assigned, and submission re-validates current account state.
        var adminBrowser=login("ADMIN");long manager=ids.get("HOTEL_MANAGER");
        var okBody=draft(manager,"FRONT_DESK","虚构正常交班");
        ok(adminBrowser.post("/api/users/"+manager+"/set-enabled",Map.of("enabled",false)));
        assertThat(front.post("/api/handovers",okBody).statusCode()).isEqualTo(409);
        ok(adminBrowser.post("/api/users/"+manager+"/set-enabled",Map.of("enabled",true)));
        var created=ok(front.post("/api/handovers",okBody));long id=created.path("id").asLong();
        ok(adminBrowser.post("/api/users/"+manager+"/set-enabled",Map.of("enabled",false)));
        assertThat(front.request("POST","/api/handovers/"+id+"/submit",Map.of("version",0),key()).statusCode()).isEqualTo(409);
        ok(adminBrowser.post("/api/users/"+manager+"/set-enabled",Map.of("enabled",true)));
        assertThat(front.request("POST","/api/handovers/"+id+"/submit",Map.of("version",0),key()).statusCode()).isEqualTo(200);
        // Only the receiver acknowledges; the handover person is refused even with the scope permission.
        assertThat(front.request("POST","/api/handovers/"+id+"/acknowledge",Map.of("version",1),key()).statusCode()).isEqualTo(403);
        var managerBrowser=login("HOTEL_MANAGER");
        assertThat(ok(managerBrowser.request("POST","/api/handovers/"+id+"/acknowledge",Map.of("version",1),key())).path("status").asText()).isEqualTo("ACKNOWLEDGED");
    }
    @Test void reassignRecordsReasonedEventAndRequiresRightState()throws Exception {
        var front=login("FRONT_DESK");var hk=login("HOUSEKEEPING_MANAGER");var second=login("HOTEL_MANAGER2");
        long first=ids.get("HOTEL_MANAGER"),reassignedTo=ids.get("HOTEL_MANAGER2");
        // Reassign needs SUBMITTED state and a reason.
        assertThat(front.post("/api/handovers/"+999999+"/reassign",Map.of("version",0,"receiverId",reassignedTo,"reason","虚构改派")).statusCode()).isEqualTo(404);
        var draftHandover=ok(front.post("/api/handovers",draft(first,"FRONT_DESK","虚构草稿改派")));
        assertThat(front.post("/api/handovers/"+draftHandover.path("id").asLong()+"/reassign",Map.of("version",0,"receiverId",reassignedTo,"reason","虚构改派")).statusCode()).isEqualTo(409);
        long id=ok(front.post("/api/handovers",draft(first,"FRONT_DESK","虚构改派交班"))).path("id").asLong();
        long version=submit(front,id,0);
        assertThat(front.post("/api/handovers/"+id+"/reassign",Map.of("version",version,"receiverId",reassignedTo)).statusCode()).isEqualTo(400);
        assertThat(front.post("/api/handovers/"+id+"/reassign",Map.of("version",version,"receiverId",reassignedTo,"reason"," ")).statusCode()).isEqualTo(400);
        assertThat(front.post("/api/handovers/"+id+"/reassign",Map.of("version",version,"receiverId",first,"reason","相同接班人")).statusCode()).isEqualTo(400);
        assertThat(hk.post("/api/handovers/"+id+"/reassign",Map.of("version",version,"receiverId",reassignedTo,"reason","虚构无权改派")).statusCode()).isEqualTo(403);
        var reassigned=ok(front.post("/api/handovers/"+id+"/reassign",Map.of("version",version,"receiverId",reassignedTo,"reason","虚构夜班换人接收")));
        assertThat(reassigned.path("receiverId").asLong()).isEqualTo(reassignedTo);assertThat(reassigned.path("version").asLong()).isEqualTo(version+1);
        var events=reassigned.path("events");boolean found=false;
        for(var event:events) if("REASSIGNED".equals(event.path("eventType").asText())) {
            found=true;assertThat(event.path("reason").asText()).isEqualTo("虚构夜班换人接收");
            assertThat(event.path("detail").path("fromReceiverId").asLong()).isEqualTo(first);
            assertThat(event.path("detail").path("toReceiverName").asText()).isEqualTo("虚构交班HOTEL_MANAGER");
        }
        assertThat(found).isTrue();
        // Only the receiver acknowledges; the handover person is refused even with the scope permission.
        assertThat(front.request("POST","/api/handovers/"+id+"/acknowledge",Map.of("version",version+1),key()).statusCode()).isEqualTo(403);
        assertThat(ok(second.request("POST","/api/handovers/"+id+"/acknowledge",Map.of("version",version+1),key())).path("receiverId").asLong()).isEqualTo(reassignedTo);
        assertThat(front.post("/api/handovers/"+id+"/reassign",Map.of("version",version+2,"receiverId",first,"reason","虚构确认后改派")).statusCode()).isEqualTo(409);
    }
    @Test void concurrentSubmitAcknowledgeAndReassignSerialize()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        long id=ok(front.post("/api/handovers",draft(ids.get("HOTEL_MANAGER"),"FRONT_DESK","虚构并发交班"))).path("id").asLong();
        assertThat(front.request("POST","/api/handovers/"+id+"/submit",Map.of("version",0),key()).statusCode()).isEqualTo(200);
        // Concurrent acknowledge and reassign both start from the same version; exactly one wins.
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var ackCall=pool.submit(()->{gate.await();return manager.request("POST","/api/handovers/"+id+"/acknowledge",Map.of("version",1),key()).statusCode();});
            var reassignCall=pool.submit(()->{gate.await();return front.post("/api/handovers/"+id+"/reassign",Map.of("version",1,"receiverId",ids.get("HOTEL_MANAGER2"),"reason","虚构并发改派")).statusCode();});
            gate.countDown();int ackStatus=ackCall.get(20,TimeUnit.SECONDS),reassignStatus=reassignCall.get(20,TimeUnit.SECONDS);
            assertThat(List.of(ackStatus,reassignStatus)).containsExactlyInAnyOrder(200,409);
            var detail=ok(front.get("/api/handovers/"+id));
            assertThat(detail.path("version").asLong()).isEqualTo(2);
        }
        // Draft edit conflicts keep exactly one winner.
        long draftId=ok(front.post("/api/handovers",draft(ids.get("HOTEL_MANAGER"),"FRONT_DESK","虚构并发草稿"))).path("id").asLong();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return front.put("/api/handovers/"+draftId,editBody(0,"虚构并发编辑一")).statusCode();});
            var b=pool.submit(()->{gate.await();return front.put("/api/handovers/"+draftId,editBody(0,"虚构并发编辑二")).statusCode();});
            gate.countDown();int first=a.get(20,TimeUnit.SECONDS),second=b.get(20,TimeUnit.SECONDS);
            assertThat(List.of(first,second)).containsExactlyInAnyOrder(200,409);
        }
        assertThat(db.queryForObject("select count(*) from handover_events where handover_id=? and event_type='UPDATED'",Integer.class,draftId)).isEqualTo(1);
    }
    @Test void validationAndHttpSemanticsGuardEveryEntry()throws Exception {
        var front=login("FRONT_DESK");long manager=ids.get("HOTEL_MANAGER");
        assertThat(new Browser().get("/api/handovers?scope=FRONT_DESK").statusCode()).isEqualTo(401);
        var raw=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/handovers")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(draft(manager,"FRONT_DESK","无CSRF")))).build();
        assertThat(front.http.send(raw,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(front.request("POST","/api/handovers",draft(manager,"FRONT_DESK","缺幂等键"),null).statusCode()).isEqualTo(400);
        assertThat(front.post("/api/handovers",draft(manager,"SCHEDULERS","虚构错误范围")).statusCode()).isEqualTo(400);
        var noTimes=draft(manager,"FRONT_DESK","缺时间");noTimes.remove("shiftEndAt");
        assertThat(front.post("/api/handovers",noTimes).statusCode()).isEqualTo(400);
        var reversed=draft(manager,"FRONT_DESK","时间倒置");reversed.put("shiftStartAt",inHours(8).toString());reversed.put("shiftEndAt",inHours(0).toString());
        assertThat(front.post("/api/handovers",reversed).statusCode()).isEqualTo(400);
        var longSpan=draft(manager,"FRONT_DESK","跨度过长");longSpan.put("shiftStartAt",inHours(0).toString());longSpan.put("shiftEndAt",inHours(24*40).toString());
        assertThat(front.post("/api/handovers",longSpan).statusCode()).isEqualTo(400);
        var ancient=draft(manager,"FRONT_DESK","时间久远");ancient.put("shiftStartAt",OffsetDateTime.now(ZoneOffset.UTC).minusDays(400).toString());
        assertThat(front.post("/api/handovers",ancient).statusCode()).isEqualTo(400);
        assertThat(front.post("/api/handovers",draft(manager,"FRONT_DESK","a".repeat(2001))).statusCode()).isEqualTo(400);
        var tooMany=new LinkedHashMap<>(draft(manager,"FRONT_DESK","事项过多"));var many=new ArrayList<Map<String,Object>>();
        for(int i=0;i<51;i++) many.add(Map.of("category","CUSTOM","content","虚构事项"+i));
        tooMany.put("items",many);assertThat(front.post("/api/handovers",tooMany).statusCode()).isEqualTo(400);
        var longItem=new LinkedHashMap<>(draft(manager,"FRONT_DESK","事项过长"));
        longItem.put("items",List.of(Map.of("category","CUSTOM","content","a".repeat(501))));
        assertThat(front.post("/api/handovers",longItem).statusCode()).isEqualTo(400);
        var controlItem=new LinkedHashMap<>(draft(manager,"FRONT_DESK","控制字符"));
        controlItem.put("items",List.of(Map.of("category","CUSTOM","content","虚构\u0001事项")));
        assertThat(front.post("/api/handovers",controlItem).statusCode()).isEqualTo(400);
        assertThat(front.get("/api/handovers?scope=FRONT_DESK&page=0").statusCode()).isEqualTo(400);
        assertThat(front.get("/api/handovers?scope=FRONT_DESK&pageSize=101").statusCode()).isEqualTo(400);
        assertThat(front.get("/api/handovers?scope=FRONT_DESK&status=WEIRD").statusCode()).isEqualTo(400);
        assertThat(front.get("/api/handovers?scope=FRONT_DESK&involving=ELSEWHERE").statusCode()).isEqualTo(400);
        assertThat(front.get("/api/handovers/999999").statusCode()).isEqualTo(404);
        assertThat(front.request("POST","/api/handovers/999999/submit",Map.of("version",0),key()).statusCode()).isEqualTo(404);
        var missing=new LinkedHashMap<>(draft(manager,"FRONT_DESK","虚构不存在"));missing.put("version",0);
        assertThat(front.put("/api/handovers/999999",missing).statusCode()).isEqualTo(404);
        // Filters return consistent pages.
        long id=ok(front.post("/api/handovers",draft(manager,"FRONT_DESK","虚构筛选交班"))).path("id").asLong();
        assertThat(ok(front.get("/api/handovers?scope=FRONT_DESK&status=DRAFT&involving=MINE")).path("total").asInt()).isEqualTo(1);
        assertThat(ok(front.get("/api/handovers?scope=FRONT_DESK&status=SUBMITTED")).path("total").asInt()).isZero();
        assertThat(ok(front.get("/api/handovers?scope=FRONT_DESK&pageSize=1")).path("items").size()).isEqualTo(1);
        var candidates=ok(front.get("/api/handovers/candidates?scope=FRONT_DESK"));
        assertThat(candidates.toString()).contains("虚构交班HOTEL_MANAGER","虚构交班FRONT_DESK").doesNotContain("虚构交班ADMIN","虚构交班HOUSEKEEPING_STAFF");
        assertThat(ok(front.get("/api/handovers?scope=FRONT_DESK&status=DRAFT&involving=MINE")).path("total").asInt()).isEqualTo(1);
    }
    @Test void invalidRequestsLeaveNoReservation()throws Exception {
        var front=login("FRONT_DESK");long manager=ids.get("HOTEL_MANAGER");
        String k=key();
        assertThat(front.request("POST","/api/handovers",draft(manager,"SCHEDULERS","虚构无效范围"),k).statusCode()).isEqualTo(400);
        assertThat(front.request("POST","/api/handovers",draft(999999,"FRONT_DESK","虚构无效接班人"),k).statusCode()).isEqualTo(409);
        assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,k)).isZero();
        String submitKey=key();
        assertThat(front.request("POST","/api/handovers/999999/submit",Map.of("version",0),submitKey).statusCode()).isEqualTo(404);
        assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,submitKey)).isZero();
        // Staff without any handover permission never reaches reservation either.
        var staff=login("HOUSEKEEPING_STAFF");String staffKey=key();
        assertThat(staff.request("POST","/api/handovers",draft(manager,"FRONT_DESK","虚构无权"),staffKey).statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,staffKey)).isZero();
    }
    @Test void snapshotDoesNotFollowLiveOperationsAndFailureRollsBack()throws Exception {
        var front=login("FRONT_DESK");long manager=ids.get("HOTEL_MANAGER");
        long id=ok(front.post("/api/handovers",draft(manager,"FRONT_DESK","虚构快照交班"))).path("id").asLong();
        Map<String,Object> before=db.queryForMap("select summary,(select string_agg(content,'|' order by position) from handover_items where handover_id=h.id) items from shift_handovers h where h.id=?",id);
        // Draft edits and unrelated live work must not change a submitted snapshot.
        long version=submit(front,id,0);
        Map<String,Object> after=db.queryForMap("select summary,(select string_agg(content,'|' order by position) from handover_items where handover_id=h.id) items from shift_handovers h where h.id=?",id);
        assertThat(after).isEqualTo(before);
        // Event insert failure rolls back the whole handover and the idempotency reservation.
        String k=key();
        db.execute("create function hv_fail_fn() returns trigger language plpgsql as $$ begin raise exception 'fictional failure'; end $$");
        for(String table:List.of("audit_logs","handover_events")) {
            db.execute("drop trigger if exists hv_fail on "+table);
            db.execute("create trigger hv_fail before insert on "+table+" for each row execute function hv_fail_fn()");
            var body=draft(manager,"FRONT_DESK","虚构失败交班");
            assertThat(front.request("POST","/api/handovers",body,k).statusCode()).isGreaterThanOrEqualTo(500);
            assertThat(db.queryForObject("select count(*) from shift_handovers where summary='虚构失败交班'",Integer.class)).isZero();
            assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=?::uuid",Integer.class,k)).isZero();
            db.execute("drop trigger hv_fail on "+table);
        }
        db.execute("drop function hv_fail_fn()");
        assertThat(ok(front.request("POST","/api/handovers",draft(manager,"FRONT_DESK","虚构失败交班"),k)).path("status").asText()).isEqualTo("DRAFT");
        assertThat(db.queryForObject("select version from shift_handovers where id=?",Integer.class,id)).isEqualTo(version);
    }
    @Test void realBrowserAcceptance()throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Browser tests explicitly enabled");
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");var hk=login("HOUSEKEEPING_MANAGER");
        var fixtures=new LinkedHashMap<String,Object>();
        var draftFix=ok(front.post("/api/handovers",draft(ids.get("HOTEL_MANAGER"),"FRONT_DESK","虚构浏览器草稿交班")));
        fixtures.put("draft",draftFix);
        var submitted=ok(front.post("/api/handovers",draft(ids.get("HOTEL_MANAGER"),"FRONT_DESK","虚构浏览器待确认交班")));
        fixtures.put("submitted",ok(front.request("POST","/api/handovers/"+submitted.path("id").asLong()+"/submit",Map.of("version",0),key())));
        var hkDraft=ok(hk.post("/api/handovers",draft(ids.get("HOTEL_MANAGER"),"HOUSEKEEPING","虚构浏览器客房交班")));
        fixtures.put("housekeeping",hkDraft);
        var acknowledgedFix=ok(front.post("/api/handovers",draft(ids.get("HOTEL_MANAGER"),"FRONT_DESK","虚构浏览器已确认交班")));
        long version=submit(front,acknowledgedFix.path("id").asLong(),0);
        fixtures.put("acknowledged",ok(manager.request("POST","/api/handovers/"+acknowledgedFix.path("id").asLong()+"/acknowledge",Map.of("version",version),key())));
        var reassignFix=ok(front.post("/api/handovers",draft(ids.get("HOTEL_MANAGER"),"FRONT_DESK","虚构改派交班")));
        fixtures.put("reassign",reassignFix);
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).getParent();var log=root.resolve(".local/handover-browser.log");
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","handovers.spec.ts").directory(root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_HANDOVER_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-handover-results.json");
        var process=builder.start();if(!process.waitFor(300,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Handover browser timeout");}
        assertThat(process.exitValue()).as("See private handover-browser.log").isZero();
    }
}
