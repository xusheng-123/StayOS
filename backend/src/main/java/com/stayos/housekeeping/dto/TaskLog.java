package com.stayos.housekeeping.dto;
import java.time.OffsetDateTime;
/** Also serves as the immutable idempotent operation receipt. */
public class TaskLog {
    public Long id,taskId,fromAssignee,toAssignee,operatorId,taskVersion;
    public String action,fromStatus,toStatus,reason,fromAssigneeName,toAssigneeName,operatorName;
    public OffsetDateTime operatedAt;
}
