package com.stayos.reservation;

import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.billing.BillingDtos;
import com.stayos.billing.BillingMapper.PaymentRow;
import com.stayos.billing.BillingMapper.SumRow;
import com.stayos.cashier.CashierMapper;
import com.stayos.common.ApiException;
import com.stayos.reservation.ReservationDtos.*;
import com.stayos.reservation.ReservationMapper.*;
import com.stayos.room.mapper.RoomMapper;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.stay.dto.StayRequests;
import com.stayos.stay.mapper.StayMapper;
import com.stayos.stay.service.CheckInService;
import com.stayos.stay.service.GuestProtection;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Reservation lifecycle and future inventory per guide section 13.
 * Lock order (5.4): idempotency -> actor -> reservation -> date inventory (room_type_id, stay_date ascending)
 * -> stay -> folio -> room -> segments -> tasks. A failed modify rolls back and keeps the original reservation.
 */
@Service
public class ReservationService {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final int MAX_NIGHTS=365;
    private static final Set<String> SOURCES=Set.of("PHONE","WALK_IN","OTA","OTHER");
    private final ReservationMapper reservations;
    private final StayMapper requestsMapper;
    private final IdempotencyService requests;
    private final BusinessActorGuard actors;
    private final RoomMapper rooms;
    private final CheckInService checkIns;
    private final GuestProtection protection;
    private final CashierMapper cashierShifts;
    private final AuditService audit;
    private final com.stayos.integration.ChannelOutboxRecorder channelOutbox;

    public ReservationService(ReservationMapper reservations,StayMapper requestsMapper,IdempotencyService requests,
        BusinessActorGuard actors,RoomMapper rooms,CheckInService checkIns,GuestProtection protection,
        CashierMapper cashierShifts,AuditService audit,com.stayos.integration.ChannelOutboxRecorder channelOutbox) {
        this.reservations=reservations;this.requestsMapper=requestsMapper;this.requests=requests;this.actors=actors;
        this.rooms=rooms;this.checkIns=checkIns;this.protection=protection;this.cashierShifts=cashierShifts;this.audit=audit;
        this.channelOutbox=channelOutbox;
    }

