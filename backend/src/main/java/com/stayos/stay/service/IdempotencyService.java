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
    private Long reserveResult(long actor,String operation,String requestKey,Object canonicalInput,String kind) {
        String key=key(requestKey),hash;
        try { hash=protection.fingerprint(json.writeValueAsBytes(canonicalInput)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) { throw new ApiException(400,"VALIDATION_ERROR","请求内容无法处理"); }
        // An uncommitted conflicting insert waits; a rollback allows this insert to win.
        if(mapper.reserve(actor,operation,key,hash)!=0) return null;
        var old=mapper.request(actor,operation,key);
        if(old.expired) throw new ApiException(409,"IDEMPOTENCY_KEY_EXPIRED","原请求已超过 24 小时，请先查询业务记录再确认是否需要新操作");
        if(!old.requestHash.equals(hash)) throw new ApiException(409,"IDEMPOTENCY_MISMATCH","同一请求标识不能提交不同内容，请确认原操作结果");
        Long result=switch(kind) { case "TASK" -> old.resultTaskLogId; case "BATCH" -> old.resultAssignmentBatchId; default -> old.resultStayId; };
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
