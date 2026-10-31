package com.stayos.cashier;

import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.cashier.CashierDtos.*;
import com.stayos.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Business days and the manual daily close. The close batch is three explicit transactions:
 * reserve (lock day + RUNNING run), work (verify -> reconcile -> freeze report -> close day),
 * and a failure marker that survives the work rollback. Single instance per day is guaranteed
 * by the partial unique index on RUNNING runs, never by JVM locks.
 */
@Service
public class BusinessDayService {
    private final CashierMapper cashier;
    private final BusinessActorGuard actors;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public BusinessDayService(CashierMapper cashier,BusinessActorGuard actors,AuditService audit,PlatformTransactionManager transactions) {
        this.cashier=cashier; this.actors=actors; this.audit=audit;
        this.tx=new TransactionTemplate(transactions);
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(409,code,message); }

    @Transactional
    public List<BusinessDayView> days(long actor,int limit) {
        actors.require(actor,"cashier:operate");
        var today=CashierDtos.businessDate(OffsetDateTime.now());
        cashier.ensureDay(today);
        if(cashier.dayByDate(today)==null) throw conflict("BILLING_POLICY_REQUIRED","生效财务政策缺失，请先完成财务配置");
        var current=cashier.dayByDate(today);
        int safeLimit=Math.min(60,Math.max(1,limit));
        return cashier.recentDays(safeLimit).stream()
            .map(row -> new BusinessDayView(row.businessDate.toString(),row.status,row.cutoff.toString(),
                row.closedAt,row.closedByName,row.hasReport,row.businessDate.equals(current.businessDate)))
            .toList();
    }

    /** Manual, human-confirmed close. Re-runnable: a FAILED run leaves the day OPEN for retry. */
    public CloseResultView close(long actor,LocalDate date) {
        var today=CashierDtos.businessDate(OffsetDateTime.now());
        if(date.isAfter(today)) throw invalid("不能关闭未来的营业日");
        Long runId=tx.execute(status -> {
            // BusinessActorGuard.require is Propagation.MANDATORY: run it inside this reserve transaction.
            actors.require(actor,"cashier:close");
            cashier.ensureDay(date);
            var day=cashier.dayByDate(date);
            if(day==null) throw conflict("BILLING_POLICY_REQUIRED","该营业日缺少财务政策依据，无法关账");
            if("CLOSED".equals(day.status)) throw conflict("DAY_ALREADY_CLOSED","该营业日已关账，本期调整请在当前营业日入账");
            var run=new CashierMapper.CloseRunRow(); run.businessDate=date; run.actor=actor;
            cashier.insertRun(run);
            return run.id;
        });
        try {
            var result=tx.execute(status -> runClose(actor,date,runId));
            audit.success(actor,"BUSINESS_DAY_CLOSE","CASHIER",runId);
            return result;
        } catch(RuntimeException failure) {
            String reason=failure.getMessage()==null?"关账失败":failure.getMessage();
            String detail=failure instanceof CloseFailure closeFailure ? closeFailure.detail : reason;
            tx.executeWithoutResult(status -> cashier.failRun(runId,detail.length()>1000?detail.substring(0,1000):detail));
            throw failure instanceof CloseFailure closeFailure
                ? conflict("CLOSE_FAILED",closeFailure.detail)
                : failure;
        }
    }

