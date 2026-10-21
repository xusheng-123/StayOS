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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("test") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemAcceptanceTest {
 @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
 @LocalServerPort int port;
 final Map<String,Long> ids=new LinkedHashMap<>();final String password=UUID.randomUUID().toString();long type;
 @Autowired com.stayos.audit.AuditQuery audits;
 @Test void authorizedAuditServiceReturnsRealPagedFacts() {
  var rows=audits.list(ids.get("ADMIN"),new com.stayos.audit.AuditQuery.Filter(1,20,null,null,null,null,null,null));assertThat(rows.page()).isEqualTo(1);
 }
 static final String OWN="select id from users where username like 'system_test_%'";
 @BeforeAll void setup() {
  assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");StayTestFacts.restore(db);
  for(String role:List.of("ADMIN","HOTEL_MANAGER","FRONT_DESK","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
   long id=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,"system_test_"+role.toLowerCase(Locale.ROOT),passwords.encode(password),"换房测试"+role);ids.put(role,id);users.addRole(id,role);
  }
  type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('S8_TYPE','虚构换房房型',1) returning id",Long.class);
 }
 @BeforeEach void clean() {
  for(String table:List.of("room_changes","stay_room_segments","housekeeping_tasks","housekeeping_task_logs","audit_logs","idempotency_requests","rooms")) {
   db.execute("drop trigger if exists s8_fail on "+table);
  }
  db.execute("drop function if exists s8_fail_fn()");
  var guests=db.queryForList("select guest_id from stay_guests where stay_record_id in(select id from stay_records where created_by in("+OWN+"))",Long.class);
  db.update("delete from idempotency_requests where operator_id in("+OWN+")");
  db.update("delete from housekeeping_assignment_items where batch_id in(select id from housekeeping_assignment_batches where operator_id in("+OWN+"))");
  db.update("delete from housekeeping_assignment_batches where operator_id in("+OWN+")");
  db.update("delete from housekeeping_task_logs where task_id in(select id from housekeeping_tasks where created_by in("+OWN+"))");
  db.update("delete from housekeeping_tasks where created_by in("+OWN+")");
  db.update("delete from room_changes where operated_by in("+OWN+")");
  db.update("delete from room_maintenance_logs where stop_id in(select id from room_maintenance_stops where stopped_by in("+OWN+"))");
  db.update("delete from room_maintenance_stops where stopped_by in("+OWN+")");
  db.update("delete from room_issue_logs where issue_id in(select id from room_issues where reported_by in("+OWN+"))");
  db.update("delete from room_issues where reported_by in("+OWN+")");
  db.update("delete from stay_guests where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
  db.update("delete from stay_room_segments where stay_record_id in(select id from stay_records where created_by in("+OWN+"))");
  db.update("delete from stay_records where created_by in("+OWN+")");for(long g:guests) db.update("delete from guests where id=?",g);
  db.update("delete from rooms where room_number like 'S8_%'");db.update("delete from audit_logs where operator_id in("+OWN+")");StayTestFacts.restore(db);
 }
 @AfterAll void after() { clean();db.update("delete from room_types where id=?",type);for(long id:ids.values()) { db.update("delete from user_roles where user_id=?",id);db.update("delete from users where id=?",id); } }
 String key() { return UUID.randomUUID().toString(); }
 long room() { return db.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,7,?,'CLEAN') returning id",Long.class,"S8_"+key().substring(0,8).toUpperCase(Locale.ROOT),type); }
 final class Browser {
  final int targetPort;
  Browser() { this(port); }
  Browser(int targetPort) { this.targetPort=targetPort; }
  final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
  HttpResponse<String> request(String method,String path,Object body,String key) throws Exception {
   var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+targetPort+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
   if(!method.equals("GET")) { var c=ok(get("/api/auth/csrf"));r.header(c.path("headerName").asText(),c.path("token").asText()); }
   if(key!=null) r.header("Idempotency-Key",key);
   return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  }
  HttpResponse<String> get(String path) throws Exception { return request("GET",path,null,null); }
  HttpResponse<String> post(String path,Object body) throws Exception { return request("POST",path,body,key()); }
  HttpResponse<String> change(JsonNode stay,long target,String key) throws Exception { return request("POST",path(stay),body(stay,target),key); }
 }
 Browser login(String role) throws Exception { var b=new Browser();ok(b.request("POST","/api/auth/login",Map.of("username","system_test_"+role.toLowerCase(Locale.ROOT),"password",password),null));return b; }
 JsonNode ok(HttpResponse<String> r) throws Exception { assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json.readTree(r.body()).path("data"); }
 String path(JsonNode stay) { return "/api/stays/"+stay.path("id").asLong()+"/room-changes"; }
 Map<String,Object> body(JsonNode stay,long target) { return Map.of("segmentId",stay.path("segmentId").asLong(),"targetRoomId",target,"reasonType","GUEST_REQUEST","reason","虚构住客要求调整房间"); }
 Map<String,Object> inBody(long room) { return Map.of("roomId",room,"expectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString(),"guests",List.of(Map.of("name","虚构换房住客","documentType","OTHER","documentNumber","S8-FICTIONAL-001","primary",true),Map.of("name","虚构同行住客","documentType","OTHER","documentNumber","S8-FICTIONAL-002","primary",false)),"notes",""); }
 JsonNode checkIn(long room) throws Exception { return ok(login("FRONT_DESK").post("/api/stays",inBody(room))); }
 JsonNode detail(Browser b,JsonNode stay) throws Exception { return ok(b.get("/api/stays/"+stay.path("id").asLong())); }
 JsonNode roomView(Browser b,long room) throws Exception { return ok(b.get("/api/rooms/"+room)); }
 long issue(Browser b,long room,String severity) throws Exception { return ok(b.post("/api/room-issues",Map.of("roomId",room,"issueType","FACILITY","severity",severity,"description","虚构空调异常"))).path("issueId").asLong(); }
 long stop(Browser b,long room) throws Exception { return ok(b.post("/api/rooms/"+room+"/maintenance-stop",Map.of("reason","虚构维修停用"))).path("stopId").asLong(); }
 void intact(JsonNode s,long target) {
  assertThat(db.queryForObject("select room_id from stay_room_segments where stay_record_id=? and exited_at is null",Long.class,s.path("id").asLong())).isEqualTo(s.path("roomId").asLong());
  assertThat(db.queryForObject("select occupied from room_status where id=?",Boolean.class,target)).isFalse();
  assertThat(db.queryForObject("select count(*) from room_changes",Integer.class)).isZero();
  assertThat(db.queryForObject("select count(*) from housekeeping_tasks where source='ROOM_CHANGE'",Integer.class)).isZero();
  assertThat(db.queryForObject("select count(*) from idempotency_requests where operation='ROOM_CHANGE'",Integer.class)).isZero();
  assertThat(db.queryForObject("select count(*) from audit_logs where action='ROOM_CHANGE'",Integer.class)).isZero();
 }

 void finish(Browser manager,Browser staff,long room,long task) throws Exception {
  ok(manager.post("/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",ids.get("HOUSEKEEPING_STAFF"),"roomIds",List.of(room),"reason","系统验收分房")));
  var t=ok(staff.get("/api/housekeeping-tasks/"+task));ok(staff.post("/api/housekeeping-tasks/"+task+"/start",Map.of("version",t.path("version").asLong())));
  t=ok(staff.get("/api/housekeeping-tasks/"+task));ok(staff.post("/api/housekeeping-tasks/"+task+"/complete",Map.of("version",t.path("version").asLong(),"reason","虚构清洁完成")));
 }
 record Flow(long a,long b,JsonNode stay,JsonNode change,JsonNode checkout) {}
 Flow flow() throws Exception {
  var manager=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");var front=login("FRONT_DESK");long a=room(),b=room();
  db.update("update rooms set cleaning_status='DIRTY' where id in (?,?)",a,b); // Initial empty-room fixture ONLY; all transitions below use HTTP.
  var batch=ok(manager.post("/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",ids.get("HOUSEKEEPING_STAFF"),"roomIds",List.of(a,b),"reason","隔离库初始清洁")));
  for(var item:batch.path("items")) {
   long task=item.path("taskId").asLong();var t=ok(staff.get("/api/housekeeping-tasks/"+task));ok(staff.post("/api/housekeeping-tasks/"+task+"/start",Map.of("version",t.path("version").asLong())));
   t=ok(staff.get("/api/housekeeping-tasks/"+task));ok(staff.post("/api/housekeeping-tasks/"+task+"/complete",Map.of("version",t.path("version").asLong())));
  }
  assertThat(roomView(front,a).path("canCheckIn").asBoolean()).isTrue();var stay=ok(front.post("/api/stays",inBody(a)));
  long issue=issue(login("HOUSEKEEPING_STAFF"),a,"CRITICAL"),stop=stop(manager,a);
  assertThat(roomView(front,a).path("hasCriticalIssue").asBoolean()).isTrue();
  var reason=new HashMap<String,Object>(body(stay,b));reason.put("reasonType","ROOM_ISSUE");reason.put("issueId",issue);
  var change=ok(front.post(path(stay),reason));finish(manager,staff,a,change.path("taskId").asLong());
  assertThat(roomView(front,a).path("canCheckIn").asBoolean()).isFalse();
  ok(manager.post("/api/room-issues/"+issue+"/start",Map.of("version",0,"reason","正在排查")));
  ok(login("HOTEL_MANAGER").post("/api/room-issues/"+issue+"/resolve",Map.of("version",1,"reason","故障已合法解决")));
  assertThat(roomView(front,a).path("canCheckIn").asBoolean()).isFalse();
  ok(manager.post("/api/rooms/"+a+"/maintenance-resume",Map.of("stopId",stop,"reason","维修完成且确认恢复")));
  var current=detail(front,stay);var out=ok(front.post("/api/stays/"+stay.path("id").asLong()+"/check-out",Map.of("segmentId",current.path("segmentId").asLong())));
  finish(manager,staff,b,out.path("taskId").asLong());
  assertThat(roomView(front,a).path("canCheckIn").asBoolean()).isTrue();assertThat(roomView(front,b).path("canCheckIn").asBoolean()).isTrue();
  return new Flow(a,b,stay,change,out);
 }
 @Test void completeSystemCollaborationPreservesAllFactsAndScopedAudit() throws Exception {
  var f=flow();var history=detail(login("FRONT_DESK"),f.stay());
  assertThat(history.path("status").asText()).isEqualTo("CHECKED_OUT");assertThat(history.path("guests")).isEqualTo(f.stay().path("guests"));
  assertThat(history.path("checkInAt")).isEqualTo(f.stay().path("checkInAt"));assertThat(history.path("expectedCheckOutAt")).isEqualTo(f.stay().path("expectedCheckOutAt"));
  assertThat(history.path("roomSegments")).hasSize(2);assertThat(history.path("roomChanges")).hasSize(1);
  assertThat(db.queryForObject("select count(*) from (select room_id from stay_room_segments where exited_at is null group by room_id having count(*)>1) x",Integer.class)).isZero();
  assertThat(db.queryForObject("select count(*) from (select stay_record_id from stay_room_segments where exited_at is null group by stay_record_id having count(*)>1) x",Integer.class)).isZero();
  var events=ok(login("HOTEL_MANAGER").get("/api/audit-logs?pageSize=100")).path("items").toString();
  for(String action:List.of("CHECK_IN","CHECK_OUT","ROOM_CHANGE","HK_ASSIGN","HK_START","HK_COMPLETE","ISSUE_REPORT","ISSUE_RESOLVE","MAINTENANCE_STOP","MAINTENANCE_RESUME")) assertThat(events).contains(action);
  assertThat(events).doesNotContain("document","phone","ciphertext","password","token");
 }
 @ParameterizedTest @ValueSource(strings={"ADMIN","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","FRONT_DESK","HOUSEKEEPING_STAFF"})
 void fiveRoleAuditScopesAndWriteBoundaries(String role) throws Exception {
  var b=login(role);boolean allowed=!Set.of("FRONT_DESK","HOUSEKEEPING_STAFF").contains(role);
  var r=b.get("/api/audit-logs");assertThat(r.statusCode()).isEqualTo(allowed?200:403);
  if(allowed) {
   var modules=ok(b.get("/api/audit-logs/options")).path("modules");
   assertThat(modules.has("SECURITY")).isEqualTo(role.equals("ADMIN"));assertThat(modules.has("STAY")).isEqualTo(role.equals("HOTEL_MANAGER"));
   if(role.equals("HOUSEKEEPING_MANAGER")) assertThat(modules.fieldNames()).toIterable().containsExactlyInAnyOrder("HOUSEKEEPING","ISSUE","MAINTENANCE");
   String forbidden=role.equals("ADMIN")?"STAY":"ACCOUNT";assertThat(b.get("/api/audit-logs?module="+forbidden).statusCode()).isEqualTo(403);
  }
  assertThat(b.post("/api/housekeeping-tasks/999999/claim",Map.of()).statusCode()).isEqualTo(403);
  assertThat(b.post("/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",ids.get("HOUSEKEEPING_STAFF"),"roomIds",List.of(999999))).statusCode()).isEqualTo(Set.of("HOTEL_MANAGER","HOUSEKEEPING_MANAGER").contains(role)?409:403);
 }
 @Test void auditDetailPaginationDateAndOperatorFiltersCannotExpandScope() throws Exception {
  var f=flow();var admin=login("ADMIN");var manager=login("HOTEL_MANAGER");var hk=login("HOUSEKEEPING_MANAGER");
  long checkIn=db.queryForObject("select id from audit_logs where action='CHECK_IN' and target_id=?",Long.class,f.stay().path("id").asLong());
  long userLogin=db.queryForObject("select max(id) from audit_logs where action='LOGIN' and operator_id=?",Long.class,ids.get("ADMIN"));
  assertThat(admin.get("/api/audit-logs/"+checkIn).statusCode()).isEqualTo(404);assertThat(hk.get("/api/audit-logs/"+checkIn).statusCode()).isEqualTo(404);
  assertThat(manager.get("/api/audit-logs/"+userLogin).statusCode()).isEqualTo(404);assertThat(ok(manager.get("/api/audit-logs/"+checkIn)).path("targetId").asLong()).isEqualTo(f.stay().path("id").asLong());
  var q="/api/audit-logs?action=CHECK_IN&operatorId="+ids.get("FRONT_DESK")+"&pageSize=1";
  assertThat(ok(manager.get(q)).path("items")).hasSize(1);assertThat(ok(manager.get(q+"&page=2")).path("items")).isEmpty();
  assertThat(ok(manager.get(q+"&from=2000-01-01T00%3A00%3A00Z&to=2000-02-01T00%3A00%3A00Z")).path("items")).isEmpty();
  for(String suffix:List.of("?page=0","?pageSize=101","?module=UNKNOWN","?action=USER_RESET_PASSWORD","?from=2000-01-01T00%3A00%3A00Z","?to=bad")) assertThat(manager.get("/api/audit-logs"+suffix).statusCode()).isIn(400,403);
  assertThat(new Browser().get("/api/audit-logs").statusCode()).isEqualTo(401);
  assertThat(manager.request("DELETE","/api/audit-logs/"+checkIn,null,null).statusCode()).isEqualTo(405);
 }
 @Test void multipleRolesUnionAuditScopesWithoutGrantingBusinessWrites() throws Exception {
  long actor=ids.get("ADMIN");users.addRole(actor,"HOUSEKEEPING_MANAGER");
  try {var b=login("ADMIN");var scope=ok(b.get("/api/audit-logs/options")).path("modules");assertThat(scope.has("SECURITY")).isTrue();assertThat(scope.has("HOUSEKEEPING")).isTrue();assertThat(scope.has("STAY")).isFalse();}
  finally {db.update("delete from user_roles where user_id=? and role_id=(select id from roles where role_code='HOUSEKEEPING_MANAGER')",actor);}
 }
 @Test void disabledAndLoggedOutAuditUsersLoseAccessAndCsrfIsEnforced() throws Exception {
  var b=login("HOUSEKEEPING_MANAGER");long id=ids.get("HOUSEKEEPING_MANAGER");users.setEnabled(id,false);
  try { assertThat(b.get("/api/audit-logs").statusCode()).isEqualTo(401); } finally {users.setEnabled(id,true);}
  b=login("HOTEL_MANAGER");ok(b.post("/api/auth/logout",Map.of()));assertThat(b.get("/api/audit-logs").statusCode()).isEqualTo(401);
  var front=login("FRONT_DESK");var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/stays")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(inBody(room())))).build();
  assertThat(front.http.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
 }
 @Test void sensitiveGuestDataDoesNotEscapeThroughUnrelatedQueriesOrErrors() throws Exception {
  long a=room();var in=new HashMap<String,Object>(inBody(a));in.put("guests",List.of(Map.of("name","虚构安全住客","documentType","OTHER","documentNumber","FICTIONAL-SECRET-0088","phone","13900000088","primary",true)));
  var stay=ok(login("FRONT_DESK").post("/api/stays",in));
  for(String role:ids.keySet()) {
   var b=login(role);for(String path:List.of("/api/rooms/"+a,"/api/room-status","/api/room-issues","/api/housekeeping-tasks","/api/audit-logs","/api/stays/"+stay.path("id").asLong())) {
    var r=b.get(path);assertThat(r.body()).doesNotContain("FICTIONAL-SECRET-0088","13900000088","passwordHash","documentCiphertext","STAYOS_DB_PASSWORD","jdbc:postgresql");
   }
   if(!Set.of("FRONT_DESK","HOTEL_MANAGER").contains(role)) assertThat(b.get("/api/stays/"+stay.path("id").asLong()).statusCode()).isEqualTo(403);
  }
  var r=login("FRONT_DESK").post("/api/stays",Map.of("guests","FICTIONAL-SECRET-0088"));assertThat(r.statusCode()).isEqualTo(400);assertThat(r.body()).doesNotContain("FICTIONAL-SECRET-0088","Exception","SQL");
 }
 @Test void failureAuditOutageCannotExposeSqlOrCommitUntracedSuccess() throws Exception {
  var front=login("FRONT_DESK");long room=room();String k=key();var body=inBody(room);
  var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver");var messages=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();messages.start();logger.addAppender(messages);
  try {
   db.execute("create function s8_fail_fn() returns trigger language plpgsql as $$ begin raise exception 'S8_PRIVATE_DATABASE_DETAIL'; end $$");db.execute("create trigger s8_fail before insert on audit_logs for each row execute function s8_fail_fn()");
   var r=front.request("POST","/api/stays",body,k);assertThat(r.statusCode()).isEqualTo(500);assertThat(r.body()).doesNotContain("S8_PRIVATE_DATABASE_DETAIL","SQL","Exception");
   assertThat(messages.list).noneMatch(e->e.getFormattedMessage().contains("Failure in @ExceptionHandler"));
   assertThat(roomView(front,room).path("occupied").asBoolean()).isFalse();assertThat(db.queryForObject("select count(*) from idempotency_requests where request_key=cast(? as uuid)",Integer.class,k)).isZero();
  } finally {logger.detachAppender(messages);db.execute("drop trigger if exists s8_fail on audit_logs");db.execute("drop function if exists s8_fail_fn()");}
  ok(front.request("POST","/api/stays",body,k));
 }
 java.nio.file.Path evidence(String name) throws Exception {
  var root=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve(".local/phase8");java.nio.file.Files.createDirectories(root);return root.resolve(name);
 }
 void save(String name,Object data) throws Exception { json.writerWithDefaultPrettyPrinter().writeValue(evidence(name).toFile(),data); }
 @Test void tenClientPerformanceAndThirtySecondStabilityBaseline() throws Exception {
  // 80-room fixture in the isolated database. No development-room writes.
  for(int i=0;i<80;i++) room();var f=flow();long queryRoomCount=db.queryForObject("select count(*) from rooms",Long.class);long deadlocksBefore=db.queryForObject("select deadlocks from pg_stat_database where datname=current_database()",Long.class);var observations=new ArrayList<Map<String,Object>>();
  var begin=System.nanoTime();var sessions=new ArrayList<Browser>();
  var roles=List.of("FRONT_DESK","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF","HOTEL_MANAGER","ADMIN");
  for(String role:roles) {long start=System.nanoTime();sessions.add(login(role));observations.add(Map.of("endpoint","LOGIN","status",200,"milliseconds",(System.nanoTime()-start)/1e6));}
  var endpoints=List.of(List.of("/api/room-status","/api/stays/available-rooms","/api/stays","/api/stays/history","/api/stays/"+f.stay().path("id").asLong()),
   List.of("/api/housekeeping-tasks","/api/housekeeping-tasks/assignment-employees","/api/housekeeping-tasks/assignment-rooms?assigneeId="+ids.get("HOUSEKEEPING_STAFF"),"/api/room-issues","/api/audit-logs"),
   List.of("/api/housekeeping-tasks?scope=MINE","/api/room-status/statistics"),List.of("/api/room-status","/api/audit-logs"),List.of("/api/audit-logs"));
  var pool=Executors.newFixedThreadPool(10);var gate=new CountDownLatch(1);var samples=new java.util.concurrent.ConcurrentLinkedQueue<Map<String,Object>>();
  try {
   var jobs=new ArrayList<Future<?>>();for(int client=0;client<10;client++) {final int index=client%5;jobs.add(pool.submit(()->{try {gate.await();for(int j=0;j<30;j++) {String path=endpoints.get(index).get(j%endpoints.get(index).size());long t=System.nanoTime();var r=sessions.get(index).get(path);samples.add(Map.of("endpoint",path.split("\\?")[0],"status",r.statusCode(),"milliseconds",(System.nanoTime()-t)/1e6));}}catch(Exception e){throw new RuntimeException(e);}}));}gate.countDown();for(var j:jobs) j.get(90,TimeUnit.SECONDS);
   double burstSeconds=(System.nanoTime()-begin)/1e9;var soak=new java.util.concurrent.ConcurrentLinkedQueue<Map<String,Object>>();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
   jobs.clear();for(int client=0;client<5;client++) {final int index=client;jobs.add(pool.submit(()->{try {while(System.nanoTime()<until){String path=endpoints.get(index).get(0);long t=System.nanoTime();var r=sessions.get(index).get(path);soak.add(Map.of("endpoint",path,"status",r.statusCode(),"milliseconds",(System.nanoTime()-t)/1e6));Thread.sleep(180);}}catch(Exception e){throw new RuntimeException(e);}}));}for(var j:jobs) j.get(60,TimeUnit.SECONDS);
   assertThat(samples).hasSize(300);assertThat(samples).allMatch(r->r.get("status").equals(200));assertThat(soak).allMatch(r->r.get("status").equals(200));
   var distinct=new ArrayList<Long>();for(int i=0;i<5;i++) distinct.add(room());var front=login("FRONT_DESK");ok(front.get("/api/auth/csrf"));var writes=new ArrayList<Future<Integer>>();
   for(long room:distinct) writes.add(pool.submit(()->front.post("/api/stays",inBody(room)).statusCode()));for(var w:writes) assertThat(w.get()).isEqualTo(200);
   long conflict=room();writes.clear();for(int i=0;i<5;i++) writes.add(pool.submit(()->front.post("/api/stays",inBody(conflict)).statusCode()));var statuses=new ArrayList<Integer>();for(var w:writes) statuses.add(w.get());assertThat(statuses).containsExactlyInAnyOrder(200,409,409,409,409);
   // Query plans measured against real views. Synthetic audit rows are isolated performance fixtures, not fabricated production history.
   db.update("insert into audit_logs(operator_id,action,target_type,target_id,result) select ?,'CHECK_IN','STAY',null,'SUCCESS' from generate_series(1,20000)",ids.get("FRONT_DESK"));db.execute("analyze audit_logs");
   var plans=new LinkedHashMap<String,Object>();
   for(var entry:Map.of("roomBoard","select * from room_status order by floor,room_number,id limit 20","auditPage","select * from audit_logs where action in ('CHECK_IN','CHECK_OUT','ROOM_CHANGE') and created_at>=now()-interval '30 days' order by created_at desc,id desc limit 20","auditCount","select count(*) from audit_logs where action in ('CHECK_IN','CHECK_OUT','ROOM_CHANGE') and created_at>=now()-interval '30 days'").entrySet()) plans.put(entry.getKey(),db.queryForList("explain (analyze,buffers,format json) "+entry.getValue(),String.class));
   long deadlocksAfter=db.queryForObject("select deadlocks from pg_stat_database where datname=current_database()",Long.class);assertThat(deadlocksAfter).isEqualTo(deadlocksBefore);var hikari=(com.zaxxer.hikari.HikariDataSource)db.getDataSource();var metrics=hikari.getHikariPoolMXBean();assertThat(metrics.getThreadsAwaitingConnection()).isZero();
   save("performance.json",facts("queryRoomCount",queryRoomCount,"roomCountAtPlan",db.queryForObject("select count(*) from rooms",Long.class),"newDeadlocks",deadlocksAfter-deadlocksBefore,"clients",10,"burstSecondsIncludingLogin",burstSeconds,"burst",summarize(samples),"soakSeconds",30,"soakClients",5,"soak",summarize(soak),"login",observations,"differentRoomWrites",5,"sameRoomStatuses",statuses,"pool",Map.of("total",metrics.getTotalConnections(),"active",metrics.getActiveConnections(),"awaiting",metrics.getThreadsAwaitingConnection()),"processors",Runtime.getRuntime().availableProcessors(),"maxJvmBytes",Runtime.getRuntime().maxMemory()));save("query-plans.json",plans);
  } finally {pool.shutdownNow();}
 }
 Map<String,Object> summarize(Collection<Map<String,Object>> values) {
  var times=values.stream().mapToDouble(v->((Number)v.get("milliseconds")).doubleValue()).sorted().toArray();
  return Map.of("requests",times.length,"success",values.stream().filter(v->v.get("status").equals(200)).count(),"meanMs",Arrays.stream(times).average().orElse(0),"p95Ms",times[(int)Math.ceil(times.length*.95)-1],"maxMs",times[times.length-1],"samples",values);
 }
 String quote(String value) {return "'"+value.replace("'","''")+"'";}
 JsonNode powerShell(String name,String command,boolean success) throws Exception {
  var output=evidence(name+".out");var builder=new ProcessBuilder("pwsh","-NoProfile","-Command","$ErrorActionPreference='Stop'; "+command).redirectErrorStream(true).redirectOutput(output.toFile());
  var p=builder.start();if(!p.waitFor(90,TimeUnit.SECONDS)) {p.destroyForcibly();fail("PowerShell operation timeout: "+name);}if(success) assertThat(p.exitValue()).as("See private evidence "+name).isZero();else assertThat(p.exitValue()).isNotZero();
  return success?json.readTree(output.toFile()):json.nullNode();
 }
 int docker(String...args) throws Exception {var cmd=new ArrayList<String>();cmd.add("docker");cmd.addAll(List.of(args));var p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(evidence("docker-operations.log").toFile()).start();if(!p.waitFor(30,TimeUnit.SECONDS)) {p.destroyForcibly();fail("Isolated Docker operation timeout");}return p.exitValue();}
 java.sql.Connection restored(String database) throws Exception { return java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database,System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),System.getenv("STAYOS_DB_PASSWORD")); }
 Map<String,String> snapshot(java.sql.Connection c) throws Exception {
  var result=new TreeMap<String,String>();var tables=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("select tablename from pg_tables where schemaname='public' order by tablename")){while(r.next()) tables.add(r.getString(1));}
  for(String t:tables) {try(var s=c.createStatement();var r=s.executeQuery("select md5(coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text) from \""+t+"\" t")){r.next();result.put(t,r.getString(1));}}return result;
 }
 Process child(String database,int childPort,String name) throws Exception {
  String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
  return new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",cp,"com.stayos.StayosApplication","--spring.profiles.active=test","--spring.datasource.url=jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/"+database+"?socketTimeout=3&connectTimeout=2","--server.port="+childPort,"--spring.datasource.hikari.connection-timeout=1500","--spring.datasource.hikari.validation-timeout=1000","--spring.datasource.hikari.connection-init-sql=SET statement_timeout = '2000ms'")
   .redirectErrorStream(true).redirectOutput(evidence(name+".log").toFile()).start();
 }
 void ready(Process p,int childPort) throws Exception {for(int i=0;i<100;i++){assertThat(p.isAlive()).as("Isolated application stopped").isTrue();try {if(new Browser(childPort).get("/api/health").statusCode()==200) return;}catch(Exception ignored){}Thread.sleep(200);}fail("Isolated application startup timeout");}
 Browser childLogin(int childPort,String role) throws Exception {var b=new Browser(childPort);ok(b.post("/api/auth/login",Map.of("username","system_test_"+role.toLowerCase(Locale.ROOT),"password",password)));return b;}
 @Test void actualBackupRestoreKeyVerificationRestartOutageTimeoutAndCommitRollback() throws Exception {
  var f=flow();long active=room();var front=login("FRONT_DESK");String request=key();var input=new HashMap<String,Object>(inBody(active));input.put("guests",List.of(Map.of("name","虚构恢复主要住客","documentType","OTHER","documentNumber","S8-FICTIONAL-001","phone","13900000087","primary",true),Map.of("name","虚构恢复同行住客","documentType","OTHER","documentNumber","S8-FICTIONAL-002","phone","13900000088","primary",false)));var activeStay=ok(front.request("POST","/api/stays",input,request));
  Map<String,String> before;try(var initialConnection=db.getDataSource().getConnection()){before=snapshot(initialConnection);}String database="stayos_restore_"+key().replace("-","");var scripts=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("scripts");
  var backup=powerShell("backup","& "+quote(scripts.resolve("backup-database.ps1").toString())+" -Container stayos-postgres-test-1 -Database stayos_test -OutputDirectory "+quote(evidence("backups").toString())+" | ConvertTo-Json -Compress",true);
  var restore=powerShell("restore","& "+quote(scripts.resolve("restore-database.ps1").toString())+" -Container stayos-postgres-test-1 -Database "+database+" -ConfirmTarget "+database+" -BackupPath "+quote(backup.path("BackupPath").asText())+" | ConvertTo-Json -Compress",true);
  try(var c=restored(database)) {
   assertThat(snapshot(c)).isEqualTo(before);
   try(var stmt=c.createStatement();var r=stmt.executeQuery("select document_ciphertext,encryption_key_id,phone_ciphertext from guests order by id desc limit 1")) {assertThat(r.next()).isTrue();String cipher=r.getString(1);assertThat(r.getString(2)).isEqualTo("v1");assertThat(decrypt(cipher,new byte[32])).isEqualTo("S8-FICTIONAL-002");assertThat(decrypt(r.getString(3),new byte[32],"phone")).isEqualTo("13900000088");byte[] wrong=new byte[32];wrong[0]=1;assertThatThrownBy(()->decrypt(cipher,wrong)).isInstanceOf(javax.crypto.AEADBadTagException.class);}
   try(var stmt=c.createStatement();var r=stmt.executeQuery("select count(*) from pg_constraint where contype in ('f','u','c')")){r.next();assertThat(r.getInt(1)).isGreaterThan(50);}
   assertThatThrownBy(()->{try(var stmt=c.createStatement()){stmt.executeUpdate("insert into stay_guests(stay_record_id,guest_id,is_primary,position) values(9223372036854775800,9223372036854775800,true,1)");}}).isInstanceOf(java.sql.SQLException.class).satisfies(e->assertThat(((java.sql.SQLException)e).getSQLState()).isEqualTo("23503"));
   assertThatThrownBy(()->{try(var stmt=c.createStatement()){stmt.executeUpdate("insert into rooms(id,room_number,floor,room_type_id) select id,'S8_RESTORE_BAD',floor,room_type_id from rooms limit 1");}}).isInstanceOf(java.sql.SQLException.class).satisfies(e->assertThat(((java.sql.SQLException)e).getSQLState()).isEqualTo("23505"));
  }
  assertThatThrownBy(()->new com.stayos.stay.service.GuestProtection("")).isInstanceOf(IllegalStateException.class);
  String restoreScript="& "+quote(scripts.resolve("restore-database.ps1").toString());String archive=" -BackupPath "+quote(backup.path("BackupPath").asText());
  powerShell("reject-dev",restoreScript+" -Container stayos-postgres-1 -Database stayos -ConfirmTarget stayos"+archive,false);
  powerShell("reject-existing",restoreScript+" -Container stayos-postgres-test-1 -Database "+database+" -ConfirmTarget "+database+archive,false);
  powerShell("reject-confirm",restoreScript+" -Container stayos-postgres-test-1 -Database stayos_restore_wrong -ConfirmTarget wrong"+archive,false);
  var corrupt=evidence("corrupt.dump");java.nio.file.Files.copy(java.nio.file.Path.of(backup.path("BackupPath").asText()),corrupt,java.nio.file.StandardCopyOption.REPLACE_EXISTING);java.nio.file.Files.copy(java.nio.file.Path.of(backup.path("ManifestPath").asText()),java.nio.file.Path.of(corrupt+".json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);java.nio.file.Files.write(corrupt,new byte[]{0},java.nio.file.StandardOpenOption.APPEND);
  powerShell("reject-corrupt",restoreScript+" -Container stayos-postgres-test-1 -Database stayos_restore_corrupt -ConfirmTarget stayos_restore_corrupt -BackupPath "+quote(corrupt.toString()),false);
  powerShell("backup-failure","& "+quote(scripts.resolve("backup-database.ps1").toString())+" -Container stayos-postgres-test-1 -Database stayos_missing -OutputDirectory "+quote(evidence("backups").toString()),false);
  int childPort;try(var socket=new java.net.ServerSocket(0)){childPort=socket.getLocalPort();}
  Process app=null;boolean paused=false;
  try {
   app=child(database,childPort,"restore-first-start");ready(app,childPort);var session=childLogin(childPort,"FRONT_DESK");var restoredStay=ok(session.get("/api/stays/"+f.stay().path("id").asLong()));assertThat(restoredStay.path("roomChanges")).hasSize(1);assertThat(restoredStay.path("guests")).isEqualTo(f.stay().path("guests"));
   app.destroy();assertThat(app.waitFor(15,TimeUnit.SECONDS)).isTrue();app=child(database,childPort,"restore-second-start");ready(app,childPort);
   assertThat(session.get("/api/stays").statusCode()).isEqualTo(401);session=childLogin(childPort,"FRONT_DESK");assertThat(ok(session.request("POST","/api/stays",input,request)).path("id")).isEqualTo(activeStay.path("id"));
   // Only the isolated test database container is paused; development PostgreSQL remains running.
   assertThat(docker("pause","stayos-postgres-test-1")).isZero();paused=true;var unavailable=session.get("/api/room-status");assertThat(unavailable.statusCode()).isEqualTo(503);assertThat(unavailable.body()).doesNotContain("jdbc:","Exception","password","SQL");
   assertThat(docker("unpause","stayos-postgres-test-1")).isZero();paused=false;
   boolean recovered=false;for(int i=0;i<30;i++) {try {if(session.get("/api/room-status").statusCode()==200){recovered=true;break;}}catch(Exception ignored){}Thread.sleep(200);}assertThat(recovered).isTrue();
   // Block one room so the child's server-side statement timeout rejects the write atomically.
   try(var lock=restored(database)) {lock.setAutoCommit(false);try(var s=lock.prepareStatement("select id from rooms where id=? for update")){s.setLong(1,active);s.executeQuery().close();}
    var out=session.post("/api/stays/"+activeStay.path("id").asLong()+"/check-out",Map.of("segmentId",activeStay.path("segmentId").asLong()));assertThat(out.statusCode()).isEqualTo(503);assertThat(out.body()).doesNotContain("SQL","Exception");lock.rollback();}
   assertThat(ok(session.get("/api/stays/"+activeStay.path("id").asLong())).path("status").asText()).isEqualTo("IN_HOUSE");
   // A deferred constraint trigger fails at COMMIT, after every business statement ran.
   try(var c=restored(database);var s=c.createStatement()) {s.execute("create function s8_commit_failure() returns trigger language plpgsql as $$ begin if new.action='CHECK_OUT' then raise exception 'isolated commit failure'; end if; return new; end $$");s.execute("create constraint trigger s8_commit_fault after insert on audit_logs deferrable initially deferred for each row execute function s8_commit_failure()");}
   String outKey=key();var outBody=Map.of("segmentId",activeStay.path("segmentId").asLong());assertThat(session.request("POST","/api/stays/"+activeStay.path("id").asLong()+"/check-out",outBody,outKey).statusCode()).isEqualTo(500);
   assertThat(ok(session.get("/api/stays/"+activeStay.path("id").asLong())).path("status").asText()).isEqualTo("IN_HOUSE");
   try(var c=restored(database);var s=c.createStatement()) {s.execute("drop trigger s8_commit_fault on audit_logs");s.execute("drop function s8_commit_failure()");}
   ok(session.request("POST","/api/stays/"+activeStay.path("id").asLong()+"/check-out",outBody,outKey));
   var original=ok(session.get("/api/stays/"+f.stay().path("id").asLong()));assertThat(original.path("roomSegments")).hasSize(2);assertThat(original.path("status").asText()).isEqualTo("CHECKED_OUT");
   save("recovery.json",facts("backupSeconds",backup.path("Seconds").asDouble(),"restoreSeconds",restore.path("Seconds").asDouble(),"tableFingerprintsCompared",before.size(),"guestKeyId","v1","correctKeyDecrypted",true,"phoneDecrypted",true,"wrongKeyRejected",true,"missingKeyRejected",true,"processRestart",true,"oldSessionRejected",true,"persistedIdempotency",true,"databaseOutageRecovered",true,"queryTimeoutRollback",true,"commitFailureRollbackAndRetry",true));
  } finally {if(paused) docker("unpause","stayos-postgres-test-1");if(app!=null) {app.destroy();if(!app.waitFor(15,TimeUnit.SECONDS))app.destroyForcibly();}}
 }
 @Test void systemBrowserAcceptanceUsesIsolatedPostgres() throws Exception {
  Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Enable with -Dstayos.e2e=true");flow();
  var fixtures=new LinkedHashMap<String,Object>();
  for(String name:List.of("ASSIGN_A","ASSIGN_B","REPORT_RETRY","REPORT_INVALID","MOBILE_CHECKOUT","MOBILE_CHANGE","MOBILE_TARGET")) {
   long id=room();String number="S8_"+name;db.update("update rooms set room_number=?,cleaning_status=? where id=?",number,name.startsWith("ASSIGN")?"DIRTY":"CLEAN",id);fixtures.put(name,Map.of("id",id,"number",number));
  }
  var directory=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("frontend").toFile();
  var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","system.spec.ts").directory(directory).redirectErrorStream(true).redirectOutput(evidence("system-browser.log").toFile());
  builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);builder.environment().put("STAYOS_SYSTEM_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_SYSTEM_STAFF_ID",ids.get("HOUSEKEEPING_STAFF").toString());builder.environment().put("STAYOS_E2E_REPORT","../.local/phase8/playwright-system-results.json");
  var p=builder.start();if(!p.waitFor(600,TimeUnit.SECONDS)){p.descendants().forEach(ProcessHandle::destroyForcibly);p.destroyForcibly();fail("System browser timeout");}assertThat(p.exitValue()).as("See private system-browser.log").isZero();
 }
 Map<String,Object> facts(Object... pairs) {var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);return result;}
 String decrypt(String value,byte[] key) throws Exception {return decrypt(value,key,"document");}
 String decrypt(String value,byte[] key,String field) throws Exception {
  var parts=value.split("\\.");var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,Base64.getDecoder().decode(parts[1])));cipher.updateAAD(("StayOS/v1/"+field).getBytes(java.nio.charset.StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),java.nio.charset.StandardCharsets.UTF_8);
 }
}
