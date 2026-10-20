package com.stayos.room.service;

import com.stayos.room.dto.*;
import com.stayos.room.dto.RoomRequests.*;
import com.stayos.room.entity.*;
import com.stayos.room.mapper.*;
import com.stayos.audit.AuditService;
import com.stayos.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class RoomCatalogService {
    private final RoomMapper rooms;
    private final RoomTypeMapper types;
    private final AuditService audit;
    public RoomCatalogService(RoomMapper rooms, RoomTypeMapper types, AuditService audit) {
        this.rooms=rooms; this.types=types; this.audit=audit;
    }
    public record Page<T>(List<T> items, long total, int page, int pageSize) {}
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String code(String value, int max, String name) {
        String result=text(value).toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9][A-Z0-9_-]{0,"+(max-1)+"}")) throw new ApiException(400,"VALIDATION_ERROR",name+"只能包含字母、数字、下划线或短横线，且须以字母或数字开头");
        return result;
    }
    private static void version(Long expected, long actual) {
        if (expected == null) throw new ApiException(400,"VERSION_REQUIRED","请提供当前资料版本");
        if (expected != actual) throw new ApiException(409,"STALE_VERSION","资料已被其他人修改，请刷新后重试");
    }
    private RoomType type(long id) {
        RoomType result=types.lock(id);
        if (result == null) throw new ApiException(404,"ROOM_TYPE_NOT_FOUND","房型不存在");
        return result;
    }
    private Room room(long id) {
        Room result=rooms.lock(id);
        if (result == null) throw new ApiException(404,"ROOM_NOT_FOUND","房间不存在");
        return result;
    }
    private void typeFields(RoomType target, TypeInput input) {
        target.typeCode=code(input.typeCode(),50,"房型编码"); target.typeName=text(input.typeName());
        target.description=text(input.description()); target.bedDescription=text(input.bedDescription());
        target.suggestedOccupancy=input.suggestedOccupancy();
    }
    @Transactional
    public RoomType createType(long actor, TypeInput input) {
        var type=new RoomType(); typeFields(type,input); type.enabled=true; type.version=0L;
        types.insert(type); audit.success(actor,"ROOM_TYPE_CREATE","ROOM_TYPE",type.id); return types.selectById(type.id);
    }
    @Transactional
    public RoomType editType(long actor, long id, TypeInput input) {
        var type=type(id); version(input.version(),type.version); typeFields(type,input); types.updateInfo(type);
        audit.success(actor,"ROOM_TYPE_UPDATE","ROOM_TYPE",id); return types.selectById(id);
    }
    @Transactional
    public RoomType enableType(long actor, long id, Enabled input) {
        var type=type(id); version(input.version(),type.version); types.setEnabled(id,input.enabled());
        audit.success(actor,"ROOM_TYPE_SET_ENABLED","ROOM_TYPE",id); return types.selectById(id);
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page<RoomTypeView> listTypes(int page, int pageSize, Boolean enabled, String search) {
        if (page<1 || page>100000 || pageSize<1 || pageSize>100 || (search!=null && search.length()>100))
            throw new ApiException(400,"VALIDATION_ERROR","房型查询参数不正确");
        search=text(search).toUpperCase(Locale.ROOT);
        return new Page<>(types.list(enabled,search,pageSize,(long)(page-1)*pageSize),types.count(enabled,search),page,pageSize);
    }
    private void roomFields(Room target, RoomInput input) {
        if (input.floor()==0) throw new ApiException(400,"VALIDATION_ERROR","楼层不能为 0");
        target.roomNumber=code(input.roomNumber(),20,"房号"); target.floor=input.floor();
        target.roomTypeId=input.roomTypeId(); target.notes=text(input.notes());
    }
    @Transactional
    public RoomView createRoom(long actor, RoomInput input) {
        var type=type(input.roomTypeId());
        if (!type.enabled) throw new ApiException(409,"ROOM_TYPE_DISABLED","该房型已停用，请选择启用的房型");
        var room=new Room(); roomFields(room,input); room.enabled=true; room.cleaningStatus="DIRTY"; room.version=0L;
        rooms.insert(room); audit.success(actor,"ROOM_CREATE","ROOM",room.id); return rooms.detail(room.id);
    }
    @Transactional
    public RoomView editRoom(long actor, long id, RoomInput input) {
        // Always acquire the type lock before the room lock; creation uses the same order.
        var type=type(input.roomTypeId()); var room=room(id); version(input.version(),room.version);
        if (!type.enabled && !Objects.equals(room.roomTypeId,type.id))
            throw new ApiException(409,"ROOM_TYPE_DISABLED","不能改绑到已停用的房型");
        String nextNumber=code(input.roomNumber(),20,"房号");
        if (!nextNumber.equals(room.roomNumber) && rooms.detail(id).hasUsageHistory)
            throw new ApiException(409,"ROOM_NUMBER_HAS_HISTORY","该房间已有使用历史，不能直接修改房号");
        roomFields(room,input); rooms.updateInfo(room); audit.success(actor,"ROOM_UPDATE","ROOM",id); return rooms.detail(id);
    }
    @Transactional
    public RoomView enableRoom(long actor, long id, Enabled input) {
        var room=room(id); version(input.version(),room.version); var facts=rooms.detail(id);
        if (!input.enabled() && (facts.occupied || facts.hasOpenCleaningTask))
            throw new ApiException(409,"ROOM_IN_USE","房间存在当前占用或未完成清洁任务，不能停用");
        rooms.setEnabled(id,input.enabled()); audit.success(actor,"ROOM_SET_ENABLED","ROOM",id); return rooms.detail(id);
    }
    @Transactional(readOnly=true)
    public RoomView detail(long id) {
        var result=rooms.detail(id);
        if (result==null) throw new ApiException(404,"ROOM_NOT_FOUND","房间不存在");
        return result;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page<RoomView> list(RoomFilter input) {
        var q=input.normalized();
        return new Page<>(rooms.list(q,q.getOffset()),rooms.count(q),q.page(),q.pageSize());
    }
}
