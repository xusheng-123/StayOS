package com.stayos.housekeeping.service;
import com.stayos.housekeeping.mapper.TaskMapper;
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
    }
}
