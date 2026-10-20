package com.stayos.housekeeping.dto;
import jakarta.validation.constraints.*;
import com.stayos.common.ApiException;
import java.util.*;
public record TaskFilter(@Min(1) @Max(100000) Integer page,@Min(1) @Max(100) Integer pageSize,
        @Size(max=20) String roomNumber,@Min(-99) @Max(199) Integer floor,String status,
        @Positive Long assigneeId,String scope,String sort) {
    public TaskFilter normalized() {
        if((floor!=null && floor==0) || (status!=null && !Set.of("PENDING","IN_PROGRESS","COMPLETED").contains(status))
            || (scope!=null && !Set.of("ALL","MINE","UNASSIGNED").contains(scope))
            || (sort!=null && !Set.of("NEWEST","OLDEST").contains(sort)))
            throw new ApiException(400,"VALIDATION_ERROR","清洁任务查询条件不正确");
        return new TaskFilter(page==null?1:page,pageSize==null?20:pageSize,roomNumber==null?"":roomNumber.trim().toUpperCase(Locale.ROOT),floor,status,assigneeId,scope==null?"ALL":scope,sort==null?"NEWEST":sort);
    }
    public long offset() { return (long)(page-1)*pageSize; }
}
