package com.stayos.stay.service;

import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.common.ApiException;
import com.stayos.room.mapper.RoomMapper;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.stay.dto.StayExtensionDtos.*;
import com.stayos.stay.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class StayExtensionService {
    private final StayExtensionMapper extensions;
    private final StayMapper stays;
    private final CheckoutMapper segments;
    private final RoomMapper rooms;
    private final IdempotencyService requests;
    private final BusinessActorGuard actors;
    private final AuditService audit;

    public StayExtensionService(StayExtensionMapper extensions, StayMapper stays, CheckoutMapper segments,
                                RoomMapper rooms, IdempotencyService requests, BusinessActorGuard actors, AuditService audit) {
        this.extensions=extensions; this.stays=stays; this.segments=segments; this.rooms=rooms;
        this.requests=requests; this.actors=actors; this.audit=audit;
    }
    private record Canonical(long stayId, long segmentId, long version, OffsetDateTime departure, String reason) {}

    @Transactional
    public Result extend(long actor, String key, long id, Request input) {
        if (id < 1 || input == null || input.segmentId() == null || input.segmentId() < 1
                || input.version() == null || input.version() < 0 || input.newExpectedCheckOutAt() == null)
            throw invalid("请提供当前入住、房间使用段、版本及新的预计退房时间");
        String reason=input.reason() == null ? "" : input.reason().trim();
        if (reason.isBlank() || reason.length() > 1000
                || input.reason().codePoints().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))
            throw invalid("请填写调整原因（1—1000字），不要填写证件、电话等敏感信息");
        OffsetDateTime departure=input.newExpectedCheckOutAt().withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        // Precondition checks depending on current state follow the idempotency receipt lookup.
        Long previous=requests.reserveExtension(actor,key,new Canonical(id,input.segmentId(),input.version(),departure,reason));
        actors.require(actor,"stay:manage");
        if (previous != null) return required(previous);
        var stay=extensions.lock(id);
        if (stay == null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
        if (!"IN_HOUSE".equals(stay.status)) throw conflict("STAY_ALREADY_CHECKED_OUT","入住已经结束，不能调整预计退房时间");
        if (!stay.version.equals(input.version())) throw conflict("STALE_STAY_VERSION","入住记录已变化，请刷新后重新确认");
        var before=segments.current(id);
        if (before == null || rooms.lock(before.roomId) == null) throw conflict("STAY_SEGMENT_INCONSISTENT","当前房间使用记录不完整，请联系管理员核查");
        var segment=segments.lockCurrent(id);
        if (segment == null || !segment.id.equals(input.segmentId()) || !segment.roomId.equals(before.roomId))
            throw conflict("STALE_ROOM_SEGMENT","当前房间已变化，请刷新后重新确认");
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(segment.enteredAt)) throw conflict("SERVER_TIME_INVALID","服务器时间早于当前房间使用时间，请联系管理员核查");
        if (!departure.isAfter(now) || !departure.isAfter(stay.checkInAt)) throw invalid("新的预计退房时间必须晚于当前时间及实际入住时间");
        if (departure.isEqual(stay.expectedCheckOutAt)) throw invalid("新的预计退房时间与原时间相同，无需提交");
        Result result=new Result();
        result.stayRecordId=id; result.segmentId=segment.id; result.roomId=segment.roomId;
        result.oldExpectedCheckOutAt=stay.expectedCheckOutAt; result.newExpectedCheckOutAt=departure;
        result.reason=reason; result.operatorId=actor; result.operatedAt=now; result.stayVersion=stay.version+1;
        if (extensions.update(id,stay.version,departure,now) != 1 || extensions.insert(result) != 1)
            throw new IllegalStateException("Stay extension fact was not saved");
        audit.success(actor,"STAY_EXTEND","STAY_EXTENSION",result.id);
        requests.completeExtension(actor,key,result.id);
        return required(result.id);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Result> history(long id, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw invalid("分页参数不正确，每页最多100条");
        if (stays.detail(id) == null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
        return new Page<>(extensions.history(id,pageSize,((long)page-1)*pageSize),extensions.count(id),page,pageSize);
    }

    @Transactional(readOnly=true)
    public Result receipt(long actor, String key) {
        if (key == null || !key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw invalid("请求标识无效");
        var request=stays.request(actor,"STAY_EXTEND",UUID.fromString(key).toString());
        if (request == null || request.resultStayExtensionId == null)
            throw new ApiException(404,"OPERATION_NOT_FOUND","尚未查到成功结果，请核对入住及续住历史，勿直接新建请求");
        return required(request.resultStayExtensionId);
    }
    private Result required(long id) {
        Result result=extensions.detail(id);
        if (result == null) throw new IllegalStateException("Stay extension receipt is incomplete");
        return result;
    }
    private ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private ApiException conflict(String code, String message) { return new ApiException(409,code,message); }
}
