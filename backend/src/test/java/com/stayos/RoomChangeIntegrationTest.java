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
class RoomChangeIntegrationTest {
 @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
 @LocalServerPort int port;
 final Map<String,Long> ids=new LinkedHashMap<>();final String password=UUID.randomUUID().toString();long type;
 static final String OWN="select id from users where username like 'roomchange_test_%'";
 @BeforeAll void setup() {
  assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");StayTestFacts.restore(db);
  for(String role:List.of("ADMIN","HOTEL_MANAGER","FRONT_DESK","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
   long id=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,"roomchange_test_"+role.toLowerCase(Locale.ROOT),passwords.encode(password),"换房测试"+role);ids.put(role,id);users.addRole(id,role);
  }
  type=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('S7_TYPE','虚构换房房型',1) returning id",Long.class);
 }
 @BeforeEach void clean() {
  for(String table:List.of("room_changes","stay_room_segments","housekeeping_tasks","housekeeping_task_logs","audit_logs","idempotency_requests","rooms")) {
   db.execute("drop trigger if exists s7_fail on "+table);
  }
  db.execute("drop function if exists s7_fail_fn()");
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
  db.update("delete from rooms where room_number like 'S7_%'");db.update("delete from audit_logs where operator_id in("+OWN+")");StayTestFacts.restore(db);
 }
 @AfterAll void after() { clean();db.update("delete from room_types where id=?",type);for(long id:ids.values()) { db.update("delete from user_roles where user_id=?",id);db.update("delete from users where id=?",id); } }
 String key() { return UUID.randomUUID().toString(); }
 long room() { return db.queryForObject("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,7,?,'CLEAN') returning id",Long.class,"S7_"+key().substring(0,8).toUpperCase(Locale.ROOT),type); }
 final class Browser {
  final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
  HttpResponse<String> request(String method,String path,Object body,String key) throws Exception {
   var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
   if(!method.equals("GET")) { var c=ok(get("/api/auth/csrf"));r.header(c.path("headerName").asText(),c.path("token").asText()); }
   if(key!=null) r.header("Idempotency-Key",key);
   return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  }
  HttpResponse<String> get(String path) throws Exception { return request("GET",path,null,null); }
  HttpResponse<String> post(String path,Object body) throws Exception { return request("POST",path,body,key()); }
  HttpResponse<String> change(JsonNode stay,long target,String key) throws Exception { return request("POST",path(stay),body(stay,target),key); }
 }
 Browser login(String role) throws Exception { var b=new Browser();ok(b.request("POST","/api/auth/login",Map.of("username","roomchange_test_"+role.toLowerCase(Locale.ROOT),"password",password),null));return b; }
 JsonNode ok(HttpResponse<String> r) throws Exception { assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json.readTree(r.body()).path("data"); }
 String path(JsonNode stay) { return "/api/stays/"+stay.path("id").asLong()+"/room-changes"; }
 Map<String,Object> body(JsonNode stay,long target) { return Map.of("segmentId",stay.path("segmentId").asLong(),"targetRoomId",target,"reasonType","GUEST_REQUEST","reason","虚构住客要求调整房间"); }
 Map<String,Object> inBody(long room) { return Map.of("roomId",room,"expectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString(),"guests",List.of(Map.of("name","虚构换房住客","documentType","OTHER","documentNumber","S7-FICTIONAL-001","primary",true),Map.of("name","虚构同行住客","documentType","OTHER","documentNumber","S7-FICTIONAL-002","primary",false)),"notes",""); }
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
 @Test void realChangePreservesStayGuestsTimesAndCreatesExactLinkedCleaningFact() throws Exception {
  var b=login("FRONT_DESK");long a=room(),target=room();var s=checkIn(a);var before=db.queryForMap("select * from stay_records where id=?",s.path("id").asLong());
  var guests=db.queryForList("select * from stay_guests where stay_record_id=? order by position",s.path("id").asLong());var start=Instant.now().minusSeconds(1);
  var r=ok(b.change(s,target,key()));assertThat(OffsetDateTime.parse(r.path("changedAt").asText()).toInstant()).isBetween(start,Instant.now().plusSeconds(1));
  assertThat(db.queryForMap("select * from stay_records where id=?",s.path("id").asLong())).isEqualTo(before);
  assertThat(db.queryForList("select * from stay_guests where stay_record_id=? order by position",s.path("id").asLong())).isEqualTo(guests);
  assertThat(db.queryForObject("select a.exited_at=b.entered_at and a.end_reason='ROOM_CHANGE' from stay_room_segments a,stay_room_segments b where a.id=? and b.id=?",Boolean.class,r.path("oldSegmentId").asLong(),r.path("newSegmentId").asLong())).isTrue();
  assertThat(db.queryForObject("select count(*) from stay_room_segments where stay_record_id=? and exited_at is null",Integer.class,s.path("id").asLong())).isEqualTo(1);
  assertThat(roomView(b,a).path("occupied").asBoolean()).isFalse();assertThat(roomView(b,a).path("cleaningStatus").asText()).isEqualTo("DIRTY");
  assertThat(roomView(b,target).path("occupied").asBoolean()).isTrue();assertThat(roomView(b,target).path("cleaningStatus").asText()).isEqualTo("CLEAN");
  var task=db.queryForMap("select * from housekeeping_tasks where id=?",r.path("taskId").asLong());assertThat(task).containsEntry("source","ROOM_CHANGE").containsEntry("source_id",r.path("id").asLong()).containsEntry("room_change_id",r.path("id").asLong()).containsEntry("room_segment_id",s.path("segmentId").asLong()).containsEntry("stay_record_id",s.path("id").asLong());
  assertThat(ok(b.get("/api/stays")).path("items").get(0).path("roomId").asLong()).isEqualTo(target);assertThat(detail(b,s).path("guests")).isEqualTo(s.path("guests"));
  assertThat(detail(b,s).path("roomSegments")).hasSize(2);assertThat(detail(b,s).path("roomChanges")).hasSize(1);
  assertThat(ok(login("HOUSEKEEPING_MANAGER").get("/api/housekeeping-tasks/assignment-rooms?assigneeId="+ids.get("HOUSEKEEPING_STAFF"))).path("items").toString()).contains("AVAILABLE_TASK");
  assertThat(r.toString()).doesNotContain("document","phone","ciphertext");
 }
 @ParameterizedTest @ValueSource(strings={"OCCUPIED","DIRTY","CLEANING","INSPECTION","TASK","CRITICAL","STOP","DISABLED"})
 void targetRejectsEverySharedUnavailableCondition(String state) throws Exception {
  var b=login("FRONT_DESK");var m=login("HOUSEKEEPING_MANAGER");var s=checkIn(room());long t=room();
  switch(state) {
   case "OCCUPIED" -> checkIn(t);
   case "DIRTY","CLEANING","INSPECTION" -> db.update("update rooms set cleaning_status=? where id=?",state,t);
   case "TASK" -> { db.update("update rooms set cleaning_status='DIRTY' where id=?",t);ok(m.post("/api/housekeeping-tasks/manual",Map.of("roomId",t,"reason","测试夹具")));db.update("update rooms set cleaning_status='CLEAN' where id=?",t); }
   case "CRITICAL" -> issue(m,t,"CRITICAL");case "STOP" -> stop(m,t);case "DISABLED" -> db.update("update rooms set enabled=false where id=?",t);
   default -> throw new IllegalStateException();
  }
  assertThat(b.change(s,t,key()).statusCode()).isEqualTo(409);
  var list=ok(b.get("/api/stays/"+s.path("id").asLong()+"/room-change/available-rooms?roomNumber="+roomView(b,t).path("roomNumber").asText()));assertThat(list.path("total").asInt()).isZero();
 }
 @ParameterizedTest @ValueSource(strings={"ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF"})
 void fiveRoleHttpAuthorization(String role) throws Exception {
  var s=checkIn(room());long t=room();var b=login(role);boolean allowed=Set.of("FRONT_DESK","HOTEL_MANAGER").contains(role);
  assertThat(b.get(path(s)).statusCode()).isEqualTo(allowed?200:403);
  assertThat(b.get("/api/stays/"+s.path("id").asLong()+"/room-change/available-rooms").statusCode()).isEqualTo(allowed?200:403);
  assertThat(b.change(s,t,key()).statusCode()).isEqualTo(allowed?200:403);
  assertThat(b.get("/api/stays/room-changes/result/"+key()).statusCode()).isEqualTo(allowed?404:403);
 }
 @Test void unauthenticatedAndCsrfRequestsAreRejected() throws Exception {
  var s=checkIn(room());var b=new Browser();assertThat(b.change(s,room(),key()).statusCode()).isEqualTo(401);
  var front=login("FRONT_DESK");var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path(s))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(s,room())))).build();assertThat(front.http.send(r,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
 }
 @Test void sameRoomMissingRoomStayAndInvalidInputRejected() throws Exception {
  var b=login("FRONT_DESK");var s=checkIn(room());assertThat(b.change(s,s.path("roomId").asLong(),key()).statusCode()).isEqualTo(409);
  assertThat(b.change(s,99999999,key()).statusCode()).isEqualTo(404);
  for(var change:List.of(Map.of("reason",""),Map.of("reasonType","BAD"),Map.of("reason","x".repeat(1001)),Map.of("reason","bad\u0001"),Map.of("segmentId",0),Map.of("targetRoomId",0),Map.of("reasonType","GUEST_REQUEST","issueId",1))) {
   var data=new HashMap<String,Object>(body(s,room()));data.putAll(change);assertThat(b.post(path(s),data).statusCode()).isEqualTo(400);
  }
  assertThat(b.post("/api/stays/99999999/room-changes",body(s,room())).statusCode()).isEqualTo(404);
  assertThat(b.request("POST",path(s),body(s,room()),null).statusCode()).isEqualTo(400);
 }
 @Test void oldSegmentAndCheckedOutStayRejected() throws Exception {
  var b=login("FRONT_DESK");var s=checkIn(room());ok(b.change(s,room(),key()));assertThat(b.change(s,room(),key()).statusCode()).isEqualTo(409);
  assertThat(b.post("/api/stays/"+s.path("id").asLong()+"/check-out",Map.of("segmentId",s.path("segmentId").asLong())).statusCode()).isEqualTo(409);
  var current=detail(b,s);ok(b.post("/api/stays/"+s.path("id").asLong()+"/check-out",Map.of("segmentId",current.path("segmentId").asLong())));
  assertThat(b.change(current,room(),key()).statusCode()).isEqualTo(409);
 }
 @Test void multipleChangesAndFinalCheckoutKeepAllHistoryAndSnapshotNumbers() throws Exception {
  var b=login("FRONT_DESK");long a=room(),t=room(),last=room();var original=checkIn(a);var first=ok(b.change(original,t,key()));var current=detail(b,original);
  var second=ok(b.change(current,last,key()));var latest=detail(b,original);ok(b.post("/api/stays/"+original.path("id").asLong()+"/check-out",Map.of("segmentId",latest.path("segmentId").asLong())));
  var result=detail(b,original);assertThat(result.path("status").asText()).isEqualTo("CHECKED_OUT");assertThat(result.path("roomId").asLong()).isEqualTo(last);assertThat(result.path("roomSegments")).hasSize(3);
  var history=ok(b.get(path(original)));assertThat(history).hasSize(2);assertThat(history.get(0).path("id")).isEqualTo(first.path("id"));assertThat(history.get(1).path("id")).isEqualTo(second.path("id"));
  assertThat(db.queryForObject("select count(*) from housekeeping_tasks where source='ROOM_CHANGE'",Integer.class)).isEqualTo(2);
  assertThat(db.queryForObject("select room_id from housekeeping_tasks where source='CHECKOUT'",Long.class)).isEqualTo(last);
  db.update("update rooms set room_number='S7_RENAMED' where id=?",a);assertThat(ok(b.get(path(original))).get(0).path("oldRoomNumber")).isEqualTo(first.path("oldRoomNumber"));
 }
 @Test void linkedIssueMustBeRealAndFromSourceButIsNotAutomaticallyModified() throws Exception {
  var m=login("HOUSEKEEPING_MANAGER");var b=login("FRONT_DESK");var s=checkIn(room());long target=room(),i=issue(m,target,"LOW");
  var body=new HashMap<String,Object>(body(s,target));body.put("reasonType","ROOM_ISSUE");body.put("issueId",i);assertThat(b.post(path(s),body).statusCode()).isEqualTo(409);
  long own=issue(m,s.path("roomId").asLong(),"CRITICAL"),stop=stop(m,s.path("roomId").asLong());body.put("issueId",own);ok(b.post(path(s),body));
  assertThat(ok(m.get("/api/room-issues/"+own)).path("status").asText()).isEqualTo("OPEN");assertThat(roomView(b,s.path("roomId").asLong()).path("maintenanceStatus").asText()).isEqualTo("OUT_OF_SERVICE");
  assertThat(db.queryForObject("select resumed_at is null from room_maintenance_stops where id=?",Boolean.class,stop)).isTrue();
 }
 @Test void sourceWithConflictingOpenTaskRefusesAndRollsBack() throws Exception {
  var b=login("FRONT_DESK");var s=checkIn(room());long target=room();
  db.update("insert into housekeeping_tasks(room_id,task_type,source,notes,created_by) values(?,'MANUAL_CLEANING','MANUAL','隔离库异常数据夹具',?)",s.path("roomId").asLong(),ids.get("HOUSEKEEPING_MANAGER"));
  assertThat(b.change(s,target,key()).statusCode()).isEqualTo(409);intact(s,target);
 }
 @Test void crossTypeChangeDoesNotAlterGuestsOrEnforceSuggestedOccupancy() throws Exception {
  var s=checkIn(room());long t=room();long type2=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('S7_OTHER','虚构商务房',1) returning id",Long.class);
  try { db.update("update rooms set room_type_id=? where id=?",type2,t);var b=login("FRONT_DESK");var r=ok(b.change(s,t,key()));assertThat(r.path("newRoomTypeName").asText()).isEqualTo("虚构商务房");assertThat(detail(b,s).path("guestCount").asInt()).isEqualTo(2); }
  finally { clean();db.update("delete from room_types where id=?",type2); }
 }
 @Test void exactRetryAfterLaterChangesAndCheckoutReturnsOriginalSnapshotAndActorOnlyReceipt() throws Exception {
  var b=login("FRONT_DESK");var s=checkIn(room());long t=room();String key=key();var first=ok(b.change(s,t,key));var next=detail(b,s);ok(b.change(next,room(),key()));next=detail(b,s);ok(b.post("/api/stays/"+s.path("id").asLong()+"/check-out",Map.of("segmentId",next.path("segmentId").asLong())));
  assertThat(ok(b.change(s,t,key))).isEqualTo(first);assertThat(ok(b.get("/api/stays/room-changes/result/"+key))).isEqualTo(first);assertThat(login("HOTEL_MANAGER").get("/api/stays/room-changes/result/"+key).statusCode()).isEqualTo(404);
  assertThat(db.queryForObject("select count(*) from room_changes",Integer.class)).isEqualTo(2);
 }
 @ParameterizedTest @ValueSource(strings={"targetRoomId","reason","reasonType","issueId"}) void sameKeyDifferentContentRejected(String field) throws Exception {
  var b=login("FRONT_DESK");var s=checkIn(room());long t=room();String key=key();ok(b.change(s,t,key));var changed=new HashMap<String,Object>(body(s,t));
  changed.put(field,switch(field) { case "targetRoomId" -> room();case "reason" -> "另一说明";case "reasonType" -> "OTHER";default -> 999L; });
  if(field.equals("issueId")) changed.put("reasonType","ROOM_ISSUE");
  var response=b.request("POST",path(s),changed,key);assertThat(response.statusCode()).isEqualTo(409);assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("IDEMPOTENCY_MISMATCH");
 }
 @Test void expiredKeyIsNeverReexecutedButReceiptRemainsQueryable() throws Exception {
  var b=login("FRONT_DESK");var s=checkIn(room());long t=room();String key=key();var r=ok(b.change(s,t,key));db.update("update idempotency_requests set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where request_key=cast(? as uuid)",key);
  var response=b.change(s,t,key);assertThat(response.statusCode()).isEqualTo(409);assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");assertThat(ok(b.get("/api/stays/room-changes/result/"+key))).isEqualTo(r);
 }
 @ParameterizedTest @ValueSource(strings={"room_changes","stay_room_segments","rooms","housekeeping_tasks","housekeeping_task_logs","audit_logs","idempotency_requests"})
 void actualDatabaseFailureRollsBackEveryFactAndOriginalKeyCanRetry(String table) throws Exception {
  var b=login("FRONT_DESK");var s=checkIn(room());long t=room();String key=key();String event=Set.of("rooms","idempotency_requests").contains(table)?"update":"insert";
  db.execute("create function s7_fail_fn() returns trigger language plpgsql as $$ begin raise exception 'isolated phase7 fault'; end $$");db.execute("create trigger s7_fail before "+event+" on "+table+" for each row execute function s7_fail_fn()");
  try { assertThat(b.change(s,t,key).statusCode()).isEqualTo(500);intact(s,t); }
  finally { db.execute("drop trigger s7_fail on "+table);db.execute("drop function s7_fail_fn()"); }
  ok(b.change(s,t,key));assertThat(db.queryForObject("select count(*) from room_changes",Integer.class)).isEqualTo(1);
 }
 List<HttpResponse<String>> race(Callable<HttpResponse<String>> a,Callable<HttpResponse<String>> b) throws Exception {
  var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try { var first=pool.submit(()->{ready.countDown();go.await();return a.call();});var second=pool.submit(()->{ready.countDown();go.await();return b.call();});assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)); }
  finally { pool.shutdownNow(); }
 }
 void oneWinner(List<HttpResponse<String>> rs) { assertThat(rs.stream().map(HttpResponse::statusCode).sorted().toList()).isEqualTo(List.of(200,409)); }
 @Test void concurrentSameKeyProducesOneChangeAndSameResult() throws Exception {
  var a=login("FRONT_DESK");var b=login("FRONT_DESK");var s=checkIn(room());long t=room();String key=key();var rs=race(()->a.change(s,t,key),()->b.change(s,t,key));assertThat(ok(rs.get(0))).isEqualTo(ok(rs.get(1)));assertThat(db.queryForObject("select count(*) from room_changes",Integer.class)).isEqualTo(1);
 }
 @Test void differentKeysSameStayHaveOnlyOneWinner() throws Exception {
  var a=login("FRONT_DESK");var b=login("HOTEL_MANAGER");var s=checkIn(room());long x=room(),y=room();oneWinner(race(()->a.change(s,x,key()),()->b.change(s,y,key())));
  assertThat(db.queryForObject("select count(*) from stay_room_segments where exited_at is null",Integer.class)).isEqualTo(1);
 }
 @Test void twoStaysCompeteForOneTarget() throws Exception {
  var a=login("FRONT_DESK");var b=login("HOTEL_MANAGER");var s=checkIn(room());var other=checkIn(room());long target=room();oneWinner(race(()->a.change(s,target,key()),()->b.change(other,target,key())));
  assertThat(db.queryForObject("select count(*) from stay_room_segments where room_id=? and exited_at is null",Integer.class,target)).isEqualTo(1);
 }
 @Test void checkoutAndChangeCompeteOnCurrentStay() throws Exception {
  var a=login("FRONT_DESK");var b=login("HOTEL_MANAGER");var s=checkIn(room());long target=room();oneWinner(race(()->a.change(s,target,key()),()->b.post("/api/stays/"+s.path("id").asLong()+"/check-out",Map.of("segmentId",s.path("segmentId").asLong()))));
  assertThat(db.queryForObject("select count(*) from stay_room_segments where exited_at is null",Integer.class)).isBetween(0,1);
 }
 @Test void newCheckInAndRoomChangeCompeteForTarget() throws Exception {
  var a=login("FRONT_DESK");var b=login("HOTEL_MANAGER");var s=checkIn(room());long target=room();oneWinner(race(()->a.change(s,target,key()),()->b.post("/api/stays",inBody(target))));
 }
 @Test void reciprocalRoomRequestsDoNotDeadlockAndBothOccupiedTargetsRejected() throws Exception {
  var a=login("FRONT_DESK");var b=login("HOTEL_MANAGER");var s=checkIn(room());var other=checkIn(room());var rs=race(()->a.change(s,other.path("roomId").asLong(),key()),()->b.change(other,s.path("roomId").asLong(),key()));
  assertThat(rs.stream().map(HttpResponse::statusCode)).containsExactly(409,409);
 }
 @Test void criticalIssueAndRoomChangeRaceHasConsistentOccupancyAndNeverEndsStay() throws Exception {
  var a=login("FRONT_DESK");var m=login("HOUSEKEEPING_MANAGER");var s=checkIn(room());long target=room();var rs=race(()->a.change(s,target,key()),()->m.post("/api/room-issues",Map.of("roomId",target,"issueType","SAFETY","severity","CRITICAL","description","虚构严重异常")));
  assertThat(rs.get(0).statusCode()).isIn(200,409);ok(rs.get(1));assertThat(roomView(a,target).path("maintenanceStatus").asText()).isEqualTo("OUT_OF_SERVICE");assertThat(detail(a,s).path("status").asText()).isEqualTo("IN_HOUSE");
  assertThat(roomView(a,target).path("occupied").asBoolean()).isEqualTo(rs.get(0).statusCode()==200);
 }
 @Test void committedCriticalIssueBeforeWaitingRoomChangeIsObservedAfterRoomLock() throws Exception {
  var a=login("FRONT_DESK");var s=checkIn(room());long target=room();var pool=Executors.newSingleThreadExecutor();
  try(var connection=Objects.requireNonNull(db.getDataSource()).getConnection()) {
   connection.setAutoCommit(false);try(var lock=connection.prepareStatement("select id from rooms where id=? for update")) { lock.setLong(1,target);lock.executeQuery().close(); }
   var future=pool.submit(()->a.change(s,target,key()));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean waiting=false;
   while(System.nanoTime()<deadline && !(waiting=db.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%from rooms where id=%for update%')",Boolean.class))) Thread.sleep(25);
   assertThat(waiting).as("Actual PostgreSQL room-lock waiter").isTrue();
   // Only isolated concurrency fixture SQL: commit a blocking fact while holding the real room lock.
   try(var insert=connection.prepareStatement("insert into room_issues(room_id,issue_type,severity,description,reported_by,reported_at) values(?,'SAFETY','CRITICAL','隔离并发夹具',?,now())")) { insert.setLong(1,target);insert.setLong(2,ids.get("HOUSEKEEPING_MANAGER"));insert.executeUpdate(); }
   connection.commit();assertThat(future.get(25,TimeUnit.SECONDS).statusCode()).isEqualTo(409);intact(s,target);
  } finally { pool.shutdownNow(); }
 }
 void finish(Browser m,Browser staff,long room,long task) throws Exception {
  ok(m.post("/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",ids.get("HOUSEKEEPING_STAFF"),"roomIds",List.of(room),"reason","换房后清洁")));
  var t=ok(staff.get("/api/housekeeping-tasks/"+task));ok(staff.post("/api/housekeeping-tasks/"+task+"/start",Map.of("version",t.path("version").asLong())));t=ok(staff.get("/api/housekeeping-tasks/"+task));ok(staff.post("/api/housekeeping-tasks/"+task+"/complete",Map.of("version",t.path("version").asLong(),"reason","清洁完成")));
 }
 @Test void fullHttpIssueRoomChangeBatchCleaningRestoreAndFinalCheckoutLoop() throws Exception {
  var b=login("FRONT_DESK");var m=login("HOUSEKEEPING_MANAGER");var staff=login("HOUSEKEEPING_STAFF");long a=room(),t=room();var s=checkIn(a);long i=issue(m,a,"CRITICAL"),stop=stop(m,a);
  var body=new HashMap<String,Object>(body(s,t));body.put("reasonType","ROOM_ISSUE");body.put("issueId",i);var r=ok(b.post(path(s),body));finish(m,staff,a,r.path("taskId").asLong());
  assertThat(roomView(b,a).path("cleaningStatus").asText()).isEqualTo("CLEAN");assertThat(roomView(b,a).path("canCheckIn").asBoolean()).isFalse();
  ok(m.post("/api/room-issues/"+i+"/resolve",Map.of("version",0,"reason","故障已排除")));assertThat(roomView(b,a).path("canCheckIn").asBoolean()).isFalse();
  ok(m.post("/api/rooms/"+a+"/maintenance-resume",Map.of("stopId",stop,"reason","维修完成")));assertThat(roomView(b,a).path("canCheckIn").asBoolean()).isTrue();checkIn(a);
  var current=detail(b,s);var out=ok(b.post("/api/stays/"+s.path("id").asLong()+"/check-out",Map.of("segmentId",current.path("segmentId").asLong())));assertThat(out.path("roomId").asLong()).isEqualTo(t);finish(m,staff,t,out.path("taskId").asLong());
  assertThat(roomView(b,t).path("canCheckIn").asBoolean()).isTrue();var history=detail(b,s);assertThat(history.path("roomSegments")).hasSize(2);assertThat(history.path("roomChanges")).hasSize(1);
  assertThat(history.path("guests")).isEqualTo(s.path("guests"));assertThat(db.queryForObject("select source from housekeeping_tasks where id=?",String.class,out.path("taskId").asLong())).isEqualTo("CHECKOUT");
 }
 @Test void roomChangeBrowserTestsUseOnlyIsolatedPostgres() throws Exception {
  Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Enable with -Dstayos.e2e=true");
  var fixtures=new LinkedHashMap<String,Map<String,Object>>();
  for(String name:List.of("normal","twice","linked","stale","empty","validation","network","refresh","mobile","tasks","ended")) {
   var rooms=new LinkedHashMap<String,Object>();
   for(String part:List.of("a","b","c")) { long id=room();String number="S7_UI_"+name.toUpperCase(Locale.ROOT)+"_"+part.toUpperCase(Locale.ROOT);db.update("update rooms set room_number=? where id=?",number,id);rooms.put(part,Map.of("id",id,"number",number)); }
   fixtures.put(name,rooms);
  }
  var directory=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("frontend").toFile();var log=directory.toPath().getParent().resolve(".local/room-change-browser-run.log");java.nio.file.Files.createDirectories(log.getParent());
  var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","room-changes.spec.ts").directory(directory).redirectErrorStream(true).redirectOutput(log.toFile());
  builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);builder.environment().put("STAYOS_CHANGE_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_CHANGE_STAFF_ID",ids.get("HOUSEKEEPING_STAFF").toString());builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-room-changes-results.json");
  var process=builder.start();if(!process.waitFor(500,TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Room change browser tests timed out"); }assertThat(process.exitValue()).as("Playwright details: "+log).isZero();
 }
}
