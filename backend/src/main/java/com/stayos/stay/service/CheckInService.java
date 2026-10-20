package com.stayos.stay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayos.audit.AuditService;
import com.stayos.common.ApiException;
import com.stayos.room.mapper.RoomMapper;
import com.stayos.stay.dto.StayRequests.*;
import com.stayos.stay.mapper.StayMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class CheckInService {
    private final StayMapper stays;
    private final RoomMapper rooms;
    private final GuestProtection protection;
    private final AuditService audit;
    private final ObjectMapper json;
    public CheckInService(StayMapper stays,RoomMapper rooms,GuestProtection protection,AuditService audit,ObjectMapper json) {
        this.stays=stays;this.rooms=rooms;this.protection=protection;this.audit=audit;this.json=json;
    }
    private static String text(String s) { return s==null?"":s.trim(); }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private CheckIn normalize(CheckIn input) {
        if(input.roomId()==null || input.roomId()<1 || input.expectedCheckOutAt()==null || input.guests()==null || input.guests().isEmpty() || input.guests().size()>20)
            throw invalid("请选择房间、预计退房时间并登记 1—20 名实际住客");
        var guests=new ArrayList<GuestInput>();var documents=new HashSet<String>();int primary=0;
        for(var guest:input.guests()) {
            if(guest==null) throw invalid("请补全住客信息");
            String name=text(guest.name()),type=text(guest.documentType()),number=text(guest.documentNumber()).toUpperCase(Locale.ROOT),phone=text(guest.phone()),notes=text(guest.notes());
            if(name.isEmpty() || name.length()>100 || name.chars().anyMatch(Character::isISOControl)) throw invalid("请填写有效的住客姓名");
            boolean valid=switch(type) { case "ID_CARD"->number.matches("[0-9]{17}[0-9X]");case "PASSPORT"->number.matches("[A-Z0-9]{3,30}");case "OTHER"->number.matches("[A-Z0-9_/-]{3,64}");default->false; };
            if(!valid) throw invalid("证件类型或号码格式不正确；格式检查不代表身份核验");
            if(!documents.add(type+":"+number)) throw invalid("同一次入住不能重复登记相同证件");
            if(phone.length()>30 || (!phone.isEmpty() && !phone.matches("[+0-9() -]{3,30}"))) throw invalid("联系电话格式不正确");
            if(notes.length()>1000) throw invalid("住客备注不能超过 1000 字");
            if(guest.primary()) primary++;
            guests.add(new GuestInput(name,type,number,phone,notes,guest.primary()));
        }
        if(primary!=1) throw invalid("请指定且只指定一名主要联系人");
        if(text(input.notes()).length()>1000) throw invalid("入住备注不能超过 1000 字");
        return new CheckIn(input.roomId(),input.expectedCheckOutAt().withOffsetSameInstant(ZoneOffset.UTC),List.copyOf(guests),text(input.notes()));
    }
    @Transactional
    public long create(long actor,String requestKey,CheckIn raw) {
        if(requestKey==null || !requestKey.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new ApiException(400,"IDEMPOTENCY_KEY_REQUIRED","请提供有效的入住请求标识");
        String key=UUID.fromString(requestKey).toString();CheckIn input=normalize(raw);String hash;
        try { hash=protection.fingerprint(json.writeValueAsBytes(input)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ignored) { throw invalid("入住请求无法处理"); }
        // ON CONFLICT waits for an in-flight transaction. Nothing is committed on any failure.
        if(stays.reserve(actor,key,hash)==0) {
            var old=stays.request(actor,key);
            if(old.expired) throw new ApiException(409,"IDEMPOTENCY_KEY_EXPIRED","原入住请求已超过 24 小时，请先查询入住记录再确认是否需要新操作");
            if(!old.requestHash.equals(hash)) throw new ApiException(409,"IDEMPOTENCY_MISMATCH","同一请求标识不能提交不同内容，请确认原操作结果");
            if(old.resultStayId==null) throw new ApiException(409,"REQUEST_IN_PROGRESS","入住正在处理中，请稍后重试原请求");
            return old.resultStayId;
        }
        if(rooms.lock(input.roomId())==null) throw new ApiException(404,"ROOM_NOT_FOUND","房间不存在");
        var room=rooms.detail(input.roomId());
        if(!room.canCheckIn) {
            String reason=!room.enabled?"房间已停用":room.occupied?"房间已被占用":room.hasBlockingIssue?"房间正在维修或存在阻止入住的异常":
                !room.cleaningStatus.equals("CLEAN")?"房间尚未完成清洁":room.hasOpenCleaningTask?"房间仍有未完成清洁任务":"房间当前不可入住";
            throw new ApiException(409,"ROOM_NOT_AVAILABLE",reason);
        }
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        if(!input.expectedCheckOutAt().isAfter(now)) throw invalid("预计退房时间必须晚于服务器记录的实际入住时间");
        var row=new HashMap<String,Object>();row.put("checkInAt",now);row.put("expectedCheckOutAt",input.expectedCheckOutAt());row.put("actor",actor);row.put("notes",input.notes());
        stays.insertStay(row);long id=((Number)row.get("id")).longValue();int position=0;
        for(var guest:input.guests()) {
            var g=new HashMap<String,Object>();g.put("name",guest.name());g.put("documentType",guest.documentType());
            g.put("documentCiphertext",protection.encrypt(guest.documentNumber(),"document"));g.put("documentMask",protection.mask(guest.documentNumber()));
            g.put("phoneCiphertext",protection.encrypt(guest.phone(),"phone"));g.put("phoneMask",protection.mask(guest.phone()));g.put("notes",guest.notes());
            stays.insertGuest(g);stays.link(id,((Number)g.get("id")).longValue(),guest.primary(),position++);
        }
        stays.segment(id,room.id,room.roomNumber,room.roomTypeName,now);
        audit.success(actor,"CHECK_IN","STAY",id);
        stays.complete(actor,key,id);
        return id;
    }
}
