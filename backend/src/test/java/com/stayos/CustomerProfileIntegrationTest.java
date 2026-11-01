package com.stayos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayos.staff.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** V5.0 limited guest profiles: masked boundaries, human-verified matching/merging, history never rewritten. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @TestInstance(TestInstance.Lifecycle.PER_CLASS) @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerProfileIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired UserMapper users;
    @LocalServerPort int port;
    static final String PASSWORD_PROP="stayos.test.customer.password", FIXTURES_PROP="stayos.test.customer.fixtures";
    final String password=UUID.randomUUID().toString();
    final Map<String,Long> ids=new HashMap<>();
    final String documentNumber="110101199001011234", secondDocument="110101199001015678", phone="+8613800138000";
    long roomType=-1, browserStay=-1;
    Browser browser, front;

    @BeforeAll void setup() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        StayTestFacts.restore(jdbc);
        clean();
        for(var role:new String[]{"HOTEL_MANAGER","FRONT_DESK","HOUSEKEEPING_STAFF"}) {
            long id=jdbc.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",
                Long.class,"customer_test_"+role.toLowerCase(Locale.ROOT),passwords.encode(password),"客历测试"+role);
            ids.put(role,id);users.addRole(id,role);
        }
        roomType=jdbc.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values('C5_TYPE','虚构客历测试房型',2) returning id",Long.class);
        for(String number:new String[]{"C5_A","C5_B","C5_C","C5_D"})
            jdbc.update("insert into rooms(room_number,floor,room_type_id,cleaning_status) values(?,5,?,'CLEAN')",number,roomType);
        browser=new Browser("customer_test_hotel_manager");front=new Browser("customer_test_front_desk");
        System.setProperty(PASSWORD_PROP,password);
    }
    @AfterAll void cleanup() {
        clean();
        for(long roomId:ids("C5_")) jdbc.update("delete from stay_room_segments where room_id=?",roomId);
        jdbc.update("delete from rooms where room_number like 'C5_%'");
        jdbc.update("delete from room_types where id=?",roomType);
        for(long id:ids.values()) { jdbc.update("delete from user_roles where user_id=?",id);jdbc.update("delete from users where id=?",id); }
        System.clearProperty(PASSWORD_PROP);System.clearProperty(FIXTURES_PROP);
    }
    private List<Long> ids(String prefix) { return jdbc.queryForList("select id from rooms where room_number like '"+prefix+"%'",Long.class); }
    /** Idempotent full cleanup for this fixture family, rerunnable after failures. */
    void clean() {
        var guests=jdbc.queryForList("select sg.guest_id from stay_guests sg join stay_records s on s.id=sg.stay_record_id where s.created_by in (select id from users where username like 'customer_test_%')",Long.class);
        jdbc.update("delete from idempotency_requests where operator_id in (select id from users where username like 'customer_test_%')");
        jdbc.update("delete from customer_profile_stays where profile_id in (select id from customer_profiles where created_by in (select id from users where username like 'customer_test_%'))");
        jdbc.execute("alter table customer_profiles disable trigger customer_profiles_no_delete");
        jdbc.update("delete from customer_profiles where created_by in (select id from users where username like 'customer_test_%')");
        jdbc.execute("alter table customer_profiles enable trigger customer_profiles_no_delete");
        jdbc.update("delete from stay_room_segments where stay_record_id in (select id from stay_records where created_by in (select id from users where username like 'customer_test_%'))");
        jdbc.update("delete from stay_guests where stay_record_id in (select id from stay_records where created_by in (select id from users where username like 'customer_test_%'))");
        jdbc.update("delete from stay_records where created_by in (select id from users where username like 'customer_test_%')");
        for(long guest:guests) jdbc.update("delete from guests where id=?",guest);
        jdbc.update("delete from audit_logs where operator_id in (select id from users where username like 'customer_test_%')");
    }

    // ---------- helpers ----------
    record Reply(int status,JsonNode body) { String code() { return body.path("code").asText(null); } long dataId() { return body.path("data").path("id").asLong(); } }
    Reply call(Browser who,String method,String path,Object payload) throws Exception { return call(who,method,path,payload,null); }
    Reply call(Browser who,String method,String path,Object payload,String idempotencyKey) throws Exception {
        var response=who.request(method,path,payload,idempotencyKey);
        return new Reply(response.statusCode(), json.readTree(response.body()==null||response.body().isEmpty()?"{}":response.body()));
    }
    Reply check(Browser who,int expectedStatus,String method,String path,Object payload,String key) throws Exception {
        var reply=call(who,method,path,payload,key);
        assertThat(reply.status()).as("%s %s -> %s",method,path,reply.body()).isEqualTo(expectedStatus);
        return reply;
    }
    long checkIn(Browser who,String roomNumber,String primaryName,String primaryDocument,String primaryPhone,String secondaryName) throws Exception {
        long room=jdbc.queryForObject("select id from rooms where room_number=?",Long.class,roomNumber);
        var guests=new ArrayList<Map<String,Object>>();
        guests.add(Map.of("name",primaryName,"documentType","ID_CARD","documentNumber",primaryDocument,"phone",primaryPhone==null?"":primaryPhone,"notes","","primary",true));
        if(secondaryName!=null) guests.add(Map.of("name",secondaryName,"documentType","ID_CARD","documentNumber",secondDocument,"phone","","notes","","primary",false));
        var body=Map.of("roomId",room,"expectedCheckOutAt",OffsetDateTime.now().plusDays(1).toString(),"guests",guests,"notes","");
        return check(who,200,"POST","/api/stays",body,UUID.randomUUID().toString()).dataId();
    }
    long createProfile(Browser who,String name,String document,String phone) throws Exception {
        var body=new HashMap<String,Object>();body.put("name",name);body.put("documentType","ID_CARD");body.put("documentNumber",document);
        if(phone!=null) body.put("phone",phone);else body.put("phone","");
        return profileId(check(who,200,"POST","/api/customer-profiles",body,UUID.randomUUID().toString()));
    }
    /** Create wraps the detail in CreatedProfile.profile; list/detail replies put it directly under data. */
    static long profileId(Reply reply) { return reply.body().path("data").path("profile").path("id").asLong(reply.body().path("data").path("id").asLong()); }
    static long profileVersion(Reply reply) { return reply.body().path("data").path("profile").path("version").asLong(reply.body().path("data").path("version").asLong()); }
    Map<String,Object> linkBody(long stayId,Long guestId) {
        var body=new HashMap<String,Object>();body.put("stayRecordId",stayId);body.put("guestId",guestId);return body;
    }

    class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        final String username;
        Browser(String username) throws Exception { this.username=username; login(); }
        void login() throws Exception {
            var login=Map.of("username",username,"password",password);
            var csrf=data(request("GET","/api/auth/csrf",null,null)).path("data");
            HttpResponse<String> response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
                .header("Content-Type","application/json").header(csrf.path("headerName").asText(),csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(login))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("login %s -> %s",username,response.body()).isEqualTo(200);
        }
        JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
        HttpResponse<String> request(String method,String path,Object payload,String key) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(20));
            if(!method.equals("GET")) {
                var csrf=data(request("GET","/api/auth/csrf",null,null)).path("data");
                builder.header("Content-Type","application/json").header(csrf.path("headerName").asText(),csrf.path("token").asText());
            }
            if(key!=null) builder.header("Idempotency-Key",key);
            builder.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
            return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
    }

    // ---------- 1. lifecycle ----------
    @Test @Order(1) void createEditReplayAndSameNameHint() throws Exception {
        long actor=ids.get("HOTEL_MANAGER");
        var key=UUID.randomUUID().toString();
        var first=check(browser,200,"POST","/api/customer-profiles",Map.of("name","客历张三","documentType","ID_CARD","documentNumber",documentNumber,"phone",phone,"notes",""),key);
        long firstId=profileId(first);
        // Masked boundary: response carries masks and no plaintext ever.
        String body=first.body().toString();
        assertThat(body).contains("11****34").contains("+8****00").doesNotContain(documentNumber).doesNotContain(phone);
        // Replay same key + payload returns the same profile without duplicating rows.
        var replay=check(browser,200,"POST","/api/customer-profiles",Map.of("name","客历张三","documentType","ID_CARD","documentNumber",documentNumber,"phone",phone,"notes",""),key);
        assertThat(profileId(replay)).isEqualTo(firstId);
        // Same key with different content is rejected.
        var mismatch=call(browser,"POST","/api/customer-profiles",Map.of("name","客历李四","documentType","ID_CARD","documentNumber",secondDocument,"phone","","notes",""),key);
        assertThat(mismatch.status()).isEqualTo(409);assertThat(mismatch.code()).isEqualTo("IDEMPOTENCY_MISMATCH");
        // Same-name hint on create: informational only, never an automatic identity decision.
        var second=check(browser,200,"POST","/api/customer-profiles",Map.of("name","客历张三","documentType","ID_CARD","documentNumber",secondDocument,"phone","","notes",""),UUID.randomUUID().toString());
        long secondSameName=profileId(second);
        assertThat(secondSameName).isNotEqualTo(firstId);
        boolean hintFound=false;
        for(var hint:second.body().path("data").path("similarActiveProfiles")) if(hint.path("id").asLong()==firstId) hintFound=true;
        assertThat(hintFound).as("same-name hint lists the first profile").isTrue();
        // Two ACTIVE profiles with identical names coexist: nothing was auto-merged or auto-linked.
        assertThat(jdbc.queryForObject("select count(*) from customer_profiles where display_name='客历张三' and status='ACTIVE'",Integer.class)).isEqualTo(2);
        // Optimistic-lock edit round.
        var edit=check(browser,200,"PUT","/api/customer-profiles/"+firstId,
            Map.of("name","客历张三","documentType","ID_CARD","documentNumber",documentNumber,"phone",phone,"notes","更正备注","version",profileVersion(first)),UUID.randomUUID().toString());
        assertThat(edit.body().path("data").path("notes").asText()).isEqualTo("更正备注");
        var conflict=call(browser,"PUT","/api/customer-profiles/"+firstId,
            Map.of("name","客历张三","documentType","ID_CARD","documentNumber",documentNumber,"phone",phone,"notes","旧版本","version",profileVersion(first)),UUID.randomUUID().toString());
        assertThat(conflict.status()).isEqualTo(409);assertThat(conflict.code()).isEqualTo("VERSION_CONFLICT");
        // Same-name hint is resolved by a human check: opening the sibling profile is recorded as access.
        check(browser,200,"GET","/api/customer-profiles/"+secondSameName,null,null);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where operator_id=? and action='CUSTOMER_PROFILE_VIEW' and target_id=?",Integer.class,actor,secondSameName)).isGreaterThanOrEqualTo(1);
    }

    // ---------- 2. role boundaries ----------
    @Test @Order(2) void roleBoundariesOnProfileOperations() throws Exception {
        // Housekeeping has neither customer:read nor customer:manage.
        Browser housekeeping=new Browser("customer_test_housekeeping_staff");
        var deniedRead=call(housekeeping,"GET","/api/customer-profiles",null,null);
        assertThat(deniedRead.status()).isEqualTo(403);
        // Front desk can read and manage, but merging is separately gated (customer:merge).
        long frontProfile=createProfile(front,"客历前台建档","110101198505051234",null);
        var deniedMerge=call(front,"POST","/api/customer-profiles/"+frontProfile+"/merge",Map.of("targetId",frontProfile),UUID.randomUUID().toString());
        assertThat(deniedMerge.status()).isEqualTo(403);
        // Manager (customer:merge) is the only one who may merge.
        var managerRead=call(browser,"GET","/api/customer-profiles?query=客历前台建档",null,null);
        assertThat(managerRead.status()).isEqualTo(200);
    }

    // ---------- 3. linking ----------
    @Test @Order(3) void stayLinkingRejectsDuplicatesAndKeepsHistoryIntact() throws Exception {
        long actor=ids.get("HOTEL_MANAGER");
        long stayId=checkIn(browser,"C5_A","客历链住客",documentNumber,phone,"客历链同住");
        long profileId=createProfile(browser,"客历链档案",documentNumber,phone);
        var before=jdbc.queryForMap("select status,check_in_at,expected_check_out_at from stay_records where id=?",stayId);
        // Default link resolves to the primary guest snapshot.
        var link=check(browser,200,"POST","/api/customer-profiles/"+profileId+"/stays",linkBody(stayId,null),UUID.randomUUID().toString());
        assertThat(link.body().path("data").path("guestName").asText()).isEqualTo("客历链住客");
        assertThat(link.body().path("data").path("primary").asBoolean()).isTrue();
        // Same profile twice is rejected.
        var duplicate=call(browser,"POST","/api/customer-profiles/"+profileId+"/stays",linkBody(stayId,null),UUID.randomUUID().toString());
        assertThat(duplicate.status()).isEqualTo(409);assertThat(duplicate.code()).isEqualTo("PROFILE_STAY_EXISTS");
        // Explicit second guest of the same stay is a separate link.
        long secondGuestId=jdbc.queryForObject("select guest_id from stay_guests where stay_record_id=? and not is_primary",Long.class,stayId);
        check(browser,200,"POST","/api/customer-profiles/"+profileId+"/stays",linkBody(stayId,secondGuestId),UUID.randomUUID().toString());
        // Another profile cannot claim the same registration snapshot.
        long otherProfile=createProfile(browser,"客历旁档案",secondDocument,null);
        var stolen=call(browser,"POST","/api/customer-profiles/"+otherProfile+"/stays",linkBody(stayId,null),UUID.randomUUID().toString());
        assertThat(stolen.status()).isEqualTo(409);assertThat(stolen.code()).isEqualTo("GUEST_ALREADY_LINKED");
        // The stay itself was never modified by linking.
        var after=jdbc.queryForMap("select status,check_in_at,expected_check_out_at from stay_records where id=?",stayId);
        assertThat(after).isEqualTo(before);
        // Detail lists both links; access is audited.
        var detail=check(browser,200,"GET","/api/customer-profiles/"+profileId,null,null);
        assertThat(detail.body().path("data").path("stays").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where operator_id=? and action='CUSTOMER_PROFILE_VIEW' and target_id=?",Integer.class,actor,profileId)).isGreaterThanOrEqualTo(1);
        // Unlink removes the association only; history still queryable through stays.
        long linkId=jdbc.queryForObject("select id from customer_profile_stays where profile_id=? and guest_id=?",Long.class,profileId,secondGuestId);
        check(browser,200,"POST","/api/customer-profiles/stay-links/"+linkId+"/detach",Map.of("linkId",linkId),UUID.randomUUID().toString());
        assertThat(jdbc.queryForObject("select count(*) from customer_profile_stays where guest_id=?",Integer.class,secondGuestId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stay_records where id=?",Integer.class,stayId)).isEqualTo(1);
        // Unknown stay and foreign guest are rejected.
        assertThat(call(browser,"POST","/api/customer-profiles/"+otherProfile+"/stays",linkBody(999999999,null),UUID.randomUUID().toString()).status()).isEqualTo(404);
        long foreignGuest=jdbc.queryForObject("insert into guests(display_name,document_type,document_ciphertext,document_mask,encryption_key_id) values('客历无关人','ID_CARD','v1.x.fictional','00****00','v1') returning id",Long.class);
        try {
            var foreign=call(browser,"POST","/api/customer-profiles/"+otherProfile+"/stays",linkBody(stayId,foreignGuest),UUID.randomUUID().toString());
            assertThat(foreign.status()).isEqualTo(400);
        } finally { jdbc.update("delete from guests where id=?",foreignGuest); }
    }

    // ---------- 4. merge ----------
    @Test @Order(4) void mergeMovesLinksAndMergedIsTerminal() throws Exception {
        long actor=ids.get("HOTEL_MANAGER");
        long stayId=checkIn(browser,"C5_B","客历并住客",documentNumber,null,null);
        long source=createProfile(browser,"客历并入源",documentNumber,null);
        long target=createProfile(browser,"客历并入目标",secondDocument,null);
        check(browser,200,"POST","/api/customer-profiles/"+source+"/stays",linkBody(stayId,null),UUID.randomUUID().toString());
        var merged=check(browser,200,"POST","/api/customer-profiles/"+source+"/merge",Map.of("targetId",target),UUID.randomUUID().toString());
        assertThat(merged.body().path("data").path("status").asText()).isEqualTo("MERGED");
        assertThat(merged.body().path("data").path("mergedIntoId").asLong()).isEqualTo(target);
        assertThat(merged.body().path("data").path("mergedInto").path("displayName").asText()).isEqualTo("客历并入目标");
        // Links moved to the survivor; the source is terminal for edit/status/merge.
        assertThat(jdbc.queryForObject("select count(*) from customer_profile_stays where profile_id=?",Integer.class,target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from customer_profile_stays where profile_id=?",Integer.class,source)).isZero();
        var editMerged=call(browser,"PUT","/api/customer-profiles/"+source,Map.of("name","x","documentType","ID_CARD","documentNumber",secondDocument,"phone","","version",merged.body().path("data").path("version").asLong()),UUID.randomUUID().toString());
        assertThat(editMerged.status()).isEqualTo(409);assertThat(editMerged.code()).isEqualTo("PROFILE_MERGED");
        var statusMerged=call(browser,"POST","/api/customer-profiles/"+source+"/set-status",Map.of("status","ACTIVE"),UUID.randomUUID().toString());
        assertThat(statusMerged.status()).isEqualTo(409);
        var reMerge=call(browser,"POST","/api/customer-profiles/"+source+"/merge",Map.of("targetId",target),UUID.randomUUID().toString());
        assertThat(reMerge.status()).isEqualTo(409);
        // Default (ACTIVE) list hides merged rows; the MERGED filter shows them.
        var active=call(browser,"GET","/api/customer-profiles?query=客历并入源",null,null);
        assertThat(active.body().path("data").path("total").asLong()).isZero();
        var mergedList=call(browser,"GET","/api/customer-profiles?query=客历并入源&status=MERGED",null,null);
        assertThat(mergedList.body().path("data").path("total").asLong()).isEqualTo(1);
        // Self-merge and inactive target are rejected.
        var selfMerge=call(browser,"POST","/api/customer-profiles/"+target+"/merge",Map.of("targetId",target),UUID.randomUUID().toString());
        assertThat(selfMerge.status()).isEqualTo(400);
        long disabled=createProfile(browser,"客历停目标",secondDocument,null);
        check(browser,200,"POST","/api/customer-profiles/"+disabled+"/set-status",Map.of("status","DISABLED"),UUID.randomUUID().toString());
        var mergeIntoDisabled=call(browser,"POST","/api/customer-profiles/"+target+"/merge",Map.of("targetId",disabled),UUID.randomUUID().toString());
        assertThat(mergeIntoDisabled.status()).isEqualTo(409);assertThat(mergeIntoDisabled.code()).isEqualTo("PROFILE_NOT_ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where operator_id=? and action='CUSTOMER_PROFILE_MERGE'",Integer.class,actor)).isGreaterThanOrEqualTo(1);
    }

    // ---------- 5. disabled behaviour ----------
    @Test @Order(5) void disabledProfileBehavior() throws Exception {
        long profileId=createProfile(browser,"客历停档案",documentNumber,null);
        long stayId=checkIn(browser,"C5_C","客历停住客",secondDocument,null,null);
        var disabled=check(browser,200,"POST","/api/customer-profiles/"+profileId+"/set-status",Map.of("status","DISABLED"),UUID.randomUUID().toString());
        assertThat(disabled.body().path("data").path("status").asText()).isEqualTo("DISABLED");
        // Hidden from the default ACTIVE list, visible under the DISABLED filter, detail still readable.
        assertThat(call(browser,"GET","/api/customer-profiles?query=客历停档案",null,null).body().path("data").path("total").asLong()).isZero();
        assertThat(call(browser,"GET","/api/customer-profiles?query=客历停档案&status=DISABLED",null,null).body().path("data").path("total").asLong()).isEqualTo(1);
        assertThat(call(browser,"GET","/api/customer-profiles/"+profileId,null,null).status()).isEqualTo(200);
        // A disabled profile cannot take new links, but existing stays keep their history.
        var linkDenied=call(browser,"POST","/api/customer-profiles/"+profileId+"/stays",linkBody(stayId,null),UUID.randomUUID().toString());
        assertThat(linkDenied.status()).isEqualTo(409);assertThat(linkDenied.code()).isEqualTo("PROFILE_DISABLED");
        var enabled=check(browser,200,"POST","/api/customer-profiles/"+profileId+"/set-status",Map.of("status","ACTIVE"),UUID.randomUUID().toString());
        assertThat(enabled.body().path("data").path("status").asText()).isEqualTo("ACTIVE");
        check(browser,200,"POST","/api/customer-profiles/"+profileId+"/stays",linkBody(stayId,null),UUID.randomUUID().toString());
    }

    // ---------- 6. encryption boundary ----------
    @Test @Order(6) void plaintextNeverLeavesEncryptionBoundary() throws Exception {
        long profileId=createProfile(browser,"客历密档案",documentNumber,phone);
        var row=jdbc.queryForMap("select document_ciphertext,document_mask,phone_ciphertext,phone_mask,encryption_key_id from customer_profiles where id=?",profileId);
        assertThat((String)row.get("document_ciphertext")).startsWith("v1.").doesNotContain(documentNumber);
        assertThat((String)row.get("document_mask")).isEqualTo("11****34");
        assertThat((String)row.get("phone_ciphertext")).startsWith("v1.");
        assertThat((String)row.get("phone_mask")).isEqualTo("+8****00");
        // No list/detail response ever carries the raw document number or phone.
        for(String path:new String[]{"/api/customer-profiles?query=客历密档案","/api/customer-profiles/"+profileId}) {
            String response=call(browser,"GET",path,null,null).body().toString();
            assertThat(response).doesNotContain(documentNumber).doesNotContain(phone);
        }
    }

    // ---------- 7. browser acceptance ----------
    @Test @Order(7) void realBrowserAcceptance() throws Exception {
        assumeTrue(Boolean.getBoolean("stayos.e2e"));
        browserStay=checkIn(browser,"C5_D","客历浏览器住客",documentNumber,phone,null);
        long hintProfile=createProfile(browser,"客历浏览器同名","110101197001011234",null);
        var fixtures=json.createObjectNode().put("profileName","客历浏览器同名").put("stayGuestName","客历浏览器住客").put("stayId",browserStay).put("hintProfileId",hintProfile);
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).getParent();
        var log=root.resolve(".local/customers-browser.log");
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","customers.spec.ts")
            .directory(root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);
        builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_CUSTOMER_FIXTURES",json.writeValueAsString(fixtures));
        builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-customers-results.json");
        var process=builder.start();
        if(!process.waitFor(300,java.util.concurrent.TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();
            Assertions.fail("Customer browser timeout");
        }
        assertThat(process.exitValue()).as("See private customers-browser.log").isZero();
    }
}
