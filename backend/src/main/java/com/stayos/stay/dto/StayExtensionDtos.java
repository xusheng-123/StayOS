package com.stayos.stay.dto;

import java.time.OffsetDateTime;

public final class StayExtensionDtos {
    private StayExtensionDtos() {}
    public record Request(Long segmentId, Long version, OffsetDateTime newExpectedCheckOutAt, String reason) {}
    public static class Result {
        public Long id, stayRecordId, segmentId, roomId, operatorId, stayVersion;
        public String roomNumber, operatorName, reason;
        public OffsetDateTime oldExpectedCheckOutAt, newExpectedCheckOutAt, operatedAt;
    }
}
