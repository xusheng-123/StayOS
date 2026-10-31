package com.stayos.stay.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.*;

public final class GuestCorrectionDtos {
    private GuestCorrectionDtos() {}
    public record Request(Long version, Map<String,JsonNode> changes, String reason) {}
    public record Result(long id, long stayRecordId, long guestId, long guestVersion,
                         long operatorId, String operatorName, OffsetDateTime operatedAt, String reason,
                         List<String> changedFields, Map<String,String> before, Map<String,String> after) {}
}
