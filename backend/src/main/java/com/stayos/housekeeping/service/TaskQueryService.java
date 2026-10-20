package com.stayos.housekeeping.service;
import com.stayos.housekeeping.dto.*;
import com.stayos.housekeeping.mapper.TaskMapper;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class TaskQueryService {
    private final TaskMapper mapper;
    public TaskQueryService(TaskMapper mapper) { this.mapper=mapper; }
    public Page<TaskView> list(TaskFilter filter) {
        var q=filter.normalized();return new Page<>(mapper.list(q,q.offset()),mapper.count(q),q.page(),q.pageSize());
    }
    public TaskView detail(long id) {
        var task=mapper.detail(id);
        if(task==null) throw new ApiException(404,"TASK_NOT_FOUND","清洁任务不存在");
        return task;
    }
}
