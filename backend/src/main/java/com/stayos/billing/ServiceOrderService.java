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
 * Per-stay consumption requests. Only fulfilment creates the unique charge source; cancelled orders are never billed.
 * Lock order: idempotency reservation -> actor guard -> order row -> folio row (charges only).
 */
@Service
public class ServiceOrderService {
    private static final long MAX_ITEMS=50;
    private final BillingMapper billing;
    private final StayMapper requestsMapper;
    private final IdempotencyService requests;
    private final BusinessActorGuard actors;
    private final AuditService audit;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public ServiceOrderService(BillingMapper billing,StayMapper requestsMapper,IdempotencyService requests,
                               BusinessActorGuard actors,AuditService audit,com.fasterxml.jackson.databind.ObjectMapper json) {
        this.billing=billing; this.requestsMapper=requestsMapper; this.requests=requests; this.actors=actors; this.audit=audit; this.json=json;
    }

    @Transactional(readOnly=true)
    public List<ServiceOrderView> byStay(long actor,long stayId) {
        actors.requireRead(actor,"billing:read");
        return billing.ordersByStay(stayId).stream().map(o->view(o.id)).toList();
    }

    @Transactional
    public ServiceOrderView create(long actor,String key,long stayId,ServiceOrderCreateInput input) {
        // Permission is enforced before any catalog lookup so unauthorized callers learn nothing.
        var canonical=new CanonicalOrder(stayId,shapeOnly(input==null?null:input.items()),input==null?null:trimNote(input.note()));
        Long previous=requests.reserveBilling(actor,"SERVICE_ORDER_CREATE",key,canonical,"SERVICE_ORDER");
        actors.require(actor,"billing:charge");
        if(previous!=null) return view(previous);
        var normalized=normalize(input.items(),input.note());
        var stay=billing.stayBrief(stayId);
        if(stay==null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
        var row=new OrderRow();
        row.stayRecordId=stayId; row.note=normalized.note(); row.actor=actor;
        if(billing.insertOrder(row)!=1) throw new IllegalStateException("Service order was not saved");
        saveItems(row.id,normalized.items());
        audit.success(actor,"SERVICE_ORDER_CREATE","BILLING",row.id);
        requests.completeServiceOrder(actor,"SERVICE_ORDER_CREATE",key,row.id);
        return view(row.id);
    }

    @Transactional
    public ServiceOrderView update(long actor,long id,ServiceOrderUpdateInput input) {
        if(input==null || input.version()==null || input.version()<0) throw invalid("请提供当前服务单版本");
        var order=billing.orderLock(id);
        if(order==null) throw notFound();
        actors.require(actor,"billing:charge");
        if(order.version!=input.version()) throw conflict("STALE_VERSION","服务单已被其他人修改，请刷新后重新核对");
        if(!"REQUESTED".equals(order.status)) throw conflict("ORDER_STATE","服务单已履约或取消，不能再修改");
        var normalized=normalize(input.items(),input.note());
        if(billing.updateOrderNote(id,input.version(),normalized.note())!=1)
            throw conflict("STALE_VERSION","服务单已被其他人修改，请刷新后重新核对");
        billing.deleteOrderItems(id);
        saveItems(id,normalized.items());
        audit.success(actor,"SERVICE_ORDER_UPDATE","BILLING",id);
        return view(id);
    }

    @Transactional
    public ServiceOrderView fulfill(long actor,String key,long id) {
        Long previous=requests.reserveBilling(actor,"SERVICE_ORDER_FULFILL",key,new CanonicalAction(id),"SERVICE_ORDER");
        actors.require(actor,"billing:charge");
        if(previous!=null) return view(previous);
        var order=billing.orderLock(id);
        if(order==null) throw notFound();
        if(!"REQUESTED".equals(order.status)) throw conflict("ORDER_STATE","服务单已履约或取消，不能重复履约");
        var folio=billing.folioByStay(order.stayRecordId);
        if(folio==null) throw conflict("FOLIO_REQUIRED","请先为该入住开立客账，再履约计费");
        var locked=billing.folioLock(folio.id);
        if(!"OPEN".equals(locked.status)) throw conflict("FOLIO_STATE","客账已结账，不能履约计费，如需调整请先重开客账");
        BigDecimal total=billing.itemsTotal(id);
        if(total.signum()<=0) throw conflict("ZERO_AMOUNT_ORDER","服务单合计为0，无费用可入账");
        var now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var policy=billing.activePolicy();
        if(policy==null) throw conflict("BILLING_POLICY_REQUIRED","生效财务政策缺失，请先完成财务配置");
        if(billing.fulfillOrder(id,order.version,actor,now)!=1)
            throw conflict("ORDER_STATE","服务单已履约或取消，不能重复履约");
        LocalDate businessDate=BillingDtos.businessDate(now,policy.billingCutoffTime);
        var items=billing.orderItems(id);
        for (var item:items) {
            var charge=new ChargeRow();
            charge.folioId=folio.id; charge.stayRecordId=order.stayRecordId; charge.businessDate=businessDate;
            charge.direction="CHARGE"; charge.category="SERVICE";
            charge.nameSnapshot=trimName(item.itemNameSnapshot,item.quantity);
            charge.pricingUnitSnapshot=item.unitPriceSnapshot.signum()==0?"UNIT":"ITEM";
            charge.quantity=item.quantity; charge.unitPriceSnapshot=item.unitPriceSnapshot;
            charge.amount=item.unitPriceSnapshot.multiply(BigDecimal.valueOf(item.quantity));
            charge.currency=folio.currency; charge.catalogItemId=item.catalogItemId;
            charge.sourceType="SERVICE_ORDER"; charge.sourceKey="ORDER:"+id+":"+item.id; charge.actor=actor;
            if(billing.insertCharge(charge)!=1) throw new IllegalStateException("Service charge was not saved");
        }
        audit.success(actor,"SERVICE_ORDER_FULFILL","BILLING",id);
        requests.completeServiceOrder(actor,"SERVICE_ORDER_FULFILL",key,id);
        return view(id);
    }

    @Transactional
    public ServiceOrderView cancel(long actor,String key,long id,com.stayos.billing.BillingDtos.ServiceOrderCancelInput input) {
        String reason=requiredText(input==null?null:input.reason(),500,"取消原因");
        Long previous=requests.reserveBilling(actor,"SERVICE_ORDER_CANCEL",key,new CanonicalCancel(id,reason),"SERVICE_ORDER");
        actors.require(actor,"billing:charge");
        if(previous!=null) return view(previous);
        var order=billing.orderLock(id);
        if(order==null) throw notFound();
        if(!"REQUESTED".equals(order.status)) throw conflict("ORDER_STATE","服务单已履约或取消，不能取消");
        var now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if(billing.cancelOrder(id,order.version,actor,now,reason)!=1)
            throw conflict("ORDER_STATE","服务单已履约或取消，不能取消");
        audit.success(actor,"SERVICE_ORDER_CANCEL","BILLING",id);
        requests.completeServiceOrder(actor,"SERVICE_ORDER_CANCEL",key,id);
        return view(id);
    }

    @Transactional(readOnly=true)
    public ServiceOrderView receipt(long actor,String operation,String key) {
        boolean validKey=key!=null && key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
        if(!validKey || !Set.of("SERVICE_ORDER_CREATE","SERVICE_ORDER_FULFILL","SERVICE_ORDER_CANCEL").contains(operation))
            throw invalid("请求标识或操作类型无效");
        var request=requestsMapper.request(actor,operation,UUID.fromString(key).toString());
        if(request==null || request.resultServiceOrderId==null)
            throw new ApiException(404,"OPERATION_NOT_FOUND","尚未查到成功结果，请核对服务单记录，勿直接新建请求");
        return view(request.resultServiceOrderId);
    }

    private record Normalized(List<ItemInput> items,String note) {}
    /** Shape-only validation used for the idempotency fingerprint; no database access. */
    private List<ItemInput> shapeOnly(List<ServiceOrderItemInput> items) {
        if(items==null || items.isEmpty()) throw invalid("请至少选择一个服务项目");
        if(items.size()>MAX_ITEMS) throw invalid("服务项目最多"+MAX_ITEMS+"条");
        var normalized=new ArrayList<ItemInput>();
        Set<Long> seen=new HashSet<>();
        for (var item:items) {
            if(item==null || item.catalogItemId()==null || item.catalogItemId()<1) throw invalid("服务项目不正确");
            if(item.quantity()==null || item.quantity()<1 || item.quantity()>999) throw invalid("数量须在1—999之间");
            if(!seen.add(item.catalogItemId())) throw invalid("同一项目只能出现一行，请合并数量");
            normalized.add(new ItemInput(item.catalogItemId(),item.quantity()));
        }
        return List.copyOf(normalized);
    }
    private Normalized normalize(List<ServiceOrderItemInput> items,String note) {
        var normalized=shapeOnly(items);
        for (var item:normalized) {
            var catalog=billing.catalogById(item.catalogItemId());
            if(catalog==null || !catalog.enabled) throw invalid("服务项目不存在或已停用，请刷新价目后重试");
        }
        String clean=note==null?null:note.trim();
        if(clean!=null && (clean.isEmpty() || clean.length()>500 || clean.codePoints().anyMatch(c->Character.isISOControl(c))))
            throw invalid("备注最长500字，且不能包含特殊控制字符");
        return new Normalized(normalized,clean==null||clean.isEmpty()?null:clean);
    }
    private String trimNote(String value) {
        if(value==null) return null;
        String clean=value.trim();
        return clean.isEmpty()?null:clean;
    }
    private void saveItems(long orderId,List<ItemInput> items) {
        for (var item:items) {
            var catalog=billing.catalogById(item.catalogItemId());
            var row=new OrderItemRow();
            row.orderId=orderId; row.catalogItemId=item.catalogItemId();
            row.itemNameSnapshot=catalog.itemName; row.unitPriceSnapshot=catalog.unitPrice;
            row.currencySnapshot=catalog.currency; row.quantity=item.quantity();
            if(billing.insertOrderItem(row)!=1) throw new IllegalStateException("Service order item was not saved");
        }
    }
    private ServiceOrderView view(long id) {
        var row=billing.orderById(id);
        if(row==null) throw new IllegalStateException("Service order receipt is incomplete");
        var items=billing.orderItems(id).stream().map(i->new ServiceOrderItemView(i.id,i.catalogItemId,i.itemNameSnapshot,
            BillingDtos.money(i.unitPriceSnapshot),i.quantity,BillingDtos.money(i.unitPriceSnapshot.multiply(BigDecimal.valueOf(i.quantity))))).toList();
        BigDecimal total=billing.itemsTotal(id);
        return new ServiceOrderView(row.id,row.stayRecordId,row.status,row.note,row.requestedByName,row.fulfilledByName,
            row.fulfilledAt,row.cancelledByName,row.cancelledAt,row.cancelReason,row.version,row.createdAt,items,BillingDtos.money(total));
    }
    private String trimName(String name,int quantity) {
        String text=name+"×"+quantity;
        return text.length()<=200?text:text.substring(0,199)+"…";
    }
    private String requiredText(String value,int max,String label) {
        String clean=value==null?"":value.trim();
        if(clean.isEmpty() || clean.length()>max || clean.codePoints().anyMatch(c->Character.isISOControl(c)))
            throw invalid(label+"需填写1—"+max+"字，且不能包含特殊控制字符");
        return clean;
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(409,code,message); }
    private static ApiException notFound() { return new ApiException(404,"SERVICE_ORDER_NOT_FOUND","服务单不存在"); }

    record ItemInput(long catalogItemId,int quantity) {}
    record CanonicalOrder(long stayId,List<ItemInput> items,String note) {}
    record CanonicalAction(long orderId) {}
    record CanonicalCancel(long orderId,String reason) {}
}
