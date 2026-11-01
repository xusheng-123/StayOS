package com.stayos.billing;

import com.stayos.common.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.regex.Pattern;

public final class BillingDtos {
    private BillingDtos() {}
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern MONEY = Pattern.compile("[0-9]{1,8}(\\.[0-9]{1,2})?");
    private static final BigDecimal MONEY_MAX = new BigDecimal("99999999.99");

    /** API amounts are plain decimal strings; negative, scientific, over-precision and overflow are refused. */
    public static BigDecimal amount(String value,boolean positive,String label) {
        if(value==null || !MONEY.matcher(value).matches()) throw new ApiException(400,"VALIDATION_ERROR",label+"金额必须为最多两位小数的正数格式");
        var parsed=new BigDecimal(value);
        if(parsed.compareTo(MONEY_MAX)>0) throw new ApiException(400,"VALIDATION_ERROR",label+"金额超出上限");
        if(positive && parsed.signum()<=0) throw new ApiException(400,"VALIDATION_ERROR",label+"金额必须大于零");
        return parsed;
    }
    public static String money(BigDecimal value) { return value==null?null:value.setScale(2).toPlainString(); }
    public static LocalDate date(String value,String label) {
        try { if(value==null) throw new ApiException(400,"VALIDATION_ERROR",label+"不能为空"); return LocalDate.parse(value); }
        catch(java.time.format.DateTimeParseException failure) { throw new ApiException(400,"VALIDATION_ERROR",label+"日期格式不正确"); }
    }
    public static LocalTime time(String value,String label) {
        if(value==null || !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw new ApiException(400,"VALIDATION_ERROR",label+"时间格式须为 HH:mm");
        return LocalTime.parse(value+":00");
    }
    public static LocalDate businessDate(OffsetDateTime moment) { return moment.atZoneSameInstant(BUSINESS_ZONE).toLocalDate(); }
    static LocalDate businessDate(OffsetDateTime moment,LocalTime cutoff) {
        var local=moment.atZoneSameInstant(BUSINESS_ZONE);
        return local.toLocalTime().isBefore(cutoff)?local.toLocalDate().minusDays(1):local.toLocalDate();
    }

    public record PolicyInput(String billingCutoffTime,String checkoutDeadline,String earlyCheckinMode,String lateCheckoutMode,
        String earlyCheckoutMode,String roomChangePriceMode,String extensionPriceMode,String notes) {}
    public record PolicyView(long id,int version,String billingCutoffTime,String checkoutDeadline,String earlyCheckinMode,
        String lateCheckoutMode,String earlyCheckoutMode,String roomChangePriceMode,String extensionPriceMode,
        String notes,boolean active,String createdByName,OffsetDateTime createdAt) {}

    public record RatePlanCreateInput(Long roomTypeId,String planCode,String planName,String nightlyPrice) {}
    public record RatePlanUpdateInput(String planName,String nightlyPrice,Boolean enabled,Long version) {}
    public record RatePlanView(long id,long roomTypeId,String roomTypeCode,String roomTypeName,String planCode,String planName,
        String pricingUnit,String nightlyPrice,boolean enabled,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}
    public record RateDayInput(String stayDate,String nightlyPrice) {}
    public record RateDayView(long ratePlanId,String stayDate,String nightlyPrice,OffsetDateTime updatedAt) {}

    public record CatalogCreateInput(String itemCode,String itemName,String pricingUnit,String unitPrice) {}
    public record CatalogUpdateInput(String itemName,String pricingUnit,String unitPrice,Boolean enabled,Long version) {}
    public record CatalogView(long id,String itemCode,String itemName,String pricingUnit,String unitPrice,boolean enabled,
        long version,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}

    public record FolioOpenInput(String chargesFrom) {}
    public record FolioSummary(String charges,String credits,String netReceivable,String receipts,String refunds,
        String netReceived,String balance) {}
    public record FolioView(long id,long stayRecordId,String status,String pricingStatus,String chargesEffectiveFrom,
        String currency,long version,long openedBy,String openedByName,OffsetDateTime openedAt,OffsetDateTime closedAt,
        String closedByName,long stayVersion,String stayStatus,String roomNumber,String roomTypeName,String primaryGuestName,
        OffsetDateTime checkInAt,OffsetDateTime expectedCheckOutAt,OffsetDateTime actualCheckOutAt,
        String checkInDate,String currentBusinessDate,FolioSummary summary) {}
    public record FolioListItem(long id,long stayRecordId,String status,String pricingStatus,String roomNumber,
        String primaryGuestName,OffsetDateTime checkInAt,OffsetDateTime closedAt,String charges,String netReceived,String balance) {}

    public record ChargeInput(String category,String businessDate,Long ratePlanId,String title,String unitPrice,Integer quantity,String note) {}
    public record ChargeView(long id,long folioId,String businessDate,String direction,String category,String name,
        String pricingUnit,int quantity,String unitPrice,String amount,String currency,Long policyVersion,
        Long ratePlanId,Long catalogItemId,String sourceType,String sourceKey,Long reversalOf,String note,
        String createdByName,OffsetDateTime createdAt) {}
    public record AdjustInput(Long originalChargeId,String mode,String amount,String reason) {}

    public record PaymentInput(String method,String methodReference,String purpose,String amount,String note) {}
    public record RefundInput(Long paymentRecordId,String amount,String reason) {}
    public record PaymentView(long id,long folioId,String direction,String method,String purpose,String amount,
        String currency,String methodReference,Long refundOf,Long originalPaymentId,String originalRemainder,String note,
        String receivedByName,OffsetDateTime receivedAt) {}

    public record CloseInput(Long version,Boolean acknowledgeOutstanding,String note) {}
    public record ReopenInput(Long version,String reason) {}
    public record FolioEventView(long id,String eventType,String actorName,String note,OffsetDateTime createdAt) {}
    public record FolioDetail(FolioView folio,List<ChargeView> charges,List<PaymentView> payments,List<FolioEventView> events) {}

    public record ServiceOrderItemInput(Long catalogItemId,Integer quantity) {}
    public record ServiceOrderCreateInput(java.util.List<ServiceOrderItemInput> items,String note) {}
    public record ServiceOrderUpdateInput(java.util.List<ServiceOrderItemInput> items,String note,Long version) {}
    public record ServiceOrderCancelInput(String reason) {}
    public record ServiceOrderItemView(long id,long catalogItemId,String itemName,String unitPrice,int quantity,String amount) {}
    public record ServiceOrderView(long id,long stayRecordId,String status,String note,String requestedByName,
        String fulfilledByName,OffsetDateTime fulfilledAt,String cancelledByName,OffsetDateTime cancelledAt,
        String cancelReason,long version,OffsetDateTime createdAt,java.util.List<ServiceOrderItemView> items,String totalAmount) {}
}
