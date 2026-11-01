package com.stayos.billing;

import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.billing.BillingDtos.*;
import com.stayos.billing.BillingMapper.*;
import com.stayos.common.ApiException;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.stay.mapper.StayMapper;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Folio money core. All amount arithmetic happens under the folio row lock.
 * Lock order: idempotency reservation -> actor guard -> stay (read only) -> folio row. Room rows are never locked here.
 */
@Service
public class FolioService {
    private static final Set<String> METHODS=Set.of("CASH","BANK_CARD","QR_PAYMENT","BANK_TRANSFER","OTHER");
    private static final Set<String> PURPOSES=Set.of("PAYMENT","DEPOSIT");
    private static final Set<String> RECEIPT_OPERATIONS=Set.of("FOLIO_OPEN","FOLIO_CLOSE","FOLIO_REOPEN");
    private static final Set<String> CHARGE_OPERATIONS=Set.of("CHARGE_POST","CHARGE_ADJUST");
    private static final Set<String> PAYMENT_OPERATIONS=Set.of("PAYMENT_RECEIVE","PAYMENT_REFUND");
    private final BillingMapper billing;
    private final com.stayos.cashier.CashierMapper cashierShifts;
    private final StayMapper requestsMapper;
    private final IdempotencyService requests;
    private final BusinessActorGuard actors;
    private final AuditService audit;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public FolioService(BillingMapper billing,com.stayos.cashier.CashierMapper cashierShifts,StayMapper requestsMapper,IdempotencyService requests,
                        BusinessActorGuard actors,AuditService audit,com.fasterxml.jackson.databind.ObjectMapper json) {
        this.billing=billing; this.cashierShifts=cashierShifts; this.requestsMapper=requestsMapper; this.requests=requests; this.actors=actors; this.audit=audit; this.json=json;
    }

    // ---------- queries ----------
    @Transactional(readOnly=true)
    public Page<FolioListItem> list(long actor,String status,int page,int size) {
        actors.requireRead(actor,"billing:read");
        if(page<1 || size<1 || size>100) throw invalid("分页参数不正确，每页最多100条");
        if(status!=null && !Set.of("ALL","OPEN","CLOSED").contains(status)) throw invalid("客账状态筛选不正确");
        var items=billing.folioList(status,size,((long)page-1)*size).stream().map(this::listItem).toList();
        return new Page<>(items,billing.folioCount(status),page,size);
    }
    @Transactional(readOnly=true)
    public FolioDetail detail(long actor,long id) {
        actors.requireRead(actor,"billing:read");
        var row=billing.folioById(id);
        if(row==null) throw notFound();
        return detailOf(id);
    }
    @Transactional(readOnly=true)
    public FolioDetail byStay(long actor,long stayId) {
        actors.requireRead(actor,"billing:read");
        var row=billing.folioByStay(stayId);
        if(row==null) throw new ApiException(404,"FOLIO_NOT_OPENED","该入住尚未开立客账");
        return detailOf(row.id);
    }

