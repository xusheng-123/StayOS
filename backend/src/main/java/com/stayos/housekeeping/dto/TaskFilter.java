package com.stayos.housekeeping.dto;
import jakarta.validation.constraints.*;
import com.stayos.common.ApiException;
import java.util.Locale;
public record TaskFilter(@Min(1) @Max(100000) Integer page,@Min(1) @Max(100) Integer pageSize,
        @Size(max=20) String roomNumber,@Min(-99) @Max(199) Integer floor,String status) {
    public TaskFilter normalized() {
        if((floor!=null && floor==0) || (status!=null && !status.equals("PENDING")))
            throw new ApiException(400,"VALIDATION_ERROR","楼层或清洁任务状态不正确");
        return new TaskFilter(page==null?1:page,pageSize==null?20:pageSize,roomNumber==null?"":roomNumber.trim().toUpperCase(Locale.ROOT),floor,status);
    }
    public long offset() { return (long)(page-1)*pageSize; }
}
