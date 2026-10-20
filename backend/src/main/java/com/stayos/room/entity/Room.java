package com.stayos.room.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

@TableName("rooms")
public class Room {
    @TableId(type = IdType.AUTO) public Long id;
    public String roomNumber;
    public Integer floor;
    public Long roomTypeId;
    public String notes;
    public Boolean enabled;
    public String cleaningStatus;
    public Long version;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
