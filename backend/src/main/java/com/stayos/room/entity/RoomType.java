package com.stayos.room.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

@TableName("room_types")
public class RoomType {
    @TableId(type = IdType.AUTO) public Long id;
    public String typeCode;
    public String typeName;
    public String description;
    public String bedDescription;
    public Integer suggestedOccupancy;
    public Boolean enabled;
    public Long version;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
