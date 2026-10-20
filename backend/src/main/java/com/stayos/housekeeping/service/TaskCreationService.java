package com.stayos.housekeeping.service;
import com.stayos.housekeeping.mapper.TaskMapper;
import com.stayos.housekeeping.dto.TaskLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.OffsetDateTime;

@Service
public class TaskCreationService {
    private final TaskMapper mapper;
    public TaskCreationService(TaskMapper mapper) { this.mapper=mapper; }
    /** Internal business call only; it cannot commit independently of checkout. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void fromCheckout(long room,long stay,long segment,long actor,OffsetDateTime time) {
        if(mapper.createCheckout(room,stay,segment,actor,time)!=1) throw new IllegalStateException("Checkout task was not created");
        var log=new TaskLog();log.taskId=mapper.checkoutId(stay);log.action="AUTO_CREATE";log.toStatus="PENDING";
        log.operatorId=actor;log.operatedAt=time;log.reason="";log.taskVersion=0L;
        if(mapper.log(log)!=1) throw new IllegalStateException("Checkout task history was not saved");
    }
}
