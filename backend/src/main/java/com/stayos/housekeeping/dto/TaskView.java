package com.stayos.housekeeping.dto;
import java.time.OffsetDateTime;
/** Deliberately excludes guests, stay notes, credentials and contact information. */
public class TaskView {
    public Long id,roomId,assignedTo,assignedBy,startedBy,completedBy,createdBy,version;
    public Integer floor;
    public String roomNumber,roomTypeName,taskType,source,status,notes,completionNotes,assigneeName;
    public OffsetDateTime createdAt,assignedAt,claimedAt,startedAt,completedAt;
}
