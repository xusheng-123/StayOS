package com.stayos.room.service;

import com.stayos.room.dto.*;
import com.stayos.room.mapper.RoomMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class RoomStatusService {
    private final RoomMapper rooms;
    public RoomStatusService(RoomMapper rooms) { this.rooms=rooms; }
    public record Board(List<RoomView> items,long total,int page,int pageSize,RoomStatistics statistics) {}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Board board(RoomFilter input) {
        var q=input.normalized();
        var statistics=rooms.statistics(q);
        return new Board(rooms.list(q,q.getOffset()),statistics.total,q.page(),q.pageSize(),statistics);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RoomStatistics statistics(RoomFilter input) { return rooms.statistics(input.normalized()); }
    public record Floor(Integer floor,Long roomCount) {}
    @Transactional(readOnly=true)
    public List<Floor> floors() { return rooms.floors(); }
}
