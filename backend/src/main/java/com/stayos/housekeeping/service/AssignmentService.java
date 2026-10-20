package com.stayos.housekeeping.service;
import com.stayos.common.ApiException;
import com.stayos.housekeeping.dto.*;
import com.stayos.housekeeping.mapper.*;
import com.stayos.room.mapper.RoomMapper;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;
@Service
public class AssignmentService {
    private final AssignmentMapper mapper;
    private final TaskMapper tasks;
    private final RoomMapper rooms;
    private final TaskAccess access;
    private final TaskWorkflowService workflow;
    private final IdempotencyService requests;
    private final com.stayos.stay.mapper.StayMapper requestMapper;
    public AssignmentService(AssignmentMapper mapper,TaskMapper tasks,RoomMapper rooms,TaskAccess access,TaskWorkflowService workflow,IdempotencyService requests,com.stayos.stay.mapper.StayMapper requestMapper) {
        this.mapper=mapper;this.tasks=tasks;this.rooms=rooms;this.access=access;this.workflow=workflow;this.requests=requests;this.requestMapper=requestMapper;
    }
    private ApiException conflict(String message) { return new ApiException(409,"BATCH_ASSIGN_CONFLICT",message+"；本批未执行，请刷新后重新选择"); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<AssignmentDtos.Employee> employees(long actor) {
        access.require(actor,"housekeeping:manage");
        var day=LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        return mapper.employees(day,day.plusDays(1));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<TaskView> currentTasks(long actor,long employee) {
        access.require(actor,"housekeeping:manage");
        if(employee<1) throw new ApiException(400,"VALIDATION_ERROR","员工编号不正确");
        // Also allows inspecting a disabled former owner, so managers can find stuck work.
        return mapper.currentTasks(employee);
    }
    private static final Pattern PARTS=Pattern.compile("[0-9]+|[^0-9]+");
    static int natural(String left,String right) {
        var a=PARTS.matcher(left);var b=PARTS.matcher(right);
        while(true) {
            boolean x=a.find(),y=b.find();if(!x || !y) return x?1:y?-1:left.compareTo(right);
            String p=a.group(),q=b.group();int c;
            if(Character.isDigit(p.charAt(0)) && Character.isDigit(q.charAt(0))) c=new BigInteger(p).compareTo(new BigInteger(q));
            else c=p.compareTo(q);
            if(c!=0) return c;
        }
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<AssignmentDtos.Room> rooms(long actor,AssignmentDtos.Filter input) {
        access.require(actor,"housekeeping:manage");
        if(input.floor()!=null && input.floor()==0) throw new ApiException(400,"VALIDATION_ERROR","楼层不能为 0");
        int page=input.page()==null?1:input.page(),size=input.pageSize()==null?100:input.pageSize();
        var q=new AssignmentDtos.Filter(page,size,input.roomNumber()==null?null:input.roomNumber().trim().toUpperCase(Locale.ROOT),input.floor(),input.roomTypeId(),input.assigneeId());
        var list=mapper.rooms(q);
        for(var r:list) {
            if(r.occupied) r.assignmentState="OCCUPIED";
            else if("IN_PROGRESS".equals(r.taskStatus)) r.assignmentState="IN_PROGRESS";
            else if(r.assignedTo!=null) r.assignmentState=r.assignedTo.equals(q.assigneeId())?"ASSIGNED_SELF":"ASSIGNED_OTHER";
            else if("CLEAN".equals(r.cleaningStatus) && r.taskId==null) r.assignmentState="CLEAN";
            else if("DIRTY".equals(r.cleaningStatus) && (r.taskId==null || "PENDING".equals(r.taskStatus))) r.assignmentState=r.taskId==null?"AVAILABLE_MANUAL":"AVAILABLE_TASK";
            else r.assignmentState="BLOCKED";
            r.assignable=r.assignmentState.startsWith("AVAILABLE_");
        }
        list.sort(Comparator.comparing((AssignmentDtos.Room r)->r.floor).thenComparing(r->r.roomNumber,AssignmentService::natural).thenComparing(r->r.roomId));
        long offset=(long)(page-1)*size;
        return new Page<>(list.subList((int)Math.min(offset,list.size()),(int)Math.min(offset+size,list.size())),list.size(),page,size);
    }
    private record Canonical(long assigneeId,List<Long> roomIds,String reason) {}
    @Transactional
    public AssignmentDtos.Receipt assign(long actor,String key,AssignmentDtos.Request input) {
        access.require(actor,"housekeeping:manage");
        if(input==null || input.assigneeId()==null || input.assigneeId()<1 || input.roomIds()==null || input.roomIds().isEmpty() || input.roomIds().size()>100
          || input.roomIds().stream().anyMatch(id->id==null || id<1) || new HashSet<>(input.roomIds()).size()!=input.roomIds().size())
            throw new ApiException(400,"VALIDATION_ERROR","请选择合格员工和 1—100 间不重复的有效房间");
        String notes=workflow.text(input.reason(),false);
        var ids=input.roomIds().stream().sorted().toList();
        Long old=requests.reserveBatch(actor,key,new Canonical(input.assigneeId(),ids,notes));
        if(old!=null) return receipt(actor,old);
        workflow.lockEmployees(actor,input.assigneeId(),"housekeeping:manage");
        // Lock ALL rooms in ascending stable ID before locking or creating ANY task.
        for(long id:ids) if(rooms.lock(id)==null) throw conflict("所选房间不存在");
        var result=new AssignmentDtos.Receipt();result.operatorId=actor;result.assigneeId=input.assigneeId();result.createdAt=OffsetDateTime.now(ZoneOffset.UTC);result.notes=notes;
        if(mapper.create(result)!=1) throw new IllegalStateException("Batch not saved");
        for(long id:ids) {
            var room=rooms.detail(id);
            if(room.occupied || !"DIRTY".equals(room.cleaningStatus)) throw conflict("房间 "+room.roomNumber+" 已占用或不处于待打扫状态");
            Long taskId=mapper.openTask(id),created=null;
            TaskView t;
            if(taskId==null) {
                t=new TaskView();t.roomId=id;t.createdBy=actor;t.createdAt=result.createdAt;t.notes=notes.isBlank()?"人员分房：初始客房清洁":notes;t.status="PENDING";t.version=0L;t.completionNotes="";
                if(tasks.manual(t)!=1) throw new IllegalStateException("Manual task not saved");
                created=workflow.saveLog(t,actor,"MANUAL_CREATE",null,null,t.notes,result.createdAt).id;
            } else {
                t=tasks.lock(taskId);
                if(t==null || !Long.valueOf(id).equals(t.roomId) || !"PENDING".equals(t.status) || t.assignedTo!=null)
                    throw conflict("房间 "+room.roomNumber+" 的任务已分配或正在打扫");
                if(result.createdAt.isBefore(t.createdAt)) throw conflict("服务器时间早于任务创建时间");
            }
            workflow.assign(t,input.assigneeId(),actor,result.createdAt);
            if(tasks.update(t)!=1) throw conflict("房间 "+room.roomNumber+" 的任务已变化");
            t.version++;
            var log=workflow.saveLog(t,actor,"ASSIGN","PENDING",null,notes,result.createdAt);
            if(mapper.item(result.id,id,t.id,room.roomNumber,created,log.id)!=1) throw new IllegalStateException("Batch item not saved");
        }
        requests.completeBatch(actor,key,result.id);return receipt(actor,result.id);
    }
    @Transactional(readOnly=true)
    public AssignmentDtos.Receipt receipt(long actor,long id) {
        access.require(actor,"housekeeping:manage");
        var result=mapper.receipt(id);if(result==null) throw new ApiException(404,"BATCH_NOT_FOUND","分房记录不存在");
        result.items=mapper.items(id);return result;
    }
    @Transactional(readOnly=true)
    public AssignmentDtos.Receipt result(long actor,String key) {
        access.require(actor,"housekeeping:manage");
        if(key==null || !key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new ApiException(400,"VALIDATION_ERROR","请求标识不正确");
        var row=requestMapper.request(actor,"HK_BATCH_ASSIGN",UUID.fromString(key).toString());
        if(row==null || row.resultAssignmentBatchId==null) throw new ApiException(404,"BATCH_NOT_CONFIRMED","尚未查到本次请求的成功结果");
        return receipt(actor,row.resultAssignmentBatchId);
    }
}
