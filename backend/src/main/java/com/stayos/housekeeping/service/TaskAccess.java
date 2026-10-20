package com.stayos.housekeeping.service;
import com.stayos.auth.RolePermissions;
import com.stayos.common.ApiException;
import com.stayos.staff.mapper.UserMapper;
import com.stayos.housekeeping.dto.TaskView;
import org.springframework.stereotype.Service;
import java.util.Set;
@Service
public class TaskAccess {
    private final UserMapper users;
    public TaskAccess(UserMapper users) { this.users=users; }
    public Set<String> permissions(long actor) {
        var account=users.selectById(actor);
        if(account==null || !account.enabled) throw new ApiException(403,"ACCOUNT_UNAVAILABLE","账号不可用，请重新登录");
        return RolePermissions.permissions(users.roles(actor));
    }
    public void require(long actor,String permission) {
        if(!permissions(actor).contains(permission)) throw new ApiException(403,"ACCESS_DENIED","没有执行此操作的权限");
    }
    public boolean manager(long actor) { require(actor,"housekeeping:read");return permissions(actor).contains("housekeeping:manage"); }
    public void visible(long actor,TaskView task) {
        if(task==null || (!manager(actor) && !(Long.valueOf(actor).equals(task.assignedTo))))
            throw new ApiException(404,"TASK_NOT_FOUND","清洁任务不存在或不在可查看范围内");
    }
}
