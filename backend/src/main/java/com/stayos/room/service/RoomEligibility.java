package com.stayos.room.service;
import com.stayos.common.ApiException;
import com.stayos.room.dto.RoomView;
/** Both check-in and room change consume the same PostgreSQL-derived eligibility. */
public final class RoomEligibility {
 private RoomEligibility() {}
 public static void requireAvailable(RoomView room) {
  if(room==null) throw new ApiException(404,"ROOM_NOT_FOUND","房间不存在");
  if(!Boolean.TRUE.equals(room.canCheckIn)) {
   String reason=!room.enabled?"房间已停用":room.occupied?"房间已被占用":room.hasBlockingIssue?"房间正在维修或存在阻止入住的异常":
    !"CLEAN".equals(room.cleaningStatus)?"房间尚未完成清洁":room.hasOpenCleaningTask?"房间仍有未完成清洁任务":"房间当前不可入住";
   throw new ApiException(409,"ROOM_NOT_AVAILABLE",reason);
  }
 }
}
