package com.stayos.cashier;

import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.billing.BillingMapper;
import com.stayos.cashier.CashierDtos.*;
import com.stayos.common.ApiException;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class CashierShiftService {
    private final CashierMapper cashier;
    private final BillingMapper billing;
    private final BusinessActorGuard actors;
    private final IdempotencyService requests;
    private final AuditService audit;
    public CashierShiftService(CashierMapper cashier,BillingMapper billing,BusinessActorGuard actors,
            IdempotencyService requests,AuditService audit) {
        this.cashier=cashier; this.billing=billing; this.actors=actors; this.requests=requests; this.audit=audit;
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(409,code,message); }
    private static String requiredText(String value,int max,String label) {
        if(value==null || value.isBlank()) throw invalid(label+"不能为空");
        var trimmed=value.strip();
        if(trimmed.length()>max) throw invalid(label+"最长 "+max+" 字");
        return trimmed;
    }
    private static String trimOrNull(String value,int max,String label) {
        if(value==null) return null;
        var trimmed=value.strip();
        if(trimmed.isEmpty()) return null;
        if(trimmed.length()>max) throw invalid(label+"最长 "+max+" 字");
        return trimmed;
    }

    @Transactional
    public ShiftPage list(long actor,String scope,String status,int page,int pageSize) {
        actors.require(actor,"cashier:operate");
        boolean seeAll="all".equals(scope) && actors.hasPermission(actor,"cashier:close");
        Long operatorId=seeAll?null:actor;
        int safePage=Math.max(1,page), safeSize=Math.min(100,Math.max(1,pageSize));
        var rows=cashier.shiftList(operatorId,status,safeSize,(long)(safePage-1)*safeSize);
        long total=cashier.shiftCount(operatorId,status);
        return new ShiftPage(rows.stream().map(this::view).toList(),total,safePage,safeSize);
    }
    @Transactional
    public ShiftView detail(long actor,long id) {
        actors.requireRead(actor,"cashier:operate");
        var row=cashier.shiftById(id);
        if(row==null) throw new ApiException(404,"SHIFT_NOT_FOUND","收银班次不存在");
        // 前台只看本人班次；总经理（cashier:close）可看全部。
        if(row.operatorId!=actor && !actors.hasPermission(actor,"cashier:close"))
            throw new ApiException(403,"ACCESS_DENIED","只能查看本人名下的收银班次");
        return view(row);
    }

    @Transactional
    public ShiftView open(long actor,String key,ShiftOpenInput input) {
        if(input==null) throw invalid("请提供期初备用金");
        BigDecimal openedFloat=input.openedFloat()==null?BigDecimal.ZERO:CashierDtos.amount(input.openedFloat(),true,"期初备用金");
        var canonical=new CanonicalShift("OPEN",openedFloat.toPlainString());
        Long previous=requests.reserveCashier(actor,"CASHIER_SHIFT_OPEN",key,canonical,"CASHIER_SHIFT");
        actors.require(actor,"cashier:operate");
        if(previous!=null) return view(cashier.shiftById(previous));
        if(cashier.openShiftOf(actor)!=null) throw conflict("SHIFT_ALREADY_OPEN","你已有进行中的收银班次，请先提交后再开新班");
        ensureTodayRow(actor);
        var row=new CashierMapper.ShiftRow();
        row.businessDate=CashierDtos.businessDate(OffsetDateTime.now());
        row.operatorId=actor; row.openedFloat=openedFloat;
        if(cashier.insertShift(row)!=1) throw new IllegalStateException("Cashier shift was not saved");
        audit.success(actor,"CASHIER_SHIFT_OPEN","CASHIER",row.id);
        requests.completeCashierShift(actor,"CASHIER_SHIFT_OPEN",key,row.id);
        return view(cashier.shiftById(row.id));
    }

    @Transactional
    public ShiftView submit(long actor,String key,long shiftId,ShiftSubmitInput input) {
        if(input==null || input.version()==null || input.version()<0) throw invalid("请提供当前班次版本");
        BigDecimal counted=CashierDtos.amount(input.countedCash(),true,"期末实盘");
        String reason=trimOrNull(input.differenceReason(),500,"差异原因");
        var canonical=new CanonicalShift("SUBMIT",counted.toPlainString(),reason,String.valueOf(input.version()));
        Long previous=requests.reserveCashier(actor,"CASHIER_SHIFT_SUBMIT",key,canonical,"CASHIER_SHIFT");
        actors.require(actor,"cashier:operate");
        if(previous!=null) return view(cashier.shiftById(previous));
        var shift=cashier.lockShift(shiftId);
        if(shift==null) throw new ApiException(404,"SHIFT_NOT_FOUND","收银班次不存在");
        if(shift.operatorId!=actor) throw new ApiException(403,"ACCESS_DENIED","只能提交本人名下的收银班次");
        if(!"OPEN".equals(shift.status)) throw conflict("SHIFT_STATE","该班次当前状态不允许提交");
        BigDecimal expected=shift.openedFloat.add(shift.receipts).subtract(shift.refunds);
        BigDecimal difference=counted.subtract(expected);
        if(difference.signum()!=0 && reason==null) throw invalid("实盘与账面存在差异，请填写差异原因");
        var count=new CashierMapper.CountRow();
        count.shiftId=shift.id; count.expectedCash=expected; count.countedCash=counted;
        count.difference=difference; count.differenceReason=reason; count.actor=actor;
        if(cashier.insertCount(count)!=1) throw new IllegalStateException("Cash count was not saved");
        if(cashier.submitShift(shift.id,counted,reason,input.version())!=1)
            throw conflict("STALE_VERSION","班次已被其他人更新，请刷新后重新核对");
        audit.success(actor,"CASHIER_SHIFT_SUBMIT","CASHIER",shift.id);
        requests.completeCashierShift(actor,"CASHIER_SHIFT_SUBMIT",key,shift.id);
        return view(cashier.shiftById(shift.id));
    }

    @Transactional
    public ShiftView confirm(long actor,String key,long shiftId,ShiftConfirmInput input) {
        if(input==null || input.version()==null || input.version()<0) throw invalid("请提供当前班次版本");
        var canonical=new CanonicalShift("CONFIRM",String.valueOf(input.version()));
        Long previous=requests.reserveCashier(actor,"CASHIER_SHIFT_CONFIRM",key,canonical,"CASHIER_SHIFT");
        actors.require(actor,"cashier:close");
        if(previous!=null) return view(cashier.shiftById(previous));
        var shift=cashier.lockShift(shiftId);
        if(shift==null) throw new ApiException(404,"SHIFT_NOT_FOUND","收银班次不存在");
        if(!"SUBMITTED".equals(shift.status)) throw conflict("SHIFT_STATE","只有已提交待确认的班次可以确认");
        if(cashier.confirmShift(shift.id,actor,input.version())!=1)
            throw conflict("STALE_VERSION","班次已被其他人更新，请刷新后重新核对");
        audit.success(actor,"CASHIER_SHIFT_CONFIRM","CASHIER",shift.id);
        requests.completeCashierShift(actor,"CASHIER_SHIFT_CONFIRM",key,shift.id);
        return view(cashier.shiftById(shift.id));
    }

    /** Business day row for today must exist before shifts and cash attribution; needs an active policy. */
    void ensureTodayRow(long actor) {
        var today=CashierDtos.businessDate(OffsetDateTime.now());
        cashier.ensureDay(today);
        if(cashier.dayByDate(today)==null)
            throw conflict("BILLING_POLICY_REQUIRED","生效财务政策缺失，请先完成财务配置");
    }

    private ShiftView view(CashierMapper.ShiftRow row) {
        var expected=row.openedFloat.add(row.receipts).subtract(row.refunds);
        return new ShiftView(row.id,row.operatorName,row.username,row.businessDate.toString(),row.status,
            CashierDtos.money(row.openedFloat),row.openedAt,CashierDtos.money(row.countedCash),row.differenceReason,
            row.submittedAt,row.confirmedByName,row.confirmedAt,row.version,
            new ShiftMoney(CashierDtos.money(row.receipts),CashierDtos.money(row.refunds),CashierDtos.money(expected)));
    }
    record CanonicalShift(String action,String... values) {}
}
