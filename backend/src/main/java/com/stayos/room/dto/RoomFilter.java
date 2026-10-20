package com.stayos.room.dto;

import jakarta.validation.constraints.*;
import com.stayos.common.ApiException;
import java.util.*;

public record RoomFilter(@Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer pageSize,
        @Size(max=20) String roomNumber, @Min(-99) @Max(199) Integer floor, @Positive Long roomTypeId,
        String availability, String cleaningStatus, String occupancyStatus, String maintenanceStatus,
        Boolean enabled, Boolean canCheckIn) {
    public RoomFilter normalized() {
        if (floor != null && floor == 0) throw new ApiException(400, "VALIDATION_ERROR", "楼层不能为 0");
        check(availability, Set.of("DISABLED","OCCUPIED","OUT_OF_SERVICE","CLEANING","INSPECTION","DIRTY","UNAVAILABLE","VACANT_CLEAN"));
        check(cleaningStatus, Set.of("CLEAN","DIRTY","CLEANING","INSPECTION"));
        check(occupancyStatus, Set.of("VACANT","OCCUPIED"));
        check(maintenanceStatus, Set.of("NORMAL","OUT_OF_SERVICE"));
        return new RoomFilter(page == null ? 1 : page, pageSize == null ? 20 : pageSize,
            roomNumber == null ? null : roomNumber.trim().toUpperCase(Locale.ROOT), floor, roomTypeId,
            availability, cleaningStatus, occupancyStatus, maintenanceStatus, enabled, canCheckIn);
    }
    private static void check(String value, Set<String> valid) {
        if (value != null && !valid.contains(value)) throw new ApiException(400, "VALIDATION_ERROR", "房态筛选条件不正确");
    }
    public long getOffset() { return (long)(page - 1) * pageSize; }
}
