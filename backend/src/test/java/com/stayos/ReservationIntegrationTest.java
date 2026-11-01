package com.stayos;

import com.fasterxml.jackson.databind.*;
import com.stayos.staff.mapper.UserMapper;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReservationIntegrationTest {
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords; @Autowired UserMapper users;
    @LocalServerPort int port;
    final Map<String,Long> ids=new LinkedHashMap<>();
    final String password=UUID.randomUUID().toString();
    static final String OWN="select id from users where username like 'reservation_test_%'";
    static final String TYPES="select id from room_types where type_code like 'RVT%'";
    @BeforeAll void setup() {
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        String hash=passwords.encode(password);purgeAll();
        for(String role:List.of("FRONT_DESK","HOTEL_MANAGER","HOUSEKEEPING_STAFF")) {
            long id=db.queryForObject("insert into users(username,password_hash,display_name) values(?,?,?) returning id",Long.class,
                "reservation_test_"+role.toLowerCase(Locale.ROOT),hash,"虚构预订"+role);
            ids.put(role,id);users.addRole(id,role);
        }
    }
    /** Reservation facts and their money/stay attachments are cleaned with their immutability triggers suspended. */
    private void purgeAll() { purgeBusiness();
        db.update("delete from user_roles where user_id in("+OWN+")");db.update("delete from users where id in("+OWN+")"); }
    private void purgeBusiness() {
        String own="("+OWN+")",types="("+TYPES+")";
        db.update("delete from idempotency_requests where operator_id in"+own);
        db.update("delete from billing_policies where created_by in"+own);
        db.execute("alter table reservation_events disable trigger reservation_events_append_only");
        db.execute("alter table reservations disable trigger reservation_update");
        db.execute("alter table payment_records disable trigger payment_records_guard");
        db.execute("alter table payment_records disable trigger payment_records_no_delete");
        db.execute("alter table folio_charges disable trigger folio_charges_append_only");
        db.execute("alter table folio_events disable trigger folio_events_append_only");
        db.update("delete from payment_records where reservation_id in(select id from reservations where room_type_id in"+types+")");
        db.update("delete from reservation_events where reservation_id in(select id from reservations where room_type_id in"+types+")");
        db.update("delete from reservations where room_type_id in"+types);
        db.update("delete from inventory_days where room_type_id in"+types);
        db.update("delete from inventory_blocks where room_type_id in"+types);
        db.update("delete from folio_charges where folio_id in(select id from folios where stay_record_id in(select id from stay_records where created_by in"+own+"))");
        db.update("delete from payment_records where folio_id in(select id from folios where stay_record_id in(select id from stay_records where created_by in"+own+"))");
        db.update("delete from folio_events where folio_id in(select id from folios where stay_record_id in(select id from stay_records where created_by in"+own+"))");
        db.execute("alter table folio_charges enable trigger folio_charges_append_only");
        db.execute("alter table folio_events enable trigger folio_events_append_only");
        db.update("delete from folios where stay_record_id in(select id from stay_records where created_by in"+own+")");
        db.update("delete from stay_extensions where stay_record_id in(select id from stay_records where created_by in"+own+")");
        db.update("delete from stay_guests where stay_record_id in(select id from stay_records where created_by in"+own+")");
        db.update("delete from stay_room_segments where stay_record_id in(select id from stay_records where created_by in"+own+")");
        db.update("delete from stay_records where created_by in"+own);
        db.update("delete from guests where display_name like '预订测试住客%'");
        db.execute("alter table reservation_events enable trigger reservation_events_append_only");
        db.execute("alter table reservations enable trigger reservation_update");
        db.execute("alter table payment_records enable trigger payment_records_guard");
        db.execute("alter table payment_records enable trigger payment_records_no_delete");
        db.update("delete from audit_logs where operator_id in"+own);
        db.update("delete from rooms where room_type_id in"+types);
        db.update("delete from room_types where id in"+types);
    }
    @BeforeEach void clean() {
        if(!ids.isEmpty()) purgeBusiness();
        for(var entry:ids.entrySet())
            db.update("update users set enabled=true,password_hash=?,failed_login_attempts=0,locked_until=null where id=?",passwords.encode(password),entry.getValue());
    }
    @AfterAll void after() {
        if(!ids.isEmpty()) purgeBusiness();
        for(long id:ids.values()){db.update("delete from user_roles where user_id=?",id);db.update("delete from users where id=?",id);}
    }
    String key(){return UUID.randomUUID().toString();}
    String day(int offset){return LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(offset).toString();}
    String noon(int offset){return LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(offset).atTime(12,0).atOffset(ZoneOffset.ofHours(8)).toString();}

    final class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> request(String method,String path,Object body,String idemKey)throws Exception {
            var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
            if(!method.equals("GET")){var csrf=ok(get("/api/auth/csrf"));r.header(csrf.path("headerName").asText(),csrf.path("token").asText());}
            if(idemKey!=null)r.header("Idempotency-Key",idemKey);
            return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path)throws Exception{return request("GET",path,null,null);}
        HttpResponse<String> post(String path)throws Exception{return request("POST",path,null,key());}
        HttpResponse<String> post(String path,Object body)throws Exception{return request("POST",path,body,key());}
        HttpResponse<String> post(String path,Object body,String idempotencyKey)throws Exception{return request("POST",path,body,idempotencyKey);}
    }
    Browser login(String role)throws Exception {
        var b=new Browser();ok(b.post("/api/auth/login",Map.of("username","reservation_test_"+role.toLowerCase(Locale.ROOT),"password",password)));return b;
    }
    JsonNode ok(HttpResponse<String> response)throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");}
    String error(HttpResponse<String> response)throws Exception {var body=json.readTree(response.body());assertThat(body.path("success").asBoolean()).isFalse();return body.path("code").asText();}
    String errorCode(HttpResponse<String> response)throws Exception {return json.readTree(response.body()).path("code").asText();}

    /** One room type with N rooms; every test starts from a private type so availability math is exact. */
    record TypeRef(long typeId,String typeCode,List<Long> roomIds) {}
    TypeRef typeWithRooms(int count)throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        long typeId=db.queryForObject("insert into room_types(type_code,type_name,suggested_occupancy) values(?,'预订测试房型',2) returning id",Long.class,"RVT"+suffix);
        var roomsList=new ArrayList<Long>();
        for(int i=0;i<count;i++)
            roomsList.add(db.queryForObject("insert into rooms(room_number,floor,room_type_id) values(?,8,?) returning id",Long.class,"RVT"+suffix.substring(0,6)+String.format("%02d",i),typeId));
        return new TypeRef(typeId,"RVT"+suffix,roomsList);
    }
    JsonNode createReservation(Browser front,long typeId,String arrival,String departure,String externalRef)throws Exception {
        return ok(front.post("/api/reservations",Map.of("arrivalDate",arrival,"departureDate",departure,"roomTypeId",typeId,
            "source","OTA","externalRef",externalRef,"contactName","预订测试联系人")));
    }
    JsonNode availability(Browser front,long typeId,String arrival,String departure)throws Exception {
        var all=ok(front.get("/api/availability?arrivalDate="+arrival+"&departureDate="+departure));
        for(var t:all.path("roomTypes")) if(t.path("roomTypeId").asLong()==typeId) return t;
        throw new IllegalStateException("room type missing from availability response");
    }
    long soldOf(long typeId,String date) {
        var rows=db.queryForList("select sold from inventory_days where room_type_id=? and stay_date=CAST(? AS date)",Integer.class,typeId,date);
        return rows.isEmpty()?0:rows.get(0);
    }
    Map<Object,Object> guest(String name,boolean primary) {
        var g=new HashMap<Object,Object>();g.put("name",name);g.put("documentType","ID_CARD");
        g.put("documentNumber","11010119900101234"+(primary?"1":"2"));
        g.put("phone","13800001234");g.put("primary",primary);return g;
    }

    @Test @Order(1) void createLifecyclePermissionsAndExternalRef()throws Exception {
        var hk=login("HOUSEKEEPING_STAFF");var front=login("FRONT_DESK");
        assertThat(hk.get("/api/reservations").statusCode()).isEqualTo(403);
        var type=typeWithRooms(1);
        // Held by default with an explicit expiry; replaying the same key returns the same reservation.
        String createKey=key();
        var reservation=ok(front.post("/api/reservations",Map.of("arrivalDate",day(1),"departureDate",day(3),"roomTypeId",type.typeId(),
            "source","PHONE","externalRef","OTA-1","contactName","预订测试联系人","contactPhone","13800001234"),createKey));
        long id=reservation.path("reservation").path("id").asLong();
        assertThat(reservation.path("reservation").path("status").asText()).isEqualTo("HELD");
        assertThat(reservation.path("reservation").path("heldUntil")).isNotNull();
        assertThat(reservation.path("reservation").path("contactPhoneMask").asText()).isEqualTo("13****34");
        var replay=ok(front.post("/api/reservations",Map.of("arrivalDate",day(1),"departureDate",day(3),"roomTypeId",type.typeId(),
            "source","PHONE","externalRef","OTA-1","contactName","预订测试联系人","contactPhone","13800001234"),createKey));
        assertThat(replay.path("reservation").path("id").asLong()).isEqualTo(id);
        assertThat(error(front.post("/api/reservations",Map.of("arrivalDate",day(1),"departureDate",day(3),"roomTypeId",type.typeId(),
            "source","PHONE","externalRef","OTA-1","contactName","预订测试联系人")))).isEqualTo("EXTERNAL_REF_EXISTS");
        assertThat(error(front.post("/api/reservations",Map.of("arrivalDate",day(3),"departureDate",day(3),"roomTypeId",type.typeId(),
            "source","PHONE","contactName","预订测试联系人")))).isEqualTo("VALIDATION_ERROR");
        // One sold night per night of [arrival,departure).
        assertThat(soldOf(type.typeId(),day(1))).isEqualTo(1);
        assertThat(soldOf(type.typeId(),day(2))).isEqualTo(1);
        // Detail carries the masked phone, the event trail, and never the ciphertext.
        var detail=ok(front.get("/api/reservations/"+id));
        assertThat(detail.path("events").get(0).path("action").asText()).isEqualTo("CREATE");
        assertThat(detail.path("deposits").isEmpty()).isTrue();
        assertThat(db.queryForList("select contact_phone_ciphertext from reservations where id=?",String.class,id).get(0)).isNotNull();
        assertThat(json.writeValueAsString(detail)).doesNotContain("v1.");
    }

    @Test @Order(2) void availabilityShowsSoldNightsAndLastRoomConcurrency()throws Exception {
        var front=login("FRONT_DESK");
        var type=typeWithRooms(1);  // exactly one sellable room
        var free=availability(front,type.typeId(),day(1),day(3));
        assertThat(free.path("bookable").asLong()).isEqualTo(1); // bookable=min(available): one room, both nights free
        // Two concurrent sessions fight over the last room: exactly one wins, the loser names the sold-out night.
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=CompletableFuture.supplyAsync(()-> {try {return login("FRONT_DESK").request("POST","/api/reservations",
                Map.of("arrivalDate",day(1),"departureDate",day(2),"roomTypeId",type.typeId(),"source","OTA",
                    "externalRef","CON-A","contactName","预订测试联系人"),key());}catch(Exception e){throw new RuntimeException(e);}},pool);
            var second=CompletableFuture.supplyAsync(()-> {try {return login("FRONT_DESK").request("POST","/api/reservations",
                Map.of("arrivalDate",day(1),"departureDate",day(2),"roomTypeId",type.typeId(),"source","OTA",
                    "externalRef","CON-B","contactName","预订测试联系人"),key());}catch(Exception e){throw new RuntimeException(e);}},pool);
            int created=0,soldOut=0;
            for(var future:List.of(first,second)) {
                var response=future.get(30,TimeUnit.SECONDS);
                if(response.statusCode()==200) created++;
                else if(response.statusCode()==409 && errorCode(response).equals("ROOM_TYPE_SOLD_OUT")) soldOut++;
                else fail("unexpected response: "+response.statusCode()+" "+response.body());
            }
            assertThat(created).isEqualTo(1);assertThat(soldOut).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        var after=availability(front,type.typeId(),day(1),day(3));
        assertThat(after.path("bookable").asLong()).isZero(); // day1 fully sold
        assertThat(soldOf(type.typeId(),day(1))).isEqualTo(1);
        // Cancel releases exactly the sold nights it held; a second cancel is refused.
        long id=db.queryForObject("select id from reservations where room_type_id=? and status='HELD' order by id limit 1",Long.class,type.typeId());
        ok(front.post("/api/reservations/"+id+"/cancel",Map.of("reason","预订测试取消")));
        assertThat(soldOf(type.typeId(),day(1))).isZero();
        assertThat(availability(front,type.typeId(),day(1),day(3)).path("bookable").asLong()).isEqualTo(1);
        assertThat(error(front.post("/api/reservations/"+id+"/cancel",Map.of("reason","重复取消")))).isEqualTo("RESERVATION_STATE");
    }

    @Test @Order(3) void confirmAssignAndCheckInWithoutDoubleCounting()throws Exception {
        var front=login("FRONT_DESK");
        var type=typeWithRooms(2);
        var foreign=typeWithRooms(1);
        var reservation=ok(front.post("/api/reservations",Map.of("arrivalDate",day(0),"departureDate",day(2),"roomTypeId",type.typeId(),
            "source","WALK_IN","contactName","预订测试联系人")));
        long id=reservation.path("reservation").path("id").asLong();
        // Assignment is advisory: matching type required, physical eligibility still decided at check-in.
        assertThat(error(front.post("/api/reservations/"+id+"/assign-room",Map.of()))).isEqualTo("VALIDATION_ERROR");
        assertThat(error(front.post("/api/reservations/"+id+"/assign-room",Map.of("roomId",999999)))).isEqualTo("ROOM_NOT_FOUND");
        assertThat(error(front.post("/api/reservations/"+id+"/assign-room",Map.of("roomId",foreign.roomIds().get(0))))).isEqualTo("ROOM_TYPE_MISMATCH");
        var assigned=ok(front.post("/api/reservations/"+id+"/assign-room",Map.of("roomId",type.roomIds().get(1))));
        assertThat(assigned.path("reservation").path("assignedRoomNumber").asText()).isNotEmpty();
        // Confirm then check in: a dirty room is still refused by RoomEligibility.
        ok(front.post("/api/reservations/"+id+"/confirm"));
        long dirtyRoom=type.roomIds().get(0);
        db.update("update rooms set cleaning_status='DIRTY' where id=?",dirtyRoom);
        assertThat(error(front.post("/api/reservations/"+id+"/check-in",Map.of("roomId",dirtyRoom,
            "expectedCheckOutAt",noon(2),"guests",List.of(guest("预订测试住客甲",true)))))).isEqualTo("ROOM_NOT_AVAILABLE");
        db.update("update rooms set cleaning_status='CLEAN' where id=?",dirtyRoom);
        var checkIn=ok(front.post("/api/reservations/"+id+"/check-in",Map.of("roomId",dirtyRoom,
            "expectedCheckOutAt",noon(2),"guests",List.of(guest("预订测试住客甲",true))),key()));
        long stayId=checkIn.path("stayId").asLong();
        assertThat(checkIn.path("reservationId").asLong()).isEqualTo(id);
        // Sold released, occupancy lives in the stay segment — the same night is never counted twice.
        assertThat(soldOf(type.typeId(),day(0))).isZero();
        assertThat(soldOf(type.typeId(),day(1))).isZero();
        var av=availability(front,type.typeId(),day(1),day(2));
        assertThat(av.path("nights").get(0).path("occupied").asLong()).isEqualTo(1);
        assertThat(av.path("nights").get(0).path("available").asLong()).isEqualTo(1);
        var detail=ok(front.get("/api/reservations/"+id));
        assertThat(detail.path("reservation").path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(detail.path("reservation").path("checkedInStayId").asLong()).isEqualTo(stayId);
        assertThat(error(front.post("/api/reservations/"+id+"/check-in",Map.of("roomId",dirtyRoom,
            "expectedCheckOutAt",noon(2),"guests",List.of(guest("预订测试住客乙",true)))))).isEqualTo("RESERVATION_STATE");
        assertThat(error(front.post("/api/reservations/"+id+"/cancel",Map.of("reason","迟到取消")))).isEqualTo("RESERVATION_STATE");
        // A second reservation fills the remaining room; the third night would oversell and is refused.
        ok(front.post("/api/reservations",Map.of("arrivalDate",day(0),"departureDate",day(2),"roomTypeId",type.typeId(),
            "source","PHONE","contactName","预订测试联系人")));
        assertThat(error(front.post("/api/reservations",Map.of("arrivalDate",day(0),"departureDate",day(2),"roomTypeId",type.typeId(),
            "source","PHONE","contactName","预订测试联系人")))).isEqualTo("ROOM_TYPE_SOLD_OUT");
    }

    @Test @Order(4) void modifyRollbackKeepsOriginalInventory()throws Exception {
        var front=login("FRONT_DESK");
        var type=typeWithRooms(1); // one room: day1-2 are full with the first reservation alone
        var first=createReservation(front,type.typeId(),day(1),day(3),"MOD-A");
        var second=createReservation(front,type.typeId(),day(5),day(7),"MOD-B");
        long firstId=first.path("reservation").path("id").asLong();
        long secondId=second.path("reservation").path("id").asLong();
        long firstVersion=first.path("reservation").path("version").asLong();
        long secondVersion=second.path("reservation").path("version").asLong();
        // Days 1-2 are full: moving the second reservation there must fail and preserve it entirely.
        assertThat(error(front.post("/api/reservations/"+secondId+"/modify",Map.of("version",secondVersion,
            "arrivalDate",day(1),"departureDate",day(3))))).isEqualTo("ROOM_TYPE_SOLD_OUT");
        assertThat(db.queryForObject("select arrival_date::text from reservations where id=?",String.class,secondId)).isEqualTo(day(5));
        assertThat(soldOf(type.typeId(),day(5))).isEqualTo(1);
        assertThat(soldOf(type.typeId(),day(1))).isEqualTo(1);
        // A valid move shifts the sold nights exactly once; a stale version is refused.
        var moved=ok(front.post("/api/reservations/"+secondId+"/modify",Map.of("version",secondVersion,
            "arrivalDate",day(3),"departureDate",day(5))));
        assertThat(moved.path("reservation").path("arrivalDate").asText()).isEqualTo(day(3));
        assertThat(soldOf(type.typeId(),day(5))).isZero();
        assertThat(soldOf(type.typeId(),day(3))).isEqualTo(1);
        assertThat(soldOf(type.typeId(),day(4))).isEqualTo(1);
        assertThat(error(front.post("/api/reservations/"+secondId+"/modify",Map.of("version",secondVersion,"arrivalDate",day(6),"departureDate",day(8)))))
            .isEqualTo("STALE_VERSION");
        // Extending the first reservation onto days 3-4 now collides with the moved second reservation; rollback keeps day1 sold.
        assertThat(error(front.post("/api/reservations/"+firstId+"/modify",Map.of("version",firstVersion,
            "arrivalDate",day(1),"departureDate",day(5))))).isEqualTo("ROOM_TYPE_SOLD_OUT");
        assertThat(soldOf(type.typeId(),day(1))).isEqualTo(1);
        // Notes-only changes do not touch inventory.
        ok(front.post("/api/reservations/"+firstId+"/modify",Map.of("version",firstVersion,"notes","改个备注")));
        assertThat(soldOf(type.typeId(),day(1))).isEqualTo(1);
    }

    @Test @Order(5) void expiredHoldsReleaseIdempotentlyAndNoShowReleases()throws Exception {
        var front=login("FRONT_DESK");
        var type=typeWithRooms(1);
        String past=OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString();
        var expired=ok(front.post("/api/reservations",Map.of("arrivalDate",day(1),"departureDate",day(3),"roomTypeId",type.typeId(),
            "source","OTA","externalRef","EXP-1","contactName","预订测试联系人","heldUntil",past)));
        long expiredId=expired.path("reservation").path("id").asLong();
        // The sweep cancels exactly the expired hold, releases its nights, and is idempotent.
        var sweep=ok(front.post("/api/reservations/release-expired"));
        assertThat(sweep.path("released").asInt()).isEqualTo(1);
        assertThat(soldOf(type.typeId(),day(1))).isZero();
        assertThat(db.queryForObject("select status from reservations where id=?",String.class,expiredId)).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("select cancel_reason from reservations where id=?",String.class,expiredId)).isEqualTo("保留单到期自动释放");
        var sweepAgain=ok(front.post("/api/reservations/release-expired"));
        assertThat(sweepAgain.path("released").asInt()).isZero();
        // No-show applies to confirmed reservations only and releases the nights once.
        var held=ok(front.post("/api/reservations",Map.of("arrivalDate",day(4),"departureDate",day(5),"roomTypeId",type.typeId(),
            "source","PHONE","contactName","预订测试联系人")));
        long heldId=held.path("reservation").path("id").asLong();
        assertThat(error(front.post("/api/reservations/"+heldId+"/mark-no-show"))).isEqualTo("RESERVATION_STATE");
        ok(front.post("/api/reservations/"+heldId+"/confirm"));
        ok(front.post("/api/reservations/"+heldId+"/mark-no-show",Map.of("reason","预订测试未到店")));
        assertThat(soldOf(type.typeId(),day(4))).isZero();
        assertThat(db.queryForObject("select status from reservations where id=?",String.class,heldId)).isEqualTo("NO_SHOW");
        assertThat(error(front.post("/api/reservations/"+heldId+"/mark-no-show"))).isEqualTo("RESERVATION_STATE");
    }

    @Test @Order(6) void depositsStaySingleFactsThroughCheckInAndFolio()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER"); // refunds carry billing:refund (manager only)
        var type=typeWithRooms(1);
        var reservation=ok(front.post("/api/reservations",Map.of("arrivalDate",day(0),"departureDate",day(2),"roomTypeId",type.typeId(),
            "source","PHONE","contactName","预订测试联系人")));
        long id=reservation.path("reservation").path("id").asLong();
        // The deposit is a real V2 money record attached to the booking, no folio yet.
        var deposit=ok(front.post("/api/reservations/"+id+"/deposits",Map.of("method","CASH","amount","100","note","预订测试订金")));
        long depositId=deposit.path("id").asLong();
        assertThat(deposit.path("amount").asText()).isEqualTo("100.00");
        assertThat(deposit.path("folioId").isNull()).isTrue();
        assertThat(deposit.path("direction").asText()).isEqualTo("RECEIPT");
        assertThat(error(manager.post("/api/reservations/"+id+"/deposits/refund",Map.of("paymentRecordId",depositId,"amount","150","reason","超退")))).isEqualTo("REFUND_EXCEEDS_LIMIT");
        // Partial refund, then check in; the remaining deposit follows the booking into its folio exactly once.
        ok(manager.post("/api/reservations/"+id+"/deposits/refund",Map.of("paymentRecordId",depositId,"amount","30","reason","部分退订金")));
        ok(front.post("/api/reservations/"+id+"/confirm"));
        db.update("update rooms set cleaning_status='CLEAN' where id=?",type.roomIds().get(0));
        var checkIn=ok(front.post("/api/reservations/"+id+"/check-in",Map.of("roomId",type.roomIds().get(0),
            "expectedCheckOutAt",noon(2),"guests",List.of(guest("预订测试住客甲",true)))));
        long stayId=checkIn.path("stayId").asLong();
        assertThat(error(front.post("/api/reservations/"+id+"/deposits",Map.of("method","CASH","amount","50")))).isEqualTo("RESERVATION_STATE");
        assertThat(error(manager.post("/api/reservations/"+id+"/deposits/refund",Map.of("paymentRecordId",depositId,"amount","1","reason","已入客账")))).isEqualTo("RESERVATION_STATE");
        // Opening a folio requires an active finance policy (V2 baseline).
        ok(manager.post("/api/billing/policies",Map.of("billingCutoffTime","04:00","earlyCheckinMode","FULL_NIGHT",
            "lateCheckoutMode","HOURLY","earlyCheckoutMode","FULL_NIGHT","roomChangePriceMode","BY_NEW_SEGMENT","extensionPriceMode","CURRENT_RATE")));
        var folio=ok(front.post("/api/stays/"+stayId+"/folio",Map.of()));
        long folioId=folio.path("folio").path("id").asLong();
        assertThat(folio.path("folio").path("summary").path("receipts").asText()).isEqualTo("100.00");
        assertThat(folio.path("folio").path("summary").path("refunds").asText()).isEqualTo("30.00");
        var migrated=db.queryForMap("select folio_id,reservation_id from payment_records where id=?",depositId);
        assertThat(migrated.get("folio_id")).isEqualTo(folioId);
        assertThat(migrated.get("reservation_id")).isEqualTo(id);
        // The folio lists both deposit rows exactly once; re-opening or replaying never duplicates money.
        var detail=ok(front.get("/api/folios/"+folioId));
        assertThat(detail.path("payments").size()).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from payment_records where reservation_id=?",Integer.class,id)).isEqualTo(2);
        var reservationDetail=ok(front.get("/api/reservations/"+id));
        assertThat(reservationDetail.path("deposits").get(0).path("folioId").asLong()).isEqualTo(folioId);
    }

    @Test @Order(7) void cancelRefundsDepositAndOverstayPrompts()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var type=typeWithRooms(1);
        var reservation=ok(front.post("/api/reservations",Map.of("arrivalDate",day(1),"departureDate",day(3),"roomTypeId",type.typeId(),
            "source","OTA","externalRef","DEP-C","contactName","预订测试联系人")));
        long id=reservation.path("reservation").path("id").asLong();
        var deposit=ok(front.post("/api/reservations/"+id+"/deposits",Map.of("method","BANK_CARD","amount","200")));
        long depositId=deposit.path("id").asLong();
        assertThat(error(manager.post("/api/reservations/"+id+"/deposits/refund",Map.of("paymentRecordId",depositId,"amount","0","reason","零退")))).isEqualTo("VALIDATION_ERROR");
        ok(front.post("/api/reservations/"+id+"/cancel",Map.of("reason","预订测试取消，退全款")));
        assertThat(soldOf(type.typeId(),day(1))).isZero();
        ok(manager.post("/api/reservations/"+id+"/deposits/refund",Map.of("paymentRecordId",depositId,"amount","200","reason","取消退全款")));
        assertThat(error(manager.post("/api/reservations/"+id+"/deposits/refund",Map.of("paymentRecordId",depositId,"amount","1","reason","再退"))))
            .isEqualTo("REFUND_EXCEEDS_LIMIT");
        assertThat(error(front.post("/api/reservations/"+id+"/deposits",Map.of("method","CASH","amount","50")))).isEqualTo("RESERVATION_STATE");
        // Overstay horizon: another confirmed reservation holds the night AFTER this one's departure, so an
        // over-extended check-in conflicts by name; the system never auto-evicts anyone.
        var second=typeWithRooms(1);
        db.update("update rooms set cleaning_status='CLEAN' where id=?",second.roomIds().get(0));
        var future=ok(front.post("/api/reservations",Map.of("arrivalDate",day(1),"departureDate",day(2),"roomTypeId",second.typeId(),
            "source","PHONE","contactName","预订测试联系人")));
        long futureId=future.path("reservation").path("id").asLong();
        ok(front.post("/api/reservations/"+futureId+"/confirm"));
        var later=ok(front.post("/api/reservations",Map.of("arrivalDate",day(2),"departureDate",day(3),"roomTypeId",second.typeId(),
            "source","OTA","externalRef","OVER-1","contactName","预订测试联系人")));
        ok(front.post("/api/reservations/"+later.path("reservation").path("id").asLong()+"/confirm"));
        assertThat(error(front.post("/api/reservations/"+futureId+"/check-in",Map.of("roomId",second.roomIds().get(0),
            "expectedCheckOutAt",noon(3),"guests",List.of(guest("预订测试住客甲",true)))))).isEqualTo("FUTURE_NIGHT_CONFLICT");
        assertThat(db.queryForObject("select status from reservations where id=?",String.class,futureId)).isEqualTo("CONFIRMED");
        // Checking out on the booked departure day is fine.
        var checkIn=ok(front.post("/api/reservations/"+futureId+"/check-in",Map.of("roomId",second.roomIds().get(0),
            "expectedCheckOutAt",noon(2),"guests",List.of(guest("预订测试住客甲",true)))));
        assertThat(checkIn.path("stayId").asLong()).isPositive();
    }

    @Test @Order(8) void blocksStopSalesAndReleaseRestores()throws Exception {
        var front=login("FRONT_DESK");var manager=login("HOTEL_MANAGER");
        var type=typeWithRooms(2);
        assertThat(front.get("/api/inventory/blocks").statusCode()).isEqualTo(403);
        assertThat(error(front.post("/api/inventory/blocks",Map.of("roomTypeId",type.typeId(),"startDate",day(2),"endDate",day(4),"reason","越权停售")))).isEqualTo("FORBIDDEN");
        var block=ok(manager.post("/api/inventory/blocks",Map.of("roomTypeId",type.typeId(),"startDate",day(2),"endDate",day(4),"reason","计划装修停售")));
        long blockId=block.path("id").asLong();
        assertThat(block.path("releasedAt").isNull()).isTrue();
        var av=availability(front,type.typeId(),day(1),day(5));
        assertThat(av.path("nights").get(0).path("available").asLong()).isEqualTo(2);
        assertThat(av.path("nights").get(1).path("available").asLong()).isZero();
        assertThat(av.path("nights").get(1).path("blocked").asLong()).isEqualTo(2);
        assertThat(av.path("bookable").asLong()).isZero();
        assertThat(error(front.post("/api/reservations",Map.of("arrivalDate",day(2),"departureDate",day(4),"roomTypeId",type.typeId(),
            "source","OTA","externalRef","BLK-A","contactName","预订测试联系人")))).isEqualTo("ROOM_TYPE_SOLD_OUT");
        // Booking that ends exactly at the block start is fine ([start,end) semantics).
        var before=createReservation(front,type.typeId(),day(1),day(2),"BLK-B");
        assertThat(before.path("reservation").path("status").asText()).isEqualTo("HELD");
        var released=ok(manager.post("/api/inventory/blocks/"+blockId+"/release"));
        assertThat(released.path("releasedAt")).isNotNull();
        assertThat(error(manager.post("/api/inventory/blocks/"+blockId+"/release"))).isEqualTo("BLOCK_STATE");
        assertThat(availability(front,type.typeId(),day(1),day(5)).path("bookable").asLong()).isEqualTo(1); // BLK-B still holds day1
        var created=createReservation(front,type.typeId(),day(2),day(4),"BLK-C");
        assertThat(created.path("reservation").path("id").asLong()).isPositive();
    }

    @Test @Order(9) void extensionConflictsWithFutureSoldNights()throws Exception {
        var front=login("FRONT_DESK");
        var type=typeWithRooms(1);
        db.update("update rooms set cleaning_status='CLEAN' where id=?",type.roomIds().get(0));
        // Walk-in stay tonight (checkout tomorrow noon); a reservation holds tomorrow night — the type's only room.
        var checkIn=ok(front.post("/api/stays",Map.of("roomId",type.roomIds().get(0),"expectedCheckOutAt",noon(1),
            "guests",List.of(guest("预订测试住客甲",true)))));
        long stayId=checkIn.path("id").asLong();
        long segmentId=db.queryForObject("select id from stay_room_segments where stay_record_id=? and exited_at is null",Long.class,stayId);
        var future=ok(front.post("/api/reservations",Map.of("arrivalDate",day(1),"departureDate",day(2),"roomTypeId",type.typeId(),
            "source","OTA","externalRef","EXT-1","contactName","预订测试联系人")));
        long reservationId=future.path("reservation").path("id").asLong();
        // Extending the stay into the sold night is refused by name; the guest is never auto-evicted.
        assertThat(error(front.post("/api/stays/"+stayId+"/extensions",Map.of("segmentId",segmentId,"version",
            db.queryForObject("select version from stay_records where id=?",Integer.class,stayId),
            "newExpectedCheckOutAt",noon(2),"reason","预订测试续住冲突")))).isEqualTo("FUTURE_NIGHT_CONFLICT");
        // The future reservation is intact; once it lets go, the same extension succeeds.
        assertThat(db.queryForObject("select status from reservations where id=?",String.class,reservationId)).isEqualTo("HELD");
        ok(front.post("/api/reservations/"+reservationId+"/cancel",Map.of("reason","预订测试让房")));
        var extended=ok(front.post("/api/stays/"+stayId+"/extensions",Map.of("segmentId",segmentId,"version",
            db.queryForObject("select version from stay_records where id=?",Integer.class,stayId),
            "newExpectedCheckOutAt",noon(2),"reason","预订测试续住")));
        assertThat(extended.path("newExpectedCheckOutAt")).isNotNull();
    }

    @Test @Order(10) void realBrowserAcceptance()throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("stayos.e2e"),"Browser tests explicitly enabled");
        var front=login("FRONT_DESK");
        var type=typeWithRooms(2);
        db.update("update rooms set cleaning_status='CLEAN' where id=?",type.roomIds().get(0));
        var reservation=ok(front.post("/api/reservations",Map.of("arrivalDate",day(1),"departureDate",day(3),"roomTypeId",type.typeId(),
            "source","OTA","externalRef","E2E-1","contactName","预订测试联系人","contactPhone","13800001234")));
        long id=reservation.path("reservation").path("id").asLong();
        String roomNumber=db.queryForObject("select room_number from rooms where id=?",String.class,type.roomIds().get(0));
        var fixtures=new LinkedHashMap<String,Object>();
        fixtures.put("reservationId",id);fixtures.put("typeId",type.typeId());fixtures.put("typeCode",type.typeCode());
        fixtures.put("typeName","预订测试房型");fixtures.put("roomId",type.roomIds().get(0));fixtures.put("roomNumber",roomNumber);
        fixtures.put("arrival",day(1));fixtures.put("departure",day(3));
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).getParent();var log=root.resolve(".local/reservation-browser.log");
        var builder=new ProcessBuilder("cmd.exe","/d","/c","npm","run","test:e2e","--","reservations.spec.ts").directory(root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("STAYOS_E2E_API_URL","http://127.0.0.1:"+port);builder.environment().put("STAYOS_E2E_PASSWORD",password);
        builder.environment().put("STAYOS_RESERVATION_FIXTURES",json.writeValueAsString(fixtures));builder.environment().put("STAYOS_E2E_REPORT","../.local/playwright-reservation-results.json");
        var process=builder.start();if(!process.waitFor(300,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();fail("Reservation browser timeout");}
        assertThat(process.exitValue()).as("See private reservation-browser.log").isZero();
        // The browser flow ends with the booking checked in: sold nights released exactly once, occupancy lives in the stay segment.
        assertThat(db.queryForObject("select status from reservations where id=?",String.class,id)).isEqualTo("CHECKED_IN");
        assertThat(soldOf(type.typeId(),day(1))).isZero();
        assertThat(soldOf(type.typeId(),day(2))).isZero();
        long stayId=db.queryForObject("select checked_in_stay_id from reservations where id=?",Long.class,id);
        assertThat(db.queryForObject("select count(*) from stay_room_segments where stay_record_id=? and exited_at is null",Integer.class,stayId)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from payment_records where reservation_id=?",Integer.class,id)).isEqualTo(1);
    }
}
