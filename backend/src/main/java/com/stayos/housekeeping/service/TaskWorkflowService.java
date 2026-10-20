package com.stayos.housekeeping.service;
import com.stayos.audit.AuditService;
import com.stayos.common.ApiException;
import com.stayos.housekeeping.dto.*;
import com.stayos.housekeeping.mapper.TaskMapper;
import com.stayos.room.mapper.RoomMapper;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
@Service
public class TaskWorkflowService {
    private final TaskMapper mapper;
    private final RoomMapper rooms;
    private final TaskAccess access;
    private final IdempotencyService requests;
    private final AuditService audit;
    public TaskWorkflowService(TaskMapper mapper,RoomMapper rooms,TaskAccess access,IdempotencyService requests,AuditService audit) {
        this.mapper=mapper;this.rooms=rooms;this.access=access;this.requests=requests;this.audit=audit;
    }
    private ApiException conflict(String message) { return new ApiException(409,"TASK_CONFLICT",message); }
    String text(String value,boolean required) {
        String s=value==null?"":value.trim();
        if(s.length()>1000 || (required && s.isBlank()) || s.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))
            throw new ApiException(400,"VALIDATION_ERROR","请填写有效原因或备注，最多 1000 字");
        return s;
    }
    // User locks precede rooms; account administration never locks rooms or tasks.
    // Existing-stay flows: reservation -> stay -> room -> segment. Cleaning has no stay lock:
    // reservation -> users (ascending shared locks) -> room -> task -> history/audit/result.
    void lockEmployees(long actor,Long target,String permission) {
        var ids=new TreeSet<Long>();ids.add(actor);if(target!=null) ids.add(target);
        for(long id:ids) if(mapper.lockEmployee(id)==null) throw conflict("员工账号不存在或已禁用，请刷新后重新选择");
        access.require(actor,permission);
        if(target!=null && !access.permissions(target).contains("housekeeping:execute")) throw conflict("所选员工没有清洁执行权限");
    }
    TaskLog saveLog(TaskView t,long actor,String action,String before,Long former,String reason,OffsetDateTime now) {
        var log=new TaskLog();log.taskId=t.id;log.action=action;log.fromStatus=before;log.toStatus=t.status;
        log.fromAssignee=former;log.toAssignee=t.assignedTo;log.operatorId=actor;log.operatedAt=now;log.reason=reason;log.taskVersion=t.version;
        if(mapper.log(log)!=1) throw new IllegalStateException("Task history was not saved");
        audit.success(actor,"HK_"+action,"HOUSEKEEPING_TASK",t.id);return mapper.receipt(log.id);
    }
    private record ManualInput(long roomId,String reason) {}
    @Transactional
    public TaskLog manual(long actor,String key,TaskRequests.Manual input) {
        if(input==null || input.roomId()==null || input.roomId()<1) throw new ApiException(400,"VALIDATION_ERROR","请选择有效房间");
        String reason=text(input.reason(),true);access.require(actor,"housekeeping:manage");
        Long old=requests.reserveTask(actor,"HK_MANUAL_CREATE",key,new ManualInput(input.roomId(),reason));
        if(old!=null) return receipt(old);
        lockEmployees(actor,null,"housekeeping:manage");
        if(rooms.lock(input.roomId())==null) throw new ApiException(404,"ROOM_NOT_FOUND","房间不存在");
        var room=rooms.detail(input.roomId());
        if(room.occupied || room.hasOpenCleaningTask || !"DIRTY".equals(room.cleaningStatus)) throw conflict("房间必须空闲、待打扫且没有未完成的清洁任务");
        var t=new TaskView();t.roomId=input.roomId();t.createdBy=actor;t.createdAt=OffsetDateTime.now(ZoneOffset.UTC);t.notes=reason;t.status="PENDING";t.version=0L;
        if(mapper.manual(t)!=1) throw new IllegalStateException("Manual task was not created");
        var log=saveLog(t,actor,"MANUAL_CREATE",null,null,reason,t.createdAt);
        requests.completeTask(actor,"HK_MANUAL_CREATE",key,log.id);return log;
    }
    private record ActionInput(long taskId,long version,Long assigneeId,String reason) {}
    @Transactional
    public TaskLog act(long actor,String key,long id,String action,TaskRequests.Action input) {
        if("CLAIM".equals(action)) throw new ApiException(403,"SELF_CLAIM_DISABLED","自主领取已关闭，请由主管进行分房");
        if(id<1 || input==null || input.version()==null || input.version()<0 || !Set.of("CLAIM","ASSIGN","REASSIGN","START","COMPLETE").contains(action))
            throw new ApiException(400,"VALIDATION_ERROR","请提供有效任务和当前版本");
        boolean manage=Set.of("ASSIGN","REASSIGN").contains(action);
        if(manage && (input.assigneeId()==null || input.assigneeId()<1) || (!manage && input.assigneeId()!=null))
            throw new ApiException(400,"VALIDATION_ERROR","负责人参数不正确");
        String reason=text(input.reason(),action.equals("REASSIGN"));String permission=manage?"housekeeping:manage":"housekeeping:execute";
        access.require(actor,permission);
        Long previous=requests.reserveTask(actor,"HK_"+action,key,new ActionInput(id,input.version(),input.assigneeId(),reason));
        if(previous!=null) return receipt(previous);
        lockEmployees(actor,manage?input.assigneeId():null,permission);
        var before=mapper.detail(id);access.visible(actor,before);
        if(rooms.lock(before.roomId)==null) throw conflict("关联房间不存在");
        var t=mapper.lock(id);access.visible(actor,t);
        if(!t.roomId.equals(before.roomId) || !t.version.equals(input.version())) throw conflict("任务已被其他操作更新，请刷新后重新确认");
        if(t.status.equals("COMPLETED")) throw conflict("任务已经完成，不能再次操作");
        var room=rooms.detail(t.roomId);
        if(room.occupied || !(t.status.equals("PENDING")?"DIRTY":"CLEANING").equals(room.cleaningStatus))
            throw conflict("房间占用或清洁状态与任务不一致，请先核查");
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        if(now.isBefore(t.createdAt) || t.assignedAt!=null && now.isBefore(t.assignedAt) || t.startedAt!=null && now.isBefore(t.startedAt))
            throw conflict("服务器时间早于已有操作时间，请联系管理员核查");
        String oldStatus=t.status;Long former=t.assignedTo;
        switch(action) {
            case "ASSIGN" -> {
                if(!t.status.equals("PENDING") || t.assignedTo!=null) throw conflict("已有负责人，请使用重新分配并填写原因");
                assign(t,input.assigneeId(),actor,now);
            }
            case "REASSIGN" -> {
                if(t.assignedTo==null || t.assignedTo.equals(input.assigneeId())) throw conflict("请为已分配任务选择另一位合格员工");
                assign(t,input.assigneeId(),actor,now);
            }
            case "START" -> {
                owner(t,actor);if(!t.status.equals("PENDING")) throw conflict("任务已经开始，不能重复开始");
                t.status="IN_PROGRESS";t.startedBy=actor;t.startedAt=now;
                if(mapper.cleanState(t.roomId,"CLEANING",now)!=1) throw new IllegalStateException("Room not updated");
            }
            case "COMPLETE" -> {
                owner(t,actor);if(!t.status.equals("IN_PROGRESS")) throw conflict("请先开始打扫，再提交清洁完成");
                t.status="COMPLETED";t.completedBy=actor;t.completedAt=now;t.completionNotes=reason;
                if(mapper.cleanState(t.roomId,"CLEAN",now)!=1) throw new IllegalStateException("Room not updated");
            }
            default -> throw new IllegalStateException("Unsupported task action");
        }
        if(mapper.update(t)!=1) throw conflict("任务已经变化，请刷新重试");t.version++;
        var log=saveLog(t,actor,action,oldStatus,former,reason,now);
        requests.completeTask(actor,"HK_"+action,key,log.id);return log;
    }
    void assign(TaskView t,long employee,long actor,OffsetDateTime now) {
        t.assignedTo=employee;t.assignedBy=actor;t.assignedAt=now;t.claimedAt=null;
    }
    private void owner(TaskView t,long actor) {
        if(!Long.valueOf(actor).equals(t.assignedTo)) throw new ApiException(403,"TASK_NOT_OWNER","只有当前任务负责人可以执行此操作");
    }
    private TaskLog receipt(long id) {
        var log=mapper.receipt(id);if(log==null) throw new IllegalStateException("Task operation result missing");return log;
    }
}
