package com.stayos.housekeeping.service;
import com.stayos.housekeeping.dto.*;
import com.stayos.housekeeping.mapper.TaskMapper;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class TaskQueryService {
    private final TaskMapper mapper;
    private final TaskAccess access;
    public TaskQueryService(TaskMapper mapper,TaskAccess access) { this.mapper=mapper;this.access=access; }
    public Page<TaskView> list(long actor,TaskFilter filter) {
        var q=filter.normalized();boolean manager=access.manager(actor);
        if(!manager && q.assigneeId()!=null && q.assigneeId()!=actor) throw new ApiException(403,"ACCESS_DENIED","只能按自己的负责人编号筛选");
        return new Page<>(mapper.list(q,q.offset(),actor,manager),mapper.count(q,actor,manager),q.page(),q.pageSize());
    }
    public TaskView detail(long actor,long id) {
        var task=mapper.detail(id);access.visible(actor,task);return task;
    }
    public java.util.List<TaskLog> history(long actor,long id) { detail(actor,id);return mapper.history(id); }
    public java.util.List<TaskMapper.Employee> employees(long actor) { access.require(actor,"housekeeping:manage");return mapper.employees(); }
    public java.util.List<TaskMapper.Employee> assignees(long actor) { access.require(actor,"housekeeping:manage");return mapper.assignees(); }
    public TaskStatistics statistics(long actor) {
        var today=LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        return mapper.statistics(actor,access.manager(actor),today,today.plusDays(1));
    }
}
