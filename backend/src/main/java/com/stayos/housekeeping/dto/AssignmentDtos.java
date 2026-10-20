package com.stayos.housekeeping.dto;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
public final class AssignmentDtos {
    private AssignmentDtos() {}
    public record Request(@NotNull @Positive Long assigneeId,
        @NotEmpty @Size(max=100) List<@NotNull @Positive Long> roomIds,@Size(max=1000) String reason) {}
    public record Employee(long id,String username,String displayName,boolean enabled,long pending,long inProgress,long completedToday) {}
    public static class Room {
        public Long roomId,roomTypeId,taskId,assignedTo,taskVersion;
        public String roomNumber,roomTypeName,cleaningStatus,taskStatus,assigneeName,assignmentState;
        public Integer floor;
        public Boolean occupied,enabled,hasBlockingIssue,assignable;
    }
    public record Filter(@Min(1) @Max(100000) Integer page,@Min(1) @Max(100) Integer pageSize,
        @Size(max=20) String roomNumber,@Min(-99) @Max(199) Integer floor,@Positive Long roomTypeId,@Positive Long assigneeId) {}
    public static class Receipt {
        public Long id,operatorId,assigneeId;
        public OffsetDateTime createdAt;
        public String notes;
        public List<Item> items;
    }
    public record Item(long roomId,long taskId,String roomNumber,boolean manualCreated,long assignmentLogId) {}
}
