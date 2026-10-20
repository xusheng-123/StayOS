package com.stayos.stay.dto;

import com.stayos.common.ApiException;
import java.time.OffsetDateTime;
import java.util.*;

public record StayFilter(Integer page,Integer pageSize,String status,String roomNumber,String guestName,
                         OffsetDateTime checkInFrom,OffsetDateTime checkInTo) {
    public StayFilter normalized() {
        int p=page==null?1:page,size=pageSize==null?20:pageSize;
        String state=status==null || status.isBlank()?"IN_HOUSE":status;
        String room=roomNumber==null?"":roomNumber.trim().toUpperCase(Locale.ROOT),guest=guestName==null?"":guestName.trim();
        if(p<1 || p>100000 || size<1 || size>100 || !Set.of("IN_HOUSE","CHECKED_OUT","ALL").contains(state)
            || room.length()>20 || guest.length()>100 || (checkInFrom!=null && checkInTo!=null && !checkInTo.isAfter(checkInFrom)))
            throw new ApiException(400,"VALIDATION_ERROR","入住查询条件不正确，请检查分页、状态和时间范围");
        return new StayFilter(p,size,state,room,guest,checkInFrom,checkInTo);
    }
    public long offset() { return (long)(page-1)*pageSize; }
}
