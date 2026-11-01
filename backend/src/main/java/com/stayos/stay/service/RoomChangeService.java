package com.stayos.stay.service;
import com.stayos.stay.dto.RoomChangeDtos.*;
import com.stayos.stay.mapper.*;
import com.stayos.room.mapper.RoomMapper;
import com.stayos.room.dto.RoomFilter;
import com.stayos.room.service.*;
import com.stayos.housekeeping.mapper.TaskMapper;
import com.stayos.housekeeping.service.*;
import com.stayos.audit.AuditService;
import com.stayos.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
@Service
public class RoomChangeService {
 private final RoomChangeMapper changes; private final CheckoutMapper stays; private final StayMapper requestsMapper;
 private final RoomMapper rooms; private final RoomCatalogService catalog; private final TaskMapper employees;
 private final com.stayos.reservation.ReservationMapper futureInventory;
 private final TaskAccess access; private final TaskCreationService tasks; private final IdempotencyService requests; private final AuditService audit;
 private final com.stayos.auth.BusinessActorGuard actors;
 public RoomChangeService(RoomChangeMapper changes,CheckoutMapper stays,StayMapper requestsMapper,RoomMapper rooms,RoomCatalogService catalog,TaskMapper employees,com.stayos.reservation.ReservationMapper futureInventory,TaskAccess access,TaskCreationService tasks,IdempotencyService requests,AuditService audit,com.stayos.auth.BusinessActorGuard actors) {
  this.changes=changes;this.stays=stays;this.requestsMapper=requestsMapper;this.rooms=rooms;this.catalog=catalog;this.employees=employees;this.futureInventory=futureInventory;this.access=access;this.tasks=tasks;this.requests=requests;this.audit=audit;this.actors=actors;
 }
 private ApiException conflict(String message) { return new ApiException(409,"ROOM_CHANGE_CONFLICT",message); }
 private record Canonical(long stayId,long segmentId,long targetRoomId,String reasonType,String reason,Long issueId) {}
 @Transactional
 public Result change(long actor,String key,long stayId,Request raw) {
  access.require(actor,"stay:manage");
  if(stayId<1 || raw==null || raw.segmentId()==null || raw.segmentId()<1 || raw.targetRoomId()==null || raw.targetRoomId()<1 || raw.reasonType()==null || !Set.of("GUEST_REQUEST","ROOM_ISSUE","OTHER").contains(raw.reasonType()))
   throw new ApiException(400,"VALIDATION_ERROR","请选择当前入住、目标房间和合法换房原因类型");
  String reason=raw.reason()==null?"":raw.reason().trim();
  if(reason.isBlank() || reason.length()>1000 || raw.reason().codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t') || (raw.issueId()!=null && (raw.issueId()<1 || !raw.reasonType().equals("ROOM_ISSUE"))))
   throw new ApiException(400,"VALIDATION_ERROR","请填写有效换房说明（最多1000字）；关联异常仅限房间问题原因，不要填写住客敏感信息");
  Long previous=requests.reserveChange(actor,key,new Canonical(stayId,raw.segmentId(),raw.targetRoomId(),raw.reasonType(),reason,raw.issueId()));
  if(previous!=null) return required(previous);
  // Shared order: reservation -> actor(shared) -> stay -> rooms ascending -> current segment.
  if(employees.lockEmployee(actor)==null) throw new ApiException(403,"ACCOUNT_UNAVAILABLE","账号不可用");actors.require(actor,"stay:manage");
  var stay=stays.lockStay(stayId);
  if(stay==null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
  if(!"IN_HOUSE".equals(stay.status)) throw conflict("入住已经结束，不能换房");
  var before=stays.current(stayId);if(before==null) throw conflict("缺少有效使用段，请联系管理员核查");
  if(before.roomId.equals(raw.targetRoomId())) throw conflict("目标房间不能与原房间相同");
  var roomIds=new TreeSet<Long>(List.of(before.roomId,raw.targetRoomId()));
  for(long room:roomIds) if(rooms.lock(room)==null) throw new ApiException(404,"ROOM_NOT_FOUND","房间不存在");
  var segment=stays.lockCurrent(stayId);
  if(segment==null || !segment.id.equals(raw.segmentId()) || !segment.roomId.equals(before.roomId)) throw new ApiException(409,"STALE_ROOM_SEGMENT","当前房间已变化，请刷新入住记录后重新确认换房");
  var source=rooms.detail(segment.roomId);var target=rooms.detail(raw.targetRoomId());
  RoomEligibility.requireAvailable(target);
  // V3.0: a cross-type move hands the guest's remaining nights to another room type. Advise the conflict by naming
  // a date; read-only (no inventory locks) keeps the shared stay -> rooms -> segment order deadlock-free.
  if(!source.roomTypeId.equals(target.roomTypeId) && stay.expectedCheckOutAt!=null) {
   var from=com.stayos.billing.BillingDtos.businessDate(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
   var to=com.stayos.billing.BillingDtos.businessDate(stay.expectedCheckOutAt).minusDays(1);
   if(!to.isBefore(from)) {
    var full=futureInventory.nightRows(target.roomTypeId,from,to).stream()
     .filter(n->n.available<1).map(n->n.stayDate.toString()).toList();
    if(!full.isEmpty()) throw new ApiException(409,"FUTURE_NIGHT_CONFLICT","换房后 "+full.get(0)+" 起目标房型未来库存不足，请先与前台协调预订，系统不会自动赶客");
   }
  }
  if(source.hasOpenCleaningTask) throw conflict("原房存在冲突的未完成清洁任务，请先核查");
  if(raw.issueId()!=null && !changes.issueMatches(raw.issueId(),source.id)) throw conflict("关联异常不属于原房间或不存在");
  var now=OffsetDateTime.now(ZoneOffset.UTC);if(now.isBefore(segment.enteredAt)) throw conflict("服务器时间早于当前房间使用时间");
  if(changes.close(segment.id,now)!=1) throw new IllegalStateException("Source segment not closed");
  var row=new HashMap<String,Object>();row.put("stay",stayId);row.put("room",target.id);row.put("number",target.roomNumber);row.put("type",target.roomTypeName);row.put("time",now);
  if(changes.newSegment(row)!=1) throw new IllegalStateException("Target segment missing");
  // Original snapshots come from the ended segment, not mutable room catalogue labels.
  var old=changes.segments(stayId).stream().filter(s->s.id().equals(segment.id)).findFirst().orElseThrow();
  var r=new Result();r.stayRecordId=stayId;r.oldRoomId=source.id;r.newRoomId=target.id;r.oldSegmentId=segment.id;r.newSegmentId=((Number)row.get("id")).longValue();
  r.reasonType=raw.reasonType();r.reason=reason;r.issueId=raw.issueId();r.changedAt=now;r.operatedBy=actor;
  r.oldRoomNumber=old.roomNumber();r.oldRoomTypeName=old.roomTypeName();r.newRoomNumber=target.roomNumber;r.newRoomTypeName=target.roomTypeName;
  if(changes.insert(r)!=1 || stays.dirty(source.id,now)!=1 || stays.changed(stayId,now)!=1) throw new IllegalStateException("Room change fact missing");
  tasks.fromRoomChange(source.id,stayId,segment.id,r.id,actor,now);
  audit.success(actor,"ROOM_CHANGE","ROOM_CHANGE",r.id);requests.completeChange(actor,key,r.id);return required(r.id);
 }
 private Result required(long id) { var r=changes.detail(id);if(r==null || r.taskId==null) throw new IllegalStateException("Room change result incomplete");return r; }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Object available(long actor,long id,RoomFilter f) {
  access.require(actor,"stay:manage");var stay=requestsMapper.detail(id);
  if(stay==null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
  if(!"IN_HOUSE".equals(stay.status)) throw conflict("入住已经结束，不能换房");
  return catalog.list(new RoomFilter(f.page(),f.pageSize(),f.roomNumber(),f.floor(),f.roomTypeId(),f.availability(),f.cleaningStatus(),f.occupancyStatus(),f.maintenanceStatus(),f.enabled(),true));
 }
 @Transactional(readOnly=true) public List<Result> history(long actor,long id) {
  access.require(actor,"stay:manage");if(requestsMapper.detail(id)==null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");return changes.history(id);
 }
 @Transactional(readOnly=true) public Result result(long actor,String key) {
  access.require(actor,"stay:manage");if(key==null || !key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) throw new ApiException(400,"VALIDATION_ERROR","请求标识无效");
  var r=requestsMapper.request(actor,"ROOM_CHANGE",UUID.fromString(key).toString());
  if(r==null || r.resultRoomChangeId==null) throw new ApiException(404,"OPERATION_NOT_FOUND","尚未查到成功结果，请核对入住和换房历史，勿直接新建请求");return required(r.resultRoomChangeId);
 }
}
