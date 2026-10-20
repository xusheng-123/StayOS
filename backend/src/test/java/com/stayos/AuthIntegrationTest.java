package com.stayos;

import com.fasterxml.jackson.databind.*;
import com.stayos.staff.mapper.UserMapper;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(AuthIntegrationTest.SessionCapture.class)
class AuthIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired UserMapper users;
    private final String password = UUID.randomUUID().toString();
    private final Map<String, Long> ids = new HashMap<>();
    static final List<HttpSession> sessions = new CopyOnWriteArrayList<>();
    @TestConfiguration static class SessionCapture {
        @Bean ServletListenerRegistrationBean<HttpSessionListener> capture() {
            return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
                @Override public void sessionCreated(HttpSessionEvent event) { sessions.add(event.getSession()); }
                @Override public void sessionDestroyed(HttpSessionEvent event) { sessions.remove(event.getSession()); }
            });
        }
    }
    @BeforeAll void fixtures() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("stayos_test");
        String hash = encoder.encode(password);
        for (String role : List.of("ADMIN", "HOTEL_MANAGER", "FRONT_DESK", "HOUSEKEEPING_MANAGER", "HOUSEKEEPING_STAFF", "MULTI", "DISABLED")) {
            String username = "test_" + role.toLowerCase(Locale.ROOT);
            jdbc.update("delete from audit_logs where operator_id in (select id from users where username=?)", username);
            jdbc.update("delete from user_roles where user_id in (select id from users where username=?)", username);
            jdbc.update("delete from users where username=?", username);
            Long id = jdbc.queryForObject("insert into users(username,password_hash,display_name,enabled) values(?,?,?,?) returning id", Long.class, username, hash, "测试" + role, !role.equals("DISABLED"));
            ids.put(role, id);
            for (var assigned : role.equals("MULTI") ? List.of("FRONT_DESK", "HOUSEKEEPING_STAFF") : List.of(role.equals("DISABLED") ? "FRONT_DESK" : role)) users.addRole(id, assigned);
        }
    }
    @BeforeEach void reset() {
        for (var role : ids.keySet()) jdbc.update("update users set enabled=?,failed_login_attempts=0,locked_until=null where id=?", !role.equals("DISABLED"), ids.get(role));
    }
    @AfterAll void cleanup() {
        for (long id : ids.values()) {
            jdbc.update("delete from audit_logs where operator_id=?", id);
            jdbc.update("delete from user_roles where user_id=?", id);
            jdbc.update("delete from users where id=?", id);
        }
        jdbc.update("delete from audit_logs where operator_id is null");
    }

    final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        JsonNode csrf;
        HttpResponse<String> request(String method, String path, Object body, boolean useCsrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
            if (useCsrf) {
                csrf = data(request("GET", "/api/auth/csrf", null, false));
                builder.header(csrf.get("headerName").asText(), csrf.get("token").asText());
            }
            builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, false); }
        HttpResponse<String> post(String path, Object body) throws Exception { return request("POST", path, body, true); }
        HttpResponse<String> login(String role) throws Exception { return post("/api/auth/login", Map.of("username", "test_" + role.toLowerCase(Locale.ROOT), "password", password)); }
        String cookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("STAYOS_SESSION")).map(c -> c.getName()+"="+c.getValue()).findFirst().orElseThrow(); }
    }
    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).get("data"); }
    @Test void correctCredentialsAndCurrentUserAndStatus() throws Exception {
        var browser = new Browser(); assertThat(browser.login("FRONT_DESK").statusCode()).isEqualTo(200);
        var me = browser.get("/api/auth/me"); assertThat(me.statusCode()).isEqualTo(200);
        assertThat(data(me).get("username").asText()).isEqualTo("test_front_desk");
        assertThat(browser.get("/api/auth/status").statusCode()).isEqualTo(200);
        assertThat(me.body()).doesNotContain("password", "securityVersion", password);
    }
    @Test void invalidUnknownAndDisabledAccountsHaveIdenticalFailures() throws Exception {
        String previous = null;
        for (String username : List.of("test_front_desk", "no_such_account", "test_disabled")) {
            var response = new Browser().post("/api/auth/login", Map.of("username", username, "password", "incorrect-password"));
            assertThat(response.statusCode()).isEqualTo(401);
            if (previous != null) assertThat(response.body()).isEqualTo(previous);
            previous = response.body();
        }
        assertThat(new Browser().login("DISABLED").statusCode()).isEqualTo(401);
    }
    @Test void unauthenticatedProtectedInterfacesReturnJson401() throws Exception {
        var browser = new Browser();
        for (var path : List.of("/api/auth/me", "/api/auth/status", "/api/workspaces/admin", "/api/users")) {
            var result = browser.get(path); assertThat(result.statusCode()).isEqualTo(401);
            assertThat(json.readTree(result.body()).get("code").asText()).isEqualTo("UNAUTHORIZED");
        }
    }
    @ParameterizedTest
    @CsvSource({"ADMIN,true,false,false,false,false", "HOTEL_MANAGER,false,true,true,true,true", "FRONT_DESK,false,false,true,false,false", "HOUSEKEEPING_MANAGER,false,false,false,true,true", "HOUSEKEEPING_STAFF,false,false,false,false,true"})
    void roleMatrix(String role, boolean admin, boolean manager, boolean front, boolean hkManager, boolean staff) throws Exception {
        var browser = new Browser(); assertThat(browser.login(role).statusCode()).isEqualTo(200);
        var paths = List.of("admin", "hotel-manager", "front-desk", "housekeeping-manager", "housekeeping-staff");
        var allowed = List.of(admin, manager, front, hkManager, staff);
        for (int i = 0; i < paths.size(); i++) assertThat(browser.get("/api/workspaces/" + paths.get(i)).statusCode()).as(role + " -> " + paths.get(i)).isEqualTo(allowed.get(i) ? 200 : 403);
    }
    @Test void multiRolePermissionsAreUnionWithoutAdministratorEscalation() throws Exception {
        var browser = new Browser(); assertThat(browser.login("MULTI").statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/workspaces/front-desk").statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/workspaces/housekeeping-staff").statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/workspaces/admin").statusCode()).isEqualTo(403);
        assertThat(browser.get("/api/workspaces/housekeeping-manager").statusCode()).isEqualTo(403);
    }
    @Test void cookieIsHttpOnlySameSiteAndSessionIdRotatesOnLogin() throws Exception {
        var browser = new Browser(); var before = browser.get("/api/auth/csrf");
        String header = before.headers().firstValue("set-cookie").orElseThrow();
        assertThat(header).contains("HttpOnly", "SameSite=Strict", "Path=/");
        String previous = browser.cookie(); assertThat(browser.login("FRONT_DESK").statusCode()).isEqualTo(200);
        assertThat(browser.cookie()).isNotEqualTo(previous);
    }
    @Test void csrfIsRequiredForLoginLogoutAndChanges() throws Exception {
        var browser = new Browser();
        assertThat(browser.request("POST", "/api/auth/login", Map.of("username", "test_admin", "password", password), false).statusCode()).isEqualTo(403);
        assertThat(browser.login("ADMIN").statusCode()).isEqualTo(200);
        assertThat(browser.request("POST", "/api/auth/logout", null, false).statusCode()).isEqualTo(403);
        assertThat(browser.request("POST", "/api/users/"+ids.get("FRONT_DESK")+"/set-enabled", Map.of("enabled", false), false).statusCode()).isEqualTo(403);
    }
    @Test void logoutInvalidatesOriginalCookie() throws Exception {
        var browser = new Browser(); browser.login("FRONT_DESK"); String oldCookie = browser.cookie();
        assertThat(browser.post("/api/auth/logout", null).statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
        var old = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/me")).header("Cookie",oldCookie).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(old.statusCode()).isEqualTo(401);
    }
    @Test void disabledAccountSessionCannotReviveAfterReenable() throws Exception {
        var browser = new Browser(); browser.login("FRONT_DESK");
        jdbc.update("update users set enabled=false where id=?", ids.get("FRONT_DESK"));
        jdbc.update("update users set enabled=true where id=?", ids.get("FRONT_DESK"));
        assertThat(browser.get("/api/workspaces/front-desk").statusCode()).isEqualTo(401);
    }
    @Test void disabledAccountIsRejectedImmediately() throws Exception {
        var browser = new Browser(); browser.login("FRONT_DESK");
        jdbc.update("update users set enabled=false where id=?", ids.get("FRONT_DESK"));
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
    }
    @Test void roleChangeRevokesOldSessionAndNewLoginHasNewPermissions() throws Exception {
        var browser = new Browser(); browser.login("FRONT_DESK");
        users.addRole(ids.get("FRONT_DESK"), "HOUSEKEEPING_STAFF");
        try {
            assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
            assertThat(browser.login("FRONT_DESK").statusCode()).isEqualTo(200);
            assertThat(browser.get("/api/workspaces/housekeeping-staff").statusCode()).isEqualTo(200);
        } finally { jdbc.update("delete from user_roles where user_id=? and role_id=(select id from roles where role_code='HOUSEKEEPING_STAFF')", ids.get("FRONT_DESK")); }
    }
    @Test void passwordResetRevokesSessionAndRejectsOldPassword() throws Exception {
        var browser = new Browser(); browser.login("FRONT_DESK"); String temporary = UUID.randomUUID().toString();
        jdbc.update("update users set password_hash=? where id=?", encoder.encode(temporary), ids.get("FRONT_DESK"));
        try {
            assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
            assertThat(browser.login("FRONT_DESK").statusCode()).isEqualTo(401);
        } finally { jdbc.update("update users set password_hash=? where id=?", encoder.encode(password), ids.get("FRONT_DESK")); }
    }
    @Test void expiredSessionIsRejectedByRealServletContainer() throws Exception {
        var browser = new Browser(); browser.login("FRONT_DESK"); String id = browser.cookie().split("=",2)[1];
        var session = sessions.stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow();
        session.setMaxInactiveInterval(1); Thread.sleep(1400);
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
    }
    @Test void accountLocksAfterFiveFailuresAndUnlocksAfterDeadline() throws Exception {
        var browser = new Browser();
        for (int i=0;i<5;i++) assertThat(browser.post("/api/auth/login",Map.of("username","test_front_desk","password","wrong-password")).statusCode()).isEqualTo(401);
        assertThat(browser.login("FRONT_DESK").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select failed_login_attempts from users where id=?",Integer.class,ids.get("FRONT_DESK"))).isEqualTo(5);
        jdbc.update("update users set locked_until=now()-interval '1 second' where id=?",ids.get("FRONT_DESK"));
        assertThat(browser.login("FRONT_DESK").statusCode()).isEqualTo(200);
    }
    @Test void databaseStoresOnlyHashesAndAuditDoesNotContainCredentials() throws Exception {
        var browser = new Browser(); browser.login("FRONT_DESK"); browser.get("/api/workspaces/admin"); browser.post("/api/auth/logout",null);
        String hash = jdbc.queryForObject("select password_hash from users where id=?",String.class,ids.get("FRONT_DESK"));
        assertThat(hash).startsWith("$2a$12$").isNotEqualTo(password); assertThat(encoder.matches(password,hash)).isTrue();
        String rows = jdbc.queryForList("select * from audit_logs").toString();
        assertThat(rows).contains("LOGIN","LOGOUT","ACCESS_DENIED").doesNotContain(password,hash,"STAYOS_SESSION","X-CSRF-TOKEN");
    }
    @Test void managerCannotManageUsersOrGrantSelfAdmin() throws Exception {
        var browser = new Browser(); browser.login("HOTEL_MANAGER");
        assertThat(browser.get("/api/users").statusCode()).isEqualTo(403);
        assertThat(browser.request("PUT", "/api/users/"+ids.get("HOTEL_MANAGER")+"/roles",Map.of("roles",List.of("ADMIN")),true).statusCode()).isEqualTo(403);
    }
    @Test void administratorCanManageAccountsWithoutLeakingHashesAndLastAdminIsProtected() throws Exception {
        var browser = new Browser(); browser.login("ADMIN"); String username="created_"+UUID.randomUUID().toString().substring(0,8);
        var response = browser.post("/api/users",Map.of("username",username,"password",UUID.randomUUID().toString(),"displayName","虚构员工","roles",List.of("FRONT_DESK","HOUSEKEEPING_STAFF")));
        assertThat(response.statusCode()).isEqualTo(200); long id=data(response).get("id").asLong();
        try {
            assertThat(browser.get("/api/users").body()).doesNotContain("password", "$2a$");
            assertThat(browser.post("/api/users/"+id+"/set-enabled",Map.of("enabled",false)).statusCode()).isEqualTo(200);
            assertThat(browser.post("/api/users/"+ids.get("ADMIN")+"/set-enabled",Map.of("enabled",false)).statusCode()).isEqualTo(409);
            assertThat(browser.post("/api/users/"+id+"/reset-password",Map.of("password",UUID.randomUUID().toString())).statusCode()).isEqualTo(200);
        } finally { jdbc.update("delete from audit_logs where target_id=? or operator_id=?",id,id); jdbc.update("delete from user_roles where user_id=?",id);jdbc.update("delete from users where id=?",id); }
    }
    @Test void browserEndToEndWithRealBackend() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"), "Browser tests enabled by scripts/test.ps1 -Browser");
        var directory = java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("frontend").toFile();
        var log = directory.toPath().getParent().resolve(".local/browser-run.log");
        java.nio.file.Files.createDirectories(log.getParent());
        var builder = new ProcessBuilder("cmd.exe", "/d", "/c", "npm", "run", "test:e2e", "--", "auth.spec.ts")
            .directory(directory).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL", "http://127.0.0.1:"+port);
        builder.environment().put("STAYOS_E2E_PASSWORD", password);
        builder.environment().put("STAYOS_E2E_IDS", json.writeValueAsString(ids));
        var process = builder.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            fail("Browser tests timed out");
        }
        assertThat(process.exitValue()).as("Playwright real-browser suite; details: " + log).isZero();
    }
}
