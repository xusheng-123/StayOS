package com.stayos.billing;

import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.billing.BillingDtos.*;
import com.stayos.billing.BillingMapper.*;
import com.stayos.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Controlled maintenance of policies, rate plans, day prices and the service catalog. Price changes never rewrite posted history. */
@Service
public class BillingConfigService {
    private static final Map<String,Set<String>> POLICY_MODES=Map.of(
        "earlyCheckin",Set.of("FULL_NIGHT","HOURLY","FREE","DECLINED"),
        "lateCheckout",Set.of("FREE","HOURLY","FULL_NIGHT","DECLINED"),
        "earlyCheckout",Set.of("FULL_NIGHT","CREDIT_UNUSED","DECLINED"),
        "roomChange",Set.of("BY_NEW_SEGMENT","ORIGINAL_PRICE","DECLINED"),
        "extension",Set.of("CURRENT_RATE","RATE_AT_CHECKIN","DECLINED"));
    private static final BigDecimal UNIT_MAX=new BigDecimal("999999.99");
    private final BillingMapper billing;
    private final BusinessActorGuard actors;
    private final AuditService audit;
    public BillingConfigService(BillingMapper billing,BusinessActorGuard actors,AuditService audit) {
        this.billing=billing; this.actors=actors; this.audit=audit;
    }

    // ---------- policies ----------
    @Transactional(readOnly=true)
    public Map<String,Object> policyOverview(long actor) {
        actors.requireRead(actor,"billing:read");
        var active=billing.activePolicy();
        // LinkedHashMap because Map.of rejects null values and "active" is null before the first policy exists.
        var overview=new java.util.LinkedHashMap<String,Object>();
        overview.put("active",active==null?null:view(active));
        overview.put("history",billing.policyHistory().stream().map(this::view).toList());
        return overview;
    }
    @Transactional
    public PolicyView createPolicy(long actor,PolicyInput input) {
        if(input==null) throw invalid("请提供财务政策内容");
        var row=new PolicyRow();
        row.version=billing.nextPolicyVersion();
        row.billingCutoffTime=BillingDtos.time(input.billingCutoffTime(),"计费日切");
        row.checkoutDeadline=input.checkoutDeadline()==null||input.checkoutDeadline().isBlank()?null:BillingDtos.time(input.checkoutDeadline(),"退房时限");
        row.earlyCheckinMode=mode(input.earlyCheckinMode(),"earlyCheckin","早到政策");
        row.lateCheckoutMode=mode(input.lateCheckoutMode(),"lateCheckout","晚退政策");
        row.earlyCheckoutMode=mode(input.earlyCheckoutMode(),"earlyCheckout","提前退房政策");
        row.roomChangePriceMode=mode(input.roomChangePriceMode(),"roomChange","换房差价政策");
        row.extensionPriceMode=mode(input.extensionPriceMode(),"extension","续住定价政策");
        row.notes=input.notes()==null?null:trim(input.notes(),500,"政策备注");
        actors.require(actor,"billing:configure");
        row.isActive=true;
        row.actor=actor;
        billing.deactivatePolicies();
        if(billing.insertPolicy(row)!=1) throw new IllegalStateException("Policy was not saved");
        audit.success(actor,"BILLING_POLICY_UPDATE","BILLING",row.id);
        return view(billing.activePolicy());
    }
    private String mode(String value,String kind,String label) {
        if(value==null || !POLICY_MODES.get(kind).contains(value)) throw invalid(label+"取值不正确");
        return value;
    }
    private PolicyView view(PolicyRow row) {
        return new PolicyView(row.id,row.version,row.billingCutoffTime.toString(),
            row.checkoutDeadline==null?null:row.checkoutDeadline.toString(),
            row.earlyCheckinMode,row.lateCheckoutMode,row.earlyCheckoutMode,row.roomChangePriceMode,row.extensionPriceMode,
            row.notes,row.isActive,row.createdByName,row.createdAt);
    }

