package com.stayos.cashier;

import com.stayos.common.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import java.util.regex.Pattern;

public final class CashierDtos {
    private CashierDtos() {}
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern MONEY = Pattern.compile("[0-9]{1,8}(\\.[0-9]{1,2})?");
    private static final BigDecimal MONEY_MAX = new BigDecimal("99999999.99");

    /** API amounts are plain decimal strings; negative, scientific, over-precision and overflow are refused. */
    public static BigDecimal amount(String value,boolean allowZero,String label) {
        if(value==null || !MONEY.matcher(value).matches()) throw new ApiException(400,"VALIDATION_ERROR",label+"金额必须为最多两位小数的非负数格式");
        var parsed=new BigDecimal(value);
        if(parsed.compareTo(MONEY_MAX)>0) throw new ApiException(400,"VALIDATION_ERROR",label+"金额超出上限");
        if(!allowZero && parsed.signum()==0) throw new ApiException(400,"VALIDATION_ERROR",label+"金额必须大于零");
        return parsed;
    }
    public static String money(BigDecimal value) { return value==null?null:value.setScale(2).toPlainString(); }
    public static LocalDate date(String value,String label) {
        try { if(value==null) throw new ApiException(400,"VALIDATION_ERROR",label+"不能为空"); return LocalDate.parse(value); }
        catch(java.time.format.DateTimeParseException failure) { throw new ApiException(400,"VALIDATION_ERROR",label+"日期格式不正确"); }
    }
    static LocalDate businessDate(OffsetDateTime moment) { return moment.atZoneSameInstant(BUSINESS_ZONE).toLocalDate(); }

    public record ShiftOpenInput(String openedFloat) {}
    public record ShiftSubmitInput(String countedCash,String differenceReason,Long version) {}
    public record ShiftConfirmInput(Long version) {}
    public record ShiftMoney(String receipts,String refunds,String expectedCash) {}
    public record ShiftView(long id,String operatorName,String username,String businessDate,String status,
        String openedFloat,OffsetDateTime openedAt,String countedCash,String differenceReason,OffsetDateTime submittedAt,
        String confirmedByName,OffsetDateTime confirmedAt,long version,ShiftMoney money) {}
    public record ShiftPaymentView(long id,String direction,String method,String amount,OffsetDateTime receivedAt,String receivedByName) {}

    public record BusinessDayView(String businessDate,String status,String cutoff,OffsetDateTime closedAt,
        String closedByName,boolean hasReport,boolean current) {}
    public record CloseResultView(long runId,String businessDate,String status,String failureReason,int stepsDone) {}

    public record DailyReportView(long id,String businessDate,Map<String,Object> snapshot,String createdByName,OffsetDateTime createdAt) {}
    public record ReportPage(java.util.List<DailyReportView> items,long total,int page,int pageSize) {}
    public record ShiftPage(java.util.List<ShiftView> items,long total,int page,int pageSize) {}
}