    // ---------- queries ----------
    @Transactional(readOnly=true)
    public Page<ReservationView> list(long actor,String status,String arrivalFrom,String arrivalTo,String query,int page,int pageSize) {
        actors.requireRead(actor,"reservation:manage");
        var q=queryOf(status,arrivalFrom,arrivalTo,query,page,pageSize);
        var items=reservations.list(q,q.offset()).stream().map(this::view).toList();
        return new Page<>(items,reservations.count(q),q.page(),q.pageSize());
    }
    @Transactional(readOnly=true)
    public ReservationDetail detail(long actor,long id) {
        actors.requireRead(actor,"reservation:manage");
        var row=reservations.byId(id);
        if(row==null) throw notFound();
        var events=reservations.events(id).stream().map(e->new ReservationEventView(e.id,e.action,e.actorName,e.detail,e.createdAt)).toList();
        var deposits=reservations.deposits(id).stream().map(this::depositView).toList();
        return new ReservationDetail(view(row),events,deposits);
    }
    @Transactional(readOnly=true)
    public Object receipt(long actor,String operation,String key) {
        if(key==null || !key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw invalid("请求标识无效");
        if(!Set.of("RESERVATION_CREATE","RESERVATION_MODIFY","RESERVATION_CONFIRM","RESERVATION_CANCEL","RESERVATION_NO_SHOW",
            "RESERVATION_ASSIGN_ROOM","RESERVATION_CHECK_IN","RESERVATION_DEPOSIT_RECEIVE","RESERVATION_DEPOSIT_REFUND").contains(operation))
            throw invalid("操作类型无效");
        var request=requestsMapper.request(actor,operation,UUID.fromString(key).toString());
        if(request==null || request.resultReservationId==null)
            throw new ApiException(404,"OPERATION_NOT_FOUND","尚未查到成功结果，请核对预订记录，勿直接新建请求");
        return detail(actor,request.resultReservationId);
    }

    // ---------- lifecycle ----------
    @Transactional
    public ReservationDetail create(long actor,String key,CreateInput input) {
        if(input==null) throw invalid("请提供预订内容");
        var arrival=parseDate(input.arrivalDate(),"到店日期");
        var departure=parseDate(input.departureDate(),"离店日期");
        if(!departure.isAfter(arrival)) throw invalid("离店日期必须晚于到店日期（[到店, 离店) 半开区间，至少一晚）");
        if(arrival.isBefore(businessToday())) throw invalid("到店日期不能早于当前营业日");
        var nights=nights(arrival,departure);
        if(input.roomTypeId()==null || input.roomTypeId()<1 || reservations.roomTypeEnabled(input.roomTypeId())!=1)
            throw new ApiException(404,"ROOM_TYPE_NOT_FOUND","房型不存在或已停用");
        if(input.source()==null || !SOURCES.contains(input.source())) throw invalid("预订来源只能是电话、线下、OTA 或其他");
        String externalRef=input.externalRef()==null?null:input.externalRef().trim();
        if(externalRef!=null && externalRef.isEmpty()) externalRef=null;
        if(externalRef!=null && externalRef.length()>120) throw invalid("外部单号最长 120 字");
        String contactName=input.contactName()==null?"":input.contactName().trim();
        if(contactName.isEmpty() || contactName.length()>100 || contactName.chars().anyMatch(Character::isISOControl)) throw invalid("请填写有效的联系人姓名");
        String phone=input.contactPhone()==null?"":input.contactPhone().trim();
        if(!phone.isEmpty() && !phone.matches("[+0-9() -]{3,30}")) throw invalid("联系电话格式不正确");
        String notes=input.notes()==null?"":input.notes().trim();
        if(notes.length()>1000) throw invalid("备注不能超过 1000 字");
        var heldUntil=parseHeldUntil(input.heldUntil());
        // Canonical input carries the RAW held-until string: the default (now+24h) is computed per call and
        // would make an identical retry hash differently. Normalized fields here are stable for identical input.
        var canonical=new CanonicalCreate(arrival.toString(),departure.toString(),input.roomTypeId(),input.source(),externalRef,contactName,phone,input.heldUntil()==null?"":input.heldUntil().trim(),notes);
        Long previous=requests.reserveReservation(actor,"RESERVATION_CREATE",key,canonical);
        actors.require(actor,"reservation:manage");
        if(previous!=null) return detail(actor,previous);
        if(externalRef!=null && reservations.idByExternalRef(externalRef)!=null)
            throw conflict("EXTERNAL_REF_EXISTS","外部单号已存在，不能重复建档");
        var row=new Row();
        row.arrivalDate=arrival; row.departureDate=departure; row.roomTypeId=input.roomTypeId(); row.source=input.source();
        row.externalRef=externalRef; row.contactName=contactName;
        row.contactPhoneMask="";                                     // phone is optional; mask column is NOT NULL
        if(!phone.isEmpty()) { row.contactPhoneCiphertext=protection.encrypt(phone,"phone"); row.contactPhoneMask=protection.mask(phone); row.contactEncryptionKeyId=GuestProtection.KEY_ID; }
        row.heldUntil=heldUntil; row.notes=notes; row.createdBy=actor;
        occupyRange(input.roomTypeId(),arrival,departure);           // lock rows exist, lock ordered, conflicts raise 409
        if(reservations.insert(row)!=1) throw new IllegalStateException("Reservation was not saved");
        reservations.insertEvent(row.id,"CREATE",actor,externalRef==null?"来源 "+input.source():"来源 "+input.source()+"，外部单号 "+externalRef);
        audit.success(actor,"RESERVATION_CREATE","RESERVATION",row.id);
        requests.completeReservation(actor,"RESERVATION_CREATE",key,row.id);
        return detail(actor,row.id);
    }

    /**
     * Dates/room-type changes lock BOTH the old and new night ranges (ascending by room type id and date), release
     * the old sold nights, re-verify availability for the new ones, then occupy. Any failure rolls the whole
     * transaction back and the original reservation with its original inventory stays intact.
     */
    @Transactional
    public ReservationDetail modify(long actor,String key,long id,ModifyInput input) {
        if(input==null || input.version()==null || input.version()<0) throw invalid("请提供当前预订版本");
        String phone=input.contactPhone()==null?"":input.contactPhone().trim();
        if(!phone.isEmpty() && !phone.matches("[+0-9() -]{3,30}")) throw invalid("联系电话格式不正确");
        String contactName=input.contactName()==null?null:input.contactName().trim();
        if(contactName!=null && (contactName.isEmpty() || contactName.length()>100 || contactName.chars().anyMatch(Character::isISOControl)))
            throw invalid("请填写有效的联系人姓名");
        String notes=input.notes()==null?null:input.notes().trim();
        if(notes!=null && notes.length()>1000) throw invalid("备注不能超过 1000 字");
        Long newRoomTypeId=input.roomTypeId();
        var canonical=new CanonicalModify(id,input.version(),input.arrivalDate(),input.departureDate(),newRoomTypeId,contactName,phone,notes);
        Long previous=requests.reserveReservation(actor,"RESERVATION_MODIFY",key,canonical);
        actors.require(actor,"reservation:manage");
        if(previous!=null) return detail(actor,previous);
        var row=locked(id);
        if(!"HELD".equals(row.status) && !"CONFIRMED".equals(row.status))
            throw conflict("RESERVATION_STATE","只有保留或已确认的预订可以修改");
        if(row.version!=input.version()) throw conflict("STALE_VERSION","预订已被其他人更新，请刷新后重新提交");
        var newArrival=input.arrivalDate()==null||input.arrivalDate().isBlank()?row.arrivalDate:parseDate(input.arrivalDate(),"到店日期");
        var newDeparture=input.departureDate()==null||input.departureDate().isBlank()?row.departureDate:parseDate(input.departureDate(),"离店日期");
        if(!newDeparture.isAfter(newArrival)) throw invalid("离店日期必须晚于到店日期");
        long effectiveRoomType=newRoomTypeId==null?row.roomTypeId:newRoomTypeId;
        if(newRoomTypeId!=null && newRoomTypeId!=row.roomTypeId && reservations.roomTypeEnabled(newRoomTypeId)!=1)
            throw new ApiException(404,"ROOM_TYPE_NOT_FOUND","房型不存在或已停用");
        boolean sameRange=newArrival.equals(row.arrivalDate) && newDeparture.equals(row.departureDate) && effectiveRoomType==row.roomTypeId;
        boolean phoneChanged=!phone.isEmpty();
        if(sameRange && contactName==null && notes==null && !phoneChanged) throw invalid("未做任何修改，无需提交");
        // Lock old and new night ranges together, ascending by (room_type_id, stay_date), so concurrent
        // reservation operations always serialize in the same global order (5.4).
        var oldTo=row.departureDate.minusDays(1);
        var newTo=newDeparture.minusDays(1);
        if(effectiveRoomType==row.roomTypeId) {
            var nights=nights(newArrival.isBefore(row.arrivalDate)?newArrival:row.arrivalDate,
                newDeparture.isAfter(row.departureDate)?newDeparture:row.departureDate);
            reservations.ensureDays(row.roomTypeId,nights);
            if(reservations.lockDays(row.roomTypeId,nights.get(0),nights.get(nights.size()-1)).size()!=nights.size())
                throw new IllegalStateException("Inventory lock rows missing after ensure");
        } else {
            var first=newRoomTypeId<row.roomTypeId;
            lockRange(first?newRoomTypeId:row.roomTypeId,first?newArrival:row.arrivalDate,first?newTo:oldTo);
            lockRange(first?row.roomTypeId:newRoomTypeId,first?row.arrivalDate:newArrival,first?oldTo:newTo);
        }
        reservations.release(row.roomTypeId,row.arrivalDate,oldTo);
        if(!sameRange) {
            var full=reservations.nightRows(effectiveRoomType,newArrival,newTo).stream()
                .filter(n->n.available<1).map(n->n.stayDate.toString()).toList();
            if(!full.isEmpty()) throw conflict("ROOM_TYPE_SOLD_OUT",full.get(0)+" 该房型可售房量不足（或该日期已停售），原预订保持不变");
        }
        reservations.occupy(effectiveRoomType,newArrival,newTo);
        var update=new Row();
        update.id=id; update.arrivalDate=newArrival; update.departureDate=newDeparture; update.roomTypeId=effectiveRoomType;
        update.contactName=contactName!=null?contactName:row.contactName;
        update.notes=notes!=null?notes:row.notes;
        if(phoneChanged) {
            update.contactPhoneCiphertext=protection.encrypt(phone,"phone");
            update.contactPhoneMask=protection.mask(phone);
            update.contactEncryptionKeyId=GuestProtection.KEY_ID;
        }
        if(reservations.modify(update,row.version)!=1) throw conflict("STALE_VERSION","预订已被其他人更新，请刷新后重新提交");
        reservations.insertEvent(id,"MODIFY",actor,describeModify(row,newArrival,newDeparture,effectiveRoomType));
        channelOutbox.recordReservationEvent(id,"RESERVATION_MODIFIED",null);
        audit.success(actor,"RESERVATION_MODIFY","RESERVATION",id);
        requests.completeReservation(actor,"RESERVATION_MODIFY",key,id);
        return detail(actor,id);
    }
    private void lockRange(long roomTypeId,LocalDate from,LocalDate to) {
        var nights=nights(from,to.plusDays(1));
        reservations.ensureDays(roomTypeId,nights);
        if(reservations.lockDays(roomTypeId,from,to).size()!=nights.size())
            throw new IllegalStateException("Inventory lock rows missing after ensure");
    }
    private String describeModify(Row before,LocalDate newArrival,LocalDate newDeparture,long newRoomTypeId) {
        var parts=new ArrayList<String>();
        if(!before.arrivalDate.equals(newArrival) || !before.departureDate.equals(newDeparture))
            parts.add("日期 "+before.arrivalDate+"→"+newArrival+" / "+before.departureDate+"→"+newDeparture);
        if(newRoomTypeId!=before.roomTypeId) parts.add("房型 "+before.roomTypeCode+"→"+newRoomTypeId);
        return String.join("；",parts);
    }

    @Transactional
    public ReservationDetail confirm(long actor,String key,long id) {        Long previous=requests.reserveReservation(actor,"RESERVATION_CONFIRM",key,new CanonicalRef(id));
        actors.require(actor,"reservation:manage");
        if(previous!=null) return detail(actor,previous);
        if(reservations.lock(id)==null) throw notFound();
        var now=now();
        if(reservations.confirm(id,actor,now)!=1) throw conflict("RESERVATION_STATE","只有保留（HELD）状态的预订可以确认");
        reservations.insertEvent(id,"CONFIRM",actor,"预订已确认");
        channelOutbox.recordReservationEvent(id,"RESERVATION_CONFIRMED",null);
        audit.success(actor,"RESERVATION_CONFIRM","RESERVATION",id);
        requests.completeReservation(actor,"RESERVATION_CONFIRM",key,id);
        return detail(actor,id);
    }

    @Transactional
    public ReservationDetail cancel(long actor,String key,long id,CancelInput input) {
        String reason=input==null?"":(input.reason()==null?"":input.reason().trim());
        if(reason.isEmpty() || reason.length()>500 || reason.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))
            throw invalid("请填写取消原因（1—500字），不要填写证件、电话等敏感信息");
        Long previous=requests.reserveReservation(actor,"RESERVATION_CANCEL",key,new CanonicalCancel(id,reason));
        actors.require(actor,"reservation:manage");
        if(previous!=null) return detail(actor,previous);
        var row=locked(id);
        if(!"HELD".equals(row.status) && !"CONFIRMED".equals(row.status))
            throw conflict("RESERVATION_STATE","只有保留或已确认的预订可以取消");
        releaseRange(row.roomTypeId,row.arrivalDate,row.departureDate);
        // First version records the fee policy honestly: no configured penalty policy means zero penalty charged.
        if(reservations.cancel(id,actor,now(),reason)!=1) throw conflict("RESERVATION_STATE","预订当前状态不能取消");
        reservations.insertEvent(id,"CANCEL",actor,reason+"；取消罚金 0.00 元（首版未配置罚金政策，如实记录）");
        channelOutbox.recordReservationEvent(id,"RESERVATION_CANCELLED",null);
        audit.success(actor,"RESERVATION_CANCEL","RESERVATION",id);
        requests.completeReservation(actor,"RESERVATION_CANCEL",key,id);
        return detail(actor,id);
    }