    // ---------- rate plans ----------
    @Transactional(readOnly=true)
    public List<RatePlanView> ratePlans(long actor) {
        actors.requireRead(actor,"billing:read");
        return billing.ratePlanList().stream().map(this::view).toList();
    }
    @Transactional
    public RatePlanView createRatePlan(long actor,RatePlanCreateInput input) {
        if(input==null || input.roomTypeId()==null || input.roomTypeId()<1) throw invalid("请选择房型");
        String code=requiredText(input.planCode(),40,"方案编码"),name=requiredText(input.planName(),60,"方案名称");
        var price=unitPrice(input.nightlyPrice(),false,"房价");
        actors.require(actor,"billing:configure");
        if(billing.roomTypeEnabled(input.roomTypeId())==0) throw invalid("房型不存在或已停用");
        var row=new RatePlanRow();
        row.roomTypeId=input.roomTypeId(); row.planCode=code; row.planName=name; row.nightlyPrice=price;
        row.actor=actor;
        if(billing.insertRatePlan(row)!=1) throw new IllegalStateException("Rate plan was not saved");
        audit.success(actor,"BILLING_RATE_PLAN_UPDATE","BILLING",row.id);
        return view(billing.ratePlanById(row.id));
    }
    @Transactional
    public RatePlanView updateRatePlan(long actor,long id,RatePlanUpdateInput input) {
        if(input==null || input.version()==null || input.version()<0) throw invalid("请提供当前价目方案版本");
        String name=requiredText(input.planName(),60,"方案名称");
        var price=unitPrice(input.nightlyPrice(),false,"房价");
        actors.require(actor,"billing:configure");
        var current=billing.ratePlanById(id);
        if(current==null) throw new ApiException(404,"RATE_PLAN_NOT_FOUND","价目方案不存在");
        if(current.version!=input.version()) throw conflict("STALE_VERSION","价目方案已被其他人修改，请刷新后重新核对");
        boolean enabled=input.enabled()==null?current.enabled:input.enabled();
        if(billing.updateRatePlan(id,input.version(),name,price,enabled)!=1)
            throw conflict("STALE_VERSION","价目方案已被其他人修改，请刷新后重新核对");
        audit.success(actor,"BILLING_RATE_PLAN_UPDATE","BILLING",id);
        return view(billing.ratePlanById(id));
    }

