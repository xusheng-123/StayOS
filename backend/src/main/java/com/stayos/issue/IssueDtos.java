package com.stayos.issue;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
public final class IssueDtos {
 private IssueDtos() {}
 public record Report(@NotNull @Positive Long roomId,@NotBlank String issueType,@NotBlank String severity,@NotBlank @Size(max=2000) String description,@Size(max=1000) String notes) {}
 public record Action(@NotNull @PositiveOrZero Long version,@Size(max=1000) String reason,String severity) {}
 public record Stop(@Positive Long issueId,@NotBlank @Size(max=1000) String reason) {}
 public record Resume(@NotNull @Positive Long stopId,@NotBlank @Size(max=1000) String reason) {}
 public record Filter(@Min(1) @Max(100000) Integer page,@Min(1) @Max(100) Integer pageSize,@Size(max=20) String roomNumber,@Positive Long roomId,@Min(-99) @Max(199) Integer floor,String issueType,String severity,String status) {}
 public static class Issue {
  public Long id,roomId,reportedBy,startedBy,resolvedBy,version;
  public Integer floor;
  public String roomNumber,roomTypeName,issueType,severity,status,description,notes,reporterName,handlerName,resolution;
  public OffsetDateTime reportedAt,startedAt,resolvedAt;
 }
 public static class Log {
  public Long id,issueId,operatorId,issueVersion;
  public String action,fromStatus,toStatus,fromSeverity,toSeverity,reason,operatorName;
  public OffsetDateTime operatedAt;
 }
 public static class Maintenance {
  public Long id,roomId,issueId,stoppedBy,resumedBy;
  public String reason,resumeReason,stoppedByName,resumedByName;
  public OffsetDateTime stoppedAt,resumedAt;
 }
 public static class MaintenanceLog {
  public Long id,stopId,roomId,operatorId;
  public String action,reason,operatorName;
  public OffsetDateTime operatedAt;
 }
 public record Statistics(long open,long inProgress,long critical,long maintenanceRooms) {}
}
