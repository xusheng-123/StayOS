package com.stayos.stay.dto;
import java.time.OffsetDateTime;
/** Facts at checkout, not a second copy of the current room or task state. */
public class CheckoutResult {
    public Long stayId,segmentId,roomId,taskId,checkedOutBy;
    public String roomNumber,roomTypeName;
    public OffsetDateTime actualCheckOutAt;
    public String cleaningStatusAtCheckout="DIRTY",taskStatusAtCreation="PENDING";
}