    private CloseResultView runClose(long actor,LocalDate date,Long runId) {
        // Step 1: room fees must exist for every stay segment covering the day (unique source per stay+date).
        List<String> problems=new ArrayList<>();
        for(var segment:cashier.segmentFees(date)) {
            if(segment.pricingStatus==null) {
                problems.add(("未开立客账：%s，入住#%d 占用该营业日但尚未开账").formatted(segment.roomNumber,segment.stayRecordId));
                continue;
            }
            if(!"PRICED".equals(segment.pricingStatus)) continue; // legacy-access folios never back-filled
            if(segment.chargeId==null) {
                problems.add(("缺少房费：%s，入住#%d，房型%s，当日无房费入账").formatted(segment.roomNumber,segment.stayRecordId,segment.typeName));
                continue;
            }
            if(segment.dayPrice!=null && segment.postedAmount!=null && segment.dayPrice.compareTo(segment.postedAmount)!=0)
                problems.add(("日价不符：#%d %s 房 应为%s（特殊日价）实为%s").formatted(segment.chargeId,segment.roomNumber,
                    CashierDtos.money(segment.dayPrice),CashierDtos.money(segment.postedAmount)));
        }
        if(!problems.isEmpty()) { cashier.bumpStep(runId,1,"VERIFY_ROOM_FEES"); throw new CloseFailure(String.join("；",problems)); }

        // Step 2: reconcile the totals the report will freeze against the live source sums (same query, frozen together).
        var charges=cashier.chargeSums(date);
        var payments=cashier.paymentSumsByDay(date);
        BigDecimal netReceivable=charges.charges.subtract(charges.credits);
        BigDecimal netReceived=payments.receipts.subtract(payments.refunds);
        cashier.bumpStep(runId,2,"RECONCILE");

        // Step 3: freeze the report snapshot (denominators, data-gap markers, post-close adjustment trail).
        int occupied=cashier.occupiedRooms(date), available=cashier.availableRooms(date);
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("businessDate",date.toString());
        snapshot.put("frozenAt",OffsetDateTime.now().toString());
        snapshot.put("charges",Map.of("total",charges.charges,"credits",charges.credits,"netReceivable",netReceivable,
            "roomNights",charges.roomNights,"roomRevenue",charges.roomRevenue));
        snapshot.put("payments",Map.of("receipts",payments.receipts,"refunds",payments.refunds,"netReceived",netReceived,
            "cashIn",payments.cashIn,"cashOut",payments.cashOut));
        var occupancy=new LinkedHashMap<String,Object>();
        occupancy.put("roomNightsSold",charges.roomNights);
        occupancy.put("occupiedRooms",occupied);
        occupancy.put("availableRooms",available<0?0:available);
        if(available<=0) occupancy.put("occupancyRate",null); else occupancy.put("occupancyRate",
            BigDecimal.valueOf(occupied*100L).divide(BigDecimal.valueOf(available),1, RoundingMode.HALF_UP));
        if(charges.roomNights<=0) occupancy.put("adr",null); else occupancy.put("adr",
            charges.roomRevenue.divide(BigDecimal.valueOf(charges.roomNights),2, RoundingMode.HALF_UP));
        var gaps=new ArrayList<String>();
        if(available<=0) gaps.add("当日无可售房分母，出租率标记为数据不足");
        if(charges.roomNights<=0) gaps.add("当日无已入账房晚，ADR 标记为数据不足");
        occupancy.put("dataGaps",gaps);
        snapshot.put("occupancy",occupancy);
        var adjustments=cashier.laterAdjustments(date);
        snapshot.put("laterAdjustments",adjustments);
        snapshot.put("notes","金额为当日入账口径；现金为收退款中现金方式的合计；分母含维修停售扣减。报表为关账时点快照，之后调整见 laterAdjustments。");
        try { cashier.insertReport(date,runId,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snapshot),actor); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("快照序列化失败",failure); }
        cashier.bumpStep(runId,3,"REPORT_FROZEN");

        // Step 4: mark the day CLOSED.
        if(cashier.closeDay(date,actor)!=1) throw conflict("DAY_ALREADY_CLOSED","该营业日已关账，请刷新查看");
        cashier.completeRun(runId,4,"DAY_CLOSED");
        return new CloseResultView(runId,date.toString(),"COMPLETED",null,4);
    }

    @Transactional(readOnly=true)
    public ReportPage reports(long actor,LocalDate from,LocalDate to,int page,int pageSize) {
        actors.requireRead(actor,"report:finance");
        int safePage=Math.max(1,page), safeSize=Math.min(100,Math.max(1,pageSize));
        var items=cashier.reportList(from,to,safeSize,(long)(safePage-1)*safeSize).stream().map(row -> {
            @SuppressWarnings("unchecked") Map<String,Object> snapshot=parseSnapshot(row.get("snapshot"));
            Object at=row.get("created_at");
            OffsetDateTime createdAt=at instanceof OffsetDateTime direct?direct
                :at instanceof java.sql.Timestamp ts?ts.toInstant().atOffset(java.time.ZoneOffset.UTC):null;
            return new DailyReportView(((Number)row.get("id")).longValue(),row.get("business_date").toString(),
                snapshot,(String)row.get("created_by_name"),createdAt);
        }).toList();
        return new ReportPage(items,cashier.reportCount(from,to),safePage,safeSize);
    }

    /** JDBC returns JSONB as PGobject (or String); normalize to a Map for the API response. Big decimals keep their scale. */
    private static Map<String,Object> parseSnapshot(Object raw) {
        if(raw instanceof Map<?,?> map) return (Map<String,Object>)map;
        try { return new com.fasterxml.jackson.databind.ObjectMapper()
            .readerFor(new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {})
            .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readValue(raw.toString()); }
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("日报快照解析失败",failure); }
    }

    static final class CloseFailure extends RuntimeException {
        final String detail;
        CloseFailure(String detail) { super(detail); this.detail=detail; }
    }
}
