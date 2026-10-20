package com.stayos.audit;

import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditService {
    private final AuditMapper mapper;
    public AuditService(AuditMapper mapper) { this.mapper = mapper; }
    public void success(Long actor, String action, String type, Long target) { mapper.insert(actor, action, type, target, "SUCCESS"); }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(Long actor, String action, String type, Long target) { mapper.insert(actor, action, type, target, "FAILURE"); }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(Long actor) { mapper.insert(actor, "ACCESS_DENIED", "AUTHORIZATION", null, "DENIED"); }
}

@Mapper
interface AuditMapper {
    @Insert("insert into audit_logs(operator_id,action,target_type,target_id,result) values(#{actor},#{action},#{type},#{target},#{result})")
    void insert(@Param("actor") Long actor, @Param("action") String action, @Param("type") String type, @Param("target") Long target, @Param("result") String result);
}