    // ---------- folio lifecycle ----------
    @Transactional
    public FolioDetail open(long actor,String key,long stayId,FolioOpenInput input) {
        var open=input==null?new FolioOpenInput(null):input;
        Long previous=requests.reserveBilling(actor,"FOLIO_OPEN",key,new CanonicalOpen(open.chargesFrom()),"FOLIO");
        actors.require(actor,"billing:charge");
        if(previous!=null) return detailOf(previous);
        var stay=billing.stayBrief(stayId);
        if(stay==null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
        var existing=billing.folioByStay(stayId);
        if(existing!=null) return detailOf(existing.id);
        var policy=billing.activePolicy();
        if(policy==null) throw conflict("BILLING_POLICY_REQUIRED","请先在价目配置中保存生效的财务政策，再开立客账");
        LocalDate effectiveFrom=null;
        if(open.chargesFrom()!=null && !open.chargesFrom().isBlank()) {
            effectiveFrom=BillingDtos.date(open.chargesFrom(),"费用生效日");
            LocalDate checkIn=BillingDtos.businessDate(stay.checkInAt);
            if(effectiveFrom.isBefore(checkIn)) throw invalid("费用生效日不能早于入住日");
            if(effectiveFrom.isAfter(currentBusinessDate())) throw invalid("费用生效日不能晚于当前营业日");
        }
        var row=new FolioRow();
        row.stayRecordId=stayId;
        row.pricingStatus=effectiveFrom==null?"PRICED":"PRICING_REQUIRED";
        row.chargesEffectiveFrom=effectiveFrom;
        row.openedBy=actor;
        if(billing.insertFolio(row)!=1) throw new IllegalStateException("Folio was not saved");
        // V3.0: reservation deposits follow the booking into its folio exactly once (guarded trigger), so the same
        // money is never recorded twice — the payment row keeps reservation_id for traceability.
        int migratedDeposits=0;
        var reservationId=billing.reservationOfStay(stayId);
        if(reservationId!=null) migratedDeposits=billing.migrateReservationDeposits(row.id,reservationId);
        billing.insertEvent(row.id,"OPENED",actor,null,encode(java.util.Map.of("pricingStatus",row.pricingStatus,"migratedDeposits",migratedDeposits)));
        audit.success(actor,"FOLIO_OPEN","BILLING",row.id);
        requests.completeFolio(actor,"FOLIO_OPEN",key,row.id);
        return detailOf(row.id);
    }

    @Transactional
    public ChargeView postCharge(long actor,String key,long folioId,ChargeInput input) {
        if(input==null) throw invalid("请提供入账内容");
        String category=input.category();
        if(!Set.of("ROOM_NIGHT","MANUAL").contains(category)) throw invalid("费用类别只能是房费或手工费用");
        LocalDate date=BillingDtos.date(input.businessDate(),"入账日期");
        BigDecimal unitPrice; int quantity; Long ratePlanId=null; String title; String note=trimNote(input.note());
        if("ROOM_NIGHT".equals(category)) {
            ratePlanId=input.ratePlanId();
            if(ratePlanId==null || ratePlanId<1) throw invalid("请选择计价采用的价目方案");
            unitPrice=null; quantity=1; title=null;
        } else {
            title=requiredText(input.title(),200,"费用名称");
            unitPrice=BillingDtos.amount(input.unitPrice(),true,"费用");
            if(unitPrice.compareTo(new BigDecimal("999999.99"))>0) throw invalid("费用单价超出上限");
            quantity=input.quantity()==null?1:input.quantity();
            if(quantity<1 || quantity>999) throw invalid("数量须在1—999之间");
        }
        var canonical=new CanonicalCharge(category,date.toString(),ratePlanId,title,unitPrice==null?null:unitPrice.toPlainString(),quantity,note);
        Long previous=requests.reserveBilling(actor,"CHARGE_POST",key,canonical,"CHARGE");
        actors.require(actor,"billing:charge");
        if(previous!=null) return chargeView(billing.chargeById(previous));
        var folio=lockedOpenFolio(folioId);
        var stay=billing.stayBrief(folio.stayRecordId);
        validateChargeDate(date,folio,stay);
        var charge=new ChargeRow();
        charge.folioId=folio.id; charge.stayRecordId=folio.stayRecordId; charge.businessDate=date;
        charge.direction="CHARGE"; charge.currency=folio.currency; charge.note=note; charge.actor=actor;
        if("ROOM_NIGHT".equals(category)) {
            var plan=billing.ratePlanById(ratePlanId);
            if(plan==null) throw new ApiException(404,"RATE_PLAN_NOT_FOUND","价目方案不存在");
            if(!plan.enabled) throw conflict("RATE_PLAN_DISABLED","该价目方案已停用，请重新选择");
            var segment=billing.coveringSegment(folio.stayRecordId,date);
            if(segment==null) throw conflict("NO_ROOM_SEGMENT","该日期没有对应的住房使用段，请核对入账日期");
            if(plan.roomTypeId.longValue()!=segment.roomTypeId.longValue())
                // Same code as a missing plan: an inapplicable plan is not an available plan, and the
                // response must not reveal which room types other rate plans belong to.
                throw new ApiException(404,"RATE_PLAN_NOT_FOUND","价目方案不存在或不适用于该日入住房型");
            BigDecimal price=billing.rateDayPrice(plan.id,date);
            if(price==null) price=plan.nightlyPrice;
            if(price.signum()<=0) throw conflict("ZERO_AMOUNT_CHARGE","该日期房价为0，无需入账");
            if(billing.roomNightPosted(folio.id,folio.stayRecordId,date)>0)
                throw conflict("CHARGE_ALREADY_POSTED","该日期房费已入账，请刷新客账核对");
            var policy=billing.activePolicy();
            if(policy==null) throw conflict("BILLING_POLICY_REQUIRED","生效财务政策缺失，请先完成财务配置");
            charge.roomSegmentId=segment.id; charge.category="ROOM_NIGHT";
            charge.nameSnapshot=trim(plan.planName+" 房费",200);
            charge.pricingUnitSnapshot="NIGHT"; charge.quantity=1;
            charge.unitPriceSnapshot=price; charge.amount=price;
            charge.policyVersion=(long)policy.version; charge.ratePlanId=plan.id;
            charge.sourceType="ROOM_NIGHT"; charge.sourceKey="STAY:"+folio.stayRecordId+":"+date;
        } else {
            charge.category="MANUAL"; charge.nameSnapshot=title;
            charge.pricingUnitSnapshot="UNIT"; charge.quantity=quantity;
            charge.unitPriceSnapshot=unitPrice; charge.amount=unitPrice.multiply(BigDecimal.valueOf(quantity));
            charge.sourceType="MANUAL"; charge.sourceKey="MANUAL:"+UUID.randomUUID();
        }
        if(billing.insertCharge(charge)!=1) throw new IllegalStateException("Charge was not saved");
        audit.success(actor,"CHARGE_POST","BILLING",charge.id);
        requests.completeFolioCharge(actor,"CHARGE_POST",key,charge.id);
        return chargeView(billing.chargeById(charge.id));
    }

    @Transactional
    public ChargeView adjust(long actor,String key,long folioId,AdjustInput input) {
        if(input==null || input.originalChargeId()==null || input.mode()==null) throw invalid("请提供要调整的费用与调整方式");
        if(!Set.of("REVERSAL","CORRECTION").contains(input.mode())) throw invalid("调整方式只能是全额冲正或部分调整");
        String reason=requiredText(input.reason(),500,"调整原因");
        BigDecimal amount=input.mode().equals("CORRECTION")?BillingDtos.amount(input.amount(),true,"调整"):null;
        var canonical=new CanonicalAdjust(input.originalChargeId(),input.mode(),amount==null?null:amount.toPlainString(),reason);
        Long previous=requests.reserveBilling(actor,"CHARGE_ADJUST",key,canonical,"CHARGE");
        actors.require(actor,"billing:adjust");
        if(previous!=null) return chargeView(billing.chargeById(previous));
        var folio=lockedOpenFolio(folioId);
        var original=billing.chargeById(input.originalChargeId());
        if(original==null || original.folioId.longValue()!=folio.id.longValue())
            throw new ApiException(404,"CHARGE_NOT_FOUND","费用记录不存在或不在该客账中");
        if(!"CHARGE".equals(original.direction)) throw invalid("只能对费用记录做调整，贷项记录不可再调整");
        BigDecimal remaining=original.amount.subtract(billing.creditedTotal(original.id));
        if(remaining.signum()<=0) throw conflict("NOTHING_TO_CREDIT","该费用已全额冲正，不能再调整");
        BigDecimal credit;
        if(input.mode().equals("REVERSAL")) credit=remaining;
        else {
            if(amount==null) throw invalid("请提供调整金额");
            if(amount.compareTo(remaining)>0) throw conflict("CREDIT_EXCEEDS_CHARGE","调整金额超过该费用剩余可调整额度（最多 "+BillingDtos.money(remaining)+" 元）");
            credit=amount;
        }
        var row=new ChargeRow();
        row.folioId=folio.id; row.stayRecordId=folio.stayRecordId; row.businessDate=original.businessDate;
        row.direction="CREDIT"; row.category=input.mode().equals("REVERSAL")?"REVERSAL":"CORRECTION";
        row.nameSnapshot=trim((input.mode().equals("REVERSAL")?"冲正：":"调整：")+original.nameSnapshot,200);
        row.pricingUnitSnapshot=original.pricingUnitSnapshot; row.quantity=1;
        row.unitPriceSnapshot=credit; row.amount=credit; row.currency=folio.currency;
        row.sourceType=input.mode().equals("REVERSAL")?"REVERSAL":"CREDIT"; row.sourceKey="CREDIT:"+UUID.randomUUID(); row.reversalOf=original.id; row.note=reason;
        row.actor=actor;
        if(billing.insertCharge(row)!=1) throw new IllegalStateException("Credit was not saved");
        audit.success(actor,"CHARGE_ADJUST","BILLING",row.id);
        requests.completeFolioCharge(actor,"CHARGE_ADJUST",key,row.id);
        return chargeView(billing.chargeById(row.id));
    }

    @Transactional
    public PaymentView receive(long actor,String key,long folioId,PaymentInput input) {
        if(input==null) throw invalid("请提供收款内容");
        if(!METHODS.contains(input.method())) throw invalid("收款方式不正确");
        if(!PURPOSES.contains(input.purpose())) throw invalid("收款用途只能是抵扣应收或预收押金");
        BigDecimal amount=BillingDtos.amount(input.amount(),true,"收款");
        String reference=trimOrNull(input.methodReference(),120,"外部参考");
        String note=trimNote(input.note());
        var canonical=new CanonicalPayment(input.method(),reference,input.purpose(),amount.toPlainString(),note);
        Long previous=requests.reserveBilling(actor,"PAYMENT_RECEIVE",key,canonical,"PAYMENT");
        actors.require(actor,"billing:receive");
        if(previous!=null) return paymentView(billing.paymentById(previous),null);
        var folio=lockedOpenFolio(folioId);
        var row=new PaymentRow();
        row.folioId=folio.id; row.direction="RECEIPT"; row.method=input.method(); row.purpose=input.purpose();
        row.amount=amount; row.currency=folio.currency; row.methodReference=reference; row.note=note; row.actor=actor;
        // 现金/非现金收款都挂到收款人当前班次（可能为空：未开班的历史或当前员工），V2.1 报表按班次归属。
        row.cashierShiftId=cashierShifts.openShiftOf(actor);
        if(billing.insertPayment(row)!=1) throw new IllegalStateException("Payment was not saved");
        audit.success(actor,"PAYMENT_RECEIVE","BILLING",row.id);
        requests.completePaymentRecord(actor,"PAYMENT_RECEIVE",key,row.id);
        return paymentView(billing.paymentById(row.id),null);
    }

    @Transactional
    public PaymentView refund(long actor,String key,long folioId,RefundInput input) {
        if(input==null || input.paymentRecordId()==null) throw invalid("请提供要退款的收款记录");
        String reason=requiredText(input.reason(),500,"退款原因");
        BigDecimal amount=BillingDtos.amount(input.amount(),true,"退款");
        var canonical=new CanonicalRefund(input.paymentRecordId(),amount.toPlainString(),reason);
        Long previous=requests.reserveBilling(actor,"PAYMENT_REFUND",key,canonical,"PAYMENT");
        actors.require(actor,"billing:refund");
        if(previous!=null) return paymentView(billing.paymentById(previous),null);
        var folio=lockedOpenFolio(folioId);
        var original=billing.paymentById(input.paymentRecordId());
        if(original==null || original.folioId.longValue()!=folio.id.longValue())
            throw new ApiException(404,"PAYMENT_NOT_FOUND","收款记录不存在或不在该客账中");
        if(!"RECEIPT".equals(original.direction)) throw invalid("只能对收款记录登记退款");
        var limits=refundLimit(folio.id,original);
        if(limits[0].signum()<=0) throw conflict("NOTHING_TO_REFUND","该笔收款已全额退回");
        if(limits[1].signum()<=0) throw conflict("REFUND_EXCEEDS_LIMIT","当前没有可退的预收余额，请先冲减费用再退款");
        if(amount.compareTo(limits[0])>0) throw conflict("REFUND_EXCEEDS_LIMIT","退款超过该笔收款未退回金额（最多 "+BillingDtos.money(limits[0])+" 元）");
        if(amount.compareTo(limits[1])>0) throw conflict("REFUND_EXCEEDS_LIMIT","退款超过当前可退预收余额（最多 "+BillingDtos.money(limits[1])+" 元）");
        var row=new PaymentRow();
        row.folioId=folio.id; row.direction="REFUND"; row.method=original.method; row.amount=amount;
        row.currency=folio.currency; row.refundOf=original.id; row.note=reason; row.actor=actor;
        if(billing.insertPayment(row)!=1) throw new IllegalStateException("Refund was not saved");
        audit.success(actor,"PAYMENT_REFUND","BILLING",row.id);
        requests.completePaymentRecord(actor,"PAYMENT_REFUND",key,row.id);
        return paymentView(billing.paymentById(row.id),original.id);
    }

    @Transactional
    public FolioDetail close(long actor,String key,long folioId,CloseInput input) {
        if(input==null || input.version()==null || input.version()<0) throw invalid("请提供当前客账版本");
        var canonical=new CanonicalClose(input.version(),Boolean.TRUE.equals(input.acknowledgeOutstanding()),trimNote(input.note()));
        Long previous=requests.reserveBilling(actor,"FOLIO_CLOSE",key,canonical,"FOLIO");
        actors.require(actor,"billing:charge");
        if(previous!=null) return detailOf(previous);
        var folio=lockedFolio(folioId);
        if(folio.version!=input.version()) throw conflict("STALE_VERSION","客账已被其他人更新，请刷新后重新核对");
        if(!"OPEN".equals(folio.status)) throw conflict("FOLIO_STATE","客账已结账，不能重复结账");
        var summary=summaryOf(folio.id);
        boolean outstanding=new BigDecimal(summary.balance()).signum()!=0;
        if(outstanding && !Boolean.TRUE.equals(input.acknowledgeOutstanding()))
            throw conflict("OUTSTANDING_BALANCE","该客账仍有未结余额 "+summary.balance()+" 元，如确认带余额结账请勾选确认");
        var now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if(billing.closeFolio(folio.id,input.version(),actor,now)!=1)
            throw conflict("STALE_VERSION","客账已被其他人更新，请刷新后重新核对");
        billing.insertEvent(folio.id,"CLOSED",actor,trimNote(input.note()),
            encode(java.util.Map.of("balance",summary.balance(),"acknowledgedOutstanding",outstanding)));
        audit.success(actor,"FOLIO_CLOSE","BILLING",folio.id);
        requests.completeFolio(actor,"FOLIO_CLOSE",key,folio.id);
        return detailOf(folio.id);
    }

    @Transactional
    public FolioDetail reopen(long actor,String key,long folioId,ReopenInput input) {
        if(input==null || input.version()==null || input.version()<0) throw invalid("请提供当前客账版本");
        String reason=requiredText(input.reason(),500,"重开原因");
        Long previous=requests.reserveBilling(actor,"FOLIO_REOPEN",key,new CanonicalReopen(input.version(),reason),"FOLIO");
        actors.require(actor,"billing:adjust");
        if(previous!=null) return detailOf(previous);
        var folio=lockedFolio(folioId);
        if(folio.version!=input.version()) throw conflict("STALE_VERSION","客账已被其他人更新，请刷新后重新核对");
        if(!"CLOSED".equals(folio.status)) throw conflict("FOLIO_STATE","只有已结账的客账可以重开");
        if(billing.reopenFolio(folio.id,input.version())!=1)
            throw conflict("STALE_VERSION","客账已被其他人更新，请刷新后重新核对");
        billing.insertEvent(folio.id,"REOPENED",actor,reason,encode(java.util.Map.of()));
        audit.success(actor,"FOLIO_REOPEN","BILLING",folio.id);
        requests.completeFolio(actor,"FOLIO_REOPEN",key,folio.id);
        return detailOf(folio.id);
    }

    // ---------- receipt recovery ----------
    @Transactional(readOnly=true)
    public Object receipt(long actor,String operation,String key) {
        boolean validKey=key!=null && key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
        if(!validKey || !(RECEIPT_OPERATIONS.contains(operation)||CHARGE_OPERATIONS.contains(operation)||PAYMENT_OPERATIONS.contains(operation)))
            throw invalid("请求标识或操作类型无效");
        var request=requestsMapper.request(actor,operation,UUID.fromString(key).toString());
        if(request==null) throw new ApiException(404,"OPERATION_NOT_FOUND","尚未查到成功结果，请核对业务记录，勿直接新建请求");
        Long folio=request.resultFolioId,charge=request.resultFolioChargeId,payment=request.resultPaymentRecordId;
        if(folio!=null) return detailOf(folio);
        if(charge!=null) return chargeView(billing.chargeById(charge));
        if(payment!=null) return paymentView(billing.paymentById(payment),null);
        throw new ApiException(409,"REQUEST_IN_PROGRESS","操作正在处理中，请稍后重试原请求");
    }

    // ---------- shared internals ----------
    record RefundSums(BigDecimal charges,BigDecimal credits,BigDecimal receipts,BigDecimal refunds) {}
    /** Returns {unrefunded remainder on the receipt, current refundable prepaid surplus}. */
    private BigDecimal[] refundLimit(long folioId,PaymentRow original) {
        BigDecimal refunded=billing.refundedTotal(original.id);
        BigDecimal remainder=original.amount.subtract(refunded);
        var sums=summaryNumbers(folioId);
        BigDecimal surplus=sums[1].subtract(sums[0]);
        return new BigDecimal[]{remainder,surplus};
    }
    /** {netReceivable,netReceived,balance} under the caller's folio lock. */
    private BigDecimal[] summaryNumbers(long folioId) {
        var cs=billing.chargeSums(folioId); var ps=billing.paymentSums(folioId);
        BigDecimal netReceivable=cs.charges.subtract(cs.credits);
        BigDecimal netReceived=ps.receipts.subtract(ps.refunds);
        return new BigDecimal[]{netReceivable,netReceived,netReceivable.subtract(netReceived)};
    }
    FolioSummary summaryOf(long folioId) {
        var cs=billing.chargeSums(folioId); var ps=billing.paymentSums(folioId);
        BigDecimal netReceivable=cs.charges.subtract(cs.credits);
        BigDecimal netReceived=ps.receipts.subtract(ps.refunds);
        BigDecimal balance=netReceivable.subtract(netReceived);
        return new FolioSummary(BillingDtos.money(cs.charges),BillingDtos.money(cs.credits),BillingDtos.money(netReceivable),
            BillingDtos.money(ps.receipts),BillingDtos.money(ps.refunds),BillingDtos.money(netReceived),BillingDtos.money(balance));
    }
    private FolioRow lockedFolio(long id) {
        var folio=billing.folioLock(id);
        if(folio==null) throw notFound();
        return folio;
    }
    private FolioRow lockedOpenFolio(long id) {
        var folio=lockedFolio(id);
        if(!"OPEN".equals(folio.status)) throw conflict("FOLIO_STATE","客账已结账，不能再变更费用或收退款，如需调整请先重开客账");
        return folio;
    }
    private void validateChargeDate(LocalDate date,FolioRow folio,StayBrief stay) {
        if(folio.pricingStatus.equals("PRICING_REQUIRED") && date.isBefore(folio.chargesEffectiveFrom))
            throw conflict("CHARGE_BEFORE_EFFECTIVE","入账日期早于账务接入生效日 "+folio.chargesEffectiveFrom+"，不能补录历史费用");
        // No explicit check-in floor here: room nights are guarded by covering-segment existence (NO_ROOM_SEGMENT),
        // and manual charges may legitimately date back within the stay.
        if(date.isAfter(currentBusinessDate())) throw invalid("入账日期不能晚于当前营业日");
    }
    LocalDate currentBusinessDate() { return BillingDtos.businessDate(OffsetDateTime.now(ZoneOffset.UTC)); }
    private FolioDetail detailOf(long id) {
        var folio=billing.folioById(id);
        if(folio==null) throw new IllegalStateException("Folio receipt is incomplete");
        var charges=billing.chargesByFolio(id).stream().map(this::chargeView).toList();
        var payments=billing.paymentsByFolio(id).stream().map(p->paymentView(p,null)).toList();
        var events=billing.eventsByFolio(id).stream().map(e->new FolioEventView(e.id,e.eventType,e.actorName,e.note,e.createdAt)).toList();
        return new FolioDetail(view(folio),charges,payments,events);
    }
    private FolioView view(FolioRow row) {
        var summary=summaryOf(row.id);
        return new FolioView(row.id,row.stayRecordId,row.status,row.pricingStatus,
            row.chargesEffectiveFrom==null?null:row.chargesEffectiveFrom.toString(),row.currency,row.version,
            row.openedBy,row.openedByName,row.openedAt,row.closedAt,row.closedByName,row.stayVersion,row.stayStatus,
            row.roomNumber,row.roomTypeName,row.primaryGuestName,row.checkInAt,row.expectedCheckOutAt,row.actualCheckOutAt,
            BillingDtos.businessDate(row.checkInAt).toString(),currentBusinessDate().toString(),summary);
    }
    private FolioListItem listItem(FolioRow row) {
        var cs=billing.chargeSums(row.id); var ps=billing.paymentSums(row.id);
        BigDecimal netReceivable=cs.charges.subtract(cs.credits);
        BigDecimal balance=netReceivable.subtract(ps.receipts.subtract(ps.refunds));
        return new FolioListItem(row.id,row.stayRecordId,row.status,row.pricingStatus,row.roomNumber,row.primaryGuestName,
            row.checkInAt,row.closedAt,BillingDtos.money(netReceivable),
            BillingDtos.money(ps.receipts.subtract(ps.refunds)),BillingDtos.money(balance));
    }
    private ChargeView chargeView(ChargeRow row) {
        if(row==null) throw new IllegalStateException("Charge receipt is incomplete");
        return new ChargeView(row.id,row.folioId,row.businessDate.toString(),row.direction,row.category,row.nameSnapshot,
            row.pricingUnitSnapshot,row.quantity,BillingDtos.money(row.unitPriceSnapshot),BillingDtos.money(row.amount),
            row.currency,row.policyVersion,row.ratePlanId,row.catalogItemId,row.sourceType,row.sourceKey,row.reversalOf,
            row.note,row.createdByName,row.createdAt);
    }
    private PaymentView paymentView(PaymentRow row,Long originalPaymentId) {
        if(row==null) throw new IllegalStateException("Payment receipt is incomplete");
        String remainder=null;
        if("RECEIPT".equals(row.direction)) {
            var refunded=row.amount.subtract(billing.refundedTotal(row.id));
            remainder=BillingDtos.money(refunded);
        }
        return new PaymentView(row.id,row.folioId,row.direction,row.method,row.purpose,BillingDtos.money(row.amount),
            row.currency,row.methodReference,row.refundOf,originalPaymentId,remainder,row.note,row.receivedByName,row.receivedAt);
    }
    private String requiredText(String value,int max,String label) {
        String clean=value==null?"":value.trim();
        if(clean.isEmpty() || clean.length()>max || clean.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))
            throw invalid(label+"需填写1—"+max+"字，且不能包含特殊控制字符");
        return clean;
    }
    private String trimOrNull(String value,int max,String label) {
        if(value==null) return null;
        String clean=value.trim();
        if(clean.isEmpty()) return null;
        if(clean.length()>max) throw invalid(label+"最长"+max+"字");
        return clean;
    }
    private String trimNote(String value) { return value==null?null:trimOrNull(value,500,"备注"); }
    private String trim(String value,int max) { return value.length()<=max?value:value.substring(0,max-1)+"…"; }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Folio event encoding unavailable"); }
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(409,code,message); }
    private static ApiException notFound() { return new ApiException(404,"FOLIO_NOT_FOUND","客账不存在"); }

    record CanonicalOpen(String chargesFrom) {}
    record CanonicalCharge(String category,String businessDate,Long ratePlanId,String title,String unitPrice,Integer quantity,String note) {}
    record CanonicalAdjust(Long originalChargeId,String mode,String amount,String reason) {}
    record CanonicalPayment(String method,String methodReference,String purpose,String amount,String note) {}
    record CanonicalRefund(Long paymentRecordId,String amount,String reason) {}
    record CanonicalClose(long version,boolean acknowledgeOutstanding,String note) {}
    record CanonicalReopen(long version,String reason) {}
}
