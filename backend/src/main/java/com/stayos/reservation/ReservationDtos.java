package com.stayos.reservation;

import java.time.OffsetDateTime;
import java.util.List;

/** Reservation and future-inventory API shapes. Dates are ISO yyyy-MM-dd in the hotel business zone. */
public final class ReservationDtos {
    private ReservationDtos() {}

    public record CreateInput(String arrivalDate,String departureDate,Long roomTypeId,String source,String externalRef,
        String contactName,String contactPhone,String heldUntil,String notes) {}
    public record ModifyInput(Long version,String arrivalDate,String departureDate,Long roomTypeId,
        String contactName,String contactPhone,String notes) {}
    public record CancelInput(String reason) {}
    public record NoShowInput(String reason) {}
    public record AssignRoomInput(Long roomId) {}
    public record DepositInput(String method,String amount,String methodReference,String note) {}
    public record DepositRefundInput(Long paymentRecordId,String amount,String reason) {}
    public record BlockCreateInput(Long roomTypeId,String startDate,String endDate,String reason) {}

    public record NightAvailability(String date,long enabledRooms,long occupied,long sold,long blocked,long available) {}
    public record RoomTypeAvailability(Long roomTypeId,String typeCode,String typeName,
        List<NightAvailability> nights,long bookable) {}
    public record AvailabilityView(String arrivalDate,String departureDate,List<RoomTypeAvailability> roomTypes) {}

    public record ReservationView(long id,String arrivalDate,String departureDate,long nights,Long roomTypeId,
        String roomTypeCode,String roomTypeName,String status,String source,String externalRef,String contactName,
        String contactPhoneMask,String heldUntil,OffsetDateTime confirmedAt,String confirmedByName,
        OffsetDateTime cancelledAt,String cancelledByName,String cancelReason,OffsetDateTime noShowAt,String noShowByName,
        Long checkedInStayId,Long assignedRoomId,String assignedRoomNumber,long version,String notes,
        OffsetDateTime createdAt,String createdByName) {}
    public record ReservationEventView(long id,String action,String actorName,String detail,OffsetDateTime createdAt) {}
    public record DepositView(long id,String direction,String method,String purpose,String amount,String currency,
        Long folioId,String methodReference,String note,OffsetDateTime receivedAt,String receivedByName) {}
    public record ReservationDetail(ReservationView reservation,List<ReservationEventView> events,List<DepositView> deposits) {}
    public record ReleaseResult(int released) {}
    public record CheckInResult(long reservationId,long stayId) {}

    public record BlockView(long id,Long roomTypeId,String roomTypeCode,String roomTypeName,String startDate,
        String endDate,String reason,String createdByName,OffsetDateTime createdAt,String releasedByName,OffsetDateTime releasedAt) {}
}
