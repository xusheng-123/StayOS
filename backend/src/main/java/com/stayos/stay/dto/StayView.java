package com.stayos.stay.dto;

import java.time.OffsetDateTime;
import java.util.List;

public class StayView {
    public Long id,roomId,segmentId,createdBy;
    public String roomNumber,roomTypeName,status,operatorName,primaryGuestName,notes;
    public Integer guestCount;
    public OffsetDateTime checkInAt,expectedCheckOutAt,actualCheckOutAt,createdAt,updatedAt;
    public List<Guest> guests;
    public record Guest(Long id,String name,String documentType,String documentNumber,String phone,String notes,boolean primary) {}
}
