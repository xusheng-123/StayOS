package com.stayos.operations;

import com.stayos.auth.*;
import com.stayos.common.*;
import com.stayos.housekeeping.service.TaskAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController @RequestMapping("/api/operations")
@PreAuthorize("hasAnyAuthority('stay:manage','housekeeping:read','issue:report')")
public class OperationsController {
    private final OperationsService service;
    public OperationsController(OperationsService service){this.service=service;}
    @GetMapping("/summary") public ApiResponse<?> summary(Authentication auth){return ApiResponse.ok(service.summary(((SessionIdentity)auth.getPrincipal()).userId()));}
}

@Service
class OperationsService {
    private final OperationsMapper mapper;private final TaskAccess access;private final Duration pendingAge,startedAge;
    OperationsService(OperationsMapper mapper,TaskAccess access,@Value("${stayos.operations.pending-warning:4h}") Duration pendingAge,@Value("${stayos.operations.cleaning-warning:2h}") Duration startedAge){
        this.mapper=mapper;this.access=access;this.pendingAge=pendingAge;this.startedAge=startedAge;
        if(pendingAge.isNegative()||pendingAge.isZero()||startedAge.isNegative()||startedAge.isZero())throw new IllegalArgumentException("Operation warning intervals must be positive");
    }
    public record Summary(OffsetDateTime generatedAt,Long overdueStays,Long unassignedTasks,Long delayedTasks,Long strandedTasks,long criticalIssues,long maintenanceStops,long pendingWarningMinutes,long cleaningWarningMinutes,List<OperationsMapper.Notice> items){}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Summary summary(long actor){
        var p=access.permissions(actor);boolean stays=p.contains("stay:manage"),tasks=p.contains("housekeeping:read"),manager=p.contains("housekeeping:manage");
        if(!stays&&!tasks&&!p.contains("issue:report"))throw new ApiException(403,"ACCESS_DENIED","没有查看运营待办的权限");
        boolean allIssues=stays||p.contains("issue:resolve");var now=OffsetDateTime.now(ZoneOffset.UTC);var items=new ArrayList<OperationsMapper.Notice>();
        if(stays)items.addAll(mapper.overdueItems(now));
        if(tasks){items.addAll(mapper.strandedItems(actor,manager));items.addAll(mapper.delayedItems(actor,manager,now.minus(pendingAge),now.minus(startedAge)));}
        items.addAll(mapper.criticalItems(actor,allIssues));
        return new Summary(now,stays?mapper.overdue(now):null,manager?mapper.unassigned(actor,true):null,tasks?mapper.delayed(actor,manager,now.minus(pendingAge),now.minus(startedAge)):null,
            tasks?mapper.stranded(actor,manager):null,mapper.critical(actor,allIssues),mapper.stops(),pendingAge.toMinutes(),startedAge.toMinutes(),List.copyOf(items));
    }
}
