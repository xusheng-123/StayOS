package com.stayos.stay.service;
import com.stayos.audit.AuditService;
import com.stayos.common.ApiException;
import com.stayos.room.mapper.RoomMapper;
import com.stayos.stay.dto.*;
import com.stayos.stay.mapper.CheckoutMapper;
import com.stayos.housekeeping.service.TaskCreationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Service
public class CheckoutService {
    private final CheckoutMapper mapper;
    private final RoomMapper rooms;
    private final IdempotencyService requests;
    private final AuditService audit;
    private final TaskCreationService tasks;
    public CheckoutService(CheckoutMapper mapper,RoomMapper rooms,IdempotencyService requests,AuditService audit,TaskCreationService tasks) {
        this.mapper=mapper;this.rooms=rooms;this.requests=requests;this.audit=audit;this.tasks=tasks;
    }
    private ApiException conflict(String code,String message) { return new ApiException(409,code,message); }
    private record Canonical(long stayId,long segmentId) {}
    @Transactional
    public CheckoutResult checkout(long actor,String key,long id,CheckoutRequest input) {
        if(id<1 || input==null || input.segmentId()==null || input.segmentId()<1)
            throw new ApiException(400,"VALIDATION_ERROR","请提供有效的入住记录和当前房间使用段");
        Long previous=requests.reserve(actor,"CHECK_OUT",key,new Canonical(id,input.segmentId()));
        if(previous!=null) return requiredResult(previous);
        // All existing-stay operations: request reservation -> stay -> rooms (ascending) -> segments.
        var stay=mapper.lockStay(id);
        if(stay==null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
        if(!stay.status.equals("IN_HOUSE")) throw conflict("STAY_ALREADY_CHECKED_OUT","这笔入住已结束，请查看历史记录");
        var before=mapper.current(id);
        if(before==null) throw conflict("STAY_SEGMENT_INCONSISTENT","入住记录缺少有效房间使用段，请联系管理员核查");
        if(rooms.lock(before.roomId)==null) throw conflict("STAY_SEGMENT_INCONSISTENT","关联房间不存在，请联系管理员核查");
        var segment=mapper.lockCurrent(id);
        if(segment==null || !segment.id.equals(input.segmentId()) || !segment.roomId.equals(before.roomId))
            throw conflict("STALE_ROOM_SEGMENT","房间使用段已变化，请刷新入住记录后重新确认退房");
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        if(now.isBefore(segment.enteredAt)) throw conflict("SERVER_TIME_INVALID","服务器时间早于入住时间，请联系管理员核查");
        if(mapper.closeStay(id,actor,now)!=1 || mapper.closeSegment(segment.id,now)!=1 || mapper.dirty(segment.roomId,now)!=1)
            throw new IllegalStateException("Checkout update lost a locked record");
        tasks.fromCheckout(segment.roomId,id,segment.id,actor,now);
        audit.success(actor,"CHECK_OUT","STAY",id);
        requests.complete(actor,"CHECK_OUT",key,id);
        return requiredResult(id);
    }
    private CheckoutResult requiredResult(long id) {
        var result=mapper.result(id);
        if(result==null) throw new IllegalStateException("Checkout result is incomplete");
        return result;
    }
}
