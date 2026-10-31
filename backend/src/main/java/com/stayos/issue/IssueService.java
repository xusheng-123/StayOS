package com.stayos.issue;
import com.stayos.issue.IssueDtos.*;
import com.stayos.audit.AuditService;
import com.stayos.common.ApiException;
import com.stayos.housekeeping.service.TaskAccess;
import com.stayos.housekeeping.mapper.TaskMapper;
import com.stayos.room.mapper.RoomMapper;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
@Service
public class IssueService {
 private final IssueMapper mapper; private final RoomMapper rooms; private final TaskAccess access;
 private final TaskMapper employees; private final IdempotencyService requests; private final AuditService audit; private final com.stayos.stay.mapper.StayMapper stayRequests;
 private final com.stayos.auth.BusinessActorGuard actors;
 public IssueService(IssueMapper mapper,RoomMapper rooms,TaskAccess access,TaskMapper employees,IdempotencyService requests,AuditService audit,com.stayos.stay.mapper.StayMapper stayRequests,com.stayos.auth.BusinessActorGuard actors) {
  this.mapper=mapper;this.rooms=rooms;this.access=access;this.employees=employees;this.requests=requests;this.audit=audit;this.stayRequests=stayRequests;this.actors=actors;
 }
 private static final Set<String> TYPES=Set.of("FACILITY","ELECTRICAL","PLUMBING","SANITATION","SAFETY","OTHER"),SEVERITIES=Set.of("LOW","MEDIUM","CRITICAL"),STATES=Set.of("OPEN","IN_PROGRESS","RESOLVED");
 private ApiException conflict(String s) { return new ApiException(409,"ISSUE_CONFLICT",s); }
 private void valid(boolean b,String message) { if(!b) throw new ApiException(400,"VALIDATION_ERROR",message); }
 private String text(String raw,int max,boolean required) {
  String s=raw==null?"":raw.trim();valid(s.length()<=max && (!required || !s.isBlank()) && (raw==null?"":raw).codePoints().noneMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'),"请填写有效说明，且不要填写住客证件号码或电话");return s;
 }
 private void enumValue(String s,Set<String> set) { valid(s!=null && set.contains(s),"异常分类、严重程度或状态不正确"); }
 private boolean all(long actor) { var p=access.permissions(actor);return p.contains("issue:resolve") || p.contains("stay:manage"); }
 private void lockActor(long actor,String permission) { if(employees.lockEmployee(actor)==null) throw new ApiException(403,"ACCOUNT_UNAVAILABLE","账号不可用");actors.require(actor,permission); }
 private void lockRoom(long id) { if(rooms.lock(id)==null) throw new ApiException(404,"ROOM_NOT_FOUND","房间不存在"); }
 // Shared order: idempotency -> actor(shared) -> room -> issue/stop -> log/audit/result.
 // No stay locks here, so no cycle with checkout's stay -> room order.
 @Transactional(readOnly=true) public Issue detail(long actor,long id) {
  access.require(actor,"issue:report");var i=mapper.detail(id);
  if(i==null || (!all(actor) && !Long.valueOf(actor).equals(i.reportedBy))) throw new ApiException(404,"ISSUE_NOT_FOUND","异常记录不存在或不在可查看范围内");return i;
 }
 public record Page(List<Issue> items,long total,int page,int pageSize) {}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Page list(long actor,Filter raw) {
  access.require(actor,"issue:report");
  if(raw.issueType()!=null) enumValue(raw.issueType(),TYPES);if(raw.severity()!=null) enumValue(raw.severity(),SEVERITIES);if(raw.status()!=null) enumValue(raw.status(),STATES);
  valid(raw.floor()==null || raw.floor()!=0,"楼层不能为 0");
  int page=raw.page()==null?1:raw.page(),size=raw.pageSize()==null?20:raw.pageSize();
  valid(page>=1 && page<=100000 && size>=1 && size<=100,"分页参数不正确");
  var q=new Filter(page,size,raw.roomNumber()==null?null:raw.roomNumber().trim().toUpperCase(Locale.ROOT),raw.roomId(),raw.floor(),raw.issueType(),raw.severity(),raw.status());
  return new Page(mapper.list(actor,all(actor),q,(long)(page-1)*size),mapper.count(actor,all(actor),q),page,size);
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Statistics statistics(long actor) {
  access.require(actor,"issue:report");boolean all=all(actor);return new Statistics(mapper.stateCount(actor,all,"OPEN"),mapper.stateCount(actor,all,"IN_PROGRESS"),mapper.criticalCount(actor,all),mapper.maintenanceCount());
 }
 @Transactional(readOnly=true) public List<Log> history(long actor,long id) { detail(actor,id);return mapper.history(id); }
 private Log receipt(long actor,long id) { var l=mapper.receipt(id);if(l==null) throw new IllegalStateException("Issue receipt missing");detail(actor,l.issueId);return l; }
 private Log save(Issue i,long actor,String action,String before,String severity,String reason,OffsetDateTime now) {
  var l=new Log();l.issueId=i.id;l.action=action;l.fromStatus=before;l.toStatus=i.status;l.fromSeverity=severity;l.toSeverity=i.severity;l.operatorId=actor;l.operatedAt=now;l.reason=reason;l.issueVersion=i.version;
  if(mapper.log(l)!=1) throw new IllegalStateException("Issue log missing");audit.success(actor,"ISSUE_"+action,"ROOM_ISSUE",i.id);return mapper.receipt(l.id);
 }
 @Transactional public Log report(long actor,String key,Report raw) {
  access.require(actor,"issue:report");valid(raw!=null && raw.roomId()!=null && raw.roomId()>0,"请选择房间");enumValue(raw.issueType(),TYPES);enumValue(raw.severity(),SEVERITIES);
  var input=new Report(raw.roomId(),raw.issueType(),raw.severity(),text(raw.description(),2000,true),text(raw.notes(),1000,false));
  Long old=requests.reserveRoomOperation(actor,"ISSUE_REPORT",key,input,false);if(old!=null) return receipt(actor,old);
  lockActor(actor,"issue:report");lockRoom(input.roomId());
  var i=new Issue();i.roomId=input.roomId();i.issueType=input.issueType();i.severity=input.severity();i.description=input.description();i.notes=input.notes();i.reportedBy=actor;i.reportedAt=OffsetDateTime.now(ZoneOffset.UTC);i.status="OPEN";i.version=0L;
  if(mapper.insert(i)!=1) throw new IllegalStateException("Issue not created");
  var l=save(i,actor,"REPORT",null,null,input.description(),i.reportedAt);requests.completeRoomOperation(actor,"ISSUE_REPORT",key,l.id,false);return l;
 }
 private record CanonicalAction(long id,long version,String action,String reason,String severity) {}
 @Transactional public Log act(long actor,String key,long id,String action,Action raw) {
  access.require(actor,"issue:resolve");valid(raw!=null && raw.version()!=null && raw.version()>=0 && id>0 && Set.of("START","RESOLVE","SEVERITY").contains(action),"请提供当前异常版本");
  String reason=text(raw.reason(),1000,!action.equals("START"));if(action.equals("SEVERITY")) enumValue(raw.severity(),SEVERITIES);else valid(raw.severity()==null,"此操作不能同时修改严重程度");
  String op="ISSUE_"+action;Long old=requests.reserveRoomOperation(actor,op,key,new CanonicalAction(id,raw.version(),action,reason,raw.severity()),false);if(old!=null) return receipt(actor,old);
  lockActor(actor,"issue:resolve");var before=detail(actor,id);lockRoom(before.roomId);var i=mapper.lock(id);
  if(i==null || !i.version.equals(raw.version()) || i.status.equals("RESOLVED")) throw conflict("异常已经更新或解决，请刷新后重试");
  String from=i.status,severity=i.severity;var now=OffsetDateTime.now(ZoneOffset.UTC);if(now.isBefore(i.startedAt==null?i.reportedAt:i.startedAt)) throw conflict("服务器时间早于已有业务时间");
  switch(action) {
   case "START" -> { if(!i.status.equals("OPEN")) throw conflict("异常已经开始处理");i.status="IN_PROGRESS";i.startedBy=actor;i.startedAt=now; }
   case "RESOLVE" -> { i.status="RESOLVED";i.resolvedBy=actor;i.resolvedAt=now;i.resolution=reason; }
   case "SEVERITY" -> { if(i.severity.equals(raw.severity())) throw conflict("严重程度没有变化");i.severity=raw.severity(); }
   default -> throw new IllegalStateException();
  }
  if(mapper.update(i)!=1) throw conflict("异常已变化，请刷新");i.version++;
  var l=save(i,actor,action,from,severity,reason,now);requests.completeRoomOperation(actor,op,key,l.id,false);return l;
 }
 @Transactional(readOnly=true) public List<Maintenance> maintenanceHistory(long actor,long room) {
  access.require(actor,"issue:report");if(rooms.detail(room)==null) throw new ApiException(404,"ROOM_NOT_FOUND","房间不存在");return mapper.maintenanceHistory(room);
 }
 private record StopInput(long room,Long issueId,String reason) {}
 private record ResumeInput(long room,long stopId,String reason) {}
 private MaintenanceLog maintenanceReceipt(long actor,long id) { access.require(actor,"issue:resolve");var l=mapper.maintenanceReceipt(id);if(l==null) throw new IllegalStateException("Maintenance receipt missing");return l; }
 @Transactional public MaintenanceLog stop(long actor,String key,long room,Stop raw) {
  access.require(actor,"issue:resolve");valid(room>0 && raw!=null && (raw.issueId()==null || raw.issueId()>0),"请选择有效房间和异常");String reason=text(raw.reason(),1000,true);
  Long old=requests.reserveRoomOperation(actor,"MAINTENANCE_STOP",key,new StopInput(room,raw.issueId(),reason),true);if(old!=null) return maintenanceReceipt(actor,old);
  lockActor(actor,"issue:resolve");lockRoom(room);if(mapper.active(room)!=null) throw conflict("房间已有有效人工维修停用，请查看原记录");
  if(raw.issueId()!=null) { var i=mapper.detail(raw.issueId());if(i==null || !i.roomId.equals(room)) throw conflict("关联异常与房间不匹配"); }
  var m=new Maintenance();m.roomId=room;m.issueId=raw.issueId();m.reason=reason;m.stoppedBy=actor;m.stoppedAt=OffsetDateTime.now(ZoneOffset.UTC);if(mapper.stop(m)!=1) throw new IllegalStateException("Stop not created");
  return saveMaintenance(actor,key,m,"STOP",reason,m.stoppedAt);
 }
 @Transactional public MaintenanceLog resume(long actor,String key,long room,Resume raw) {
  access.require(actor,"issue:resolve");valid(room>0 && raw!=null && raw.stopId()!=null && raw.stopId()>0,"请选择当前停用记录");String reason=text(raw.reason(),1000,true);
  Long old=requests.reserveRoomOperation(actor,"MAINTENANCE_RESUME",key,new ResumeInput(room,raw.stopId(),reason),true);if(old!=null) return maintenanceReceipt(actor,old);
  lockActor(actor,"issue:resolve");lockRoom(room);var m=mapper.active(room);
  if(m==null || !m.id.equals(raw.stopId())) throw conflict("有效停用记录已变化，请刷新后确认");
  if(mapper.critical(room)>0) throw conflict("仍有未解决的严重异常，请先处理后再解除人工停用");
  var now=OffsetDateTime.now(ZoneOffset.UTC);if(now.isBefore(m.stoppedAt)) throw conflict("服务器时间早于停用时间");
  m.resumedBy=actor;m.resumedAt=now;m.resumeReason=reason;if(mapper.resume(m)!=1) throw conflict("停用已经解除");
  return saveMaintenance(actor,key,m,"RESUME",reason,now);
 }
 @Transactional(readOnly=true) public Object result(long actor,String operation,String key) {
  access.require(actor,operation.equals("ISSUE_REPORT")?"issue:report":"issue:resolve");
  valid(Set.of("ISSUE_REPORT","ISSUE_START","ISSUE_RESOLVE","ISSUE_SEVERITY","MAINTENANCE_STOP","MAINTENANCE_RESUME").contains(operation) && key!=null && key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"),"操作类型或请求标识无效");
  var r=stayRequests.request(actor,operation,UUID.fromString(key).toString());
  Long log=r==null?null:operation.startsWith("ISSUE_")?r.resultIssueLogId:r.resultMaintenanceLogId;
  if(log==null) throw new ApiException(404,"OPERATION_NOT_FOUND","尚未查到成功结果，请刷新业务记录后再确认，勿直接重复新建");
  return operation.startsWith("ISSUE_")?receipt(actor,log):maintenanceReceipt(actor,log);
 }
 private MaintenanceLog saveMaintenance(long actor,String key,Maintenance m,String action,String reason,OffsetDateTime now) {
  var l=new MaintenanceLog();l.stopId=m.id;l.action=action;l.operatorId=actor;l.operatedAt=now;l.reason=reason;
  if(mapper.maintenanceLog(l)!=1) throw new IllegalStateException("Maintenance history missing");String op="MAINTENANCE_"+action;
  audit.success(actor,op,"ROOM_MAINTENANCE_STOP",m.id);requests.completeRoomOperation(actor,op,key,l.id,true);return mapper.maintenanceReceipt(l.id);
 }
}
