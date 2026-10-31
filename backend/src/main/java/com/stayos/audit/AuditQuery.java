package com.stayos.audit;

import com.stayos.auth.RolePermissions;
import com.stayos.common.ApiException;
import com.stayos.staff.mapper.UserMapper;
import com.stayos.room.service.RoomCatalogService.Page;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** Read-only projection of existing facts. Scope is derived ONLY from current server roles. */
@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class AuditQuery {
    public static final Map<String,List<String>> MODULES = Map.of(
        "SECURITY", List.of("LOGIN","LOGOUT","ACCESS_DENIED","REQUEST_FAILED"),
        "ACCOUNT", List.of("ADMIN_BOOTSTRAP","USER_CREATE","USER_SET_ENABLED","USER_SET_ROLES","USER_RESET_PASSWORD"),
        "CONFIGURATION", List.of("ROOM_CREATE","ROOM_UPDATE","ROOM_SET_ENABLED","ROOM_TYPE_CREATE","ROOM_TYPE_UPDATE","ROOM_TYPE_SET_ENABLED"),
        "STAY", List.of("CHECK_IN","CHECK_OUT","ROOM_CHANGE","STAY_EXTEND","GUEST_CORRECT"),
        "HANDOVER", List.of("HANDOVER_CREATE","HANDOVER_UPDATE","HANDOVER_SUBMIT","HANDOVER_REASSIGN","HANDOVER_ACKNOWLEDGE"),
        "BILLING", List.of("FOLIO_OPEN","FOLIO_CLOSE","FOLIO_REOPEN","CHARGE_POST","CHARGE_ADJUST","PAYMENT_RECEIVE","PAYMENT_REFUND","SERVICE_ORDER_CREATE","SERVICE_ORDER_UPDATE","SERVICE_ORDER_FULFILL","SERVICE_ORDER_CANCEL","BILLING_POLICY_UPDATE","BILLING_RATE_PLAN_UPDATE","BILLING_RATE_DAY_UPDATE","BILLING_CATALOG_UPDATE"),
        "HOUSEKEEPING", List.of("HK_MANUAL_CREATE","HK_CLAIM","HK_ASSIGN","HK_REASSIGN","HK_START","HK_COMPLETE"),
        "ISSUE", List.of("ISSUE_REPORT","ISSUE_START","ISSUE_RESOLVE","ISSUE_SEVERITY"),
        "MAINTENANCE", List.of("MAINTENANCE_STOP","MAINTENANCE_RESUME")
    );
    public record Filter(@Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer pageSize,
        OffsetDateTime from, OffsetDateTime to, @Positive Long operatorId, @Size(max=60) String action,
        @Size(max=30) String module, @Size(max=20) String result) {}
    public record Criteria(int page,int pageSize,OffsetDateTime from,OffsetDateTime to,Long operatorId,String action,String result,List<String> actions) {
        public long getOffset() { return (long)(page-1)*pageSize; }
    }
    public record Entry(long id,Long operatorId,String operatorName,String operatorUsername,String action,
        String targetType,Long targetId,String result,OffsetDateTime createdAt) {}
    public record Operator(Long id,String displayName,String username) {}
    public record Options(Map<String,List<String>> modules,List<Operator> operators,String coverage) {}
    private final AuditReadMapper mapper;
    private final UserMapper users;
    public AuditQuery(AuditReadMapper mapper,UserMapper users) { this.mapper=mapper;this.users=users; }
    private Map<String,List<String>> scope(long actor) {
        var u=users.selectById(actor);
        if(u==null || !u.enabled) throw new ApiException(403,"FORBIDDEN","账号不可用");
        var permissions=RolePermissions.permissions(users.roles(actor));
        var result=new TreeMap<String,List<String>>();
        if(permissions.contains("audit:management")) for(String m:List.of("SECURITY","ACCOUNT","CONFIGURATION")) result.put(m,MODULES.get(m));
        if(permissions.contains("audit:business")) for(String m:List.of("STAY","HANDOVER","BILLING","HOUSEKEEPING","ISSUE","MAINTENANCE","CONFIGURATION")) result.put(m,MODULES.get(m));
        if(permissions.contains("audit:housekeeping")) for(String m:List.of("HOUSEKEEPING","ISSUE","MAINTENANCE")) result.put(m,MODULES.get(m));
        if(result.isEmpty()) throw new ApiException(403,"FORBIDDEN","没有集中操作记录查看权限");
        return result;
    }
    private List<String> actions(Map<String,List<String>> scope) { return scope.values().stream().flatMap(Collection::stream).distinct().sorted().toList(); }
    public Options options(long actor) {
        var modules=scope(actor);
        return new Options(modules,mapper.operators(actions(modules)),"仅展示已有集中审计事实，不含请求正文、凭据或住客资料。自动建单见任务历史；历史批量分房按每条 HK_ASSIGN 展示。通用失败/拒绝记录仅供系统安全审计，不能据此推断完整业务失败原因。");
    }
    private Criteria criteria(long actor,Filter f) {
        var scope=scope(actor);
        if(f.module()!=null && !scope.containsKey(f.module())) throw new ApiException(403,"FORBIDDEN","所选审计模块不在授权范围内");
        var actions=f.module()==null?actions(scope):scope.get(f.module());
        if(f.action()!=null && !actions.contains(f.action())) throw new ApiException(403,"FORBIDDEN","所选操作类型不在授权范围内");
        if(f.result()!=null && !Set.of("SUCCESS","FAILURE","DENIED").contains(f.result())) throw new ApiException(400,"VALIDATION_ERROR","操作结果筛选不正确");
        var to=f.to()==null?OffsetDateTime.now(ZoneOffset.UTC):f.to();
        var from=f.from()==null?to.minusDays(30):f.from();
        if(!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(366))>0) throw new ApiException(400,"VALIDATION_ERROR","时间范围须为递增且不超过366天，结束时间不包含在查询内");
        return new Criteria(f.page()==null?1:f.page(),f.pageSize()==null?20:f.pageSize(),from,to,f.operatorId(),f.action(),f.result(),actions);
    }
    public Page<Entry> list(long actor,Filter filter) {
        var q=criteria(actor,filter);
        return new Page<>(mapper.list(q,q.getOffset()),mapper.count(q),q.page(),q.pageSize());
    }
    public Entry detail(long actor,long id) {
        var entry=mapper.detail(id,actions(scope(actor)));
        if(entry==null) throw new ApiException(404,"AUDIT_NOT_FOUND","操作记录不存在或不在授权范围内");
        return entry;
    }
}
