package com.stayos;

import com.fasterxml.jackson.databind.*;
import com.stayos.staff.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoomIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired UserMapper users;
    @Autowired ObjectMapper json;
    @Autowired com.stayos.room.service.RoomCatalogService catalog;
    final String password=UUID.randomUUID().toString();
    final Map<String,Long> ids=new HashMap<>();
    HttpBrowser admin;

    @BeforeAll void setup() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        for (String role: List.of("ADMIN","HOTEL_MANAGER","FRONT_DESK","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=jdbc.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",
                Long.class,"room_test_"+role.toLowerCase(Locale.ROOT),passwords.encode(password),"房间测试"+role);
            ids.put(role,id); users.addRole(id,role);
        }
        // Test-only facts in the TEST database; never migrated into a developer/production database.
        jdbc.execute("""
            create table test_room_facts (
                room_id bigint primary key references rooms(id) on delete cascade,
                occupied boolean not null default false,
                has_blocking_issue boolean not null default false,
                has_open_cleaning_task boolean not null default false,
                has_usage_history boolean not null default false
            )
            """);
        jdbc.execute("""
            create or replace view room_operational_facts as
            select r.id room_id, coalesce(f.occupied,false) occupied,
              coalesce(f.has_blocking_issue,false) has_blocking_issue,
              coalesce(f.has_open_cleaning_task,false) has_open_cleaning_task,
              coalesce(f.has_usage_history,false) has_usage_history
            from rooms r left join test_room_facts f on f.room_id=r.id
            """);
        admin=login("ADMIN");
    }
    @BeforeEach void cleanRooms() {
        jdbc.update("delete from rooms where room_number like 'RTEST%'");
        jdbc.update("delete from room_types where type_code like 'RTEST%'");
    }
    @AfterAll void cleanup() {
        StayTestFacts.restore(jdbc);
        jdbc.execute("drop table if exists test_room_facts");
        cleanRooms();
        for (long id:ids.values()) {
            jdbc.update("delete from audit_logs where operator_id=?",id);
            jdbc.update("delete from user_roles where user_id=?",id);
            jdbc.update("delete from users where id=?",id);
        }
    }
    final class HttpBrowser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> request(String method,String path,Object body) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
            if (!method.equals("GET")) {
                var csrf=data(request("GET","/api/auth/csrf",null));
                builder.header(csrf.path("headerName").asText(),csrf.path("token").asText());
            }
            builder.header("Content-Type","application/json");
            return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET",path,null); }
        HttpResponse<String> post(String path,Object body) throws Exception { return request("POST",path,body); }
        HttpResponse<String> put(String path,Object body) throws Exception { return request("PUT",path,body); }
    }
    HttpBrowser login(String role) throws Exception {
        var b=new HttpBrowser();
        assertThat(b.post("/api/auth/login",Map.of("username","room_test_"+role.toLowerCase(Locale.ROOT),"password",password)).statusCode()).isEqualTo(200);
        return b;
    }
    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).get("data"); }
    String code(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("code").asText(); }
    String unique() { return "RTEST"+UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase(Locale.ROOT); }
    Map<String,Object> typeBody(String code) { return new HashMap<>(Map.of("typeCode",code,"typeName","虚构大床房","description","仅测试","bedDescription","1 张大床","suggestedOccupancy",2)); }
    JsonNode createType() throws Exception {
        var response=admin.post("/api/room-types",typeBody(unique()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return data(response);
    }
    Map<String,Object> roomBody(String number,long type) { return new HashMap<>(Map.of("roomNumber",number,"roomTypeId",type,"floor",8,"notes","虚构测试房间")); }
    JsonNode createRoom(long type) throws Exception {
        var response=admin.post("/api/rooms",roomBody(unique(),type));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return data(response);
    }
    void facts(long room,boolean occupied,boolean blocking,boolean task,boolean history) {
        jdbc.update("""
            insert into test_room_facts values(?,?,?,?,?) on conflict(room_id) do update
            set occupied=excluded.occupied,has_blocking_issue=excluded.has_blocking_issue,
            has_open_cleaning_task=excluded.has_open_cleaning_task,has_usage_history=excluded.has_usage_history
            """,room,occupied,blocking,task,history);
    }

    @Test void typeCreateEditDisableAndDuplicateCode() throws Exception {
        var type=createType(); long id=type.path("id").asLong();
        var duplicate=admin.post("/api/room-types",typeBody(type.path("typeCode").asText().toLowerCase(Locale.ROOT)));
        assertThat(duplicate.statusCode()).isEqualTo(409); assertThat(code(duplicate)).isEqualTo("ROOM_TYPE_CODE_EXISTS");
        var edit=typeBody(type.path("typeCode").asText()); edit.put("version",0);edit.put("typeName","改名双床房");
        var changed=admin.put("/api/room-types/"+id,edit);
        assertThat(changed.statusCode()).isEqualTo(200); assertThat(data(changed).path("version").asLong()).isEqualTo(1);
        var disabled=admin.post("/api/room-types/"+id+"/set-enabled",Map.of("version",1,"enabled",false));
        assertThat(disabled.statusCode()).isEqualTo(200); assertThat(data(disabled).path("enabled").asBoolean()).isFalse();
        assertThat(data(admin.get("/api/room-types?enabled=false&search="+type.path("typeCode").asText())).path("total").asLong()).isEqualTo(1);
    }
    @Test void roomCreateEditStableIdDisableAndDuplicateNumber() throws Exception {
        var type=createType(); long typeId=type.path("id").asLong();var room=createRoom(typeId);long id=room.path("id").asLong();
        assertThat(room.path("canCheckIn").asBoolean()).isFalse(); assertThat(room.path("cleaningStatus").asText()).isEqualTo("DIRTY");
        var duplicate=admin.post("/api/rooms",roomBody(room.path("roomNumber").asText().toLowerCase(Locale.ROOT),typeId));
        assertThat(duplicate.statusCode()).isEqualTo(409);assertThat(code(duplicate)).isEqualTo("ROOM_NUMBER_EXISTS");
        var edit=roomBody(unique(),typeId);edit.put("version",0);edit.put("floor",-1);
        var changed=admin.put("/api/rooms/"+id,edit);
        assertThat(changed.statusCode()).isEqualTo(200);assertThat(data(changed).path("id").asLong()).isEqualTo(id);
        assertThat(data(changed).path("floor").asInt()).isEqualTo(-1);
        jdbc.update("update rooms set cleaning_status='CLEAN' where id=?",id);
        var disabled=admin.post("/api/rooms/"+id+"/set-enabled",Map.of("version",1,"enabled",false));
        assertThat(disabled.statusCode()).isEqualTo(200);assertThat(data(disabled).path("canCheckIn").asBoolean()).isFalse();
        assertThat(data(disabled).path("availability").asText()).isEqualTo("DISABLED");
        var filter=new com.stayos.room.dto.RoomFilter(null,null,edit.get("roomNumber").toString(),null,null,null,null,null,null,null,null);
        assertThat(catalog.list(filter).total()).isEqualTo(1);
        var listed=admin.get("/api/rooms?roomNumber="+edit.get("roomNumber"));
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(data(listed).path("total").asLong()).isEqualTo(1);
    }
    @Test void disabledTypeAllowsUnchangedReferencesButRejectsNewBindings() throws Exception {
        var type=createType();long typeId=type.path("id").asLong();var room=createRoom(typeId);
        assertThat(admin.post("/api/room-types/"+typeId+"/set-enabled",Map.of("enabled",false,"version",0)).statusCode()).isEqualTo(200);
        assertThat(admin.post("/api/rooms",roomBody(unique(),typeId)).statusCode()).isEqualTo(409);
        var edit=roomBody(room.path("roomNumber").asText(),typeId);edit.put("version",0);
        assertThat(admin.put("/api/rooms/"+room.path("id").asLong(),edit).statusCode()).isEqualTo(200);
        var other=createRoom(createType().path("id").asLong());
        edit.put("version",0);
        assertThat(admin.put("/api/rooms/"+other.path("id").asLong(),edit).statusCode()).isEqualTo(409);
        assertThat(data(admin.get("/api/rooms/"+room.path("id").asLong())).path("enabled").asBoolean()).isTrue();
    }
    @Test void historyPreventsRenumberButAllowsOtherMetadata() throws Exception {
        var room=createRoom(createType().path("id").asLong());long id=room.path("id").asLong();
        facts(id,false,false,false,true);
        var body=roomBody(unique(),room.path("roomTypeId").asLong());body.put("version",0);
        var rejected=admin.put("/api/rooms/"+id,body);
        assertThat(rejected.statusCode()).isEqualTo(409);assertThat(code(rejected)).isEqualTo("ROOM_NUMBER_HAS_HISTORY");
        body.put("roomNumber",room.path("roomNumber").asText());body.put("notes","历史后仍可更新备注");
        assertThat(admin.put("/api/rooms/"+id,body).statusCode()).isEqualTo(200);
    }
    @Test void occupiedOrUnfinishedTaskPreventsDisabling() throws Exception {
        var room=createRoom(createType().path("id").asLong());long id=room.path("id").asLong();
        for (boolean occupied:List.of(true,false)) {
            facts(id,occupied,false,!occupied,false);
            var result=admin.post("/api/rooms/"+id+"/set-enabled",Map.of("enabled",false,"version",0));
            assertThat(result.statusCode()).isEqualTo(409);assertThat(code(result)).isEqualTo("ROOM_IN_USE");
        }
        assertThat(data(admin.get("/api/rooms/"+id)).path("enabled").asBoolean()).isTrue();
    }
    @Test void invalidInputAndMissingReferencesReturnChineseErrors() throws Exception {
        var type=createType();long typeId=type.path("id").asLong();
        for (String number:List.of("","不允许的房号","A/1")) {
            var response=admin.post("/api/rooms",roomBody(number,typeId));
            assertThat(response.statusCode()).isEqualTo(400); assertThat(response.body()).doesNotContain("org.postgresql","stackTrace");
        }
        var zero=roomBody(unique(),typeId);zero.put("floor",0);assertThat(admin.post("/api/rooms",zero).statusCode()).isEqualTo(400);
        assertThat(admin.post("/api/rooms",roomBody(unique(),999999999L)).statusCode()).isEqualTo(404);
        for(String query:List.of("page=0","pageSize=101","floor=0","floor=abc","availability=HACK","roomTypeId=0"))
            assertThat(admin.get("/api/rooms?"+query).statusCode()).as(query).isEqualTo(400);
        assertThat(admin.get("/api/rooms/999999999").statusCode()).isEqualTo(404);
        assertThat(admin.get("/api/rooms/abc").statusCode()).isEqualTo(400);
    }
    @ParameterizedTest
    @ValueSource(strings={"HOTEL_MANAGER","FRONT_DESK","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF"})
    void nonAdminCanReadButCannotMutate(String role) throws Exception {
        var type=createType();var room=createRoom(type.path("id").asLong());var user=login(role);
        for(String path:List.of("/api/rooms","/api/rooms/"+room.path("id").asLong(),"/api/room-types"))
            assertThat(user.get(path).statusCode()).as(role+" -> "+path).isEqualTo(200);
        assertThat(user.post("/api/room-types",typeBody(unique())).statusCode()).isEqualTo(403);
        assertThat(user.put("/api/room-types/"+type.path("id").asLong(),typeBody(unique())).statusCode()).isEqualTo(403);
        assertThat(user.post("/api/rooms",roomBody(unique(),type.path("id").asLong())).statusCode()).isEqualTo(403);
        var edit=roomBody(room.path("roomNumber").asText(),type.path("id").asLong());edit.put("version",0);
        assertThat(user.put("/api/rooms/"+room.path("id").asLong(),edit).statusCode()).isEqualTo(403);
        assertThat(user.post("/api/rooms/"+room.path("id").asLong()+"/set-enabled",Map.of("enabled",false,"version",0)).statusCode()).isEqualTo(403);
        assertThat(user.post("/api/room-types/"+type.path("id").asLong()+"/set-enabled",Map.of("enabled",false,"version",0)).statusCode()).isEqualTo(403);
    }
    @Test void unauthenticatedRequestsAreRejected() throws Exception {
        var guest=new HttpBrowser();
        for(String path:List.of("/api/rooms","/api/room-types","/api/rooms/1","/api/room-status","/api/room-status/statistics","/api/floors"))
            assertThat(guest.get(path).statusCode()).isEqualTo(401);
        assertThat(guest.post("/api/rooms",roomBody(unique(),1L)).statusCode()).isEqualTo(401);
    }
    @Test void statusRulesCoverEveryCombinationAndKeepIndependentDimensions() throws Exception {
        var room=createRoom(createType().path("id").asLong());long id=room.path("id").asLong();
        int checked=0;
        for(boolean enabled:List.of(false,true))
        for(boolean occupied:List.of(false,true))
        for(boolean blocking:List.of(false,true))
        for(boolean task:List.of(false,true))
        for(String clean:List.of("CLEAN","DIRTY","CLEANING","INSPECTION")) {
            jdbc.update("update rooms set enabled=?,cleaning_status=? where id=?",enabled,clean,id);
            facts(id,occupied,blocking,task,false);
            var state=catalog.detail(id);
            boolean available=enabled && !occupied && !blocking && !task && clean.equals("CLEAN");
            assertThat(state.canCheckIn).as("combination %d",checked).isEqualTo(available);
            String label=!enabled?"DISABLED":occupied?"OCCUPIED":blocking?"OUT_OF_SERVICE":
                clean.equals("CLEANING")?"CLEANING":clean.equals("INSPECTION")?"INSPECTION":
                clean.equals("DIRTY")?"DIRTY":task?"UNAVAILABLE":"VACANT_CLEAN";
            assertThat(state.availability).isEqualTo(label);
            assertThat(state.occupancyStatus).isEqualTo(occupied?"OCCUPIED":"VACANT");
            assertThat(state.maintenanceStatus).isEqualTo(blocking?"OUT_OF_SERVICE":"NORMAL");
            checked++;
        }
        assertThat(checked).isEqualTo(64);
    }
    @Test void statisticsFiltersPaginationAndFloorsReadRealPostgres() throws Exception {
        long typeA=createType().path("id").asLong(),typeB=createType().path("id").asLong();
        var clean=createRoom(typeA);var dirty=createRoom(typeA);var cleaning=createRoom(typeB);
        var inspection=createRoom(typeB);var blocked=createRoom(typeB);var occupied=createRoom(typeA);var disabled=createRoom(typeA);
        for(var room:List.of(clean,blocked,occupied,disabled))
            jdbc.update("update rooms set cleaning_status='CLEAN' where id=?",room.path("id").asLong());
        jdbc.update("update rooms set floor=-1,cleaning_status='CLEANING' where id=?",cleaning.path("id").asLong());
        jdbc.update("update rooms set cleaning_status='INSPECTION' where id=?",inspection.path("id").asLong());
        facts(blocked.path("id").asLong(),false,true,false,false);
        facts(occupied.path("id").asLong(),true,true,false,true);
        jdbc.update("update rooms set enabled=false where id=?",disabled.path("id").asLong());
        var user=login("HOTEL_MANAGER");
        var board=user.get("/api/room-status?pageSize=2");
        assertThat(board.statusCode()).as(board.body()).isEqualTo(200);
        var data=data(board);assertThat(data.path("items").size()).isEqualTo(2);assertThat(data.path("total").asInt()).isEqualTo(7);
        var stats=data.path("statistics");
        assertThat(stats.path("total").asInt()).isEqualTo(7);
        assertThat(stats.path("available").asInt()).isEqualTo(1);
        assertThat(stats.path("occupied").asInt()).isEqualTo(1);
        assertThat(stats.path("dirty").asInt()).isEqualTo(1);
        assertThat(stats.path("cleaning").asInt()).isEqualTo(1);
        assertThat(stats.path("inspection").asInt()).isEqualTo(1);
        assertThat(stats.path("outOfService").asInt()).isEqualTo(2);
        assertThat(stats.path("disabled").asInt()).isEqualTo(1);
        for(String query:List.of("availability=VACANT_CLEAN","availability=INSPECTION","floor=-1&roomTypeId="+typeB,
                "cleaningStatus=DIRTY","canCheckIn=true","occupancyStatus=OCCUPIED","enabled=false")) {
            var result=data(user.get("/api/room-status?"+query));
            assertThat(result.path("total").asInt()).as(query).isEqualTo(1);
            assertThat(result.path("statistics").path("total").asInt()).isEqualTo(1);
        }
        assertThat(data(user.get("/api/room-status?maintenanceStatus=OUT_OF_SERVICE")).path("total").asInt()).isEqualTo(2);
        assertThat(data(user.get("/api/room-status?floor=-1&roomTypeId="+typeA)).path("total").asInt()).isZero();
        var only=data(user.get("/api/room-status?roomNumber="+dirty.path("roomNumber").asText().toLowerCase(Locale.ROOT)));
        assertThat(only.path("items").get(0).path("id").asLong()).isEqualTo(dirty.path("id").asLong());
        var standalone=data(user.get("/api/room-status/statistics"));
        assertThat(standalone).isEqualTo(stats);
        assertThat(data(user.get("/api/floors")).toString()).contains("roomCount", "-1", "8");
        var empty=data(user.get("/api/room-status?roomNumber=NO_SUCH_ROOM"));
        assertThat(empty.path("total").asLong()).isZero();
        assertThat(empty.path("statistics").path("available").asLong()).isZero();
    }
    @Test void publicMutationCannotSetBusinessStatusAndAllRolesCanQueryStatus() throws Exception {
        var type=createType();var body=roomBody(unique(),type.path("id").asLong());
        body.put("cleaningStatus","CLEAN");body.put("occupied",true);body.put("maintenanceStatus","NORMAL");
        var response=admin.post("/api/rooms",body);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(data(response).path("cleaningStatus").asText()).isEqualTo("DIRTY");
        assertThat(data(response).path("occupied").asBoolean()).isFalse();
        for(String role:ids.keySet()) {
            var user=login(role);
            for(String path:List.of("/api/room-status","/api/room-status/statistics","/api/floors"))
                assertThat(user.get(path).statusCode()).as(role+" -> "+path).isEqualTo(200);
        }
        assertThat(admin.post("/api/rooms/"+data(response).path("id").asLong()+"/status",Map.of("cleaningStatus","CLEAN")).statusCode()).isEqualTo(404);
    }
    @Test void simultaneousEditsCannotOverwriteEachOtherAndWritesAreAudited() throws Exception {
        var room=createRoom(createType().path("id").asLong());long id=room.path("id").asLong();
        var a=login("ADMIN");var b=login("ADMIN");var start=new CountDownLatch(1);
        var body=roomBody(room.path("roomNumber").asText(),room.path("roomTypeId").asLong());body.put("version",0);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var f1=pool.submit(()->{start.await();return a.put("/api/rooms/"+id,body).statusCode();});
            var f2=pool.submit(()->{start.await();return b.put("/api/rooms/"+id,body).statusCode();});
            start.countDown();
            assertThat(List.of(f1.get(30,TimeUnit.SECONDS),f2.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_type='ROOM' and target_id=? and action='ROOM_UPDATE' and result='SUCCESS'",Integer.class,id)).isEqualTo(1);
        var stale=admin.post("/api/rooms/"+id+"/set-enabled",Map.of("version",0,"enabled",false));
        assertThat(stale.statusCode()).isEqualTo(409);assertThat(code(stale)).isEqualTo("STALE_VERSION");
    }
    @Test void roomBrowserEndToEndWithRealPostgres() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Browser tests enabled by scripts/test.ps1 -Browser");
        var fixtures=new LinkedHashMap<String,JsonNode>();
        long typeA=createType().path("id").asLong(),typeB=createType().path("id").asLong();
        jdbc.update("update room_types set type_name='测试标准大床房' where id=?",typeA);
        jdbc.update("update room_types set type_name='测试标准双床房' where id=?",typeB);
        for(String key:List.of("clean","dirty","cleaning","inspection","blocked","occupied","disabled")) {
            var room=createRoom(Set.of("clean","dirty","occupied","disabled").contains(key)?typeA:typeB);
            long id=room.path("id").asLong(); fixtures.put(key,room);
            String clean=key.equals("dirty")?"DIRTY":key.equals("cleaning")?"CLEANING":key.equals("inspection")?"INSPECTION":"CLEAN";
            jdbc.update("update rooms set cleaning_status=?,floor=?,enabled=? where id=?",clean,key.equals("cleaning")?-1:8,!key.equals("disabled"),id);
            facts(id,key.equals("occupied"),Set.of("blocked","occupied").contains(key),key.equals("cleaning"),key.equals("occupied"));
        }
        var directory=java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("frontend").toFile();
        var log=directory.toPath().getParent().resolve(".local/room-browser-run.log");
        java.nio.file.Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","rooms.spec.ts")
            .directory(directory).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);
        builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_ROOM_FIXTURES",json.writeValueAsString(fixtures));
        builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-rooms-results.json");
        var process=builder.start();
        if(!process.waitFor(240,TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Room browser tests timed out");
        }
        assertThat(process.exitValue()).as("Room Playwright suite; details: "+log).isZero();
        assertThat(jdbc.queryForMap("select notes,enabled from rooms where room_number='RTEST_UI0801'"))
            .containsEntry("notes","界面修改已进入数据库").containsEntry("enabled",false);
    }
}
