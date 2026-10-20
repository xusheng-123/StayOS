package com.stayos.housekeeping.dto;
import java.time.OffsetDateTime;
/** Deliberately excludes guests, stay notes, credentials and contact information. */
public class TaskView {
    public Long id,roomId;
    public Integer floor;
    public String roomNumber,roomTypeName,taskType,source,status,notes;
    public OffsetDateTime createdAt;
}
