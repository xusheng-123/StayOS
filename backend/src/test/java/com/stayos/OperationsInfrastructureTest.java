package com.stayos;

import com.fasterxml.jackson.databind.*;
import com.stayos.auth.ProductionDatabaseGuard;
import com.stayos.stay.service.GuestProtection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class OperationsInfrastructureTest {
    final ObjectMapper json=new ObjectMapper();
    final Path root=Path.of(System.getProperty("user.dir")).getParent(),scripts=root.resolve("scripts");
    final String owner=System.getenv().getOrDefault("STAYOS_DB_USER","stayos"),ownerPassword=System.getenv("STAYOS_DB_PASSWORD");
    final String url="jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("STAYOS_TEST_DB_PORT","15433")+"/";
    String quote(String s){return "'"+s.replace("'","''")+"'";}
    Connection owner(String database)throws Exception{return DriverManager.getConnection(url+database,owner,ownerPassword);}
    Path evidence(String prefix)throws Exception {var p=root.resolve(".local/ops-infrastructure").resolve(prefix+UUID.randomUUID().toString().replace("-",""));Files.createDirectories(p);return p;}
    String ps(Path folder,String name,String command,boolean success,Map<String,String> env)throws Exception {
        var output=folder.resolve(name+".out");var builder=new ProcessBuilder("pwsh","-NoProfile","-Command","[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);$OutputEncoding=[Console]::OutputEncoding;$ErrorActionPreference='Stop'; "+command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(env);var p=builder.start();if(!p.waitFor(90,TimeUnit.SECONDS)){p.destroyForcibly();fail("PowerShell timeout");}
        if(success)assertThat(p.exitValue()).as("Private script result: "+output).isZero();else assertThat(p.exitValue()).isNotZero();return success?Files.readString(output):"";
    }
    @Test void scheduledBackupJobPreservesLastSuccessAndChecksKeyIdentityWithoutInstallingTask()throws Exception {
        var out=evidence("backup-");var keyFile=out.resolve("test-key.env");String encoded=Base64.getEncoder().encodeToString(new byte[32]);Files.writeString(keyFile,"STAYOS_GUEST_ENCRYPTION_KEY="+encoded);
        String fingerprint=new GuestProtection(encoded).keyFingerprint();
        String args=" -Container stayos-postgres-test-1 -OutputDirectory "+quote(out.resolve("backups").toString())+" -StatusDirectory "+quote(out.resolve("status").toString())+" -KeyEnvPath "+quote(keyFile.toString());
        var result=json.readTree(ps(out,"backup","& "+quote(scripts.resolve("run-backup-job.ps1").toString())+" -Database stayos_test"+args+" | ConvertTo-Json -Compress",true,Map.of()));
        assertThat(result.path("Status").asText()).isEqualTo("SUCCESS");var manifest=json.readTree(Path.of(result.path("ManifestPath").asText()).toFile());assertThat(manifest.path("guestKeyFingerprint").asText()).isEqualTo(fingerprint);
        String success=Files.readString(out.resolve("status/last-success.json"));
        ps(out,"failed-backup","& "+quote(scripts.resolve("run-backup-job.ps1").toString())+" -Database stayos_ops_missing"+args,false,Map.of());
        assertThat(Files.readString(out.resolve("status/last-success.json"))).isEqualTo(success);
        assertThat(json.readTree(out.resolve("status/last-attempt.json").toFile()).path("status").asText()).isEqualTo("FAILED");
        ps(out,"monitor-failed","& "+quote(scripts.resolve("check-operations.ps1").toString())+" -BaseUrl http://127.0.0.1:1 -StatusDirectory "+quote(out.resolve("status").toString()),false,Map.of());
        var monitoring=json.readTree(out.resolve("monitor-failed.out").toFile());assertThat(monitoring.path("checks").path("backupLatestAttemptSucceeded").asBoolean()).isFalse();assertThat(monitoring.path("checks").path("backupRecent").asBoolean()).isTrue();
        assertThat(Path.of(result.path("BackupPath").asText())).exists();
        String database="stayos_restore_ops_"+UUID.randomUUID().toString().replace("-","");
        String restore="& "+quote(scripts.resolve("restore-database.ps1").toString())+" -Container stayos-postgres-test-1 -Database "+database+" -ConfirmTarget "+database+" -BackupPath "+quote(result.path("BackupPath").asText());
        ps(out,"wrong-key",restore+" -ExpectedGuestKeyFingerprint "+"f".repeat(64),false,Map.of());
        try {
            ps(out,"right-key",restore+" -ExpectedGuestKeyFingerprint "+fingerprint+" | ConvertTo-Json -Compress",true,Map.of());
            try(var c=owner(database);var q=c.createStatement();var r=q.executeQuery("select count(*) from flyway_schema_history where success")){r.next();assertThat(r.getInt(1)).isEqualTo(20);}
        }finally {try(var c=owner("stayos_test");var q=c.createStatement()){q.execute("drop database if exists "+database+" with(force)");}}
        String task="StayOS-Backup-Ops"+UUID.randomUUID().toString().replace("-","");
        ps(out,"task-preview","& "+quote(scripts.resolve("install-backup-task.ps1").toString())+" -Database stayos_test"+args+" -TaskName "+task+" -WhatIf",true,Map.of());
        var registered=ps(out,"task-not-installed","[bool](Get-ScheduledTask -TaskName "+task+" -ErrorAction SilentlyContinue) | ConvertTo-Json -Compress",true,Map.of());assertThat(registered.trim()).isEqualTo("false");
    }
    final class Browser {
        final int port;final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser(int port){this.port=port;}
        HttpResponse<String> get(String path)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        JsonNode post(String path,Object body)throws Exception {
            var token=ok(get("/api/auth/csrf"));var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).header(token.path("headerName").asText(),token.path("token").asText()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            return ok(http.send(request,HttpResponse.BodyHandlers.ofString()));
        }
    }
    JsonNode ok(HttpResponse<String> r)throws Exception{assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json.readTree(r.body()).path("data");}
    Browser login(int port,String role,String password)throws Exception {var b=new Browser(port);b.post("/api/auth/login",Map.of("username","runtime_"+role.toLowerCase(Locale.ROOT),"password",password));return b;}
    @Test void restrictedRuntimeRoleCanServeRealHotelFlowButCannotRewriteHistoryOrRunDdl()throws Exception {
        var out=evidence("runtime-");String suffix=UUID.randomUUID().toString().replace("-","");String database="stayos_restore_run_"+suffix,role="stayos_run_"+suffix;
        String runtimePassword="Runtime!"+UUID.randomUUID(),staffPassword="Fictional!"+UUID.randomUUID();Process app=null;
        try {
            try(var c=owner("stayos_test");var s=c.createStatement()){s.execute("create database "+database);}
            Flyway.configure().dataSource(url+database,owner,ownerPassword).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
            try(var c=owner(database)) {
                for(String staff:List.of("ADMIN","FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_MANAGER","HOUSEKEEPING_STAFF")){
                    try(var q=c.prepareStatement("insert into users(username,password_hash,display_name) values(?,?,?) returning id")){
                        q.setString(1,"runtime_"+staff.toLowerCase(Locale.ROOT));q.setString(2,new BCryptPasswordEncoder().encode(staffPassword));q.setString(3,"虚构受限账号");
                        long id;try(var r=q.executeQuery()){r.next();id=r.getLong(1);}try(var p=c.prepareStatement("insert into user_roles(user_id,role_id) select ?,id from roles where role_code=?")){p.setLong(1,id);p.setString(2,staff);p.executeUpdate();}
                    }
                }
            }
            String command="$secret=ConvertTo-SecureString $env:STAYOS_OPS_ROLE_PASSWORD -AsPlainText -Force; & "+quote(scripts.resolve("create-runtime-db-role.ps1").toString())+" -Container stayos-postgres-test-1 -Database "+database+" -RuntimeRole "+role+" -Password $secret";
            ps(out,"role",command,true,Map.of("STAYOS_OPS_ROLE_PASSWORD",runtimePassword));
            ps(out,"role-existing-rejected",command,false,Map.of("STAYOS_OPS_ROLE_PASSWORD",runtimePassword));
            var restricted=new DriverManagerDataSource(url+database,role,runtimePassword);new ProductionDatabaseGuard(restricted).run(new DefaultApplicationArguments());
            assertThatThrownBy(()->new ProductionDatabaseGuard(new DriverManagerDataSource(url+database,owner,ownerPassword)).run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
            try(var c=restricted.getConnection();var s=c.createStatement()) {
                for(String forbidden:List.of("create table public.forbidden_table(id integer)","delete from audit_logs","update audit_logs set result='FAILURE'","delete from housekeeping_task_logs","delete from room_changes","delete from stay_records","delete from stay_extensions","update stay_extensions set reason='rewritten'","delete from guest_corrections","update guests set encryption_key_id='rewritten'","update guests set notes='rewritten'","update guest_corrections set reason='rewritten'","delete from shift_handovers","delete from handover_items","delete from handover_events","update handover_events set reason='rewritten'","update handover_items set content='rewritten'","delete from billing_policies","delete from rate_plans","delete from room_rate_days","delete from service_catalog","delete from service_orders","delete from folios","delete from folio_charges","update folio_charges set amount='1'","delete from payment_records","update payment_records set amount='1'","delete from folio_events","delete from inbound_events","delete from outbox_events","delete from sync_attempts","delete from reconciliation_runs","update sync_attempts set outcome='FAILURE'","delete from customer_profiles"))
                    assertThatThrownBy(()->s.execute(forbidden)).isInstanceOf(SQLException.class).extracting(e->((SQLException)e).getSQLState()).isEqualTo("42501");
            }
            int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-XX:TieredStopAtLevel=1","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"com.stayos.StayosApplication","--spring.profiles.active=test","--spring.datasource.url="+url+database,"--spring.datasource.username="+role,"--server.port="+port,"--server.address=127.0.0.1","--logging.level.com.stayos.StayosApplication=INFO").redirectErrorStream(true).redirectOutput(out.resolve("runtime-application.log").toFile());
            builder.environment().put("SPRING_DATASOURCE_PASSWORD",runtimePassword);builder.environment().put("SPRING_FLYWAY_USER",owner);builder.environment().put("SPRING_FLYWAY_PASSWORD",ownerPassword);builder.environment().put("SPRING_FLYWAY_URL",url+database);app=builder.start();
            SystemAcceptanceTest.ready(app,port);
            var admin=login(port,"ADMIN",staffPassword);var manager=login(port,"HOUSEKEEPING_MANAGER",staffPassword);var staff=login(port,"HOUSEKEEPING_STAFF",staffPassword);var front=login(port,"FRONT_DESK",staffPassword);var hotel=login(port,"HOTEL_MANAGER",staffPassword);
            long type=admin.post("/api/room-types",Map.of("typeCode","RUNTIME","typeName","虚构受限房型","suggestedOccupancy",2)).path("id").asLong();
            long room=admin.post("/api/rooms",Map.of("roomNumber","RUNTIME01","floor",1,"roomTypeId",type)).path("id").asLong();
            long staffId=ok(staff.get("/api/auth/me")).path("id").asLong();var batch=manager.post("/api/housekeeping-tasks/batch-assign",Map.of("assigneeId",staffId,"roomIds",List.of(room)));long task=batch.path("items").get(0).path("taskId").asLong();
            long version=ok(staff.get("/api/housekeeping-tasks/"+task)).path("version").asLong();staff.post("/api/housekeeping-tasks/"+task+"/start",Map.of("version",version));version=ok(staff.get("/api/housekeeping-tasks/"+task)).path("version").asLong();staff.post("/api/housekeeping-tasks/"+task+"/complete",Map.of("version",version));
            var stay=front.post("/api/stays",Map.of("roomId",room,"expectedCheckOutAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString(),"guests",List.of(Map.of("name","虚构受限住客","documentType","OTHER","documentNumber","RUNTIME-FICTIONAL","primary",true))));
            var extension=front.post("/api/stays/"+stay.path("id").asLong()+"/extensions",Map.of("segmentId",stay.path("segmentId").asLong(),"version",stay.path("version").asLong(),"newExpectedCheckOutAt",java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(2).toString(),"reason","虚构受限账号续住"));assertThat(extension.path("stayVersion").asLong()).isEqualTo(1);
            var correction=front.post("/api/stays/"+stay.path("id").asLong()+"/guests/"+stay.path("guests").get(0).path("id").asLong()+"/corrections",Map.of("version",0,"changes",Map.of("name","虚构受限更正","phone","13900000995"),"reason","虚构受限账号更正"));
            assertThat(correction.path("guestVersion").asLong()).isEqualTo(1);
            long hotelId=ok(hotel.get("/api/auth/me")).path("id").asLong();var handover=front.post("/api/handovers",Map.of("scope","FRONT_DESK","receiverId",hotelId,"shiftStartAt",OffsetDateTime.now(ZoneOffset.UTC).toString(),"shiftEndAt",OffsetDateTime.now(ZoneOffset.UTC).plusHours(8).toString(),"summary","虚构受限交班","items",List.of(Map.of("category","CUSTOM","content","虚构受限交班事项"))));assertThat(handover.path("status").asText()).isEqualTo("DRAFT");
            front.post("/api/handovers/"+handover.path("id").asLong()+"/submit",Map.of("version",handover.path("version").asLong()));
            var acknowledged=hotel.post("/api/handovers/"+handover.path("id").asLong()+"/acknowledge",Map.of("version",handover.path("version").asLong()+1));assertThat(acknowledged.path("status").asText()).isEqualTo("ACKNOWLEDGED");
            // Billing flow under the restricted role: configure, open folio, post tonight's room night, receipt, close.
            assertThat(hotel.post("/api/billing/policies",Map.of("billingCutoffTime","04:00","earlyCheckinMode","FULL_NIGHT","lateCheckoutMode","HOURLY","earlyCheckoutMode","FULL_NIGHT","roomChangePriceMode","BY_NEW_SEGMENT","extensionPriceMode","CURRENT_RATE")).path("active").asBoolean()).isTrue();
            var runtimePlan=hotel.post("/api/billing/rate-plans",Map.of("roomTypeId",type,"planCode","RUNTIME","planName","虚构受限门市价","nightlyPrice","288"));long runtimePlanId=runtimePlan.path("id").asLong();
            var runtimeFolio=front.post("/api/stays/"+stay.path("id").asLong()+"/folio",Map.of());long runtimeFolioId=runtimeFolio.path("folio").path("id").asLong();
            assertThat(runtimeFolio.path("folio").path("status").asText()).isEqualTo("OPEN");
            var runtimeNight=front.post("/api/folios/"+runtimeFolioId+"/charges",Map.of("category","ROOM_NIGHT","businessDate",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),"ratePlanId",runtimePlanId));
            assertThat(runtimeNight.path("amount").asText()).isEqualTo("288.00");
            front.post("/api/folios/"+runtimeFolioId+"/payments",Map.of("method","CASH","purpose","PAYMENT","amount","288"));
            long runtimeFolioVersion=ok(front.get("/api/folios/"+runtimeFolioId)).path("folio").path("version").asLong();
            var runtimeClosed=front.post("/api/folios/"+runtimeFolioId+"/close",Map.of("version",runtimeFolioVersion));
            assertThat(runtimeClosed.path("folio").path("status").asText()).isEqualTo("CLOSED");
            var issue=manager.post("/api/room-issues",Map.of("roomId",room,"issueType","FACILITY","severity","LOW","description","虚构受限故障"));assertThat(issue.path("id").asLong()).isPositive();
            front.post("/api/stays/"+stay.path("id").asLong()+"/check-out",Map.of("segmentId",stay.path("segmentId").asLong()));assertThat(ok(front.get("/api/rooms/"+room)).path("cleaningStatus").asText()).isEqualTo("DIRTY");
            assertThat(ok(manager.get("/api/operations/summary")).path("unassignedTasks").asLong()).isEqualTo(1);
            var keyFile=out.resolve("test-key.env");Files.writeString(keyFile,"STAYOS_GUEST_ENCRYPTION_KEY="+Base64.getEncoder().encodeToString(new byte[32]));
            ps(out,"runtime-backup","& "+quote(scripts.resolve("run-backup-job.ps1").toString())+" -Container stayos-postgres-test-1 -Database "+database+" -OutputDirectory "+quote(out.resolve("backups").toString())+" -StatusDirectory "+quote(out.resolve("status").toString())+" -KeyEnvPath "+quote(keyFile.toString())+" | ConvertTo-Json -Compress",true,Map.of());
            String monitor="& "+quote(scripts.resolve("check-operations.ps1").toString())+" -BaseUrl http://127.0.0.1:"+port+" -StatusDirectory "+quote(out.resolve("status").toString());
            var checks=json.readTree(ps(out,"healthy-monitor",monitor,true,Map.of()));assertThat(checks.path("healthy").asBoolean()).isTrue();
            app.destroy();assertThat(app.waitFor(10,TimeUnit.SECONDS)).isTrue();app=null;
            ps(out,"stopped-monitor",monitor,false,Map.of());
        } finally {
            if(app!=null){app.destroy();if(!app.waitFor(10,TimeUnit.SECONDS))app.destroyForcibly().waitFor();}
            try(var c=owner("stayos_test");var s=c.createStatement()){s.execute("drop database if exists "+database+" with(force)");s.execute("drop role if exists "+role);}
        }
    }
}
