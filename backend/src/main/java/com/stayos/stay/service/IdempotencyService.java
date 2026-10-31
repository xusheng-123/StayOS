package com.stayos.stay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayos.common.ApiException;
import com.stayos.stay.mapper.StayMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

/** Shared PostgreSQL protocol. Reservation and result MUST join the business transaction. */
@Service
public class IdempotencyService {
    private final StayMapper mapper;
    private final GuestProtection protection;
    private final ObjectMapper json;
    public IdempotencyService(StayMapper mapper,GuestProtection protection,ObjectMapper json) {
        this.mapper=mapper;this.protection=protection;this.json=json;
    }
    private String key(String key) {
        if(key==null || !key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new ApiException(400,"IDEMPOTENCY_KEY_REQUIRED","请提供有效的业务请求标识");
        return UUID.fromString(key).toString();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long reserve(long actor,String operation,String requestKey,Object canonicalInput) {
        return reserveResult(actor,operation,requestKey,canonicalInput,"STAY");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long reserveTask(long actor,String operation,String requestKey,Object canonicalInput) {
        return reserveResult(actor,operation,requestKey,canonicalInput,"TASK");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long reserveBatch(long actor,String requestKey,Object canonicalInput) {
        return reserveResult(actor,"HK_BATCH_ASSIGN",requestKey,canonicalInput,"BATCH");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeBatch(long actor,String requestKey,long batch) {
        if(mapper.completeBatch(actor,key(requestKey),batch)!=1) throw new IllegalStateException("Missing batch request reservation");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long reserveRoomOperation(long actor,String operation,String key,Object input,boolean maintenance) {
        return reserveResult(actor,operation,key,input,maintenance?"MAINTENANCE":"ISSUE");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeRoomOperation(long actor,String operation,String requestKey,long log,boolean maintenance) {
        int count=maintenance?mapper.completeMaintenance(actor,operation,key(requestKey),log):mapper.completeIssue(actor,operation,key(requestKey),log);
        if(count!=1) throw new IllegalStateException("Missing room operation reservation");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long reserveChange(long actor,String key,Object input) { return reserveResult(actor,"ROOM_CHANGE",key,input,"CHANGE"); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeChange(long actor,String requestKey,long change) {
        if(mapper.completeChange(actor,key(requestKey),change)!=1) throw new IllegalStateException("Missing room change reservation");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long reserveExtension(long actor,String key,Object input) { return reserveResult(actor,"STAY_EXTEND",key,input,"EXTENSION"); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeExtension(long actor,String requestKey,long extension) {
        if(mapper.completeExtension(actor,key(requestKey),extension)!=1) throw new IllegalStateException("Missing stay extension reservation");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long reserveCorrection(long actor,String key,Object input) { return reserveResult(actor,"GUEST_CORRECT",key,input,"CORRECTION"); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeCorrection(long actor,String requestKey,long correction) {
        if(mapper.completeCorrection(actor,key(requestKey),correction)!=1) throw new IllegalStateException("Missing guest correction reservation");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long reserveHandover(long actor,String operation,String key,Object input) { return reserveResult(actor,operation,key,input,"HANDOVER"); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeHandover(long actor,String operation,String requestKey,long handover) {
        if(mapper.completeHandover(actor,operation,key(requestKey),handover)!=1) throw new IllegalStateException("Missing handover reservation");
    }
    private Long reserveResult(long actor,String operation,String requestKey,Object canonicalInput,String kind) {
        String key=key(requestKey),hash;
        try { hash=protection.fingerprint(json.writeValueAsBytes(canonicalInput)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) { throw new ApiException(400,"VALIDATION_ERROR","请求内容无法处理"); }
        // An uncommitted conflicting insert waits; a rollback allows this insert to win.
        if(mapper.reserve(actor,operation,key,hash)!=0) return null;
        var old=mapper.request(actor,operation,key);
        if(old.expired) throw new ApiException(409,"IDEMPOTENCY_KEY_EXPIRED","原请求已超过 24 小时，请先查询业务记录再确认是否需要新操作");
        if(!old.requestHash.equals(hash)) throw new ApiException(409,"IDEMPOTENCY_MISMATCH","同一请求标识不能提交不同内容，请确认原操作结果");
        Long result=switch(kind) { case "HANDOVER" -> old.resultHandoverId; case "CORRECTION" -> old.resultGuestCorrectionId; case "EXTENSION" -> old.resultStayExtensionId; case "CHANGE" -> old.resultRoomChangeId; case "ISSUE" -> old.resultIssueLogId; case "MAINTENANCE" -> old.resultMaintenanceLogId; case "TASK" -> old.resultTaskLogId; case "BATCH" -> old.resultAssignmentBatchId; default -> old.resultStayId; };
        if(result==null) throw new ApiException(409,"REQUEST_IN_PROGRESS","操作正在处理中，请稍后重试原请求");
        return result;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeTask(long actor,String operation,String requestKey,long log) {
        if(mapper.completeTask(actor,operation,key(requestKey),log)!=1) throw new IllegalStateException("Missing task request reservation");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void complete(long actor,String operation,String requestKey,long stay) {
        if(mapper.complete(actor,operation,key(requestKey),stay)!=1) throw new IllegalStateException("Missing business request reservation");
    }
}
