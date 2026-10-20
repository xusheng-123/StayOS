package com.stayos.stay.dto;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
public final class RoomChangeDtos {
 private RoomChangeDtos() {}
 public record Request(@NotNull @Positive Long segmentId,@NotNull @Positive Long targetRoomId,
  @NotBlank @Size(max=20) String reasonType,@NotBlank @Size(max=1000) String reason,@Positive Long issueId) {}
 public static class Result {
  public Long id,stayRecordId,oldRoomId,newRoomId,oldSegmentId,newSegmentId,issueId,operatedBy,taskId;
  public String reasonType,reason,oldRoomNumber,newRoomNumber,oldRoomTypeName,newRoomTypeName,operatorName;
  public OffsetDateTime changedAt,createdAt;
 }
 public record Segment(Long id,Long roomId,String roomNumber,String roomTypeName,OffsetDateTime enteredAt,OffsetDateTime exitedAt,String endReason) {}
}
