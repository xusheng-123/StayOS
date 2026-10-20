package com.stayos.stay.service;

import com.stayos.stay.dto.*;
import com.stayos.stay.mapper.StayMapper;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class StayQueryService {
    private final StayMapper stays;
    private final com.stayos.stay.mapper.RoomChangeMapper changes;
    public StayQueryService(StayMapper stays,com.stayos.stay.mapper.RoomChangeMapper changes) { this.stays=stays;this.changes=changes; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public StayView detail(long id) {
        var result=stays.detail(id);
        if(result==null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
        result.guests=stays.guests(id);result.roomSegments=changes.segments(id);result.roomChanges=changes.history(id);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<StayView> list(StayFilter query) {
        var q=query.normalized();return new Page<>(stays.list(q,q.offset()),stays.count(q),q.page(),q.pageSize());
    }
}