    // ---------- day prices ----------
    @Transactional(readOnly=true)
    public List<RateDayView> rateDays(long actor,long planId,String from,String to) {
        actors.requireRead(actor,"billing:read");
        if(billing.ratePlanById(planId)==null) throw new ApiException(404,"RATE_PLAN_NOT_FOUND","价目方案不存在");
        var start=BillingDtos.date(from,"开始日期"); var end=BillingDtos.date(to,"结束日期");
        if(end.isBefore(start) || end.toEpochDay()-start.toEpochDay()>366) throw invalid("日期范围须为递增且不超过366天");
        return billing.rateDayList(planId,start,end).stream().map(r->new RateDayView(r.ratePlanId,r.stayDate.toString(),BillingDtos.money(r.nightlyPrice),r.updatedAt)).toList();
    }
    @Transactional
    public RateDayView upsertRateDay(long actor,long planId,RateDayInput input) {
        if(input==null) throw invalid("请提供日期价格");
        var date=BillingDtos.date(input.stayDate(),"住宿日期");
        var price=unitPrice(input.nightlyPrice(),false,"房价");
        actors.require(actor,"billing:configure");
        var plan=billing.ratePlanById(planId);
        if(plan==null) throw new ApiException(404,"RATE_PLAN_NOT_FOUND","价目方案不存在");
        billing.upsertRateDay(planId,date,price,actor);
        audit.success(actor,"BILLING_RATE_DAY_UPDATE","BILLING",planId);
        return new RateDayView(planId,date.toString(),BillingDtos.money(price),java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
    }

    // ---------- service catalog ----------
    @Transactional(readOnly=true)
    public List<CatalogView> catalog(long actor) {
        actors.requireRead(actor,"billing:read");
        return billing.catalogList().stream().map(this::view).toList();
    }
    @Transactional
    public CatalogView createCatalogItem(long actor,CatalogCreateInput input) {
        if(input==null) throw invalid("请提供价目内容");
        String code=requiredText(input.itemCode(),40,"项目编码"),name=requiredText(input.itemName(),60,"项目名称");
        if(input.pricingUnit()==null || !Set.of("ITEM","TIME").contains(input.pricingUnit())) throw invalid("计价单位不正确");
        var price=unitPrice(input.unitPrice(),false,"单价");
        actors.require(actor,"billing:configure");
        var row=new CatalogRow();
        row.itemCode=code; row.itemName=name; row.pricingUnit=input.pricingUnit(); row.unitPrice=price;
        row.actor=actor;
        if(billing.insertCatalog(row)!=1) throw new IllegalStateException("Catalog item was not saved");
        audit.success(actor,"BILLING_CATALOG_UPDATE","BILLING",row.id);
        return view(billing.catalogById(row.id));
    }
    @Transactional
    public CatalogView updateCatalogItem(long actor,long id,CatalogUpdateInput input) {
        if(input==null || input.version()==null || input.version()<0) throw invalid("请提供当前价目版本");
        String name=requiredText(input.itemName(),60,"项目名称");
        if(input.pricingUnit()==null || !Set.of("ITEM","TIME").contains(input.pricingUnit())) throw invalid("计价单位不正确");
        var price=unitPrice(input.unitPrice(),false,"单价");
        actors.require(actor,"billing:configure");
        var current=billing.catalogById(id);
        if(current==null) throw new ApiException(404,"CATALOG_ITEM_NOT_FOUND","价目项目不存在");
        if(current.version!=input.version()) throw conflict("STALE_VERSION","价目已被其他人修改，请刷新后重新核对");
        boolean enabled=input.enabled()==null?current.enabled:input.enabled();
        if(billing.updateCatalog(id,input.version(),name,input.pricingUnit(),price,enabled)!=1)
            throw conflict("STALE_VERSION","价目已被其他人修改，请刷新后重新核对");
        audit.success(actor,"BILLING_CATALOG_UPDATE","BILLING",id);
        return view(billing.catalogById(id));
    }
    private CatalogView view(CatalogRow row) {
        return new CatalogView(row.id,row.itemCode,row.itemName,row.pricingUnit,BillingDtos.money(row.unitPrice),row.enabled,row.version,row.createdAt,row.updatedAt);
    }
    private RatePlanView view(RatePlanRow row) {
        return new RatePlanView(row.id,row.roomTypeId,row.roomTypeCode,row.roomTypeName,row.planCode,row.planName,
            row.pricingUnit,BillingDtos.money(row.nightlyPrice),row.enabled,row.version,row.createdAt,row.updatedAt);
    }
    private BigDecimal unitPrice(String value,boolean positive,String label) {
        var amount=BillingDtos.amount(value,positive,label);
        if(amount.compareTo(UNIT_MAX)>0) throw invalid(label+"超出单价上限");
        return amount;
    }
    private String requiredText(String value,int max,String label) {
        String clean=value==null?"":value.trim();
        if(clean.isEmpty() || clean.length()>max || clean.codePoints().anyMatch(c->Character.isISOControl(c)))
            throw invalid(label+"需填写1—"+max+"字，且不能包含特殊控制字符");
        return clean;
    }
    private String trim(String value,int max,String label) {
        String clean=value.trim();
        if(clean.length()>max) throw invalid(label+"最长"+max+"字");
        return clean.isEmpty()?null:clean;
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(409,code,message); }
}