    @Transactional
    public ReservationDetail markNoShow(long actor,String key,long id,NoShowInput input) {
        String detail=input==null||input.reason()==null?"":input.reason().trim();
        if(detail.length()>500 || detail.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))
            throw invalid("未到店说明最长 500 字，不要填写证件、电话等敏感信息");
        Long previous=requests.reserveReservation(actor,"RESERVATION_NO_SHOW",key,new CanonicalRef(id));
        actors.require(actor,"reservation:manage");
        if(previous!=null) return detail(actor,previous);
        var row=locked(id);
        if(!"CONFIRMED".equals(row.status))
            throw conflict("RESERVATION_STATE","只有已确认（CONFIRMED）的预订可以标记未到店");
        releaseRange(row.roomTypeId,row.arrivalDate,row.departureDate);
        if(reservations.markNoShow(id,actor,now())!=1) throw conflict("RESERVATION_STATE","只有已确认（CONFIRMED）的预订可以标记未到店");
        reservations.insertEvent(id,"NO_SHOW",actor,detail.isEmpty()?"客人未到店，库存已释放":detail+"；库存已释放");
        audit.success(actor,"RESERVATION_NO_SHOW","RESERVATION",id);
        requests.completeReservation(actor,"RESERVATION_NO_SHOW",key,id);
        return detail(actor,id);
    }

    @Transactional
    public ReservationDetail assignRoom(long actor,String key,long id,AssignRoomInput input) {
        if(input==null || input.roomId()==null || input.roomId()<1) throw invalid("请提供要排入的房间");
        Long previous=requests.reserveReservation(actor,"RESERVATION_ASSIGN_ROOM",key,new CanonicalAssign(id,input.roomId()));
        actors.require(actor,"reservation:manage");
        if(previous!=null) return detail(actor,previous);
        if(reservations.lock(id)==null) throw notFound();
        var row=reservations.byId(id);
        if(!"HELD".equals(row.status) && !"CONFIRMED".equals(row.status))
            throw conflict("RESERVATION_STATE","只有保留或已确认的预订可以排房");
        var room=rooms.detail(input.roomId());
        if(room==null) throw new ApiException(404,"ROOM_NOT_FOUND","房间不存在");
        if(!room.enabled) throw conflict("ROOM_NOT_AVAILABLE","房间已停用，不能排入");
        if(room.roomTypeId==null || room.roomTypeId.longValue()!=row.roomTypeId.longValue())
            throw conflict("ROOM_TYPE_MISMATCH","排入房间与预订房型不一致；房号为预分配，入住时仍以实际房型核验为准");
        if(reservations.assignRoom(id,input.roomId(),actor,now())!=1) throw conflict("RESERVATION_STATE","预订当前状态不能排房");
        reservations.insertEvent(id,"ASSIGN_ROOM",actor,"预排房间 "+room.roomNumber+"（入住时仍需通过实际可入核验）");
        audit.success(actor,"RESERVATION_ASSIGN_ROOM","RESERVATION",id);
        requests.completeReservation(actor,"RESERVATION_ASSIGN_ROOM",key,id);
        return detail(actor,id);
    }

    /** Reservation -> stay. Releases the sold counter (occupancy moves to the live segment, never counted twice). */
    @Transactional
    public CheckInResult checkIn(long actor,String key,long id,StayRequests.CheckIn raw) {
        var input=checkIns.normalize(raw);
        Long previous=requests.reserveReservation(actor,"RESERVATION_CHECK_IN",key,new CanonicalCheckIn(id,input.roomId(),input.expectedCheckOutAt().toString(),input.notes(),input.guests()));
        actors.require(actor,"reservation:manage");
        if(previous!=null) {
            var row=reservations.byId(previous);
            if(row==null || row.checkedInStayId==null) throw new IllegalStateException("Reservation check-in receipt is incomplete");
            return new CheckInResult(previous,row.checkedInStayId);
        }
        var row=locked(id);
        if(!"CONFIRMED".equals(row.status)) throw conflict("RESERVATION_STATE","只有已确认（CONFIRMED）的预订可以办理入住，请先确认预订");
        var expectedOut=input.expectedCheckOutAt();
        // Release the reservation's sold nights, then create the stay: the same night must never be counted both ways.
        // Leaving earlier than booked is allowed; the freed nights simply show up as availability again.
        releaseRange(row.roomTypeId,row.arrivalDate,row.departureDate);
        long stayId=checkIns.createInternal(actor,input);
        var room=rooms.detail(input.roomId());
        if(room.roomTypeId==null || room.roomTypeId.longValue()!=row.roomTypeId.longValue())
            throw conflict("ROOM_TYPE_MISMATCH","实际入住房间与预订房型不一致，请重新核对");
        // Overstay horizon check: the stay's extra nights must fit future type inventory. We prompt conflicts and
        // never auto-evict; rolling back keeps reservation and stay consistent.
        var from=row.departureDate;
        var to=BillingDtos.businessDate(expectedOut).minusDays(1);
        if(!to.isBefore(from)) {
            var full=reservations.nightRows(row.roomTypeId,from,to).stream().filter(n->n.available<1).map(n->n.stayDate.toString()).toList();
            if(!full.isEmpty()) throw conflict("FUTURE_NIGHT_CONFLICT","预计退房超出预订区间，"+full.get(0)+" 起的未来库存不足，请调整预计退房时间或先调整预订");
        }
        if(reservations.checkIn(id,stayId)!=1) throw conflict("RESERVATION_STATE","预订当前状态不能转入住");
        reservations.insertEvent(id,"CHECK_IN",actor,"转为入住，房间 "+room.roomNumber);
        channelOutbox.recordReservationEvent(id,"RESERVATION_CHECKED_IN",null);
        audit.success(actor,"RESERVATION_CHECK_IN","RESERVATION",id);
        requests.completeReservation(actor,"RESERVATION_CHECK_IN",key,id);
        return new CheckInResult(id,stayId);
    }

    /** Expired holds -> cancelled with inventory released. Naturally idempotent: a second sweep finds nothing. */
    @Transactional
    public ReleaseResult releaseExpired(long actor) {
        actors.require(actor,"reservation:manage");
        var ids=reservations.expiredHeldIds();
        int released=0;
        for(long id:ids) {
            var row=reservations.byId(id);
            releaseRange(row.roomTypeId,row.arrivalDate,row.departureDate);
            if(reservations.cancel(id,actor,now(),"保留单到期自动释放")!=1) continue;
            reservations.insertEvent(id,"EXPIRE_RELEASE",actor,"保留单到期，库存自动释放");
            audit.success(actor,"RESERVATION_EXPIRE_RELEASE","RESERVATION",id);
            released++;
        }
        return new ReleaseResult(released);
    }

    // ---------- deposits ----------
    @Transactional
    public DepositView receiveDeposit(long actor,String key,long id,DepositInput input) {
        if(input==null) throw invalid("请提供订金内容");
        if(!Set.of("CASH","BANK_CARD","QR_PAYMENT","BANK_TRANSFER","OTHER").contains(input.method())) throw invalid("收款方式不正确");
        BigDecimal amount=BillingDtos.amount(input.amount(),true,"订金");
        if(amount.compareTo(new BigDecimal("999999.99"))>0) throw invalid("订金金额超出上限");
        String reference=input.methodReference()==null?null:input.methodReference().trim();
        if(reference!=null && reference.isEmpty()) reference=null;
        if(reference!=null && reference.length()>120) throw invalid("外部参考最长 120 字");
        String note=input.note()==null?"":input.note().trim();
        if(note.length()>500) throw invalid("备注不能超过 500 字");
        var canonical=new CanonicalDeposit(id,input.method(),amount.toPlainString(),reference,note);
        Long previous=requests.reserveReservation(actor,"RESERVATION_DEPOSIT_RECEIVE",key,canonical);
        actors.require(actor,"billing:receive");
        if(previous!=null) return depositOf(previous,null);
        var row=locked(id);
        switch(row.status) {
            case "CANCELLED","NO_SHOW" -> throw conflict("RESERVATION_STATE","已取消或未到店的预订单不能新收订金，请直接办理退款");
            case "CHECKED_IN" -> throw conflict("RESERVATION_STATE","预订已转入住，请在客账中办理收款");
            default -> {}
        }
        var payment=new PaymentRow();
        payment.reservationId=id; payment.direction="RECEIPT"; payment.method=input.method(); payment.purpose="DEPOSIT";
        payment.amount=amount; payment.currency="CNY"; payment.methodReference=reference; payment.note=note.isEmpty()?null:note; payment.actor=actor;
        payment.cashierShiftId=cashierShifts.openShiftOf(actor);
        if(reservations.insertDeposit(payment)!=1) throw new IllegalStateException("Deposit was not saved");
        reservations.insertEvent(id,"DEPOSIT_RECEIVE",actor,"订金 "+BillingDtos.money(amount)+" 元（"+input.method()+"）");
        audit.success(actor,"RESERVATION_DEPOSIT_RECEIVE","RESERVATION",id);
        requests.completeReservation(actor,"RESERVATION_DEPOSIT_RECEIVE",key,id);
        return depositOf(id,payment.id);
    }

    @Transactional
    public DepositView refundDeposit(long actor,String key,long id,DepositRefundInput input) {
        if(input==null || input.paymentRecordId()==null) throw invalid("请提供要退款的订金记录");
        String reason=input.reason()==null?"":input.reason().trim();
        if(reason.isEmpty() || reason.length()>500) throw invalid("请填写退款原因（1—500字）");
        BigDecimal amount=BillingDtos.amount(input.amount(),true,"退款");
        var canonical=new CanonicalDepositRefund(id,input.paymentRecordId(),amount.toPlainString(),reason);
        Long previous=requests.reserveReservation(actor,"RESERVATION_DEPOSIT_REFUND",key,canonical);
        actors.require(actor,"billing:refund");
        if(previous!=null) return depositOf(previous,null);
        var row=locked(id);
        if("CHECKED_IN".equals(row.status)) throw conflict("RESERVATION_STATE","订金已随入住转入客账，请在客账中办理退款");
        var original=reservations.depositById(input.paymentRecordId(),id);
        if(original==null) throw new ApiException(404,"PAYMENT_NOT_FOUND","订金记录不存在或不属于该预订");
        if(!"RECEIPT".equals(original.direction)) throw invalid("只能对收款记录登记退款");
        if(original.folioId!=null) throw conflict("RESERVATION_STATE","该订金已迁移到客账，请在客账中办理退款");
        var refunded=reservations.refundedTotal(original.id);
        if(amount.compareTo(original.amount.subtract(refunded))>0)
            throw conflict("REFUND_EXCEEDS_LIMIT","退款超过该笔订金未退回金额（最多 "+BillingDtos.money(original.amount.subtract(refunded))+" 元）");
        var sums=reservations.depositSums(id);
        if(amount.compareTo(sums.receipts.subtract(sums.refunds))>0)
            throw conflict("REFUND_EXCEEDS_LIMIT","退款超过该预订的订金余额（最多 "+BillingDtos.money(sums.receipts.subtract(sums.refunds))+" 元）");
        var payment=new PaymentRow();
        // REFUND rows carry no purpose by schema (payment_records_check): purpose belongs to receipts only.
        payment.reservationId=id; payment.direction="REFUND"; payment.method=original.method; payment.purpose=null;
        payment.amount=amount; payment.currency="CNY"; payment.refundOf=original.id; payment.note=reason; payment.actor=actor;
        payment.cashierShiftId=cashierShifts.openShiftOf(actor);
        if(reservations.insertDeposit(payment)!=1) throw new IllegalStateException("Deposit refund was not saved");
        reservations.insertEvent(id,"DEPOSIT_REFUND",actor,"订金退款 "+BillingDtos.money(amount)+" 元："+reason);
        audit.success(actor,"RESERVATION_DEPOSIT_REFUND","RESERVATION",id);
        requests.completeReservation(actor,"RESERVATION_DEPOSIT_REFUND",key,id);
        return depositOf(id,payment.id);
    }

    // ---------- shared internals ----------
    private Row locked(long id) {
        if(reservations.lock(id)==null) throw notFound();
        var row=reservations.byId(id);
        if(row==null) throw new IllegalStateException("Reservation row vanished under lock");
        return row;
    }
    /** Ensure counter rows exist, lock them in (room_type_id, stay_date) order, then reject overselling by naming a date. */
    private void occupyRange(long roomTypeId,LocalDate arrival,LocalDate departure) {
        var nights=nights(arrival,departure);
        reservations.ensureDays(roomTypeId,nights);
        var locked=reservations.lockDays(roomTypeId,arrival,departure.minusDays(1));
        if(locked.size()!=nights.size()) throw new IllegalStateException("Inventory lock rows missing after ensure");
        var full=reservations.nightRows(roomTypeId,arrival,departure.minusDays(1)).stream()
            .filter(n->n.available<1).map(n->n.stayDate.toString()).toList();
        if(!full.isEmpty()) throw conflict("ROOM_TYPE_SOLD_OUT",full.get(0)+" 该房型可售房量不足（或该日期已停售）");
        reservations.occupy(roomTypeId,arrival,departure.minusDays(1));
    }
    private void releaseRange(long roomTypeId,LocalDate arrival,LocalDate departure) {
        var nights=nights(arrival,departure);
        reservations.ensureDays(roomTypeId,nights);
        var locked=reservations.lockDays(roomTypeId,arrival,departure.minusDays(1));
        if(locked.size()!=nights.size()) throw new IllegalStateException("Inventory lock rows missing after ensure");
        reservations.release(roomTypeId,arrival,departure.minusDays(1));  // sold>=0 CHECK makes double release fail loudly
    }
    static List<LocalDate> nights(LocalDate arrival,LocalDate departure) {
        var result=new ArrayList<LocalDate>();
        for(var d=arrival;d.isBefore(departure);d=d.plusDays(1)) result.add(d);
        if(result.size()>MAX_NIGHTS) throw new ApiException(400,"VALIDATION_ERROR","单笔预订最长 "+MAX_NIGHTS+" 晚");
        return result;
    }
    private ReservationView view(Row row) {
        return new ReservationView(row.id,row.arrivalDate.toString(),row.departureDate.toString(),
            nights(row.arrivalDate,row.departureDate).size(),row.roomTypeId,row.roomTypeCode,row.roomTypeName,row.status,
            row.source,row.externalRef,row.contactName,row.contactPhoneMask==null?"":row.contactPhoneMask,
            row.heldUntil==null?null:row.heldUntil.toString(),row.confirmedAt,row.confirmedByName,
            row.cancelledAt,row.cancelledByName,row.cancelReason,row.noShowAt,row.noShowByName,
            row.checkedInStayId,row.assignedRoomId,row.roomNumber,row.version,row.notes,row.createdAt,row.createdByName);
    }
    private DepositView depositView(PaymentRow row) {
        return new DepositView(row.id,row.direction,row.method,row.purpose,BillingDtos.money(row.amount),row.currency,
            row.folioId,row.methodReference,row.note,row.receivedAt,row.receivedByName);
    }
    private DepositView depositOf(long reservationId,Long paymentId) {
        var rows=reservations.deposits(reservationId);
        if(rows.isEmpty()) throw new IllegalStateException("Deposit receipt is incomplete");
        if(paymentId==null) return depositView(rows.get(rows.size()-1));
        return depositView(rows.stream().filter(r->r.id.equals(paymentId)).findFirst()
            .orElseThrow(()->new IllegalStateException("Deposit receipt is incomplete")));
    }
    static ReservationQuery queryOf(String status,String arrivalFrom,String arrivalTo,String query,int page,int pageSize) {
        if(page<1 || pageSize<1 || pageSize>100) throw invalidStatic("分页参数不正确，每页最多100条");
        String st=status==null?"ALL":status;
        if(!Set.of("ALL","HELD","CONFIRMED","CANCELLED","NO_SHOW","CHECKED_IN").contains(st)) throw invalidStatic("预订状态筛选不正确");
        LocalDate from=arrivalFrom==null||arrivalFrom.isBlank()?null:parseDateStatic(arrivalFrom,"到店日期起");
        LocalDate to=arrivalTo==null||arrivalTo.isBlank()?null:parseDateStatic(arrivalTo,"到店日期止");
        if(from!=null && to!=null && from.isAfter(to)) throw invalidStatic("到店日期区间起止颠倒");
        String q=query==null?"":query.trim();
        if(q.length()>120) throw invalidStatic("检索关键词最长 120 字");
        return new ReservationQuery(st,from,to,q,page,pageSize);
    }
    private static LocalDate parseDateStatic(String value,String label) {
        try { return LocalDate.parse(value); } catch(Exception failure) { throw invalidStatic(label+"格式不正确，应为 yyyy-MM-dd"); }
    }
    private static ApiException invalidStatic(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private LocalDate parseDate(String value,String label) {
        if(value==null || value.isBlank()) throw invalid("请提供"+label);
        return parseDateStatic(value,label);
    }
    private OffsetDateTime parseHeldUntil(String value) {
        var now=now();
        if(value==null || value.isBlank()) return now.plusHours(24);
        OffsetDateTime parsed;
        try { parsed=OffsetDateTime.parse(value); } catch(Exception failure) { throw invalid("保留截止时间格式不正确，应为 ISO-8601 带时区时间"); }
        return parsed.withOffsetSameInstant(ZoneOffset.UTC);
    }
    private static LocalDate businessToday() { return BillingDtos.businessDate(OffsetDateTime.now(ZoneOffset.UTC)); }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private static ApiException notFound() { return new ApiException(404,"RESERVATION_NOT_FOUND","预订单不存在"); }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(409,code,message); }
    record CanonicalCreate(String arrivalDate,String departureDate,long roomTypeId,String source,String externalRef,
        String contactName,String contactPhone,String heldUntil,String notes) {}
    record CanonicalModify(long id,long version,String arrivalDate,String departureDate,Long roomTypeId,
        String contactName,String contactPhone,String notes) {}
    record CanonicalRef(long id) {}
    record CanonicalCancel(long id,String reason) {}
    record CanonicalAssign(long id,long roomId) {}
    record CanonicalCheckIn(long id,long roomId,String expectedCheckOutAt,String notes,List<StayRequests.GuestInput> guests) {}
    record CanonicalDeposit(long id,String method,String amount,String methodReference,String note) {}
    record CanonicalDepositRefund(long id,long paymentRecordId,String amount,String reason) {}
}
